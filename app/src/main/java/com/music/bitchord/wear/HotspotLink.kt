package com.music.bitchord.wear

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.ACTION_TOGGLE_FAVORITE
import com.music.bitchord.playback.ACTION_TOGGLE_SHUFFLE
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.PlaybackService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import com.music.bitchord.wear.WearProtocol as P

/**
 * Remote control over the phone's hotspot: the watch drives a music player
 * (a DAP such as the FiiO JM21) that is connected to the phone's hotspot.
 *
 *     Watch ──Data Layer──▶ Phone (controller, TCP server) ──hotspot LAN──▶ DAP (player, client)
 *
 * Roles are automatic: a DAP ([AudioRouting.isHifiDap]) is the player, anything
 * else the controller. The DAP finds the phone as its default gateway, so no
 * address has to be typed in. Newline-delimited JSON over one TCP socket;
 * the first line is an HMAC-free token check (shared [BuildConfig.HOTSPOT_LINK_TOKEN],
 * compared in constant time). Everything here is off when the token is empty.
 *
 * While a player is connected, the phone forwards the watch's transport
 * commands to it and shows the player's state on the watch instead of its own.
 */
object HotspotLink {
    private const val TAG = "HotspotLink"
    const val PORT = 47123
    private const val HELLO_TIMEOUT_MS = 5_000
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val PING_MS = 15_000L
    private const val PUSH_COALESCE_MS = 150L
    private const val MAX_BACKOFF_MS = 5_000L
    private const val RECONNECT_GRACE_MS = 6_000L
    private const val RECENT_PLAYER_MS = 3 * 60 * 60 * 1000L
    private const val KEY_LAST_PLAYER = "last_player_at"

    /** Transport commands the phone hands to the player instead of running them itself. */
    private val FORWARDED = setOf(
        P.CMD_SYNC, P.CMD_TOGGLE, P.CMD_NEXT, P.CMD_PREVIOUS, P.CMD_LIKE, P.CMD_SHUFFLE,
        P.CMD_REPEAT, P.CMD_SEEK, P.CMD_SKIP_TO, P.CMD_QUEUE, P.CMD_VOLUME,
    )

    private val token: String = BuildConfig.HOTSPOT_LINK_TOKEN
    val enabled: Boolean get() = token.isNotEmpty()

    @Volatile private var started = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** The live socket to the other side, whichever role this device has. */
    @Volatile private var peer: Peer? = null

    /** Phone side: true while a player is connected and owns what the watch shows. */
    val isControllingRemote: Boolean get() = !AudioRouting.isHifiDap && peer != null

    fun start(context: Context) {
        if (!enabled || started) return
        started = true
        app = context.applicationContext
        val role = if (AudioRouting.isHifiDap) "player" else "controller"
        Log.i(TAG, "starting as $role on port $PORT")
        if (AudioRouting.isHifiDap) thread("hotspot-client") { clientLoop() } else thread("hotspot-server") { serverLoop() }
        thread("hotspot-ping") { pingLoop() }
    }

    // ------------------------------------------------------------------ wire

