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

// A herdr agent finish surfaces as one of TWO public agent statuses, both
// derived from the same internal AgentState::Idle (herdr
// app/api_helpers.rs:75-76):
//
//   - "done" — finished, NOT yet seen by any interactive client (no herdr UI
//     attached with the agent's tab focused). The common headless case.
//   - "idle" — finished, ALREADY seen (an interactive herdr client had the
//     agent's tab focused when it finished, so herdr immediately marked the
//     pane seen and collapsed done->idle).
//
// Either means "the agent stopped and wants the human" — i.e. agent-finished.
// We must treat BOTH as a finish, or finishes that land directly in "idle"
// (focused-tab case) are silently dropped.
func isFinishedStatus(s string) bool { return s == "idle" || s == "done" }

// waitStatus is what we pass to `herdr wait agent-status <pane> --status`.
// We wait on "idle": that catches the live working->idle transition (the
// focused-tab finish) with low latency. The complementary working->done
// transition (headless finish) is caught by the reconcile() snapshot path
// (it edge-emits any pane found resting in a finished status on the next
// `agent list` / 5s tick), so both finish flavors are covered.
//
// NB: herdr's `wait agent-status` subscription is a strict single-status
// server-side filter (subscriptions.rs:386 `wanted != agent_status =>
// continue`) — unlike `agent wait` it does NOT auto-include done when waiting
// on idle. The scanner below still accepts either via isFinishedStatus so a
// done frame on this pipe (should herdr ever deliver one) is honored, but the
// authoritative done catch is reconcile, not this waiter.
const waitStatus = "idle"

// finishedMark is the sentinel lastStatus value emitFinish writes after firing.
// It collapses the done<->idle pair into ONE "already finished" state so the
// done->idle (or idle->done) acknowledgment transition never double-fires.
// emitFinish is the sole writer of finishedMark; reconcile only ever writes
// raw herdr statuses (working/blocked/unknown/...), so when a finished pane
// resumes work reconcile overwrites finishedMark with the live status and
// re-arms a waiter, re-arming the next finish as a fresh edge.
const finishedMark = "finished"

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

// shouldEmitFinish is the pure edge-gate: given the PRIOR lastStatus for a
// pane, report whether a freshly observed finish should actually emit. It
// suppresses when the pane was already in a finished status (idle/done) or was
// already marked finished — so the done<->idle acknowledgment transition, and
// repeated finish observations from waiter+snapshot racing, never double-fire.
func shouldEmitFinish(prev string) bool {
	return !(isFinishedStatus(prev) || prev == finishedMark)
}

// watchState holds the daemon's mutable edge-trigger state: which panes have a
// live waiter goroutine, and the last status seen per pane. Splitting it out
// (with reconcile/prime/emitFinish as methods) lets the tests drive the core
// logic synchronously instead of through the run() goroutine soup.
type watchState struct {
	w *herdrWatcher

	mu sync.Mutex
	// active tracks pane_ids that currently have a live waiter goroutine, so
	// reconciliation never double-spawns for the same pane.
	active map[string]context.CancelFunc
	// lastStatus is the last agent_status we observed per pane. It makes the
	// daemon EDGE-triggered: an agent_finished event fires only on the
	// transition INTO a finished status, never repeatedly while a pane rests
	// finished. emitFinish writes finishedMark here; reconcile writes raw
	// statuses. (herdr's `wait agent-status` is level-triggered — it returns
	// instantly for an already-finished pane — so without this gate a resting
	// finished pane would spin a hot re-arm loop emitting duplicate alerts.)
	lastStatus map[string]string
}

func newWatchState(w *herdrWatcher) *watchState {
	return &watchState{
		w:          w,
		active:     map[string]context.CancelFunc{},
		lastStatus: map[string]string{},
	}
}

// emitFinish fires Tier1+Tier2 finish handling for a pane, but only on the
// finished EDGE (see shouldEmitFinish). It collapses the marker to finishedMark
// so a following done<->idle ack is suppressed. Returns true iff it emitted.
func (s *watchState) emitFinish(f herdrStatusFrame) bool {
	pane := f.Data.PaneID
	s.mu.Lock()
	prev := s.lastStatus[pane]
	s.lastStatus[pane] = finishedMark
	s.mu.Unlock()
	if !shouldEmitFinish(prev) {
		return false // already finished / already counted; suppress duplicate.
	}
	s.w.onFinish(f)
	return true
}

