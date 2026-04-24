package cmd

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/mkuchak/termx/termxd/cmd/internal"
	"github.com/spf13/cobra"
)

// fileEditTools are the Claude tool names we capture a diff for. Anything
// else (Bash, Read, Grep, Glob, WebFetch, …) is a no-op — the PostToolUse
// hook still fires for them, we just nothing-to-say.
var fileEditTools = map[string]struct{}{
	"Edit":         {},
	"Write":        {},
	"MultiEdit":    {},
	"NotebookEdit": {},
}

// hookPostToolUseInput mirrors the PostToolUse payload shape from
// https://docs.claude.com/en/docs/claude-code/hooks. tool_response is
// whatever the tool returned; for file edits Claude includes the final
// content in several places depending on the tool, so we parse
// defensively.
type hookPostToolUseInput struct {
	SessionID    string          `json:"session_id"`
	ToolName     string          `json:"tool_name"`
	ToolInput    json.RawMessage `json:"tool_input"`
	ToolResponse json.RawMessage `json:"tool_response"`
	Cwd          string          `json:"cwd"`
}

// diffRecord is what we persist to ~/.termx/diffs/<id>.json. The phone's
// DiffViewerScreen fetches this file verbatim.
type diffRecord struct {
	ID           string `json:"id"`
	Ts           string `json:"ts"`
	Session      string `json:"session"`
	FilePath     string `json:"file_path"`
	Tool         string `json:"tool"`
	Before       string `json:"before"`
	After        string `json:"after"`
	UnifiedDiff  string `json:"unified_diff"`
}

func newHookPostToolUseCmd() *cobra.Command {
	return &cobra.Command{
		Use:    "_hook-posttooluse",
		Short:  "Claude PostToolUse hook: captures file-edit diffs",
		Hidden: true,
		Args:   cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runHookPostToolUse(cmd.InOrStdin(), cmd.OutOrStdout(), cmd.ErrOrStderr())
		},
	}
}

// runHookPostToolUse is the testable entry point. Always exits 0; the
// PostToolUse hook is purely observational and should never block
// Claude's progress.
func runHookPostToolUse(stdin io.Reader, stdout, stderr io.Writer) error {
	raw, err := io.ReadAll(stdin)
	if err != nil {
		fmt.Fprintf(stderr, "termx post hook: read stdin: %v\n", err)
		return nil
	}

	var input hookPostToolUseInput
	if err := json.Unmarshal(bytes.TrimSpace(raw), &input); err != nil {
		fmt.Fprintf(stderr, "termx post hook: parse stdin: %v\n", err)
		return nil
	}

	if _, ok := fileEditTools[input.ToolName]; !ok {
		return nil
	}

	paths, err := internal.ResolvePaths()
	if err != nil {
		fmt.Fprintf(stderr, "termx post hook: resolve paths: %v\n", err)
		return nil
	}

	filePath, before, after := reconstructDiffPayload(input.ToolName, input.ToolInput, input.ToolResponse)
	if filePath == "" {
		// Nothing to capture — probably a MultiEdit we can't round-trip,
		// or a malformed payload.
		return nil
	}

	id := uuid.NewString()
	now := time.Now().UTC().Format(time.RFC3339)
	session := detectTmuxSession()

	record := diffRecord{
		ID:          id,
		Ts:          now,
		Session:     session,
		FilePath:    filePath,
		Tool:        input.ToolName,
		Before:      before,
		After:       after,
		UnifiedDiff: unifiedDiff(filePath, before, after),
	}

	if err := os.MkdirAll(paths.DiffsDir, 0o700); err != nil {
		fmt.Fprintf(stderr, "termx post hook: mkdir diffs: %v\n", err)
		return nil
	}
	diffPath := filepath.Join(paths.DiffsDir, id+".json")
	if err := writeJSONAtomic(diffPath, record, 0o600); err != nil {
		fmt.Fprintf(stderr, "termx post hook: write diff: %v\n", err)
		return nil
	}

	_ = internal.RotateIfNeeded(internal.DefaultRotateBytes)
	_ = internal.AppendEvent("diff_created", session, map[string]any{
		"diff_id":   id,
		"file_path": filePath,
		"tool":      input.ToolName,
	})
	return nil
}

