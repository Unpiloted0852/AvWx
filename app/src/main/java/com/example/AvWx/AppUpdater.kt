package com.example.AvWx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs a release APK from this app's GitHub releases over the running app.
 *
 * The APK is streamed straight into a PackageInstaller session (nothing is saved to shared
 * storage) and then handed to Android, which shows its own "update this app?" confirmation
 * and, the first time, the "allow installs from this app" setting. On Android 12+ later
 * updates may install without that confirmation. The update only installs if it is signed
 * with the same key as the installed app.
 */
class AppUpdater(private val activity: Activity) {

    @Volatile private var running = false

    /**
     * Downloads [url] and starts the install. [onProgress] gets the download percentage and
     * [onFinished] gets null on success (in practice the update kills the app first) or a short
     * reason, "cancelled" if the user backed out. Both are called on the main thread.
     */
    fun start(url: String, onProgress: (Int) -> Unit, onFinished: (String?) -> Unit) {
        if (running) return
        // The link comes from the web layer, so only ever install from this app's own releases.
        if (!url.startsWith(RELEASES_PREFIX)) {
            activity.runOnUiThread { onFinished("unexpected download link") }
            return
        }
        running = true

        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val action = activity.packageName + ".UPDATE_STATUS"
        lateinit var receiver: BroadcastReceiver
        val finish = { error: String? ->
            runCatching { activity.unregisterReceiver(receiver) }
            running = false
            activity.runOnUiThread { onFinished(error) }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { activity.startActivity(it) }
                    PackageInstaller.STATUS_SUCCESS -> finish(null)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> finish("cancelled")
                    else -> finish(
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Thread {
            var sessionId = -1
            try {
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    try {
                        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("download failed (HTTP ${conn.responseCode})")
                        }
                        val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                        session.openWrite("update.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            val input = conn.inputStream
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    activity.runOnUiThread { onProgress(pct) }
                                }
                            }
                            session.fsync(out)
                        }
                    } finally {
                        conn.disconnect()
                    }
                    // Mutable so the installer can attach the status extras; package-scoped so it
                    // only ever reaches the receiver registered above.
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
                    val callback = PendingIntent.getBroadcast(
                        activity, sessionId, Intent(action).setPackage(activity.packageName), flags
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
                finish(e.message ?: "download failed")
            }
        }.start()
    }

    companion object {
        private const val RELEASES_PREFIX = "https://github.com/Unpiloted0852/AvWx/releases/"
    }
}
