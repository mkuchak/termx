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
func AppendEvent(eventType string, session string, payload map[string]any) error {
	path, err := EventsPath()
	if err != nil {
		return err
	}
	return AppendEventAt(path, eventType, session, payload, time.Now().UTC())
}

// AppendEventAt is the path/clock-injected variant used by tests.
func AppendEventAt(path, eventType, session string, payload map[string]any, now time.Time) error {
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
	defer f.Close()
	_, err = f.Write(b)
	return err
}

// RotateIfNeeded renames events.ndjson to events.ndjson.1 once it grows
// past maxBytes. We keep a single rotated copy; older history is
// discarded. Not fatal if the primary file is missing.
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

// DefaultRotateBytes is the size threshold (10 MiB) that hook commands
// check before appending — keeps the tailed file bounded for phone-side
// readers.
const DefaultRotateBytes = 10 * 1024 * 1024