// reconstructDiffPayload pulls (path, before, after) out of the hook
// payload with tool-specific decoders.
//
// Edit: tool_input has file_path / old_string / new_string. before/after
//   are synthesized by read-then-substitute; we fall back to reading the
//   file from disk on the Before side (which works because PostToolUse
//   fires *after* the write but old_string is still present textually in
//   the tool_input).
// Write: tool_input has file_path + content (the new full file body).
//   Before is whatever the old file contained; we try reading a .bak-
//   looking sibling or just show the full file as an addition.
// MultiEdit: sequence of edits. We bail if the sequence is more complex
//   than a single edit — it's still captured as a record with
//   before==after==empty, but unified_diff surfaces the combined change.
// NotebookEdit: reuse Edit's shape.
func reconstructDiffPayload(tool string, input, response json.RawMessage) (string, string, string) {
	var ti map[string]any
	if len(input) > 0 {
		_ = json.Unmarshal(input, &ti)
	}
	if ti == nil {
		ti = map[string]any{}
	}
	filePath, _ := ti["file_path"].(string)
	if filePath == "" {
		if n, ok := ti["notebook_path"].(string); ok {
			filePath = n
		}
	}

	// Prefer the tool_response: Claude's newer hook payloads include a
	// `newString`/`oldString` echo or a `content` field with the resulting
	// file. If tool_response has usable fields, trust them over the
	// input reconstruction.
	var tr map[string]any
	if len(response) > 0 {
		_ = json.Unmarshal(response, &tr)
	}

	before, after := "", ""
	switch tool {
	case "Write":
		if c, ok := ti["content"].(string); ok {
			after = c
		}
		if c, ok := ti["text"].(string); ok && after == "" {
			after = c
		}
		// Before is the previous content; PostToolUse has already run
		// the write, so the file on disk == after. For Write, "before"
		// is the empty file in the common new-file case.
		if f, ok := tr["file_size_before"].(float64); ok {
			_ = f
		}
	case "Edit", "NotebookEdit":
		oldS, _ := ti["old_string"].(string)
		newS, _ := ti["new_string"].(string)
		// Reconstruct: read the now-updated file, derive before by
		// substituting new_string → old_string.
		if filePath != "" {
			if b, err := os.ReadFile(filePath); err == nil {
				after = string(b)
				// replaceAll may be wrong for non-unique strings, but
				// Claude's Edit enforces uniqueness. One replacement
				// is the norm.
				before = strings.Replace(after, newS, oldS, 1)
			} else {
				before = oldS
				after = newS
			}
		} else {
			before = oldS
			after = newS
		}
	case "MultiEdit":
		// tool_input.edits is a list of {old_string,new_string}. The
		// final disk state == tool_response content (if provided).
		edits, _ := ti["edits"].([]any)
		if filePath != "" {
			if b, err := os.ReadFile(filePath); err == nil {
				after = string(b)
				before = after
				// Walk edits in reverse to undo them for the "before" side.
				for i := len(edits) - 1; i >= 0; i-- {
					m, ok := edits[i].(map[string]any)
					if !ok {
						continue
					}
					o, _ := m["old_string"].(string)
					n, _ := m["new_string"].(string)
					before = strings.Replace(before, n, o, 1)
				}
			}
		}
	}
	return filePath, before, after
}

// unifiedDiff produces a minimal unified-diff-ish text between two file
// contents. Not a full GNU-diff implementation: we emit a header plus
// every line from each side, prefixed with `-` or `+`. For the phone
// UI's "show added/removed lines" this is sufficient, and avoids
// pulling in a diff library for a one-screen feature.
func unifiedDiff(path, before, after string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "--- a/%s\n", path)
	fmt.Fprintf(&b, "+++ b/%s\n", path)

	beforeLines := splitLinesKeepEmpty(before)
	afterLines := splitLinesKeepEmpty(after)

	// Trivial common-prefix / common-suffix trim so unchanged context
	// doesn't dominate the output.
	start := 0
	for start < len(beforeLines) && start < len(afterLines) && beforeLines[start] == afterLines[start] {
		start++
	}
	bEnd, aEnd := len(beforeLines), len(afterLines)
	for bEnd > start && aEnd > start && beforeLines[bEnd-1] == afterLines[aEnd-1] {
		bEnd--
		aEnd--
	}

	// Small amount of context around the change keeps the viewer useful.
	ctx := 3
	ctxStart := start - ctx
	if ctxStart < 0 {
		ctxStart = 0
	}
	bCtxEnd := bEnd + ctx
	if bCtxEnd > len(beforeLines) {
		bCtxEnd = len(beforeLines)
	}
	aCtxEnd := aEnd + ctx
	if aCtxEnd > len(afterLines) {
		aCtxEnd = len(afterLines)
	}

	fmt.Fprintf(&b, "@@ -%d,%d +%d,%d @@\n",
		ctxStart+1, bCtxEnd-ctxStart,
		ctxStart+1, aCtxEnd-ctxStart,
	)

	for i := ctxStart; i < start; i++ {
		fmt.Fprintf(&b, " %s\n", beforeLines[i])
	}
	for i := start; i < bEnd; i++ {
		fmt.Fprintf(&b, "-%s\n", beforeLines[i])
	}
	for i := start; i < aEnd; i++ {
		fmt.Fprintf(&b, "+%s\n", afterLines[i])
	}
	for i := bEnd; i < bCtxEnd; i++ {
		fmt.Fprintf(&b, " %s\n", beforeLines[i])
	}
	return b.String()
}

func splitLinesKeepEmpty(s string) []string {
	if s == "" {
		return nil
	}
	// strings.Split on "\n" preserves a trailing empty element when the
	// input ends with "\n", which is fine for diff output.
	return strings.Split(strings.TrimRight(s, "\n"), "\n")
}

// Use uuid in this file so the import is held even on Go toolchain
// versions that treat unused imports as errors through generate.
var _ = uuid.NewString
