package cmd

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/mkuchak/termx/termxd/cmd/internal"
)

// fake runners for the injectable preflight seams.

func fakeCharmap(out string, err error) charmapRunner {
	return func() (string, error) { return out, err }
}

func fakeLookPath(found bool) lookPathFunc {
	return func(name string) (string, error) {
		if found {
			return "/usr/sbin/" + name, nil
		}
		return "", errors.New("executable file not found in $PATH")
	}
}

func fakeUfwStatus(out string, err error) ufwStatusRunner {
	return func() (string, error) { return out, err }
}

// ---- locale ----

func TestPreflightLocaleUTF8Silent(t *testing.T) {
	var errb bytes.Buffer
	preflightLocale(&errb, internal.DistroUbuntu, fakeCharmap("UTF-8", nil))
	if errb.Len() != 0 {
		t.Errorf("UTF-8 charmap produced output, want silence:\n%s", errb.String())
	}
}

func TestPreflightLocaleCLocaleWarns(t *testing.T) {
	var errb bytes.Buffer
	preflightLocale(&errb, internal.DistroUbuntu, fakeCharmap("ANSI_X3.4-1968", nil))
	got := errb.String()
	for _, sub := range []string{
		`warning: locale charmap is "ANSI_X3.4-1968", not UTF-8 — mosh-server will refuse to start under this locale.`,
		"fix: sudo locale-gen en_US.UTF-8",
		"the termx app forces LANG=C.UTF-8 when starting mosh-server",
	} {
		if !strings.Contains(got, sub) {
			t.Errorf("locale warning missing %q\n got:\n%s", sub, got)
		}
	}
}

func TestPreflightLocaleNonDebianGenericFix(t *testing.T) {
	var errb bytes.Buffer
	preflightLocale(&errb, internal.DistroArch, fakeCharmap("ANSI_X3.4-1968", nil))
	got := errb.String()
	want := "fix: enable a UTF-8 locale (debian/ubuntu: sudo locale-gen en_US.UTF-8)"
	if !strings.Contains(got, want) {
		t.Errorf("non-debian locale fix missing %q\n got:\n%s", want, got)
	}
}

func TestPreflightLocaleCommandErrorWarns(t *testing.T) {
	var errb bytes.Buffer
	preflightLocale(&errb, internal.DistroUnknown, fakeCharmap("", errors.New("exec: \"locale\": not found")))
	got := errb.String()
	if !strings.Contains(got, "warning: could not determine locale charmap") {
		t.Errorf("missing could-not-determine warning, got:\n%s", got)
	}
	if !strings.Contains(got, "mosh-server needs a UTF-8 locale to run") {
		t.Errorf("missing mosh-server rationale, got:\n%s", got)
	}
}

// ---- firewall ----

const ufwActiveNoMosh = `Status: active

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW       Anywhere
`

const ufwActiveWithMosh = `Status: active

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW       Anywhere
60000:60010/udp            ALLOW       Anywhere
`

func TestPreflightFirewallUfwActiveRuleMissing(t *testing.T) {
	var out, errb bytes.Buffer
	preflightFirewall(&out, &errb, fakeLookPath(true), fakeUfwStatus(ufwActiveNoMosh, nil))
	got := errb.String()
	for _, sub := range []string{
		"warning: ufw is active but mosh's UDP range is not allowed — mosh connections will hang.",
		"fix: sudo ufw allow 60000:60010/udp",
	} {
		if !strings.Contains(got, sub) {
			t.Errorf("ufw remedy missing %q\n got:\n%s", sub, got)
		}
	}
	if out.Len() != 0 {
		t.Errorf("stdout should stay quiet on the remedy path, got:\n%s", out.String())
	}
}

func TestPreflightFirewallUfwActiveRulePresentSilent(t *testing.T) {
	var out, errb bytes.Buffer
	preflightFirewall(&out, &errb, fakeLookPath(true), fakeUfwStatus(ufwActiveWithMosh, nil))
	if out.Len() != 0 || errb.Len() != 0 {
		t.Errorf("allowed rule should be silent, got stdout:\n%s\nstderr:\n%s", out.String(), errb.String())
	}
}

func TestPreflightFirewallUfwAbsentGenericNote(t *testing.T) {
	var out, errb bytes.Buffer
	preflightFirewall(&out, &errb, fakeLookPath(false), fakeUfwStatus("", errors.New("never called")))
	want := "note: mosh needs inbound UDP 60000-60010 — check your provider firewall.\n"
	if out.String() != want {
		t.Errorf("generic note = %q, want %q", out.String(), want)
	}
	if errb.Len() != 0 {
		t.Errorf("no warnings expected without ufw, got:\n%s", errb.String())
	}
}

func TestPreflightFirewallUfwInactiveGenericNote(t *testing.T) {
	var out, errb bytes.Buffer
	preflightFirewall(&out, &errb, fakeLookPath(true), fakeUfwStatus("Status: inactive\n", nil))
	if !strings.Contains(out.String(), "note: mosh needs inbound UDP 60000-60010 — check your provider firewall.") {
		t.Errorf("inactive ufw should fall back to the generic note, got:\n%s", out.String())
	}
}

func TestPreflightFirewallUfwStatusErrorGenericNote(t *testing.T) {
	// `ufw status` needs root; a permission error must degrade to the
	// generic note, never to a wrong "rule missing" remedy.
	var out, errb bytes.Buffer
	preflightFirewall(&out, &errb, fakeLookPath(true),
		fakeUfwStatus("ERROR: You need to be root to run this script\n", errors.New("exit status 1")))
	if !strings.Contains(out.String(), "note: mosh needs inbound UDP 60000-60010 — check your provider firewall.") {
		t.Errorf("ufw status error should fall back to the generic note, got:\n%s", out.String())
	}
	if errb.Len() != 0 {
		t.Errorf("no warning expected when status is unreadable, got:\n%s", errb.String())
	}
}

// ---- dry-run wire contract ----

// TestDryRunExcludesPreflight runs the real `install --dry-run` path against a
// throwaway HOME and asserts stdout is pure JSON ({"changes":[...]}) with none
// of the preflight guidance strings — the Android wizard parses this output
// verbatim, so preflight prints must never leak into it.
func TestDryRunExcludesPreflight(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	var out, errb bytes.Buffer
	if err := runInstall(&out, &errb, true, false, false); err != nil {
		t.Fatalf("runInstall(dry-run): %v", err)
	}
	var rep dryRunReport
	if err := json.Unmarshal(out.Bytes(), &rep); err != nil {
		t.Fatalf("dry-run stdout is not valid JSON: %v\n%s", err, out.String())
	}
	if len(rep.Changes) == 0 {
		t.Fatal("dry-run report has no changes")
	}
	for _, sub := range []string{"locale", "ufw", "UDP", "warning:", "note:"} {
		if strings.Contains(out.String(), sub) {
			t.Errorf("dry-run stdout leaked preflight text %q:\n%s", sub, out.String())
		}
	}
	if errb.Len() != 0 {
		t.Errorf("dry-run wrote to stderr: %s", errb.String())
	}
}
