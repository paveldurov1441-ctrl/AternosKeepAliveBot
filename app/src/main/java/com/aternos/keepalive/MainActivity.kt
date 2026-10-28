package com.aternos.keepalive

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var nickname: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        val title = TextView(this).apply {
            text = "Aternos KeepAlive Bot"
            textSize = 24f
            setTextColor(Color.rgb(30, 50, 80))
            gravity = Gravity.CENTER
        }
        layout.addView(title, matchWrap())

        host = field("Адрес сервера")
        port = field("Порт", "25565")
        nickname = field("Ник бота", "KeepAliveBot")

        layout.addView(host, matchWrap())
        layout.addView(port, matchWrap())
        layout.addView(nickname, matchWrap())

        val versions = Spinner(this)
        versions.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("1.21.1", "1.20.1", "1.19.2", "1.18.2")
        )
        layout.addView(versions, matchWrap())

        val loaders = Spinner(this)
        loaders.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Vanilla", "Forge", "NeoForge", "Fabric")
        )
        layout.addView(loaders, matchWrap())

        val connect = Button(this).apply {
            text = "Проверить настройки"
            setOnClickListener {
                val address = host.text.toString().trim()
                val portNumber = port.text.toString().toIntOrNull()

                status.text = when {
                    address.isBlank() -> "Введи адрес сервера."
                    portNumber == null || portNumber !in 1..65535 ->
                        "Укажи правильный порт от 1 до 65535."
                    nickname.text.toString().isBlank() ->
                        "Введи ник бота."
                    else ->
                        "Настройки заполнены. Реальное подключение к Minecraft пока не реализовано."
                }
            }
        }
        layout.addView(connect, matchWrap())

        status = TextView(this).apply {
            text = "Заполни данные сервера."
            textSize = 16f
            setPadding(0, 20, 0, 0)
        }
        layout.addView(status, matchWrap())

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun field(label: String, defaultValue: String = "") =
        EditText(this).apply {
            hint = label
            setText(defaultValue)
            singleLine = true
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = 8
    }
}
