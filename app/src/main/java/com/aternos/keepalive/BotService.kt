package com.aternos.keepalive

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

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
        isConnected = false
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
                updateNotification("Подключение к $host...")

                withContext(Dispatchers.IO) {
                    doConnect(host, port, nick, version)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                isConnected = false
                val msg = e.message ?: e.javaClass.simpleName
                log("Ошибка: $msg")
                updateStatus("Ошибка / переподключение", false, true)
                updateNotification("Ошибка: $msg")
                delay(4000L + (attempt.coerceAtMost(5) * 2000L))
            }
        }
        isConnected = false
        isRunning = false
        updateStatus("Отключено", false, false)
        updateNotification("Отключено")
    }

    private fun doConnect(host: String, port: Int, nick: String, version: String) {
        val protocol = protocolVersion(version)
        log("Protocol $protocol ($version)")

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = 20000
        socket.connect(InetSocketAddress(host, port), 12000)

        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())

        // ===== Handshake (next state = 2 LOGIN) =====
        val handshakePayload = ByteArrayOutputStream()
        val hp = DataOutputStream(handshakePayload)
        writeVarInt(hp, 0x00) // packet id
        writeVarInt(hp, protocol)
        writeString(hp, host)
        hp.writeShort(port and 0xFFFF)
        writeVarInt(hp, 2) // LOGIN
        sendRaw(out, handshakePayload.toByteArray())

        // ===== Login Start =====
        val loginPayload = ByteArrayOutputStream()
        val lp = DataOutputStream(loginPayload)
        writeVarInt(lp, 0x00) // packet id Login Start
        writeString(lp, nick)

        // 1.19+ requires hasUUID + UUID
        if (protocol >= 759) { // 1.19+
            lp.writeBoolean(true)
            val uuid = offlineUuid(nick)
            lp.writeLong(uuid.mostSignificantBits)
            lp.writeLong(uuid.leastSignificantBits)
        }
        sendRaw(out, loginPayload.toByteArray())
        log("Handshake + Login Start отправлены")

        var compressionThreshold = -1
        var loggedIn = false
        val start = System.currentTimeMillis()

        // Read login responses
        while (!stopFlag.get() && !loggedIn && System.currentTimeMillis() - start < 20000) {
            val packet = readPacket(input, compressionThreshold) ?: break
            val pin = DataInputStream(ByteArrayInputStream(packet))
            val packetId = readVarInt(pin)

            when (packetId) {
                0x00 -> { // Disconnect (login)
                    val reason = try { readString(pin) } catch (_: Exception) { "unknown" }
                    throw Exception("Сервер отклонил: $reason")
                }
                0x01 -> { // Encryption Request — online-mode server
                    throw Exception("Сервер в online-mode. Нужен offline-mode (online-mode=false)")
                }
                0x02 -> { // Login Success
                    loggedIn = true
                    log("Login Success — вошли как $nick")
                }
                0x03 -> { // Set Compression
                    compressionThreshold = readVarInt(pin)
                    log("Compression threshold: $compressionThreshold")
                }
                0x04 -> { // Login Plugin Request — ignore / respond empty if needed
                    log("Login Plugin Request (игнорируем)")
                }
                else -> {
                    log("Login packet id=$packetId")
                }
            }
        }

        if (!loggedIn) {
            throw Exception("Нет ответа Login Success (проверь версию / online-mode)")
        }

        // Now we are in PLAY (or CONFIGURATION on 1.20.2+)
        isConnected = true
        updateStatus("Подключено ✓", true, false)
        updateNotification("Онлайн: $nick @ $host:$port")
        log("Бот в игре. Держим соединение...")

        // Keep connection alive: read packets and answer KeepAlive
        while (!stopFlag.get() && !socket.isClosed) {
            try {
                socket.soTimeout = 30000
                val packet = readPacket(input, compressionThreshold) ?: break
                val pin = DataInputStream(ByteArrayInputStream(packet))
                val packetId = readVarInt(pin)

                // Keep Alive (clientbound) — id differs by version, common ones:
                // 1.20.5+/1.21: 0x26, older play: 0x21 / 0x1F etc.
                // We answer any KeepAlive-looking packet with same long id
                if (packetId == 0x26 || packetId == 0x24 || packetId == 0x21 || packetId == 0x1F || packetId == 0x20) {
                    try {
                        val keepId = pin.readLong()
                        val resp = ByteArrayOutputStream()
                        val ro = DataOutputStream(resp)
                        // Serverbound Keep Alive is usually 0x18 / 0x15 / 0x12 depending on version
                        // Send a few common ids — servers ignore unknown, but better to match
                        writeVarInt(ro, 0x18)
                        ro.writeLong(keepId)
                        sendRaw(out, resp.toByteArray())
                    } catch (_: Exception) {}
                }

                // Disconnect play
                if (packetId == 0x1D || packetId == 0x1A || packetId == 0x17) {
                    val reason = try { readString(pin) } catch (_: Exception) { "kicked" }
                    throw Exception("Кик: $reason")
                }
            } catch (e: java.net.SocketTimeoutException) {
                // no data — connection still open, continue
                continue
            }
        }

        try { socket.close() } catch (_: Exception) {}
        isConnected = false
        log("Соединение закрыто")
        throw Exception("Disconnected")
    }

    private fun protocolVersion(version: String): Int = when {
        version.startsWith("1.21.1") || version == "1.21" -> 767
        version.startsWith("1.20.6") || version.startsWith("1.20.5") -> 766
        version.startsWith("1.20.4") || version.startsWith("1.20.3") -> 765
        version.startsWith("1.20.2") -> 764
        version.startsWith("1.20") -> 763
        version.startsWith("1.19.4") -> 762
        version.startsWith("1.19.3") -> 761
        version.startsWith("1.19.2") || version.startsWith("1.19.1") -> 760
        version.startsWith("1.19") -> 759
        version.startsWith("1.18.2") -> 758
        version.startsWith("1.18") -> 757
        version.startsWith("1.17") -> 756
        version.startsWith("1.16.5") || version.startsWith("1.16.4") -> 754
        else -> 767
    }

    /** Offline-mode UUID (same algorithm as Minecraft) */
    private fun offlineUuid(name: String): UUID {
        val data = ("OfflinePlayer:" + name).toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(data)
    }

    private fun sendRaw(out: DataOutputStream, data: ByteArray) {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        writeVarInt(dos, data.size)
        dos.write(data)
        out.write(buf.toByteArray())
        out.flush()
    }

    private fun readPacket(input: DataInputStream, compressionThreshold: Int): ByteArray? {
        val length = readVarInt(input)
        if (length <= 0 || length > 2_000_000) return null
        val data = ByteArray(length)
        input.readFully(data)

        if (compressionThreshold >= 0) {
            val din = DataInputStream(ByteArrayInputStream(data))
            val dataLength = readVarInt(din)
            if (dataLength == 0) {
                // uncompressed
                val remaining = ByteArray(din.available())
                din.readFully(remaining)
                return remaining
            } else {
                val compressed = ByteArray(din.available())
                din.readFully(compressed)
                val inflater = Inflater()
                inflater.setInput(compressed)
                val uncompressed = ByteArray(dataLength)
                inflater.inflate(uncompressed)
                inflater.end()
                return uncompressed
            }
        }
        return data
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

    private fun readVarInt(input: DataInputStream): Int {
        var numRead = 0
        var result = 0
        var read: Int
        do {
            read = input.readUnsignedByte()
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) throw RuntimeException("VarInt too big")
        } while ((read and 0x80) != 0)
        return result
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val len = readVarInt(input)
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
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
