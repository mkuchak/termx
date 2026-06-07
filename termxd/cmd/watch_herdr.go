package cmd

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

// ---------------------------------------------------------------------------
// termx watch-herdr — long-running daemon that turns herdr agent-finish into
// termx events (Tier 1, ~/.termx/events.ndjson) and best-effort ntfy pushes
// (Tier 2, the URL the phone synced to ~/.termx/ntfy-endpoint).
//
// This is the FIRST long-running termx subcommand; every other command is
// short-lived. It is designed to run under a process supervisor (systemd
// user unit / tmux / nohup) and to survive herdr restarts.
//
// herdr integration path — CLI (`herdr wait agent-status`), not the raw
// socket. Rationale, verified against herdr 0.6.8 at build time:
//
//   - herdr's socket DOES expose `events.subscribe`, but a subscription
//     element requires a *concrete* pane_id; a "*"/wildcard subscription is
//     rejected ("failed to decode pane get error"). There is no global
//     agent-done push stream to attach to, so the socket buys us nothing the
//     CLI doesn't already give us — we'd still have to enumerate panes and
//     subscribe per-pane, reimplementing herdr's own framing.
//   - The prior-research frame
//     {"method":"events.subscribe","params":{"subscriptions":[{"type":
//     "pane.agent_status_changed","agent_status":"done"}]}} is rejected
//     outright by 0.6.8 (the subscription schema has no agent_status field
//     and demands pane_id).
//   - `herdr wait agent-status <pane> --status done` blocks until the pane
//     enters (or already is in) the "done" state, then prints EXACTLY the
//     frame the phone contract wants:
//       {"event":"pane.agent_status_changed","data":{"pane_id":..,
//        "workspace_id":..,"agent_status":"done","agent":..}}
//     and exits 0. On timeout it exits non-zero. That is the supported,
//     stable surface, so it is the path implemented here.
//
// Design: snapshot panes via `herdr agent list` (JSON), spawn one
// `herdr wait agent-status <pane> --status done` per agent-bearing pane in a
// goroutine. When a waiter returns (match, timeout, or pane gone) we
// re-snapshot and reconcile the waiter set. A done-match emits the event +
// ntfy push. The whole loop reconnects with backoff if `herdr agent list`
// fails (herdr not running yet / restarting).
// ---------------------------------------------------------------------------

// finishStatus is the herdr agent status that means "the agent stopped and
// wants the human" — i.e. agent-finished. herdr explicitly designates "done"
// as the UI attention state for completion (`agent wait` refuses "done" and
// points at "idle" for *programmatic* idle waits, but `wait agent-status`
// accepts "done" and it is the state surfaced as agent_status:"done" in
// `agent list` when Claude finishes a turn).
const finishStatus = "done"

// herdrListBackoff bounds reconnect attempts when herdr is unavailable.
const (
	herdrBackoffMin = 500 * time.Millisecond
	herdrBackoffMax = 10 * time.Second
	// waitTimeoutMs caps each per-pane `wait agent-status` so a long-lived
	// working pane still gets its waiter recycled periodically (and so a
	// pane that vanished while we waited is noticed promptly on re-list).
	waitTimeoutMs = 60000
)

func newWatchHerdrCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "watch-herdr",
		Short: "Watch herdr for agent-finish; append agent_finished events and push ntfy alerts",
		Long: `Subscribe to the herdr workspace manager and, whenever a coding agent
finishes (enters herdr's "done" attention state), append an agent_finished
event to ~/.termx/events.ndjson (Tier 1) and, if ~/.termx/ntfy-endpoint holds
a URL, POST a short alert to it (Tier 2).

Runs until interrupted. Reconnects automatically if herdr restarts.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			paths, err := internal.ResolvePaths()
			if err != nil {
				return err
			}
			w := &herdrWatcher{
				paths:  paths,
				stdout: cmd.OutOrStdout(),
				stderr: cmd.ErrOrStderr(),
				lister: cliAgentLister,
				waiter: cliAgentWaiter,
				poster: httpPost,
				now:    func() time.Time { return time.Now().UTC() },
			}
			return w.run(cmd.Context())
		},
	}
}

// ---- herdr JSON shapes (subset we consume) ----

// herdrAgentListResult is the envelope `herdr agent list` (and the socket
// `agent.list`) returns. We only read result.agents.
type herdrAgentListResult struct {
	Result struct {
		Agents []herdrAgent `json:"agents"`
	} `json:"result"`
}

// herdrAgent is one entry of `agent list`.
type herdrAgent struct {
	PaneID      string `json:"pane_id"`
	WorkspaceID string `json:"workspace_id"`
	Agent       string `json:"agent"`
	AgentStatus string `json:"agent_status"`
	Cwd         string `json:"cwd"`
}

// herdrStatusFrame is the line `herdr wait agent-status` prints when its
// match fires. Mirrors {"event":"pane.agent_status_changed","data":{...}}.
type herdrStatusFrame struct {
	Event string `json:"event"`
	Data  struct {
		PaneID      string `json:"pane_id"`
		WorkspaceID string `json:"workspace_id"`
		Agent       string `json:"agent"`
		AgentStatus string `json:"agent_status"`
	} `json:"data"`
}

// ---- injectable seams (real impls below; tests substitute fakes) ----

// agentLister returns the current agent-bearing panes.
type agentLister func(ctx context.Context) ([]herdrAgent, error)

// agentWaiter blocks until pane enters status (or the call's own timeout
// elapses / the pane is gone). It returns the matched frame and true on a
// real status match, or ok=false for timeout / no-match / transient error.
type agentWaiter func(ctx context.Context, paneID, status string) (herdrStatusFrame, bool)

// poster performs the Tier-2 push. Returns the HTTP status code (0 on
// transport failure) and an error.
type poster func(url, body string) (int, error)

// herdrWatcher holds the daemon's wiring.
type herdrWatcher struct {
	paths  *internal.Paths
	stdout io.Writer
	stderr io.Writer
	lister agentLister
	waiter agentWaiter
	poster poster
	now    func() time.Time

	// lastSnapshot is the most recent pane_id->agent map, read lock-free by
	// waiter callbacks to enrich a done frame with a workspace label.
	lastSnapshot atomic.Pointer[snapshot]
}

// run is the supervise loop: (re)snapshot panes, keep one done-waiter alive
// per pane, and reconcile whenever a waiter returns. Exits cleanly on
// ctx cancellation (SIGINT/SIGTERM via cobra's signal context).
func (w *herdrWatcher) run(ctx context.Context) error {
	fmt.Fprintln(w.stderr, "termx watch-herdr: starting (herdr CLI integration, status=done)")

	// done channel carries the pane_id of any waiter goroutine that returned,
	// so the supervisor knows to reconcile.
	type waiterDone struct {
		paneID string
		frame  herdrStatusFrame
		match  bool
	}
	doneCh := make(chan waiterDone, 16)

	// active tracks pane_ids that currently have a live waiter goroutine, so
	// reconciliation never double-spawns for the same pane.
	active := map[string]context.CancelFunc{}
	// lastStatus is the last agent_status we observed per pane. It makes the
	// daemon EDGE-triggered: an agent_finished event fires only on the
	// transition INTO "done", never repeatedly while a pane sits in done.
	// (herdr's `wait agent-status` is level-triggered — it returns instantly
	// for an already-done pane — so without this gate an idle-but-done pane
	// would spin a hot re-arm loop emitting duplicate alerts.)
	lastStatus := map[string]string{}
	var mu sync.Mutex

	backoff := herdrBackoffMin

	// spawnWaiter launches one done-waiter for paneID if none is running.
	// Only meaningful for panes NOT already in "done" — those are caught by
	// the snapshot edge-check in reconcile, not by a waiter.
	spawnWaiter := func(paneID string) {
		mu.Lock()
		if _, ok := active[paneID]; ok {
			mu.Unlock()
			return
		}
		wctx, cancel := context.WithCancel(ctx)
		active[paneID] = cancel
		mu.Unlock()
		go func() {
			frame, ok := w.waiter(wctx, paneID, finishStatus)
			select {
			case doneCh <- waiterDone{paneID: paneID, frame: frame, match: ok}:
			case <-ctx.Done():
			}
		}()
	}

	// emitFinish fires the Tier1+Tier2 finish handling for a pane, but only on
	// the done EDGE (prior status != done). Updates lastStatus. Returns true
	// if it actually emitted.
	emitFinish := func(f herdrStatusFrame) bool {
		pane := f.Data.PaneID
		mu.Lock()
		prev := lastStatus[pane]
		lastStatus[pane] = finishStatus
		mu.Unlock()
		if prev == finishStatus {
			return false // already counted this done; suppress duplicate.
		}
		w.onFinish(f)
		return true
	}

	// reconcile lists panes and reconciles waiters + edge state:
	//   - records each pane's current status (so the next change is an edge);
	//   - for a pane ALREADY "done" in the snapshot, emits once on the edge
	//     (no hot waiter — a done pane stays done until the human acts);
	//   - for any other pane, arms a low-latency `wait agent-status done`
	//     goroutine to catch the working→done transition promptly.
	// Vanished panes drop out of lastStatus so a recreated pane re-edges.
	reconcile := func() error {
		agents, err := w.lister(ctx)
		if err != nil {
			return err
		}
		seen := map[string]herdrAgent{}
		for _, a := range agents {
			if a.PaneID == "" {
				continue
			}
			seen[a.PaneID] = a
		}
		w.lastSnapshot.Store(snapshotPtr(seen))

		// Prune lastStatus for panes that disappeared.
		mu.Lock()
		for pane := range lastStatus {
			if _, ok := seen[pane]; !ok {
				delete(lastStatus, pane)
			}
		}
		mu.Unlock()

		for _, a := range agents {
			if a.PaneID == "" {
				continue
			}
			if a.AgentStatus == finishStatus {
				// Edge-emit for already-done panes; no waiter.
				emitFinish(frameFromAgent(a))
				continue
			}
			// Not done now — record status and arm a waiter to catch the
			// next done transition with low latency.
			mu.Lock()
			lastStatus[a.PaneID] = a.AgentStatus
			mu.Unlock()
			spawnWaiter(a.PaneID)
		}
		return nil
	}

	// Prime the snapshot store so label lookups work before first reconcile.
	w.lastSnapshot.Store(snapshotPtr(map[string]herdrAgent{}))

	if err := reconcile(); err != nil {
		fmt.Fprintf(w.stderr, "watch-herdr: herdr not ready (%v); will retry\n", err)
	} else {
		backoff = herdrBackoffMin
	}

	// Periodic re-snapshot: catches panes that appeared without any existing
	// waiter returning (e.g. a brand-new workspace while every other agent is
	// still working). Cheap — one `agent list` per tick.
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			mu.Lock()
			for _, cancel := range active {
				cancel()
			}
			mu.Unlock()
			fmt.Fprintln(w.stderr, "watch-herdr: shutting down")
			return nil

		case d := <-doneCh:
			mu.Lock()
			delete(active, d.paneID)
			mu.Unlock()
			if d.match && d.frame.Event == "pane.agent_status_changed" &&
				d.frame.Data.AgentStatus == finishStatus {
				// Edge-gated: emits only if this pane wasn't already done.
				emitFinish(d.frame)
			}
			// Re-snapshot and reconcile. reconcile() decides whether to
			// re-arm a waiter: a still-done pane gets no waiter (it's already
			// counted), so we never hot-loop; a pane that left done re-arms.
			if err := reconcile(); err != nil {
				// herdr likely went away; back off before the ticker retries.
				time.Sleep(jitterBackoff(&backoff))
			} else {
				backoff = herdrBackoffMin
			}

		case <-ticker.C:
			if err := reconcile(); err != nil {
				fmt.Fprintf(w.stderr, "watch-herdr: re-list failed (%v); backing off\n", err)
				time.Sleep(jitterBackoff(&backoff))
			} else {
				backoff = herdrBackoffMin
			}
		}
	}
}

// snapshot is a pane_id->agent map captured from the latest `agent list`.
type snapshot = map[string]herdrAgent

func snapshotPtr(m map[string]herdrAgent) *snapshot { return &m }

// onFinish handles a confirmed agent-done frame: Tier 1 event append + Tier 2
// ntfy push. Best-effort throughout — a failure here must never crash the
// daemon (it would silently stop alerting otherwise).
func (w *herdrWatcher) onFinish(f herdrStatusFrame) {
	snap := map[string]herdrAgent{}
	if p := w.lastSnapshot.Load(); p != nil {
		snap = *p
	}
	session, agent, workspace := finishFields(f, snap)

	// Tier 1: append the event. Field names agent/workspace/source MUST match
	// the Kotlin TermxEvent.AgentFinished(source, agent, workspace) contract.
	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	if err := internal.AppendEvent("agent_finished", session, map[string]any{
		"agent":     agent,
		"workspace": workspace,
		"source":    "herdr",
	}); err != nil {
		fmt.Fprintf(w.stderr, "watch-herdr: append event failed: %v\n", err)
	} else {
		fmt.Fprintf(w.stdout, "agent_finished: %s in %s (pane %s)\n", agent, workspace, session)
	}

	// Tier 2: best-effort ntfy push to the synced endpoint.
	url := w.readNtfyEndpoint()
	if url == "" {
		return
	}
	body := publishBody(agent, workspace)
	code, err := w.poster(url, body)
	switch {
	case err != nil:
		fmt.Fprintf(w.stderr, "watch-herdr: ntfy POST failed: %v\n", err)
	case code < 200 || code >= 300:
		fmt.Fprintf(w.stderr, "watch-herdr: ntfy POST non-2xx: %d\n", code)
	}
}

// readNtfyEndpoint reads ~/.termx/ntfy-endpoint and returns a trimmed URL, or
// "" if the file is absent/empty/not an http(s) URL. Re-read on every finish
// so the phone can (re)sync the endpoint without restarting the daemon.
func (w *herdrWatcher) readNtfyEndpoint() string {
	b, err := os.ReadFile(w.paths.NtfyEndpointFile)
	if err != nil {
		return ""
	}
	return sanitizeEndpoint(string(b))
}

// ---- pure helpers (unit-tested) ----

// frameFromAgent synthesizes the same frame shape `wait agent-status` emits
// from an `agent list` entry, so the snapshot edge-path and the waiter path
// feed identical data into onFinish.
func frameFromAgent(a herdrAgent) herdrStatusFrame {
	var f herdrStatusFrame
	f.Event = "pane.agent_status_changed"
	f.Data.PaneID = a.PaneID
	f.Data.WorkspaceID = a.WorkspaceID
	f.Data.Agent = a.Agent
	f.Data.AgentStatus = a.AgentStatus
	return f
}

// finishFields derives the (session, agent, workspace) tuple the event/push
// use from a done frame, enriched by the last `agent list` snapshot.
//
//   - session  = pane_id (stable per-tab id; the Kotlin side keys on it)
//   - agent    = the agent label from the frame, falling back to the snapshot
//   - workspace= a human label: the snapshot cwd's basename, else workspace_id
func finishFields(f herdrStatusFrame, snap map[string]herdrAgent) (session, agent, workspace string) {
	session = f.Data.PaneID
	agent = f.Data.Agent
	workspace = f.Data.WorkspaceID

	if a, ok := snap[f.Data.PaneID]; ok {
		if agent == "" {
			agent = a.Agent
		}
		if label := workspaceLabel(a.Cwd); label != "" {
			workspace = label
		} else if a.WorkspaceID != "" {
			workspace = a.WorkspaceID
		}
	}
	if agent == "" {
		agent = "agent"
	}
	if workspace == "" {
		workspace = f.Data.WorkspaceID
	}
	return session, agent, workspace
}

// workspaceLabel turns a cwd into a short human label (its basename). Empty
// for empty/root cwds so callers fall back to the workspace id.
func workspaceLabel(cwd string) string {
	cwd = strings.TrimSpace(cwd)
	if cwd == "" {
		return ""
	}
	base := filepath.Base(filepath.Clean(cwd))
	if base == "." || base == "/" || base == string(filepath.Separator) {
		return ""
	}
	return base
}

// publishBody is the Tier-2 ntfy message body. Kept tiny and human-readable;
// the phone shows it as the notification text.
func publishBody(agent, workspace string) string {
	return "herdr: " + agent + " finished in " + workspace
}

// sanitizeEndpoint trims whitespace and accepts only http(s) URLs. Anything
// else (blank file, a comment, garbage) yields "" so we never POST to junk.
func sanitizeEndpoint(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return ""
	}
	// A synced endpoint may carry a trailing newline (the phone writes a
	// single line). Take the first non-empty line, defensively.
	if i := strings.IndexAny(s, "\r\n"); i >= 0 {
		s = strings.TrimSpace(s[:i])
	}
	if !strings.HasPrefix(s, "http://") && !strings.HasPrefix(s, "https://") {
		return ""
	}
	return s
}

// jitterBackoff returns the current backoff and doubles it (capped). No
// random jitter — single daemon, no thundering herd to avoid; determinism
// keeps logs readable.
func jitterBackoff(d *time.Duration) time.Duration {
	cur := *d
	next := cur * 2
	if next > herdrBackoffMax {
		next = herdrBackoffMax
	}
	*d = next
	return cur
}

// ---- real herdr CLI seams ----

// cliAgentLister runs `herdr agent list` and parses the JSON envelope.
func cliAgentLister(ctx context.Context) ([]herdrAgent, error) {
	out, err := exec.CommandContext(ctx, "herdr", "agent", "list").Output()
	if err != nil {
		return nil, fmt.Errorf("herdr agent list: %w", err)
	}
	return parseAgentList(out)
}

// parseAgentList is the pure parser, split out for testing.
func parseAgentList(out []byte) ([]herdrAgent, error) {
	var env herdrAgentListResult
	if err := json.Unmarshal(out, &env); err != nil {
		return nil, fmt.Errorf("decode agent list: %w", err)
	}
	return env.Result.Agents, nil
}

// cliAgentWaiter runs `herdr wait agent-status <pane> --status done` and reads
// the first matching frame from stdout. Returns ok=true only on a real
// pane.agent_status_changed match; timeout / pane-gone / parse failure all
// return ok=false so the supervisor just re-lists.
func cliAgentWaiter(ctx context.Context, paneID, status string) (herdrStatusFrame, bool) {
	cmd := exec.CommandContext(ctx, "herdr", "wait", "agent-status", paneID,
		"--status", status, "--timeout", fmt.Sprintf("%d", waitTimeoutMs))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return herdrStatusFrame{}, false
	}
	cmd.Stderr = nil
	if err := cmd.Start(); err != nil {
		return herdrStatusFrame{}, false
	}

	frame, ok := scanFirstStatusFrame(stdout, status)
	// Drain & reap regardless so we don't leak the child or a zombie.
	_, _ = io.Copy(io.Discard, stdout)
	_ = cmd.Wait()
	return frame, ok
}

// scanFirstStatusFrame reads NDJSON lines and returns the first
// pane.agent_status_changed frame whose agent_status == want. Lines that
// aren't that frame (acks, the "timed out" human string, error envelopes) are
// skipped, returning ok=false at EOF.
func scanFirstStatusFrame(r io.Reader, want string) (herdrStatusFrame, bool) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] != '{' {
			continue
		}
		var f herdrStatusFrame
		if err := json.Unmarshal([]byte(line), &f); err != nil {
			continue
		}
		if f.Event == "pane.agent_status_changed" && f.Data.AgentStatus == want {
			return f, true
		}
	}
	return herdrStatusFrame{}, false
}

// httpPost is the real Tier-2 transport: an anonymous text/plain POST (ntfy
// up* topics are anonymous-write, so no auth header). Short timeout so a dead
// endpoint can't wedge the finish handler.
func httpPost(url, body string) (int, error) {
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Post(url, "text/plain", strings.NewReader(body))
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return resp.StatusCode, nil
}
