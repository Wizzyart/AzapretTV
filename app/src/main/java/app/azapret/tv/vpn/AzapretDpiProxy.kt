package app.azapret.tv.vpn

object AzapretDpiProxy {
    init {
        System.loadLibrary("azapret_byedpi")
    }

    fun start(args: Array<String>): Int = jniStartProxy(args)

    fun stop(): Int = jniStopProxy()

    fun forceClose(): Int = jniForceClose()

    @JvmStatic
    private external fun jniStartProxy(args: Array<String>): Int

    @JvmStatic
    private external fun jniStopProxy(): Int

    @JvmStatic
    private external fun jniForceClose(): Int
}
