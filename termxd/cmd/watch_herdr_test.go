package cmd

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/mkuchak/termx/termxd/cmd/internal"
)

// TestHerdrBinHonorsEnv verifies $HERDR_BIN overrides PATH/dir resolution, so a
// systemd-user service (sanitized PATH that omits ~/.local/bin) can still
// locate herdr.
func TestHerdrBinHonorsEnv(t *testing.T) {
	t.Setenv("HERDR_BIN", "/custom/path/to/herdr")
	if got := herdrBin(); got != "/custom/path/to/herdr" {
		t.Errorf("herdrBin() with $HERDR_BIN = %q, want /custom/path/to/herdr", got)
	}
}

// TestHerdrBinFallsBackToName confirms that with no env override, nothing on
// PATH, and no ~/.local/bin/herdr, herdrBin returns the bare name so exec
// surfaces a normal not-found error rather than a fabricated path.
func TestHerdrBinFallsBackToName(t *testing.T) {
	t.Setenv("HERDR_BIN", "")
	t.Setenv("PATH", t.TempDir()) // empty dir: no herdr on PATH
	t.Setenv("HOME", t.TempDir()) // no ~/.local/bin/herdr
	// The well-known dirs may legitimately hold herdr on some hosts; only
	// assert the bare-name fallback when they don't.
	if isExecutableFile("/usr/local/bin/herdr") || isExecutableFile("/usr/bin/herdr") {
		t.Skip("herdr present in a well-known dir on this host; fallback not exercisable")
	}
	if got := herdrBin(); got != "herdr" {
		t.Errorf("herdrBin() fallback = %q, want \"herdr\"", got)
	}
}

// statusFrame builds a herdr pane.agent_status_changed frame for tests.
func statusFrame(pane, ws, agent, status string) herdrStatusFrame {
	var f herdrStatusFrame
	f.Event = "pane.agent_status_changed"
	f.Data.PaneID = pane
	f.Data.WorkspaceID = ws
	f.Data.Agent = agent
	f.Data.AgentStatus = status
	return f
}

// doneFrame builds a herdr "done" (finished, not-yet-seen) frame for tests.
func doneFrame(pane, ws, agent string) herdrStatusFrame {
	return statusFrame(pane, ws, agent, "done")
}

// idleFrame builds a herdr "idle" (finished, already-seen) frame for tests —
// the finish flavor that lands directly in idle when a client has the agent's
// tab focused.
func idleFrame(pane, ws, agent string) herdrStatusFrame {
	return statusFrame(pane, ws, agent, "idle")
}

func TestFinishFields(t *testing.T) {
	cases := []struct {
		name          string
		frame         herdrStatusFrame
		snap          map[string]herdrAgent
		wantSession   string
		wantAgent     string
		wantWorkspace string
	}{
		{
			name:          "snapshot supplies cwd basename as workspace label",
			frame:         doneFrame("w1-1", "w1", "claude"),
			snap:          map[string]herdrAgent{"w1-1": {PaneID: "w1-1", WorkspaceID: "w1", Agent: "claude", Cwd: "/home/u/workspace/termx"}},
			wantSession:   "w1-1",
			wantAgent:     "claude",
			wantWorkspace: "termx",
		},
		{
			name:          "no snapshot falls back to workspace_id",
			frame:         doneFrame("w2-1", "w2abcdef", "codex"),
			snap:          map[string]herdrAgent{},
			wantSession:   "w2-1",
			wantAgent:     "codex",
			wantWorkspace: "w2abcdef",
		},
		{
			name:          "empty agent in frame filled from snapshot",
			frame:         func() herdrStatusFrame { f := doneFrame("w3-1", "w3", ""); return f }(),
			snap:          map[string]herdrAgent{"w3-1": {PaneID: "w3-1", WorkspaceID: "w3", Agent: "claude", Cwd: "/srv/proj"}},
			wantSession:   "w3-1",
			wantAgent:     "claude",
			wantWorkspace: "proj",
		},
		{
			name:          "empty agent everywhere defaults to 'agent'",
			frame:         func() herdrStatusFrame { f := doneFrame("w4-1", "w4", ""); return f }(),
			snap:          map[string]herdrAgent{},
			wantSession:   "w4-1",
			wantAgent:     "agent",
			wantWorkspace: "w4",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			session, agent, workspace := finishFields(tc.frame, tc.snap)
			if session != tc.wantSession {
				t.Errorf("session = %q, want %q", session, tc.wantSession)
			}
			if agent != tc.wantAgent {
				t.Errorf("agent = %q, want %q", agent, tc.wantAgent)
			}
			if workspace != tc.wantWorkspace {
				t.Errorf("workspace = %q, want %q", workspace, tc.wantWorkspace)
			}
		})
	}
}

