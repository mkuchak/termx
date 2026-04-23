package internal

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUpsertMarkedBlock_NewFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	changed, err := UpsertMarkedBlock(p, "export PATH=/x:$PATH", 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true for new file")
	}
	b, _ := os.ReadFile(p)
	s := string(b)
	if !strings.Contains(s, BeginMarker) || !strings.Contains(s, EndMarker) {
		t.Errorf("markers missing: %q", s)
	}
	if !strings.Contains(s, "export PATH=/x:$PATH") {
		t.Errorf("body missing: %q", s)
	}
}

func TestUpsertMarkedBlock_Idempotent(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	if _, err := UpsertMarkedBlock(p, "foo", 0o600); err != nil {
		t.Fatal(err)
	}
	changed, err := UpsertMarkedBlock(p, "foo", 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Error("second identical upsert should be no-op")
	}
	b, _ := os.ReadFile(p)
	count := strings.Count(string(b), BeginMarker)
	if count != 1 {
		t.Errorf("expected 1 block, found %d", count)
	}
}

func TestUpsertMarkedBlock_ReplaceInPlace(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	seed := "line before\n" + BeginMarker + "\nold body\n" + EndMarker + "\nline after\n"
	if err := os.WriteFile(p, []byte(seed), 0o600); err != nil {
		t.Fatal(err)
	}
	changed, err := UpsertMarkedBlock(p, "new body", 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true")
	}
	b, _ := os.ReadFile(p)
	s := string(b)
	if strings.Contains(s, "old body") {
		t.Errorf("old body still present: %q", s)
	}
	if !strings.Contains(s, "new body") {
		t.Errorf("new body missing: %q", s)
	}
	if !strings.HasPrefix(s, "line before\n") {
		t.Errorf("prefix clobbered: %q", s)
	}
	if !strings.Contains(s, "line after") {
		t.Errorf("suffix clobbered: %q", s)
	}
	if strings.Count(s, BeginMarker) != 1 {
		t.Errorf("duplicate markers: %q", s)
	}
}

func TestUpsertMarkedBlock_AppendsToExisting(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	if err := os.WriteFile(p, []byte("# user stuff\nexport FOO=bar\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := UpsertMarkedBlock(p, "body", 0o600); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(p)
	s := string(b)
	if !strings.Contains(s, "export FOO=bar") {
		t.Errorf("pre-existing content lost: %q", s)
	}
	if !strings.Contains(s, BeginMarker) {
		t.Error("block missing")
	}
}

func TestRemoveMarkedBlock(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	seed := "keep me\n\n" + BeginMarker + "\nbody\n" + EndMarker + "\nkeep me too\n"
	if err := os.WriteFile(p, []byte(seed), 0o600); err != nil {
		t.Fatal(err)
	}
	changed, err := RemoveMarkedBlock(p)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Error("expected changed=true")
	}
	b, _ := os.ReadFile(p)
	s := string(b)
	if strings.Contains(s, BeginMarker) || strings.Contains(s, "body") {
		t.Errorf("block not removed: %q", s)
	}
	if !strings.Contains(s, "keep me") || !strings.Contains(s, "keep me too") {
		t.Errorf("surrounding content lost: %q", s)
	}
}

func TestRemoveMarkedBlock_Missing(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "rc")
	changed, err := RemoveMarkedBlock(p)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Error("expected changed=false for missing file")
	}
}
