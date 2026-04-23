package internal

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// Distro is a coarse identifier for the Linux flavor detected.
type Distro string

const (
	DistroUbuntu  Distro = "ubuntu"
	DistroDebian  Distro = "debian"
	DistroAlpine  Distro = "alpine"
	DistroArch    Distro = "arch"
	DistroFedora  Distro = "fedora"
	DistroCentOS  Distro = "centos"
	DistroRHEL    Distro = "rhel"
	DistroUnknown Distro = "unknown"
)

// DetectDistro parses /etc/os-release (or a path injected via the OSRELEASE
// env var for tests) and returns a supported Distro or DistroUnknown.
func DetectDistro() (Distro, error) {
	path := os.Getenv("OSRELEASE")
	if path == "" {
		path = "/etc/os-release"
	}
	return detectDistroFromFile(path)
}

func detectDistroFromFile(path string) (Distro, error) {
	f, err := os.Open(path)
	if err != nil {
		return DistroUnknown, err
	}
	defer f.Close()

	fields := map[string]string{}
	s := bufio.NewScanner(f)
	for s.Scan() {
		line := s.Text()
		if idx := strings.IndexByte(line, '='); idx > 0 {
			k := line[:idx]
			v := strings.Trim(line[idx+1:], `"`)
			fields[k] = v
		}
	}
	if err := s.Err(); err != nil {
		return DistroUnknown, err
	}

	candidates := []string{fields["ID"]}
	if idLike, ok := fields["ID_LIKE"]; ok {
		for _, p := range strings.Fields(idLike) {
			candidates = append(candidates, p)
		}
	}
	for _, c := range candidates {
		switch strings.ToLower(c) {
		case "ubuntu":
			return DistroUbuntu, nil
		case "debian":
			return DistroDebian, nil
		case "alpine":
			return DistroAlpine, nil
		case "arch":
			return DistroArch, nil
		case "fedora":
			return DistroFedora, nil
		case "centos":
			return DistroCentOS, nil
		case "rhel":
			return DistroRHEL, nil
		}
	}
	return DistroUnknown, nil
}

// InstallCmdFor returns the distro-appropriate command string to install the
// given system packages. The returned command is NOT executed by termx —
// callers print it for the user or run it behind --install-deps.
func InstallCmdFor(d Distro, pkgs ...string) string {
	if len(pkgs) == 0 {
		return ""
	}
	list := strings.Join(pkgs, " ")
	switch d {
	case DistroUbuntu, DistroDebian:
		return fmt.Sprintf("sudo apt install -y %s", list)
	case DistroAlpine:
		return fmt.Sprintf("sudo apk add %s", list)
	case DistroArch:
		return fmt.Sprintf("sudo pacman -S --noconfirm %s", list)
	case DistroFedora:
		return fmt.Sprintf("sudo dnf install -y %s", list)
	case DistroCentOS, DistroRHEL:
		return fmt.Sprintf("sudo yum install -y %s", list)
	default:
		return fmt.Sprintf("# unknown distro — install manually: %s", list)
	}
}