func TestWorkspaceLabel(t *testing.T) {
	cases := map[string]string{
		"/home/u/workspace/termx":  "termx",
		"/home/u/workspace/termx/": "termx",
		"/srv/a/b/c":               "c",
		"":                         "",
		"/":                        "",
		".":                        "",
	}
	for in, want := range cases {
		if got := workspaceLabel(in); got != want {
			t.Errorf("workspaceLabel(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestPublishBody(t *testing.T) {
	got := publishBody("claude", "termx")
	want := "herdr: claude finished in termx"
	if got != want {
		t.Errorf("publishBody = %q, want %q", got, want)
	}
}

func TestSanitizeEndpoint(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"https://ntfy.example.com/upABC123", "https://ntfy.example.com/upABC123"},
		{"  https://ntfy.example.com/upABC123  \n", "https://ntfy.example.com/upABC123"},
		{"https://ntfy.example.com/upABC123\nsecond line", "https://ntfy.example.com/upABC123"},
		{"http://10.0.0.5:8080/upXYZ", "http://10.0.0.5:8080/upXYZ"},
		{"", ""},
		{"   \n  ", ""},
		{"not-a-url", ""},
		{"ftp://nope/x", ""},
		{"# a comment", ""},
	}
	for _, tc := range cases {
		if got := sanitizeEndpoint(tc.in); got != tc.want {
			t.Errorf("sanitizeEndpoint(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestParseAgentList(t *testing.T) {
	// Real envelope shape captured from `herdr agent list` (herdr 0.6.8).
	raw := []byte(`{"id":"cli:agent:list","result":{"agents":[` +
		`{"agent":"claude","agent_status":"idle","cwd":"/home/u/ws/a","pane_id":"wa-1","workspace_id":"wa"},` +
		`{"agent":"claude","agent_status":"done","cwd":"/home/u/ws/b","pane_id":"wb-1","workspace_id":"wb"}` +
		`],"type":"agent_list"}}`)
	agents, err := parseAgentList(raw)
	if err != nil {
		t.Fatalf("parseAgentList: %v", err)
	}
	if len(agents) != 2 {
		t.Fatalf("agents = %d, want 2", len(agents))
	}
	if agents[1].PaneID != "wb-1" || agents[1].AgentStatus != "done" || agents[1].Cwd != "/home/u/ws/b" {
		t.Errorf("agents[1] = %+v", agents[1])
	}

	// Garbage in -> error, not panic.
	if _, err := parseAgentList([]byte("not json")); err == nil {
		t.Error("expected error on non-JSON input")
	}
}

func TestIsFinishedStatus(t *testing.T) {
	cases := map[string]bool{
		"idle":     true,
		"done":     true,
		"working":  false,
		"blocked":  false,
		"unknown":  false,
		"":         false,
		"finished": false, // the internal sentinel is NOT a herdr status
	}
	for in, want := range cases {
		if got := isFinishedStatus(in); got != want {
			t.Errorf("isFinishedStatus(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestScanFirstStatusFrame(t *testing.T) {
	// Mixed stream: ack line, a working frame, then the finish frame.
	stream := strings.Join([]string{
		`{"id":"cli:wait:agent-status:sub:0:probe","result":{"type":"subscription_started"}}`,
		`{"event":"pane.agent_status_changed","data":{"pane_id":"wb-1","workspace_id":"wb","agent_status":"working","agent":"claude"}}`,
		`{"event":"pane.agent_status_changed","data":{"pane_id":"wb-1","workspace_id":"wb","agent_status":"done","agent":"claude"}}`,
	}, "\n")
	f, ok := scanFirstStatusFrame(strings.NewReader(stream), isFinishedStatus)
	if !ok {
		t.Fatal("expected a finish match")
	}
	if f.Data.PaneID != "wb-1" || f.Data.AgentStatus != "done" || f.Data.Agent != "claude" {
		t.Errorf("frame = %+v", f)
	}

	// THE CORE FIX: an idle-only finish frame now MATCHES (previously, waiting
	// on the "done" literal, this was dropped and the finish was missed).
	idleOnly := `{"event":"pane.agent_status_changed","data":{"pane_id":"wb-1","agent_status":"idle"}}` + "\n"
	fi, ok := scanFirstStatusFrame(strings.NewReader(idleOnly), isFinishedStatus)
	if !ok {
		t.Fatal("expected idle frame to match isFinishedStatus (the core idle-finish fix)")
	}
	if fi.Data.AgentStatus != "idle" {
		t.Errorf("idle frame = %+v", fi)
	}

	// A predicate that accepts nothing finished -> a working-only stream yields
	// no match (confirms the predicate is actually consulted).
	working := `{"event":"pane.agent_status_changed","data":{"pane_id":"wb-1","agent_status":"working"}}` + "\n"
	if _, ok := scanFirstStatusFrame(strings.NewReader(working), isFinishedStatus); ok {
		t.Error("expected no finish match for working-only stream")
	}

	// "timed out" human line + EOF -> ok=false, no panic.
	if _, ok := scanFirstStatusFrame(strings.NewReader("timed out waiting for agent status change\n"), isFinishedStatus); ok {
		t.Error("expected no match for timeout line")
	}
}

// TestEmitFinishEdgeGate exercises the pure edge predicate: a fresh finish
// (prev empty / working / blocked) emits; a finish observed while the pane was
// already finished (idle/done) or already marked finished is suppressed — so
// the done<->idle acknowledgment never double-fires.
func TestEmitFinishEdgeGate(t *testing.T) {
	cases := map[string]bool{
		"":         true,  // never seen -> emit
		"working":  true,  // working -> finished is a real edge
		"blocked":  true,  // blocked -> finished is a real edge
		"unknown":  true,  // unknown -> finished is a real edge
		"idle":     false, // already finished (seen) -> suppress
		"done":     false, // already finished (unseen) -> suppress
		"finished": false, // already collapsed to the sentinel -> suppress
	}
	for prev, want := range cases {
		if got := shouldEmitFinish(prev); got != want {
			t.Errorf("shouldEmitFinish(%q) = %v, want %v", prev, got, want)
		}
	}
}

// TestOnFinishAppendsEventAndPosts exercises the full Tier-1 + Tier-2 path
// with a fake poster, asserting the NDJSON payload matches the Kotlin
// TermxEvent.AgentFinished(source, agent, workspace) contract exactly.
func TestOnFinishAppendsEventAndPosts(t *testing.T) {
	dir := isolateHome(t)
	// Write a synced ntfy endpoint.
	endpoint := "https://ntfy.example.com/upTOPIC"
	if err := os.WriteFile(filepath.Join(dir, "ntfy-endpoint"), []byte(endpoint+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	paths := mustPaths(t)
	var gotURL, gotBody string
	var posted int
	w := &herdrWatcher{
		paths:  paths,
		stdout: &bytes.Buffer{},
		stderr: &bytes.Buffer{},
		poster: func(url, body string) (int, error) {
			gotURL, gotBody = url, body
			posted++
			return 200, nil
		},
		now: func() time.Time { return time.Now().UTC() },
	}
	// Prime snapshot so the workspace label resolves to the cwd basename.
	w.lastSnapshot.Store(snapshotPtr(map[string]herdrAgent{
		"wb-1": {PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", Cwd: "/home/u/workspace/termx"},
	}))

	w.onFinish(doneFrame("wb-1", "wb", "claude"))

	// Tier 2 assertions.
	if posted != 1 {
		t.Fatalf("poster called %d times, want 1", posted)
	}
	if gotURL != endpoint {
		t.Errorf("POST url = %q, want %q", gotURL, endpoint)
	}
	if gotBody != "herdr: claude finished in termx" {
		t.Errorf("POST body = %q", gotBody)
	}

	// Tier 1 assertions: exactly one event with the contract fields.
	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1", len(events))
	}
	ev := events[0]
	if ev["type"] != "agent_finished" {
		t.Errorf("type = %v, want agent_finished", ev["type"])
	}
	if ev["session"] != "wb-1" {
		t.Errorf("session = %v, want wb-1", ev["session"])
	}
	if ev["source"] != "herdr" {
		t.Errorf("source = %v, want herdr", ev["source"])
	}
	if ev["agent"] != "claude" {
		t.Errorf("agent = %v, want claude", ev["agent"])
	}
	if ev["workspace"] != "termx" {
		t.Errorf("workspace = %v, want termx", ev["workspace"])
	}
	// ts must be present (RFC3339Nano).
	if _, ok := ev["ts"].(string); !ok {
		t.Errorf("ts missing or not a string: %v", ev["ts"])
	}
	// No stray fields beyond the contract.
	allowed := map[string]bool{"ts": true, "type": true, "session": true, "agent": true, "workspace": true, "source": true}
	for k := range ev {
		if !allowed[k] {
			t.Errorf("unexpected field %q in event", k)
		}
	}
}

// TestOnFinishNoEndpointSkipsPost verifies Tier 2 is skipped (no panic, no
// POST) when ~/.termx/ntfy-endpoint is absent, while Tier 1 still fires.
func TestOnFinishNoEndpointSkipsPost(t *testing.T) {
	dir := isolateHome(t)
	paths := mustPaths(t)
	var posted int
	w := &herdrWatcher{
		paths:  paths,
		stdout: &bytes.Buffer{},
		stderr: &bytes.Buffer{},
		poster: func(url, body string) (int, error) { posted++; return 200, nil },
		now:    func() time.Time { return time.Now().UTC() },
	}
	w.lastSnapshot.Store(snapshotPtr(map[string]herdrAgent{}))

	w.onFinish(doneFrame("wc-1", "wc", "claude"))

	if posted != 0 {
		t.Errorf("poster called %d times, want 0 (no endpoint file)", posted)
	}
	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want 1", len(events))
	}
	if events[0]["workspace"] != "wc" {
		t.Errorf("workspace = %v, want wc (fallback to workspace_id)", events[0]["workspace"])
	}
}

// mustPaths resolves paths against the (isolated) HOME or fails the test.
func mustPaths(t *testing.T) *internal.Paths {
	t.Helper()
	p, err := internal.ResolvePaths()
	if err != nil {
		t.Fatalf("ResolvePaths: %v", err)
	}
	return p
}

// newTestWatcher wires a herdrWatcher with a no-op poster and discard buffers,
// rooted at the isolated HOME, ready for synchronous prime/reconcile driving.
func newTestWatcher(t *testing.T) *herdrWatcher {
	t.Helper()
	return &herdrWatcher{
		paths:  mustPaths(t),
		stdout: &bytes.Buffer{},
		stderr: &bytes.Buffer{},
		poster: func(string, string) (int, error) { return 200, nil },
		now:    func() time.Time { return time.Now().UTC() },
	}
}

// scriptedLister returns a lister that yields the next scripted snapshot on each
// call (clamping at the last once exhausted), so a test can step the daemon
// through a status timeline by calling prime()/reconcile() in sequence.
func scriptedLister(steps ...[]herdrAgent) agentLister {
	i := 0
	return func(context.Context) ([]herdrAgent, error) {
		s := steps[i]
		if i < len(steps)-1 {
			i++
		}
		return s, nil
	}
}

// noopSpawn is a waiter-spawn that does nothing — the integration tests drive
// the edge logic via prime/reconcile directly, so no real waiter goroutines are
// needed (and none must run, to keep the tests deterministic). The working->idle
// transition is delivered by the NEXT reconcile's snapshot instead of a waiter.
func noopSpawn(string) {}

// TestRunCatchesIdleFinish is the core fix: a pane that goes working then lands
// directly in "idle" (focused-tab finish) must emit exactly one agent_finished.
// Pre-fix, the daemon only waited on "done" and this finish was silently lost.
func TestRunCatchesIdleFinish(t *testing.T) {
	dir := isolateHome(t)
	w := newTestWatcher(t)
	w.lister = scriptedLister(
		[]herdrAgent{{PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", AgentStatus: "working", Cwd: "/home/u/ws/termx"}},
		[]herdrAgent{{PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", AgentStatus: "idle", Cwd: "/home/u/ws/termx"}},
	)
	st := newWatchState(w)
	ctx := context.Background()

	if err := st.prime(ctx, noopSpawn); err != nil { // working: record, no emit
		t.Fatalf("prime: %v", err)
	}
	if got := readEvents(t, dir); len(got) != 0 {
		t.Fatalf("prime emitted %d events, want 0", len(got))
	}
	if err := st.reconcile(ctx, noopSpawn); err != nil { // working -> idle: emit
		t.Fatalf("reconcile: %v", err)
	}

	events := readEvents(t, dir)
	if len(events) != 1 {
		t.Fatalf("events = %d, want exactly 1 (idle finish must fire once)", len(events))
	}
	if events[0]["type"] != "agent_finished" || events[0]["session"] != "wb-1" || events[0]["workspace"] != "termx" {
		t.Errorf("event = %v", events[0])
	}
}

// TestRunNoDoubleFireOnAck: a finish that arrives as "done" and is then
// acknowledged to "idle" (the done->idle collapse when the human focuses the
// tab) must emit exactly once — the edge gate suppresses the idle ack.
func TestRunNoDoubleFireOnAck(t *testing.T) {
	dir := isolateHome(t)
	w := newTestWatcher(t)
	w.lister = scriptedLister(
		[]herdrAgent{{PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", AgentStatus: "working", Cwd: "/home/u/ws/termx"}},
		[]herdrAgent{{PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", AgentStatus: "done", Cwd: "/home/u/ws/termx"}},
		[]herdrAgent{{PaneID: "wb-1", WorkspaceID: "wb", Agent: "claude", AgentStatus: "idle", Cwd: "/home/u/ws/termx"}},
	)
	st := newWatchState(w)
	ctx := context.Background()

	if err := st.prime(ctx, noopSpawn); err != nil { // working
		t.Fatalf("prime: %v", err)
	}
	if err := st.reconcile(ctx, noopSpawn); err != nil { // working -> done: emit
		t.Fatalf("reconcile done: %v", err)
	}
	if err := st.reconcile(ctx, noopSpawn); err != nil { // done -> idle ack: suppress
		t.Fatalf("reconcile idle: %v", err)
	}

	if events := readEvents(t, dir); len(events) != 1 {
		t.Fatalf("events = %d, want exactly 1 (done then idle ack must not double-fire)", len(events))
	}
}

// TestRunSuppressesRestingAtStartup: panes already resting finished (idle and
// done) when the daemon starts must NOT re-alert. prime seeds their state
// without emitting, so a restart doesn't replay every already-seen finish.
func TestRunSuppressesRestingAtStartup(t *testing.T) {
	dir := isolateHome(t)
	w := newTestWatcher(t)
	w.lister = scriptedLister(
		[]herdrAgent{
			{PaneID: "wa-1", WorkspaceID: "wa", Agent: "claude", AgentStatus: "idle", Cwd: "/home/u/ws/a"},
			{PaneID: "wb-1", WorkspaceID: "wb", Agent: "codex", AgentStatus: "done", Cwd: "/home/u/ws/b"},
		},
	)
	st := newWatchState(w)
	ctx := context.Background()

	if err := st.prime(ctx, noopSpawn); err != nil {
		t.Fatalf("prime: %v", err)
	}

	if events := readEvents(t, dir); len(events) != 0 {
		t.Fatalf("events = %d, want 0 (resting-finished panes must not replay on startup)", len(events))
	}
}
