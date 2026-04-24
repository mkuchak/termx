package dev.kuch.termx.libs.sshnative.impl

import android.os.ParcelFileDescriptor

/**
 * Pseudo-terminal launcher for mosh-client.
 *
 * mosh-client aborts at startup with "tcgetattr: Inappropriate ioctl
 * for device" when stdin isn't a real tty — which is exactly what
 * [ProcessBuilder] gives you (pipes). Android's public
 * [android.system.Os] API doesn't expose openpty/forkpty, so we ship
 * a small JNI helper (libtermxpty.so) that wires /dev/ptmx → grantpt →
 * unlockpt → ptsname_r → open slave → fork → setsid + TIOCSCTTY +
 * dup2 + execve.
 *
 * The resulting master fd is bidirectional — reads drain the child's
 * combined stdout+stderr, writes go to the child's stdin. Because a
 * pty merges the two output streams, there's no longer a way to
 * distinguish them; in practice that's fine for mosh (and arguably
 * preferable — the emulator sees every byte in order).
 *
 * All file-descriptor ownership flows through [ParcelFileDescriptor]
 * so the garbage collector can reclaim leaked fds instead of leaving
 * them dangling against `/dev/ptmx` slots.
 */
internal object NativePty {
    init { System.loadLibrary("termxpty") }

    /**
     * Fork/exec [path] under a fresh pseudo-terminal.
     *
     * @param path  absolute path to the executable.
     * @param argv  argv for execve — by convention argv[0] is the
     *              program name (often equal to [path]).
     * @param envp  env vars of the form "KEY=VALUE", NULL-terminated
     *              by the JNI layer on the native side.
     * @param rows  initial TIOCSWINSZ rows.
     * @param cols  initial TIOCSWINSZ cols.
     * @param pidOut single-element IntArray that receives the child's pid.
     * @return the master-side file descriptor, or -1 on failure.
     */
    @JvmStatic
    external fun forkExec(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
        pidOut: IntArray,
    ): Int

    /**
     * TIOCSWINSZ on the master fd. The kernel automatically delivers
     * SIGWINCH to the slave's foreground process group, so the child
     * picks up the new geometry without any extra signalling.
     */
    @JvmStatic external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /**
     * Block until [pid] exits. Returns the WIFEXITED exit code
     * (0..255), or 128 + signal number if the child was killed, or
     * -1 on a waitpid failure. Callers MUST invoke this on a dedicated
     * thread — it blocks the caller indefinitely.
     */
    @JvmStatic external fun waitPid(pid: Int): Int

    /** Send [signal] to [pid]. Returns 0 on success, -1 on error. */
    @JvmStatic external fun sendSignal(pid: Int, signal: Int): Int

    /** Convenience wrapper around [forkExec] that adopts the master fd. */
    data class Spawn(val master: ParcelFileDescriptor, val pid: Int)

    fun spawn(
        path: String,
        argv: Array<String>,
        envp: Map<String, String>,
        rows: Int,
        cols: Int,
    ): Spawn {
        val pidOut = IntArray(1)
        val envArray = envp.map { "${it.key}=${it.value}" }.toTypedArray()
        val fd = forkExec(path, argv, envArray, rows, cols, pidOut)
        check(fd >= 0) { "NativePty.forkExec failed (fd=$fd)" }
        // adoptFd transfers ownership — closing the ParcelFileDescriptor
        // closes the underlying integer fd. Dup before lending it to
        // stream wrappers so read-side and write-side can die independently.
        return Spawn(ParcelFileDescriptor.adoptFd(fd), pidOut[0])
    }

    // POSIX signal numbers we care about. Android doesn't expose them
    // in `android.system.OsConstants` in a uniform way across API
    // levels — pinning them here keeps the call sites obvious.
    const val SIGHUP = 1
    const val SIGTERM = 15
    const val SIGKILL = 9
}
