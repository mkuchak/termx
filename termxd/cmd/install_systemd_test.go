package cmd

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/mkuchak/termx/termxd/cmd/internal"
)

// TestHerdrWatchUnitBody pins the systemd user unit body to the exact contract:
// a network-online After, the %h-relative ExecStart, always-restart, and a
// default.target install. This is load-bearing — a drift here silently breaks
// 24/7 Tier-2 alerting.
func TestHerdrWatchUnitBody(t *testing.T) {
	want := "[Unit]\n" +
		"Description=termx herdr watcher\n" +
		"After=network-online.target\n" +
		"\n" +
		"[Service]\n" +
		"Environment=PATH=%h/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
		"ExecStart=%h/.local/bin/termx watch-herdr\n" +
		"Restart=always\n" +
		"RestartSec=2\n" +
		"\n" +
		"[Install]\n" +
		"WantedBy=default.target\n"
	if herdrWatchUnit != want {
		t.Fatalf("herdrWatchUnit mismatch:\n--- got ---\n%s\n--- want ---\n%s", herdrWatchUnit, want)
	}
	// Guard the individual must-have directives explicitly so a future
	// reformat can't quietly drop one.
	for _, sub := range []string{
		"Environment=PATH=%h/.local/bin:",
		"ExecStart=%h/.local/bin/termx watch-herdr",
		"Restart=always",
		"WantedBy=default.target",
	} {
		if !strings.Contains(herdrWatchUnit, sub) {
			t.Errorf("herdrWatchUnit missing %q", sub)
		}
	}
	if herdrWatchUnitName != "termx-herdr-watch.service" {
		t.Errorf("unit name = %q, want termx-herdr-watch.service", herdrWatchUnitName)
	}
}

// TestNtfyServerYML pins the starter ntfy config to the CHANGE-ME base-url and
// the loopback-only listener (TLS is terminated by the user's reverse proxy).
func TestNtfyServerYML(t *testing.T) {
	for _, sub := range []string{
		"base-url: https://ntfy.CHANGE-ME",
		`listen-http: "127.0.0.1:2586"`,
	} {
		if !strings.Contains(ntfyServerYML, sub) {
			t.Errorf("ntfyServerYML missing %q\n got:\n%s", sub, ntfyServerYML)
		}
	}
}

func TestShellQuote(t *testing.T) {
	cases := []struct{ in, want string }{
		{"plain", "'plain'"},
		{"two words", "'two words'"},
		{"it's", `'it'\''s'`},
		{"a'b'c", `'a'\''b'\''c'`},
	}
	for _, c := range cases {
		if got := shellQuote(c.in); got != c.want {
			t.Errorf("shellQuote(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

// TestBuildDryRunIncludesUnit verifies the install phase wiring is reflected in
// --dry-run: the watcher unit write must be reported at
// ~/.config/systemd/user/termx-herdr-watch.service.
func TestBuildDryRunIncludesUnit(t *testing.T) {
	home := t.TempDir()
	p := &internal.Paths{Home: home}
	rep, err := buildDryRun(p)
	if err != nil {
		t.Fatalf("buildDryRun: %v", err)
	}
	wantPath := "~/.config/systemd/user/termx-herdr-watch.service"
	var found bool
	for _, ch := range rep.Changes {
		if ch.Type == "write_unit" {
			found = true
			if ch.Path != wantPath {
				t.Errorf("unit change path = %q, want %q", ch.Path, wantPath)
			}
			if ch.Mode != "0644" {
				t.Errorf("unit change mode = %q, want 0644", ch.Mode)
			}
		}
	}
	if !found {
		t.Fatalf("buildDryRun produced no write_unit change; got %+v", rep.Changes)
	}
}

// TestRemoveHerdrWatchService writes a unit file under a fake HOME and checks
// removeHerdrWatchService deletes it (true), and that a second call with the
// file already gone reports false. The best-effort systemctl stop/disable run
// against the real user bus for a never-enabled unit, which is a harmless
// no-op.
func TestRemoveHerdrWatchService(t *testing.T) {
	home := t.TempDir()
	p := &internal.Paths{Home: home}
	unitDir := filepath.Join(home, ".config", "systemd", "user")
	if err := os.MkdirAll(unitDir, 0o755); err != nil {
		t.Fatal(err)
	}
	unitPath := filepath.Join(unitDir, herdrWatchUnitName)
	if err := os.WriteFile(unitPath, []byte(herdrWatchUnit), 0o644); err != nil {
		t.Fatal(err)
	}

	var out, errb bytes.Buffer
	if removed := removeHerdrWatchService(p, &out, &errb); !removed {
		t.Errorf("first removeHerdrWatchService = false, want true (stderr: %s)", errb.String())
	}
	if internal.Exists(unitPath) {
		t.Errorf("unit file still present after removal: %s", unitPath)
	}
	// Idempotent: gone-already returns false without error noise.
	out.Reset()
	errb.Reset()
	if removed := removeHerdrWatchService(p, &out, &errb); removed {
		t.Errorf("second removeHerdrWatchService = true, want false")
	}
}
