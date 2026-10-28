package com.aternos.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BotService.StatusListener {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var nickInput: EditText
    private lateinit var versionSpinner: Spinner
    private lateinit var loaderSpinner: Spinner
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var connectBtn: Button

    private var botService: BotService? = null
    private var bound = false

    private val versions = listOf(
        "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1",
        "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5"
    )

    private val loaders = listOf("Vanilla", "Forge", "NeoForge", "Fabric", "Quilt")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BotService.LocalBinder
            botService = binder.getService()
            botService?.setStatusListener(this@MainActivity)
            bound = true
            updateUiFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            botService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(20))
        }

        val title = TextView(this).apply {
            text = "Aternos KeepAlive"
            textSize = 26f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title)

        val subtitle = TextView(this).apply {
            text = "Offline-бот для Aternos"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(subtitle)
        content.addView(header)

        // Status card
        val statusCard = card()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        statusDot = android.view.View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(12)
            }
        }
        statusRow.addView(statusDot)

        statusText = TextView(this).apply {
            text = "Отключено"
            textSize = 15f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        statusRow.addView(statusText)
        statusCard.addView(statusRow)
        content.addView(statusCard, matchWrap(bottom = 16))

        // Settings card
        val settingsCard = card()
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        settings.addView(label("Адрес сервера"))
        hostInput = field("CreateFer.aternos.me")
        settings.addView(hostInput, matchWrap(bottom = 12))

        settings.addView(label("Порт"))
        portInput = field("25565")
        settings.addView(portInput, matchWrap(bottom = 12))

        settings.addView(label("Ник бота (offline)"))
        nickInput = field("KeepAliveBot")
        settings.addView(nickInput, matchWrap(bottom = 12))

        settings.addView(label("Версия Minecraft"))
        versionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, versions)
        }
        settings.addView(versionSpinner, matchWrap(bottom = 12))

        settings.addView(label("Загрузчик"))
        loaderSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, loaders)
        }
        settings.addView(loaderSpinner, matchWrap(bottom = 8))

        settingsCard.addView(settings)
        content.addView(settingsCard, matchWrap(bottom = 16))

        // Buttons
        connectBtn = Button(this).apply {
            text = "ПОДКЛЮЧИТЬСЯ"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { toggleConnection() }
        }
        content.addView(connectBtn, matchWrap(bottom = 16))

        // Log card
        val logCard = card()
        val logInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        logInner.addView(label("Лог"))
        logView = TextView(this).apply {
            text = "Готов к подключению.\nСервер должен быть в offline-mode (online-mode=false)."
            textSize = 13f
            setTextColor(Color.parseColor("#334155"))
            setLineSpacing(0f, 1.25f)
        }
        logInner.addView(logView)
        logCard.addView(logInner)
        content.addView(logCard, matchWrap())

        root.addView(content)
        setContentView(root)

        // Restore last values
        val prefs = getSharedPreferences("bot", MODE_PRIVATE)
        hostInput.setText(prefs.getString("host", ""))
        portInput.setText(prefs.getString("port", "25565"))
        nickInput.setText(prefs.getString("nick", "KeepAliveBot"))
        val ver = prefs.getString("version", "1.21.1")
        versionSpinner.setSelection(versions.indexOf(ver).coerceAtLeast(0))
        val ldr = prefs.getString("loader", "Vanilla")
        loaderSpinner.setSelection(loaders.indexOf(ldr).coerceAtLeast(0))
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BotService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            botService?.setStatusListener(null)
            unbindService(connection)
            bound = false
        }
    }

    private fun toggleConnection() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val nick = nickInput.text.toString().trim()

        if (host.isBlank()) {
            appendLog("Введи адрес сервера")
            return
        }
        if (port == null || port !in 1..65535) {
            appendLog("Порт должен быть от 1 до 65535")
            return
        }
        if (nick.isBlank()) {
            appendLog("Введи ник бота")
            return
        }

        // Save prefs
        getSharedPreferences("bot", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("port", port.toString())
            .putString("nick", nick)
            .putString("version", versions[versionSpinner.selectedItemPosition])
            .putString("loader", loaders[loaderSpinner.selectedItemPosition])
            .apply()

        val intent = Intent(this, BotService::class.java).apply {
            putExtra("host", host)
            putExtra("port", port)
            putExtra("nick", nick)
            putExtra("version", versions[versionSpinner.selectedItemPosition])
            putExtra("loader", loaders[loaderSpinner.selectedItemPosition])
        }

        if (botService?.isRunning == true) {
            stopService(intent)
            botService?.stopBot()
            setDisconnectedUi()
        } else {
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            setConnectingUi()
        }
    }

    override fun onStatus(status: String, connected: Boolean, connecting: Boolean) {
        runOnUiThread {
            statusText.text = status
            when {
                connected -> {
                    statusDot.setBackgroundColor(Color.parseColor("#00C853"))
                    connectBtn.text = "ОТКЛЮЧИТЬСЯ"
                    connectBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_button_danger)
                }
                connecting -> {
                    statusDot.setBackgroundColor(Color.parseColor("#F59E0B"))
                    connectBtn.text = "ПОДКЛЮЧЕНИЕ..."
                    connectBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                }
                else -> setDisconnectedUi()
            }
        }
    }

    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    private fun setDisconnectedUi() {
        statusText.text = "Отключено"
        statusDot.setBackgroundColor(Color.parseColor("#94A3B8"))
        connectBtn.text = "ПОДКЛЮЧИТЬСЯ"
        connectBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
    }

    private fun setConnectingUi() {
        statusText.text = "Подключение..."
        statusDot.setBackgroundColor(Color.parseColor("#F59E0B"))
        connectBtn.text = "ПОДКЛЮЧЕНИЕ..."
    }

    private fun updateUiFromService() {
        botService?.let {
            if (it.isRunning) {
                onStatus(it.lastStatus, it.isConnected, !it.isConnected)
            }
        }
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logView.text = "$time  $msg\n${logView.text}"
    }

    // UI helpers
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
        elevation = dp(2).toFloat()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#64748B"))
        setPadding(0, 0, 0, dp(4))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setText(hint.takeIf { it.matches(Regex(".*\\d+.*")) || it.contains(".") } ?: "")
        if (hint == "25565") setText("25565")
        if (hint == "KeepAliveBot") setText("KeepAliveBot")
        isSingleLine = true
        setBackgroundColor(Color.parseColor("#F8FAFC"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(Color.parseColor("#0F172A"))
        setHintTextColor(Color.parseColor("#94A3B8"))
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