    private class Peer(val socket: Socket) {
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        fun send(json: JSONObject): Boolean = synchronized(this) {
            runCatching {
                writer.write(json.toString())
                writer.write("\n")
                writer.flush()
            }.isSuccess
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private fun send(json: JSONObject) {
        val current = peer ?: return
        if (!current.send(json)) drop(current)
    }

    private fun drop(which: Peer) {
        which.close()
        if (peer === which) {
            peer = null
            if (!AudioRouting.isHifiDap) WearBridge.clearRemote(app)
            Log.i(TAG, "peer gone")
        }
    }

    private fun tokenMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    private fun pingLoop() {
        while (true) {
            Thread.sleep(PING_MS)
            send(JSONObject().put("t", "ping"))
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread({
            runCatching(body).onFailure { Log.e(TAG, "$name died", it) }
        }, name).apply { isDaemon = true }.start()
    }

    // --------------------------------------------------- controller (phone)

    private fun serverLoop() {
        while (true) {
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            }.getOrElse {
                Log.w(TAG, "bind failed: ${it.message}")
                Thread.sleep(10_000)
                null
            } ?: continue
            try {
                while (true) {
                    val socket = server.accept()
                    thread("hotspot-peer") { serveController(socket) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "accept failed: ${error.message}")
                runCatching { server.close() }
                Thread.sleep(2_000)
            }
        }
    }

    private fun serveController(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = HELLO_TIMEOUT_MS
        val candidate = Peer(socket)
        val hello = runCatching { JSONObject(candidate.reader.readLine() ?: "") }.getOrNull()
        if (hello?.optString("t") != "hello" || !tokenMatches(hello.optString("token"))) {
            Log.w(TAG, "rejected ${socket.inetAddress}")
            candidate.close()
            return
        }
        socket.soTimeout = READ_TIMEOUT_MS
        // A reconnecting player replaces the old socket.
        peer?.let { old -> if (old !== candidate) old.close() }
        peer = candidate
        prefs().edit().putLong(KEY_LAST_PLAYER, System.currentTimeMillis()).apply()
        Log.i(TAG, "player connected from ${socket.inetAddress.hostAddress}")
        candidate.send(JSONObject().put("t", "welcome"))
        // Ask for the player's state right away so the watch switches over.
        candidate.send(command(P.CMD_SYNC, ""))
        readLoop(candidate) { onControllerMessage(it) }
    }

    private fun onControllerMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "state" -> WearBridge.publishRemote(app, msg)
            "queue" -> {
                val rows = msg.optJSONArray("rows") ?: JSONArray()
                val list = (0 until rows.length()).map { i ->
                    val r = rows.getJSONObject(i)
                    WearBridge.QueueRow(
                        index = r.optInt("i"),
                        title = r.optString("title"),
                        artist = r.optString("artist"),
                        artworkUrl = r.optString("art").ifBlank { null },
                    )
                }
                WearBridge.publishQueue(app, list, msg.optInt("current"), msg.optString("mediaId").ifBlank { null })
            }
        }
    }