// prime seeds lastStatus from the FIRST snapshot WITHOUT emitting, so a daemon
// (re)start does not re-alert every pane already resting in a finished status
// (this matters now that we treat idle as a finish — every already-seen pane is
// "idle" at startup). Panes resting finished are marked finishedMark (so the
// next real edge emits); non-resting panes record their raw status and arm a
// waiter, exactly as reconcile would. Trade-off: a finish that happened during
// daemon downtime is lost — acceptable, as the daemon is the sole producer.
func (s *watchState) prime(ctx context.Context, spawn func(paneID string)) error {
	agents, err := s.w.lister(ctx)
	if err != nil {
		return err
	}
	seen := indexAgents(agents)
	s.w.lastSnapshot.Store(snapshotPtr(seen))

	for _, a := range agents {
		if a.PaneID == "" {
			continue
		}
		if isFinishedStatus(a.AgentStatus) {
			// Resting finished at startup: record as already-finished, do NOT
			// emit, do NOT arm a waiter (a finished pane stays finished until
			// the human acts; reconcile will re-arm when it resumes work).
			s.mu.Lock()
			s.lastStatus[a.PaneID] = finishedMark
			s.mu.Unlock()
			continue
		}
		s.mu.Lock()
		s.lastStatus[a.PaneID] = a.AgentStatus
		s.mu.Unlock()
		spawn(a.PaneID)
	}
	return nil
}

// reconcile lists panes and reconciles waiters + edge state:
//   - records each pane's current status (so the next change is an edge);
//   - for a pane ALREADY finished (idle/done) in the snapshot, emits once on
//     the edge (no hot waiter — a finished pane stays finished until the human
//     acts);
//   - for any other pane, arms a low-latency `wait agent-status idle` goroutine
//     to catch the working->idle transition promptly.
//
// Vanished panes drop out of lastStatus so a recreated pane re-edges.
func (s *watchState) reconcile(ctx context.Context, spawn func(paneID string)) error {
	agents, err := s.w.lister(ctx)
	if err != nil {
		return err
	}
	seen := indexAgents(agents)
	s.w.lastSnapshot.Store(snapshotPtr(seen))

	// Prune lastStatus for panes that disappeared.
	s.mu.Lock()
	for pane := range s.lastStatus {
		if _, ok := seen[pane]; !ok {
			delete(s.lastStatus, pane)
		}
	}
	s.mu.Unlock()

	for _, a := range agents {
		if a.PaneID == "" {
			continue
		}
		if isFinishedStatus(a.AgentStatus) {
			// Edge-emit for already-finished panes; no waiter.
			s.emitFinish(frameFromAgent(a))
			continue
		}
		// Not finished now — record status and arm a waiter to catch the next
		// finish transition with low latency.
		s.mu.Lock()
		s.lastStatus[a.PaneID] = a.AgentStatus
		s.mu.Unlock()
		spawn(a.PaneID)
	}
	return nil
}

// indexAgents builds the pane_id->agent map, skipping entries with no pane_id.
func indexAgents(agents []herdrAgent) map[string]herdrAgent {
	seen := map[string]herdrAgent{}
	for _, a := range agents {
		if a.PaneID == "" {
			continue
		}
		seen[a.PaneID] = a
	}
	return seen
}

