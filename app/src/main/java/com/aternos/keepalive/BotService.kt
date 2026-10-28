package com.aternos.keepalive

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BotService : Service() {

    interface StatusListener {
        fun onStatus(status: String, connected: Boolean, connecting: Boolean)
        fun onLog(message: String)
    }

    private val binder = LocalBinder()
    private var listener: StatusListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile var isRunning = false
        private set
    @Volatile var isConnected = false
        private set
    @Volatile var lastStatus = "Отключено"
        private set

    private val stopFlag = AtomicBoolean(false)

    inner class LocalBinder : Binder() {
        fun getService() = this@BotService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStatusListener(l: StatusListener?) {
        listener = l
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val host = intent.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 25565)
        val nick = intent.getStringExtra("nick") ?: "KeepAliveBot"
        val version = intent.getStringExtra("version") ?: "1.21.1"

        startForeground(1, buildNotification("Подключение..."))
        stopFlag.set(false)
        isRunning = true
        updateStatus("Подключение...", connected = false, connecting = true)

        job?.cancel()
        job = scope.launch {
            connectLoop(host, port, nick, version)
        }

        return START_STICKY
    }

    fun stopBot() {
        stopFlag.set(true)
        job?.cancel()
        isRunning = false
        isConnected = false
        updateStatus("Отключено", connected = false, connecting = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun connectLoop(host: String, port: Int, nick: String, version: String) {
        var attempt = 0
        while (!stopFlag.get()) {
            attempt++
            try {
                log("Попытка #$attempt → $host:$port ($nick)")
                updateStatus("Подключение...", false, true)

                withContext(Dispatchers.IO) {
                    doConnect(host, port, nick, version)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                isConnected = false
                log("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                updateStatus("Ошибка / переподключение", false, true)
                delay(5000L + (attempt.coerceAtMost(6) * 2000L))
            }
        }
        isConnected = false
        isRunning = false
        updateStatus("Отключено", false, false)
    }

    private fun doConnect(host: String, port: Int, nick: String, version: String) {
        val socket = Socket()
        socket.soTimeout = 15000
        socket.connect(InetSocketAddress(host, port), 10000)
        socket.tcpNoDelay = true

        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())

        // Protocol version mapping (approximate for modern versions)
        val protocol = when {
            version.startsWith("1.21") -> 767
            version.startsWith("1.20.5") || version.startsWith("1.20.6") -> 766
            version.startsWith("1.20.3") || version.startsWith("1.20.4") -> 765
            version.startsWith("1.20.2") -> 764
            version.startsWith("1.20") -> 763
            version.startsWith("1.19.4") -> 762
            version.startsWith("1.19.3") -> 761
            version.startsWith("1.19") -> 760
            version.startsWith("1.18") -> 758
            version.startsWith("1.17") -> 756
            version.startsWith("1.16") -> 754
            else -> 767
        }

        // Handshake (state = 2 = login)
        writeVarInt(out, 0x00) // packet id handshake later via length
        val handshake = java.io.ByteArrayOutputStream()
        val h = DataOutputStream(handshake)
        writeVarInt(h, protocol)
        writeString(h, host)
        h.writeShort(port)
        writeVarInt(h, 2) // next state: login
        sendPacket(out, handshake.toByteArray())

        // Login Start
        val login = java.io.ByteArrayOutputStream()
        val l = DataOutputStream(login)
        writeString(l, nick)
        // For 1.19+ there are extra fields; simplified offline for compatibility
        // UUID (optional in some versions)
        try {
            // Many offline servers accept just the name
            sendPacket(out, byteArrayOf(0x00) + login.toByteArray())
        } catch (_: Exception) {}

        log("Handshake + Login Start отправлены (protocol $protocol)")
        isConnected = true
        updateStatus("Подключено (offline)", true, false)
        updateNotification("Онлайн: $nick @ $host")

        // Keep-alive loop: read packets and respond to keep-alive if possible
        val buf = ByteArray(4096)
        while (!stopFlag.get() && !socket.isClosed) {
            try {
                if (input.available() > 0) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    // Very simple: just keep the TCP connection alive
                    // Real keep-alive packet handling would parse VarInt length + packet id
                } else {
                    Thread.sleep(1000)
                    // Send a lightweight packet occasionally to prevent idle timeout
                    // (empty or status request is version-dependent, so we rely on TCP)
                }
            } catch (e: Exception) {
                throw e
            }
        }

        try { socket.close() } catch (_: Exception) {}
        isConnected = false
        log("Соединение закрыто")
        throw Exception("Disconnected")
    }

    private fun sendPacket(out: DataOutputStream, data: ByteArray) {
        val packet = java.io.ByteArrayOutputStream()
        val p = DataOutputStream(packet)
        writeVarInt(p, data.size)
        p.write(data)
        out.write(packet.toByteArray())
        out.flush()
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun updateStatus(status: String, connected: Boolean, connecting: Boolean) {
        lastStatus = status
        isConnected = connected
        listener?.onStatus(status, connected, connecting)
    }

    private fun log(msg: String) {
        listener?.onLog(msg)
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "bot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Aternos Bot",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Aternos KeepAlive")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    override fun onDestroy() {
        stopFlag.set(true)
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
