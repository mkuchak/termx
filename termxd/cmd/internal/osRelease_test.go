package internal

import (
	"os"
	"path/filepath"
	"testing"
)

func writeOsRelease(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "os-release")
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestDetectDistro(t *testing.T) {
	cases := []struct {
		name string
		body string
		want Distro
	}{
		{"ubuntu", "ID=ubuntu\nVERSION_ID=22.04\n", DistroUbuntu},
		{"debian", "ID=debian\n", DistroDebian},
		{"alpine", "ID=alpine\n", DistroAlpine},
		{"arch", "ID=arch\n", DistroArch},
		{"fedora", "ID=fedora\n", DistroFedora},
		{"centos", "ID=\"centos\"\n", DistroCentOS},
		{"rhel", "ID=rhel\n", DistroRHEL},
		{"derivative-via-ID_LIKE", "ID=pop\nID_LIKE=\"ubuntu debian\"\n", DistroUbuntu},
		{"unknown", "ID=gentoo\n", DistroUnknown},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := writeOsRelease(t, tc.body)
			t.Setenv("OSRELEASE", p)
			got, err := DetectDistro()
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Errorf("got %q want %q", got, tc.want)
			}
		})
	}
}

func TestInstallCmdFor(t *testing.T) {
	cases := []struct {
		d    Distro
		want string
	}{
		{DistroUbuntu, "sudo apt install -y mosh tmux"},
		{DistroDebian, "sudo apt install -y mosh tmux"},
		{DistroAlpine, "sudo apk add mosh tmux"},
		{DistroArch, "sudo pacman -S --noconfirm mosh tmux"},
		{DistroFedora, "sudo dnf install -y mosh tmux"},
		{DistroCentOS, "sudo yum install -y mosh tmux"},
	}
	for _, tc := range cases {
		got := InstallCmdFor(tc.d, "mosh", "tmux")
		if got != tc.want {
			t.Errorf("%s: got %q want %q", tc.d, got, tc.want)
		}
	}
	if InstallCmdFor(DistroUbuntu) != "" {
		t.Error("empty pkg list should yield empty string")
	}
}
