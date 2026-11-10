package app.fifteen

import androidx.compose.runtime.mutableStateOf
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/**
 * Notices when another app fills the screen (a full-screen video, a movie, a game or a slideshow) so the pet
 * can step aside. A plain maximised window doesn't count: it keeps its title bar and the taskbar.
 */
object FullScreenWatch {
    /** True while another app is full screen and hiding is turned on. */
    val covered = mutableStateOf(false)

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val ownPid = ProcessHandle.current().pid().toInt()
    private val shellClasses = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")

    fun start(enabled: () -> Boolean) {
        if (!isWindows) return
        Thread {
            while (true) {
                val now = enabled() && runCatching { foregroundIsFullScreen() }.getOrDefault(false)
                if (covered.value != now) covered.value = now
                Thread.sleep(1_000)
            }
        }.apply { isDaemon = true; name = "fullscreen-watch" }.start()
    }

    private fun foregroundIsFullScreen(): Boolean {
        val u = User32.INSTANCE
        val hwnd = u.GetForegroundWindow() ?: return false
        if (!u.IsWindowVisible(hwnd)) return false

        val pid = IntByReference()
        u.GetWindowThreadProcessId(hwnd, pid)
        if (pid.value == ownPid) return false // Fifteen's own windows never hide the pet

        val cls = CharArray(256)
        u.GetClassName(hwnd, cls, cls.size)
        if (Native.toString(cls) in shellClasses) return false // the desktop and taskbar

        val style = u.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        if (style and WinUser.WS_MAXIMIZE != 0) return false // maximised, not full screen

        val rect = WinDef.RECT()
        if (!u.GetWindowRect(hwnd, rect)) return false
        val monitor = u.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST) ?: return false
        val info = WinUser.MONITORINFO()
        if (!u.GetMonitorInfo(monitor, info).booleanValue()) return false
        val m = info.rcMonitor
        return rect.left <= m.left && rect.top <= m.top && rect.right >= m.right && rect.bottom >= m.bottom
    }
}
