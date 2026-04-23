package internal

import (
	"bufio"
	"bytes"
	"os"
	"strings"
)

// BeginMarker and EndMarker frame every termx-managed block in plain-text
// rc files. Never change these strings — uninstall matches on them verbatim.
const (
	BeginMarker = "# --- termx begin ---"
	EndMarker   = "# --- termx end ---"
)

// UpsertMarkedBlock inserts or replaces a termx-managed block in the file at
// path. If the file does not exist it is created with mode. If an existing
// block is present (first match), it is replaced in place. Otherwise the new
// block is appended (with a leading blank line if the file isn't empty and
// doesn't already end in a newline).
//
// The returned bool reports whether a change was made (false = already in
// sync, no disk write).
func UpsertMarkedBlock(path string, body string, mode os.FileMode) (bool, error) {
	existing, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return false, err
	}
	newContent, changed := upsertMarkedBlockBytes(existing, body)
	if !changed {
		return false, nil
	}
	if err := os.WriteFile(path, newContent, mode); err != nil {
		return false, err
	}
	return true, nil
}

func upsertMarkedBlockBytes(existing []byte, body string) ([]byte, bool) {
	block := buildBlock(body)
	if len(existing) == 0 {
		return []byte(block + "\n"), true
	}
	startIdx, endIdx, found := findBlock(existing)
	if found {
		// Replace in place. findBlock's endIdx sits after the marker line's
		// trailing newline (or at EOF if the file didn't end in one). We
		// inject the block without a trailing newline, then stitch. If the
		// original file ended in a newline at endIdx-1, preserve it by
		// adding one when the suffix is empty.
		var buf bytes.Buffer
		buf.Write(existing[:startIdx])
		buf.WriteString(block)
		suffix := existing[endIdx:]
		if len(suffix) == 0 && endIdx > 0 && existing[endIdx-1] == '\n' {
			buf.WriteByte('\n')
		} else {
			buf.Write(suffix)
		}
		out := buf.Bytes()
		if bytes.Equal(out, existing) {
			return existing, false
		}
		return out, true
	}
	// Append; ensure a blank line separates from prior content.
	var buf bytes.Buffer
	buf.Write(existing)
	if !bytes.HasSuffix(existing, []byte("\n")) {
		buf.WriteByte('\n')
	}
	buf.WriteByte('\n')
	buf.WriteString(block)
	buf.WriteByte('\n')
	return buf.Bytes(), true
}

func buildBlock(body string) string {
	body = strings.TrimRight(body, "\n")
	return BeginMarker + "\n" + body + "\n" + EndMarker
}

// findBlock returns the byte offsets [startIdx, endIdx) covering a full
// marked block (including its marker lines and the trailing newline after
// EndMarker). Returns found=false if no block is present.
func findBlock(src []byte) (int, int, bool) {
	lines := bytes.Split(src, []byte("\n"))
	beginLine := -1
	endLine := -1
	for i, ln := range lines {
		trimmed := strings.TrimRight(string(ln), " \t\r")
		if trimmed == BeginMarker && beginLine == -1 {
			beginLine = i
		} else if trimmed == EndMarker && beginLine != -1 {
			endLine = i
			break
		}
	}
	if beginLine == -1 || endLine == -1 {
		return 0, 0, false
	}
	// Compute byte offsets.
	start := 0
	for i := 0; i < beginLine; i++ {
		start += len(lines[i]) + 1 // +1 for the '\n'
	}
	end := start
	for i := beginLine; i <= endLine; i++ {
		end += len(lines[i]) + 1
	}
	// end may exceed src length if the last line had no trailing newline;
	// clamp.
	if end > len(src) {
		end = len(src)
	}
	return start, end, true
}

// RemoveMarkedBlock strips a termx-managed block from the file. Returns
// (changed, nil) when the file was modified; (false, nil) when no block was
// present or the file does not exist.
func RemoveMarkedBlock(path string) (bool, error) {
	existing, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}
	start, end, found := findBlock(existing)
	if !found {
		return false, nil
	}
	var buf bytes.Buffer
	buf.Write(existing[:start])
	buf.Write(existing[end:])
	// Collapse any double blank line this may have introduced at the seam.
	cleaned := collapseBlankLines(buf.Bytes())
	if bytes.Equal(cleaned, existing) {
		return false, nil
	}
	info, err := os.Stat(path)
	if err != nil {
		return false, err
	}
	if err := os.WriteFile(path, cleaned, info.Mode().Perm()); err != nil {
		return false, err
	}
	return true, nil
}

func collapseBlankLines(src []byte) []byte {
	var out bytes.Buffer
	s := bufio.NewScanner(bytes.NewReader(src))
	prevBlank := false
	first := true
	for s.Scan() {
		line := s.Text()
		isBlank := strings.TrimSpace(line) == ""
		if isBlank && prevBlank {
			continue
		}
		if !first {
			out.WriteByte('\n')
		}
		out.WriteString(line)
		prevBlank = isBlank
		first = false
	}
	// Preserve trailing newline if source had one.
	if len(src) > 0 && src[len(src)-1] == '\n' {
		out.WriteByte('\n')
	}
	return out.Bytes()
}

// HasMarkedBlock reports whether path contains a termx-managed block.
func HasMarkedBlock(path string) bool {
	b, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	_, _, found := findBlock(b)
	return found
}
