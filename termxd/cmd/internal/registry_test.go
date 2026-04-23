package internal

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRegistryRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sessions", "main.json")

	in := Session{
		Name:      "main",
		CreatedAt: "2026-04-23T12:00:00Z",
		Windows:   2,
		Status:    "idle",
		Claude:    false,
	}
	if err := WriteSessionAt(path, in); err != nil {
		t.Fatalf("write: %v", err)
	}

	got, err := ReadSessionAt(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if got == nil {
		t.Fatalf("read returned nil for existing file")
	}
	if *got != in {
		t.Errorf("round-trip mismatch: got %+v, want %+v", *got, in)
	}

	if err := DeleteSessionAt(path); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("file still present after delete")
	}
}

func TestReadSessionMissingReturnsNil(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "ghost.json")
	got, err := ReadSessionAt(path)
	if err != nil {
		t.Fatalf("expected nil, got err: %v", err)
	}
	if got != nil {
		t.Errorf("expected nil session, got %+v", got)
	}
}

func TestDeleteSessionIdempotent(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "ghost.json")
	if err := DeleteSessionAt(path); err != nil {
		t.Errorf("delete missing file should be a noop, got: %v", err)
	}
}

func TestWriteSessionCreatesParentDir(t *testing.T) {
	dir := t.TempDir()
	// Nested parent dir intentionally absent.
	path := filepath.Join(dir, "a", "b", "c", "main.json")
	if err := WriteSessionAt(path, Session{Name: "main", Status: "idle"}); err != nil {
		t.Fatalf("write: %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Errorf("file not created: %v", err)
	}
}
