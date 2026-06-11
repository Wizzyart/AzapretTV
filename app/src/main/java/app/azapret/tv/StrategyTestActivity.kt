package app.azapret.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.azapret.tv.config.AppSettings
import app.azapret.tv.diagnostics.AppLog
import app.azapret.tv.vpn.AzapretVpnService
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class StrategyTestActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var bestText: TextView
    private lateinit var startButton: Button
    private lateinit var fullTestButton: Button
    private val rowViews = mutableMapOf<String, TextView>()
    private var pendingAutoStart = false
    private var autoApplyBest = false
    @Volatile
    private var running = false

    private val strategies = listOf(
        Strategy("Orbit MID", Engine.TPWS, 0, false),
        Strategy("Orbit MSS88", Engine.TPWS, 4, false),
        Strategy("Orbit SIMPLE", Engine.TPWS, 1, false),
        Strategy("DPI YOUTUBE 1", Engine.DPI, 6, false),
        Strategy("DPI YOUTUBE 2", Engine.DPI, 7, false),
        Strategy("DPI GOOGLEVIDEO", Engine.DPI, 8, false),
        Strategy("DPI YOUTUBE 3", Engine.DPI, 10, true),
        Strategy("DPI GOOGLEVIDEO 2", Engine.DPI, 11, true),
        Strategy("DPI YOUTUBE LIGHT", Engine.DPI, 12, true),
        Strategy("DPI GOOGLEVIDEO HOSTLIST", Engine.DPI, 13, true),
        Strategy("DPI HOSTS", Engine.DPI, 14, true),
        Strategy("DPI DISOOB", Engine.DPI, 15, true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoApplyBest = intent.getBooleanExtra(EXTRA_AUTO_APPLY, false)
        setContentView(buildLayout())
        if (autoApplyBest) {
            pendingAutoStart = true
            startButton.post { startStrategyTest(includeAll = false) }
        }
    }

    @Deprecated("Deprecated by Android Activity API, acceptable for minimal TV MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK && pendingAutoStart) {
            pendingAutoStart = false
            startButton.post { startStrategyTest(includeAll = false) }
        }
    }

    private fun buildLayout(): View {
        val root = ScrollView(this).apply { background = bgDrawable() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(42), dp(36), dp(42), dp(36))
        }

        val title = TextView(this).apply {
            text = "Подбор профиля"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "Быстрый тест проверяет первые 6 профилей. Полный тест проверяет все 12"
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }

        panel.addView(title)
        panel.addView(statusText)
        for (strategy in strategies) {
            val row = strategyRow(strategy.name)
            rowViews[strategy.name] = row
            panel.addView(row)
        }
        startButton = pillButton("Быстрый тест").apply { setOnClickListener { startStrategyTest(includeAll = false) } }
        startButton.post { startButton.requestFocus() }
        fullTestButton = pillButton("Полный тест").apply { setOnClickListener { startStrategyTest(includeAll = true) } }
        bestText = TextView(this).apply {
            text = ""
            textSize = 18f
            setTextColor(GREEN)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(8))
        }
        val backButton = pillButton("Назад").apply { setOnClickListener { finish() } }

        panel.addView(startButton)
        panel.addView(fullTestButton)
        panel.addView(bestText)
        panel.addView(backButton)
        root.addView(panel)
        return root
    }

    private fun strategyRow(name: String): TextView = TextView(this).apply {
        text = "$name\nСтатус: ожидание"
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rowBg(0)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun startStrategyTest(includeAll: Boolean) {
        if (running) {
            running = false
            startButton.text = "Остановка..."
            fullTestButton.isEnabled = false
            statusText.text = "Останавливаю тест и возвращаю прежнюю стратегию"
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingAutoStart = autoApplyBest
            statusText.text = "Сначала разрешите VPN, затем запустите подбор ещё раз"
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
            return
        }

        val testStrategies = if (includeAll) strategies else strategies.filter { !it.advanced }
        running = true
        startButton.text = "Стоп"
        fullTestButton.isEnabled = false
        fullTestButton.alpha = 0.55f
        statusText.text = if (includeAll) "Идёт полный подбор по 12 профилям" else "Идёт быстрый подбор по 6 профилям"
        bestText.text = ""
        strategies.forEach { strategy ->
            rowViews[strategy.name]?.apply {
                text = "${strategy.name}\nСтатус: ожидание"
                background = rowBg(0)
            }
        }
        AppLog.append(this, "STRATEGY_SCREEN_TEST_START current=${AppSettings.getBypassProfileName(this)} vpn=${AzapretVpnService.isRunning} full=$includeAll")

        thread(name = "AzapretStrategyScreenTest") {
            val originalProfile = AppSettings.getBypassProfile(this)
            val wasRunning = AzapretVpnService.isRunning
            val results = mutableListOf<StrategyResult>()

            for ((index, strategy) in testStrategies.withIndex()) {
                if (!running) break
                runOnUiThread {
                    statusText.text = "Проверка ${strategy.name} ${index + 1}/${testStrategies.size}"
                    rowViews[strategy.name]?.text = "${strategy.name}\nСтатус: проверка..."
                }
                val result = testVpnStrategy(strategy)
                results += result
                AppLog.append(this, result.toLogLine())
                if (running) runOnUiThread { updateRow(result) }
            }

            if (AzapretVpnService.isRunning) {
                AzapretVpnService.stop(this)
                Thread.sleep(ENGINE_SWITCH_DELAY_MS)
            }
            val best = results.filter { it.youtubeOk && it.googleVideoOk }.minByOrNull { it.totalMs }
                ?: results.filter { it.score > 0 }.maxWithOrNull(compareBy<StrategyResult> { it.score }.thenByDescending { -it.totalMs })
            val summary = best?.let { strategySummary(it.strategy) } ?: "Рабочая стратегия не найдена"
            AppSettings.setBestTestProfile(this, best?.strategy?.profile, summary)
            if (best != null && autoApplyBest) {
                AppSettings.setBypassProfile(this, best.strategy.profile)
                if (wasRunning) AzapretVpnService.start(this)
            } else {
                AppSettings.setBypassProfile(this, originalProfile)
                if (wasRunning) AzapretVpnService.start(this)
            }

            AppLog.append(this, "STRATEGY_SCREEN_TEST_BEST ${best?.strategy?.name ?: "none"} summary=$summary active=${AppSettings.getBypassProfileName(this)}")
            runOnUiThread {
                val finished = running
                running = false
                startButton.text = "Быстрый тест"
                fullTestButton.isEnabled = true
                fullTestButton.alpha = 1f
                statusText.text = if (finished) "Подбор завершён. Подробности записаны в журнал." else "Подбор остановлен. Прежняя стратегия возвращена."
                bestText.text = if (finished && best != null) "Лучший профиль: ${best.strategy.name}\n$summary" else if (finished) summary else ""
            }
        }
    }

    private fun strategySummary(strategy: Strategy): String {
        val provider = when {
            strategy.name.startsWith("Orbit") -> "Лучше для Дом.ru"
            strategy.name.contains("GOOGLEVIDEO") || strategy.name == "DPI HOSTS" -> "Лучше для МТС"
            else -> "Универсальный YouTube"
        }
        val stability = if (strategy.advanced) "Экспериментальный" else "Стабильный"
        return "$provider · $stability"
    }

    private fun testVpnStrategy(strategy: Strategy): StrategyResult {
        if (AzapretVpnService.isRunning) {
            AzapretVpnService.stop(this)
                Thread.sleep(ENGINE_SWITCH_DELAY_MS)
            }
        AppSettings.setBypassProfile(this, strategy.profile)
        val startMs = System.currentTimeMillis()
        AzapretVpnService.start(this)
        if (!waitForSocksPort(8000)) {
            return StrategyResult(strategy, false, false, TEST_TIMEOUT_MS, TEST_TIMEOUT_MS, TEST_TIMEOUT_MS, "SOCKS 1080 not ready")
        }
        val socksMs = (System.currentTimeMillis() - startMs).toInt()
        Thread.sleep(1200)

        val youtube = checkSocksTcp("www.youtube.com")
        val googleVideo = checkSocksTls("manifest.googlevideo.com")
        return StrategyResult(
            strategy = strategy,
            youtubeOk = youtube.ok,
            googleVideoOk = googleVideo.ok,
            socksMs = socksMs,
            youtubeMs = youtube.ms,
            googleVideoMs = googleVideo.ms,
            error = listOfNotNull(youtube.error, googleVideo.error).joinToString("; ")
        )
    }

    private fun checkSocksTcp(host: String): CheckResult {
        val started = System.currentTimeMillis()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
            Socket(proxy).use { socket ->
                socket.soTimeout = TEST_TIMEOUT_MS
                socket.connect(InetSocketAddress.createUnresolved(host, 443), TEST_TIMEOUT_MS)
            }
            CheckResult(true, (System.currentTimeMillis() - started).toInt(), null)
        } catch (e: Exception) {
            CheckResult(false, (System.currentTimeMillis() - started).toInt().coerceAtMost(TEST_TIMEOUT_MS), "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private fun checkSocksTls(host: String): CheckResult {
        val started = System.currentTimeMillis()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
            val rawSocket = Socket(proxy)
            rawSocket.soTimeout = TEST_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress.createUnresolved(host, 443), TEST_TIMEOUT_MS)
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(rawSocket, host, 443, true) as SSLSocket
            socket.soTimeout = TEST_TIMEOUT_MS
            socket.startHandshake()
            socket.close()
            CheckResult(true, (System.currentTimeMillis() - started).toInt(), null)
        } catch (e: Exception) {
            CheckResult(false, (System.currentTimeMillis() - started).toInt().coerceAtMost(TEST_TIMEOUT_MS), "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private fun waitForSocksPort(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", 1080), 250) }
                return true
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        return false
    }

    private fun updateRow(result: StrategyResult) {
        val youtube = if (result.youtubeOk) "OK" else "нет"
        val googleVideo = if (result.googleVideoOk) "OK" else "нет"
        val status = when (result.score) {
            2 -> "Работает"
            1 -> "Частично"
            else -> "Не работает"
        }
        rowViews[result.strategy.name]?.apply {
            text = "${result.strategy.name}\nСтатус: $status\nYouTube: $youtube · Видео: $googleVideo"
            if (result.error.isNotBlank()) text = "$text\n${result.error}"
            background = rowBg(result.score)
        }
    }

    private fun pillButton(textValue: String): Button = Button(this).apply {
        text = textValue
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(320), dp(50)).apply { topMargin = dp(10) }
        background = buttonBg(false)
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120).start()
            view.background = buttonBg(focused)
        }
    }

    private fun bgDrawable() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF210606.toInt(), 0xFF0F0505.toInt(), 0xFF030101.toInt())
    )

    private fun rowBg(score: Int) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        val color = when (score) {
            2 -> 0xFF123022.toInt()
            1 -> 0xFF2D1212.toInt()
            else -> 0xFF1C1C1C.toInt()
        }
        val stroke = when (score) {
            2 -> GREEN
            1 -> 0xFFFF3B3B.toInt()
            else -> 0xFF343434.toInt()
        }
        setColor(color)
        setStroke(dp(if (score > 0) 2 else 1), stroke)
    }

    private fun buttonBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(if (focused) 0xFF333333.toInt() else 0xFF242424.toInt())
        setStroke(dp(2), if (focused) Color.WHITE else 0xFF4A4A4A.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Strategy(val name: String, val engine: Engine, val profile: Int, val advanced: Boolean)
    private enum class Engine { TPWS, DPI }
    private data class CheckResult(val ok: Boolean, val ms: Int, val error: String?)
    private data class StrategyResult(
        val strategy: Strategy,
        val youtubeOk: Boolean,
        val googleVideoOk: Boolean,
        val socksMs: Int,
        val youtubeMs: Int,
        val googleVideoMs: Int,
        val error: String
    ) {
        val score: Int get() = (if (youtubeOk) 1 else 0) + (if (googleVideoOk) 1 else 0)
        val totalMs: Int get() = socksMs + youtubeMs + googleVideoMs

        fun toLogLine(): String = "STRATEGY_RESULT name=${strategy.name} engine=${strategy.engine} socksMs=$socksMs youtubeOk=$youtubeOk youtubeMs=$youtubeMs googleVideoOk=$googleVideoOk googleVideoMs=$googleVideoMs score=$score totalMs=$totalMs error=${error.ifBlank { "none" }}"
    }

    companion object {
        private const val VPN_REQUEST_CODE = 3110
        const val EXTRA_AUTO_APPLY = "app.azapret.tv.AUTO_APPLY_BEST"
        private const val TEST_TIMEOUT_MS = 10000
        private const val ENGINE_SWITCH_DELAY_MS = 2500L
        private val MUTED = 0xFFFFD2D2.toInt()
        private val GREEN = 0xFF27D986.toInt()
    }
}
