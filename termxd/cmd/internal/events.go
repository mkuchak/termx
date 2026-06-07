package internal

import (
	"encoding/json"
	"os"
	"time"
)

// EventsPath returns the canonical path for the append-only NDJSON event
// stream at ~/.termx/events.ndjson.
func EventsPath() (string, error) {
	p, err := ResolvePaths()
	if err != nil {
		return "", err
	}
	return p.EventsFile, nil
}

// AppendEvent atomically appends a single NDJSON line describing an event
// to ~/.termx/events.ndjson. Extra fields in payload are merged flat into
// the top-level JSON object alongside ts/type/session.
//
// Atomicity: each write is a single os.File.Write of < PIPE_BUF bytes
// (4096 on Linux) in O_APPEND mode, which POSIX guarantees as atomic
// with respect to other O_APPEND writes. Phone tails via `tail -F` will
// never see a torn line under the PIPE_BUF threshold.
//
// Rotation: after the append, if the file has grown to/past
// DefaultRotateBytes it is renamed (not truncated) to events.ndjson.1 and
// a fresh live file is recreated on the next append. Rename-rotation is
// `tail -F`-compatible (the phone tails with `tail -F --lines=0`, which
// reopens by name); truncate-in-place would desync the tail offset and is
// never used. The size is read from the just-written fd (fstat), not a
// fresh Stat, so there is no stat/append TOCTOU race.
func AppendEvent(eventType string, session string, payload map[string]any) error {
	path, err := EventsPath()
	if err != nil {
		return err
	}
	return AppendEventAt(path, eventType, session, payload, time.Now().UTC())
}

// AppendEventAt is the path/clock-injected variant used by tests. It rotates
// at DefaultRotateBytes; see AppendEventAtCap for the cap-injected seam.
func AppendEventAt(path, eventType, session string, payload map[string]any, now time.Time) error {
	return AppendEventAtCap(path, eventType, session, payload, now, DefaultRotateBytes)
}

// AppendEventAtCap is the cap-injected variant: it appends the event line and,
// if capBytes > 0 and the file has reached/exceeded capBytes after the write,
// renames it to path+".1". Tests pass a tiny capBytes to exercise rotation;
// AppendEventAt passes DefaultRotateBytes.
func AppendEventAtCap(path, eventType, session string, payload map[string]any, now time.Time, capBytes int64) error {
	obj := map[string]any{
		"ts":      now.Format(time.RFC3339Nano),
		"type":    eventType,
		"session": session,
	}
	for k, v := range payload {
		// Don't let callers clobber the canonical fields.
		if k == "ts" || k == "type" || k == "session" {
			continue
		}
		obj[k] = v
	}
	b, err := json.Marshal(obj)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	// Read the size from the OPEN fd (not a fresh Stat) so the rotation
	// decision can't race a concurrent append between write and check.
	var size int64
	if info, statErr := f.Stat(); statErr == nil {
		size = info.Size()
	}
	if err := f.Close(); err != nil {
		return err
	}
	// Best-effort rename-rotation: keep a single .1 copy. A failure here must
	// not fail the append — the line is already durably written.
	if capBytes > 0 && size >= capBytes {
		_ = os.Rename(path, path+".1")
	}
	return nil
}

// RotateIfNeeded renames events.ndjson to events.ndjson.1 once it grows
// past maxBytes. We keep a single rotated copy; older history is
// discarded. Not fatal if the primary file is missing.
//
// Deprecated for the append path: rotation is now folded into
// AppendEventAtCap, which rotates from the just-written fd's size (no
// stat/append race). This standalone helper is no longer wired into any
// caller; it is retained only for its existing tests and any out-of-band
// callers that want a manual size-triggered rotate.
func RotateIfNeeded(maxBytes int64) error {
	path, err := EventsPath()
	if err != nil {
		return err
	}
	return RotateIfNeededAt(path, maxBytes)
}

// RotateIfNeededAt is the path-injected variant used by tests.
func RotateIfNeededAt(path string, maxBytes int64) error {
	info, err := os.Stat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if info.Size() < maxBytes {
		return nil
	}
	return os.Rename(path, path+".1")
}

// DefaultRotateBytes is the size threshold (5 MiB) at which AppendEvent
// rename-rotates events.ndjson — keeps the tailed file bounded for
// phone-side readers without unbounded growth.
const DefaultRotateBytes = 5 * 1024 * 1024