// run is the supervise loop: (re)snapshot panes, keep one finish-waiter alive
// per pane, and reconcile whenever a waiter returns. Exits cleanly on
// ctx cancellation (SIGINT/SIGTERM via cobra's signal context).
func (w *herdrWatcher) run(ctx context.Context) error {
	fmt.Fprintln(w.stderr, "termx watch-herdr: starting (herdr CLI integration, status=idle)")

	// done channel carries the pane_id of any waiter goroutine that returned,
	// so the supervisor knows to reconcile.
	type waiterDone struct {
		paneID string
		frame  herdrStatusFrame
		match  bool
	}
	doneCh := make(chan waiterDone, 16)

	st := newWatchState(w)
	backoff := herdrBackoffMin

	// spawnWaiter launches one finish-waiter for paneID if none is running.
	// Only meaningful for panes NOT already finished — those are caught by the
	// snapshot edge-check in reconcile, not by a waiter.
	spawnWaiter := func(paneID string) {
		st.mu.Lock()
		if _, ok := st.active[paneID]; ok {
			st.mu.Unlock()
			return
		}
		wctx, cancel := context.WithCancel(ctx)
		st.active[paneID] = cancel
		st.mu.Unlock()
		go func() {
			frame, ok := w.waiter(wctx, paneID, waitStatus)
			select {
			case doneCh <- waiterDone{paneID: paneID, frame: frame, match: ok}:
			case <-ctx.Done():
			}
		}()
	}

	// Prime the snapshot store so label lookups work before first reconcile.
	w.lastSnapshot.Store(snapshotPtr(map[string]herdrAgent{}))

	// prime (not reconcile) on the first snapshot: panes already resting in a
	// finished status at startup must NOT re-alert (now that idle counts as a
	// finish, every already-seen pane is "idle"). prime seeds lastStatus
	// without emitting.
	if err := st.prime(ctx, spawnWaiter); err != nil {
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
			st.mu.Lock()
			for _, cancel := range st.active {
				cancel()
			}
			st.mu.Unlock()
			fmt.Fprintln(w.stderr, "watch-herdr: shutting down")
			return nil

		case d := <-doneCh:
			st.mu.Lock()
			delete(st.active, d.paneID)
			st.mu.Unlock()
			if d.match && d.frame.Event == "pane.agent_status_changed" &&
				isFinishedStatus(d.frame.Data.AgentStatus) {
				// Edge-gated: emits only if this pane wasn't already finished.
				st.emitFinish(d.frame)
			}
			// Re-snapshot and reconcile. reconcile() decides whether to
			// re-arm a waiter: a still-finished pane gets no waiter (it's
			// already counted), so we never hot-loop; a pane that resumed work
			// re-arms.
			if err := st.reconcile(ctx, spawnWaiter); err != nil {
				// herdr likely went away; back off before the ticker retries.
				time.Sleep(jitterBackoff(&backoff))
			} else {
				backoff = herdrBackoffMin
			}

		case <-ticker.C:
			if err := st.reconcile(ctx, spawnWaiter); err != nil {
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
	// Rotation is folded into AppendEvent (rename-rotation from the fd size).
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

// herdrBin resolves the herdr CLI path. systemd --user services run with a
// sanitized PATH that omits ~/.local/bin (a common herdr install location), so
// a bare "herdr" exec fails there. Resolution order: $HERDR_BIN, then PATH,
// then well-known install dirs; falls back to "herdr" so a genuinely missing
// binary still surfaces a clear error. Re-resolved per call (cheap) so a herdr
// installed after the daemon starts is picked up on the next retry.
func herdrBin() string {
	if env := strings.TrimSpace(os.Getenv("HERDR_BIN")); env != "" {
		return env
	}
	if p, err := exec.LookPath("herdr"); err == nil {
		return p
	}
	if home, err := os.UserHomeDir(); err == nil {
		if cand := filepath.Join(home, ".local", "bin", "herdr"); isExecutableFile(cand) {
			return cand
		}
	}
	for _, cand := range []string{"/usr/local/bin/herdr", "/usr/bin/herdr"} {
		if isExecutableFile(cand) {
			return cand
		}
	}
	return "herdr"
}

// isExecutableFile reports whether path is a non-dir file with any execute bit.
func isExecutableFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir() && info.Mode()&0o111 != 0
}

// cliAgentLister runs `herdr agent list` and parses the JSON envelope.
func cliAgentLister(ctx context.Context) ([]herdrAgent, error) {
	out, err := exec.CommandContext(ctx, herdrBin(), "agent", "list").Output()
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

// cliAgentWaiter runs `herdr wait agent-status <pane> --status idle` and reads
// the first finish frame from stdout. We pass status (waitStatus="idle") to
// herdr but accept ANY finished status (idle OR done) from the stream via
// isFinishedStatus — so a done frame is honored too, even though herdr's
// single-status subscription normally only delivers idle frames here. Returns
// ok=true only on a real finished pane.agent_status_changed match; timeout /
// pane-gone / parse failure all return ok=false so the supervisor just re-lists.
func cliAgentWaiter(ctx context.Context, paneID, status string) (herdrStatusFrame, bool) {
	cmd := exec.CommandContext(ctx, herdrBin(), "wait", "agent-status", paneID,
		"--status", status, "--timeout", fmt.Sprintf("%d", waitTimeoutMs))
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return herdrStatusFrame{}, false
	}
	cmd.Stderr = nil
	if err := cmd.Start(); err != nil {
		return herdrStatusFrame{}, false
	}

	frame, ok := scanFirstStatusFrame(stdout, isFinishedStatus)
	// Drain & reap regardless so we don't leak the child or a zombie.
	_, _ = io.Copy(io.Discard, stdout)
	_ = cmd.Wait()
	return frame, ok
}

// scanFirstStatusFrame reads NDJSON lines and returns the first
// pane.agent_status_changed frame whose agent_status satisfies match. Lines
// that aren't an accepted frame (acks, the "timed out" human string, error
// envelopes, a non-matching status) are skipped, returning ok=false at EOF.
//
// match is a predicate (not a single literal) so a finish that arrives as a
// "done" frame is accepted while waiting on idle — otherwise a working->done
// transition delivered on this pipe would be filtered out.
func scanFirstStatusFrame(r io.Reader, match func(status string) bool) (herdrStatusFrame, bool) {
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
		if f.Event == "pane.agent_status_changed" && match(f.Data.AgentStatus) {
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
