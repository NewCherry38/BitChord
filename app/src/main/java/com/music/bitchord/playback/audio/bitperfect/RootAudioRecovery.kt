package com.music.bitchord.playback.audio.bitperfect

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Personal-build workaround: after a DAC is unplugged mid-stream from the
 * direct USB driver, some Qualcomm/Oplus phones leave the ADSP's audio graph
 * wedged (every stream open fails with AGM error -131) until audioserver
 * restarts. On a rooted phone this restarts it a moment after the detach, so
 * sound comes back on the speaker by itself. Does nothing without `su`.
 */
internal object RootAudioRecovery {
    private const val TAG = "RootAudioRecovery"
    /** Let the transport tear down and Android finish its own detach handling first. */
    private const val DELAY_MS = 1_500L
    private const val MIN_INTERVAL_MS = 10_000L

    @Volatile private var lastRunAt = 0L

    private val suAvailable: Boolean by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")
            .any { File(it).exists() }
    }

    fun restartAudioServerSoon() {
        if (!suAvailable) return
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        lastRunAt = now
        Thread({
            try {
                Thread.sleep(DELAY_MS)
                val process = ProcessBuilder("su", "-c", "setprop ctl.restart audioserver")
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) process.destroy()
                Log.i(TAG, "audioserver restart requested (exit=${if (finished) process.exitValue() else "timeout"})")
            } catch (error: Exception) {
                Log.w(TAG, "audioserver restart failed", error)
            }
        }, "BitChord-audio-recovery").apply { isDaemon = true }.start()
    }
}
