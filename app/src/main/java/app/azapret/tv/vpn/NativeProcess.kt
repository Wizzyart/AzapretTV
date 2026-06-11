package app.azapret.tv.vpn

object NativeProcess {
    init {
        System.loadLibrary("azapret_native")
    }

    external fun startTun2Socks(exePath: String, tunFd: Int, proxyUrl: String, logPath: String): Int
    external fun stopProcess(pid: Int)
    external fun isProcessAlive(pid: Int): Boolean
}