    /**
     * Called by [WearCommandService] for every watch command. True when the
     * command went to the connected player and must not run on the phone.
     */
    fun forward(command: String, arg: String): Boolean {
        if (!enabled || AudioRouting.isHifiDap || command !in FORWARDED) return false
        if (peer == null && playerSeenRecently()) {
            // This process may have just been started by the watch's message; give a
            // player that was here recently a moment to reconnect before the phone
            // starts playing by itself. Runs on Play services' worker thread.
            val deadline = System.currentTimeMillis() + RECONNECT_GRACE_MS
            while (peer == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        }
        if (!isControllingRemote) return false
        send(command(command, arg))
        // The watch asked for state: answer with the last one we have at once.
        if (command == P.CMD_SYNC) WearBridge.sync(app)
        return true
    }

    private fun prefs() = app.getSharedPreferences("hotspot_link", Context.MODE_PRIVATE)

    private fun playerSeenRecently(): Boolean {
        if (!started) return false
        val at = prefs().getLong(KEY_LAST_PLAYER, 0L)
        return System.currentTimeMillis() - at < RECENT_PLAYER_MS
    }

    private fun command(command: String, arg: String) =
        JSONObject().put("t", "cmd").put("c", command).put("a", arg)

    // ------------------------------------------------------- player (DAP)

    private fun clientLoop() {
        var backoff = 2_000L
        while (true) {
            val gateway = defaultGateway()
            if (gateway == null) {
                Thread.sleep(5_000)
                continue
            }
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(gateway, PORT), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = READ_TIMEOUT_MS
                val current = Peer(socket)
                if (!current.send(JSONObject().put("t", "hello").put("token", token).put("role", "player"))) {
                    throw IllegalStateException("hello failed")
                }
                peer = current
                backoff = 2_000L
                Log.i(TAG, "connected to controller at $gateway")
                pushState()
                readLoop(current) { onPlayerMessage(it) }
            } catch (error: Exception) {
                runCatching { socket.close() }
                if (peer?.socket === socket) peer = null
            }
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** The phone, seen from a device on its hotspot: the IPv4 default gateway. */
    private fun defaultGateway(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }

    private fun onPlayerMessage(msg: JSONObject) {
        if (msg.optString("t") != "cmd") return
        val command = msg.optString("c")
        val arg = msg.optString("a")
        when (command) {
            P.CMD_SYNC -> pushState()
            P.CMD_VOLUME -> {
                WearBridge.adjustVolume(app, arg.toIntOrNull() ?: 0)
                main.postDelayed({ pushState() }, PUSH_COALESCE_MS)
            }
            else -> Controllers.use(app) { controller -> execute(controller, command, arg) }
        }
    }

    private val pushRunnable = Runnable { pushNow() }

    /** Player side: the local state changed (called from [WearBridge.publish]). */
    fun pushState() {
        if (!AudioRouting.isHifiDap || peer == null) return
        main.removeCallbacks(pushRunnable)
        main.postDelayed(pushRunnable, PUSH_COALESCE_MS)
    }

    private fun pushNow() {
        val state = WearBridge.remoteStateJson(app) ?: JSONObject().put("hasTrack", false)
        Thread { send(state.put("t", "state")) }.start()
    }

    private fun execute(controller: MediaController, command: String, arg: String) {
        if (controller.mediaItemCount == 0) {
            pushState()
            return
        }
        when (command) {
            P.CMD_TOGGLE -> if (controller.playWhenReady) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
                controller.prepareIfIdle()
                controller.play()
            }
            P.CMD_NEXT -> {
                controller.seekToNextMediaItem()
                controller.prepareIfIdle()
            }
            P.CMD_PREVIOUS -> {
                controller.seekToPrevious()
                controller.prepareIfIdle()
            }
            P.CMD_LIKE -> controller.sendCustomCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
            P.CMD_SHUFFLE -> controller.sendCustomCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY), Bundle.EMPTY)
            P.CMD_REPEAT -> {
                val next = when (controller.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                AppSettings.setRepeatMode(next)
                controller.repeatMode = next
            }
            P.CMD_SEEK -> arg.toLongOrNull()?.let { controller.seekTo(it.coerceAtLeast(0L)) }
            P.CMD_SKIP_TO -> {
                val index = arg.toIntOrNull()?.takeIf { it in 0 until controller.mediaItemCount } ?: return
                controller.seekToDefaultPosition(index)
                controller.prepareIfIdle()
                controller.play()
            }
            P.CMD_QUEUE -> {
                val current = controller.currentMediaItemIndex
                val from = (current - 1).coerceAtLeast(0)
                val to = (current + WearBridge.queueAhead).coerceAtMost(controller.mediaItemCount - 1)
                val rows = JSONArray()
                for (i in from..to) {
                    val m = controller.getMediaItemAt(i).mediaMetadata
                    rows.put(
                        JSONObject()
                            .put("i", i)
                            .put("title", m.title?.toString().orEmpty())
                            .put("artist", m.artist?.toString().orEmpty())
                            .put("art", m.artworkUri?.toString().orEmpty()),
                    )
                }
                val msg = JSONObject()
                    .put("t", "queue")
                    .put("current", current)
                    .put("mediaId", controller.currentMediaItem?.mediaId.orEmpty())
                    .put("rows", rows)
                Thread { send(msg) }.start()
            }
        }
        // Player callbacks republish on their own; this covers no-op commands.
        pushState()
    }

    private fun MediaController.prepareIfIdle() {
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    // ------------------------------------------------------------- shared

    private fun readLoop(current: Peer, onMessage: (JSONObject) -> Unit) {
        try {
            while (true) {
                val line = current.reader.readLine() ?: break
                val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (msg.optString("t") == "ping") continue
                runCatching { onMessage(msg) }.onFailure { Log.w(TAG, "message failed", it) }
            }
        } catch (error: Exception) {
            Log.i(TAG, "link closed: ${error.message}")
        } finally {
            drop(current)
        }
    }

    /** A briefly kept MediaController to the local player. Main thread. */
    private object Controllers {
        private const val IDLE_RELEASE_MS = 10_000L
        private val main = Handler(Looper.getMainLooper())
        private var future: ListenableFuture<MediaController>? = null
        private val release = Runnable {
            future?.let(MediaController::releaseFuture)
            future = null
        }

        fun use(app: Context, block: (MediaController) -> Unit) {
            main.post {
                main.removeCallbacks(release)
                val pending = future?.takeUnless { it.isDone && it.connected() == null }
                    ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                        .buildAsync().also { future = it }
                pending.addListener(
                    {
                        val controller = pending.connected()
                        if (controller == null) future = null
                        else runCatching { block(controller) }.onFailure { Log.w(TAG, "command failed", it) }
                        main.postDelayed(release, IDLE_RELEASE_MS)
                    },
                    ContextCompat.getMainExecutor(app),
                )
            }
        }

        private fun ListenableFuture<MediaController>.connected(): MediaController? =
            runCatching { get() }.getOrNull()?.takeIf { it.isConnected }
    }
}
