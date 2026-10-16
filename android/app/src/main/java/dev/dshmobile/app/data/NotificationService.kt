package dev.dshmobile.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.random.Random

/** Read-only application transport, independent of Activity command admission/lifecycle. */
class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + NotificationPosting.errors)
    private var running: Job? = null
    private var api: HostApi? = null
    @Volatile private var connectionStatus = "initializing"
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private fun localizedString(id: Int) = dev.dshmobile.app.locale.appLanguageContext(this).getString(id)
    private fun refreshLanguage() = refreshNotificationLanguage(this)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        NotificationPosting.safely { manager.createNotificationChannel(NotificationChannel(CONNECTION, localizedString(R.string.notif_connection_channel), NotificationManager.IMPORTANCE_LOW)) }
        refreshLanguage()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android's foreground-start obligation must be fulfilled before any cancellation/stop path.
        try {
            val notification = connectionNotification(connectionStatus)
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            NotificationPosting.recordForegroundPost()
        } catch (failure: Exception) { NotificationPosting.logFailure(failure); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) {
            running?.cancel(); api?.close()
            scope.launch { try { NotificationStore(this@NotificationService).update { it.copy(enabled = false, settings = it.settings.copy(enabled = false), dirty = true) }; runCatching { withTimeout(2000) { syncNotificationPreferences(this@NotificationService) } } } finally { NotificationPosting.cancelAll(this@NotificationService); stopSelf() } }
            return START_NOT_STICKY
        }
        if (!alertsAllowed()) { stopSelf(); return START_NOT_STICKY }
        if (running?.isActive != true) running = scope.launch { monitor() }
        return START_NOT_STICKY
    }
    private fun connectionNotification(status: String): android.app.Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, NotificationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CONNECTION).addExtras(connectionLanguageExtras(status)).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(localizedString(if (status == "ready") R.string.notif_connected else R.string.notif_monitoring))
            .setContentText(localizedString(when (status) { "ready" -> R.string.notif_restart; "initializing" -> R.string.notif_initializing; "offline" -> R.string.notif_offline; else -> R.string.notif_degraded }))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, localizedString(R.string.notif_disable), stop).build()
    }
    private fun alertsAllowed() = try {
        manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    } catch (failure: Exception) { NotificationPosting.logFailure(failure); false }
    private suspend fun monitor() {
        val store = NotificationStore(this)
        ensureNotificationChannels(dev.dshmobile.app.locale.appLanguageContext(this))
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            var retryAfter = 0L
            var terminal = false
            var observer: okhttp3.sse.EventSource? = null
            try {
                val host = EncryptedStateStore(this).read().host ?: break
                var local = store.read()
                if (!local.enabled || local.deviceId != host.deviceId || !alertsAllowed()) break
                EndpointPolicy.validate(host.endpoint, BuildConfig.DEBUG)
                val transport = HostApi(host.endpoint, host.deviceToken).also { api = it }
                if (!transport.capabilities().capabilities.notifications) throw MobileFailure("unsupported")
                val remote = transport.notificationSettings()
                val policy = reconcileNotificationSettings(local.settings, remote, local.dirty)
                val synced = if (local.dirty) transport.putNotificationSettings(policy) else remote
                local = store.update { current ->
                    // Do not overwrite an offline/UI edit made while the PUT was in flight.
                    if (current.settings == local.settings) current.copy(settings = synced, dirty = false) else current
                }
                var workspaces = transport.workspaces()
                var sessions = transport.sessions().items
                var namesAt = System.currentTimeMillis()
                var catchupPages = 0
                var passStarted = System.currentTimeMillis()
                val incoming = Channel<Pair<NotificationPage?, Pair<String, Long>?>>(4)
                observer = transport.observeNotifications(local.cursor, { page ->
                    if (!incoming.trySend(page to null).isSuccess) { transport.close(); incoming.close() }
                }, { key, retry -> incoming.trySend(null to (key to retry)); incoming.close() })
                for ((page, failure) in incoming) {
                    if (failure != null) {
                        retryAfter = failure.second; terminal = !notificationRetryable(failure.first)
                        if (failure.first in setOf("revoked", "unauthorized", "forbidden")) runCatching { EncryptedHistoryStore(this).reject(failure.first) }
                        break
                    }
                    if (page == null) continue
                    if (EncryptedStateStore(this).read().host?.deviceId != host.deviceId) break
                    if (page.resetRequired || System.currentTimeMillis() - namesAt > 60000 || page.items.any { e -> sessions.none { it.id == e.sessionId } }) {
                        workspaces = transport.workspaces(); sessions = transport.sessions().items; namesAt = System.currentTimeMillis()
                    }
                    var applied: AppliedNotifications? = null
                    val updated = store.update { current ->
                        if (!current.enabled || current.deviceId != host.deviceId) throw MobileFailure("not_paired")
                        current.applyPage(page, System.currentTimeMillis()).also { applied = it }.state
                    }
                    val result = applied!!
                    if (result.reset) {
                        for (chat in local.attention.keys) NotificationPosting.cancel(this, tag(host.deviceId, chat, ATTENTION), 2)
                        // Current pending attention is restored silently after reset.
                        for (pending in page.pending) show(host.deviceId, NotificationEvent(1, pending.attentionId, 0, 0, Long.MAX_VALUE, pending.workspaceId, pending.sessionId, "attention-needed", -1, attentionId = pending.attentionId), updated, workspaces, sessions, true)
                    }
                    for (event in result.display) show(host.deviceId, event, updated, workspaces, sessions, false)
                    for (chat in updated.attention.keys) {
                        if (sessions.none { it.id == chat } || updated.settings.chats.find { it.sessionId == chat }?.enabled == false)
                            NotificationPosting.cancel(this, tag(host.deviceId, chat, ATTENTION), 2)
                    }
                    connectionStatus = updated.monitoringStatus
                    postNotification(this, null, 1, connectionNotification(connectionStatus))
                    local = updated; attempt = 0
                    if (page.hasMore && (++catchupPages >= 4 || System.currentTimeMillis() - passStarted >= 30000)) { retryAfter = 5000; break }
                    if (!page.hasMore) { catchupPages = 0; passStarted = System.currentTimeMillis() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                terminal = failure is MobileFailure && !notificationRetryable(failure.key)
                if (failure is MobileFailure && failure.key in setOf("revoked", "unauthorized", "forbidden"))
                    runCatching { EncryptedHistoryStore(this).reject(failure.key) }
            }
            finally { observer?.cancel(); api?.close(); api = null }
            if (terminal) {
                NotificationPosting.cancelAll(this@NotificationService)
                runCatching { store.update { it.copy(enabled = false, monitoringStatus = "blocked") } }
                break
            }
            runCatching { store.update { it.copy(monitoringStatus = "offline") } }
            connectionStatus = "offline"
            try { postNotification(this, null, 1, connectionNotification(connectionStatus)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { NotificationPosting.logFailure(failure) }
            delay(notificationRetryDelay(attempt++, retryAfter, Random.nextDouble(0.5, 1.0)))
        }
        stopSelf()
    }
    private suspend fun show(device: String, event: NotificationEvent, local: NotificationLocal,
        workspaces: List<dev.dshmobile.app.model.Workspace>, sessions: List<dev.dshmobile.app.model.SessionSummary>, silent: Boolean) {
        val kind = if (event.kind == "answer-finished") ANSWERS else ATTENTION
        val tag = tag(device, event.sessionId, kind) // Identity does not change with sound preference.
        if (event.kind == "attention-cleared") { NotificationPosting.cancel(this, tag, 2); return }
        val ticket = NotificationPosting.ticket(tag, 2)
        try {
            NotificationPosting.pacedSuspending {
                val store = NotificationStore(this)
                val revision = store.changes.value
                val latest = store.read()
                val host = EncryptedStateStore(this).read().host
                if (host?.deviceId != device || !latest.enabled || latest.deviceId != device ||
                    revision != store.changes.value || !shouldDisplayNotification(event, latest, VisibleNotificationChat.sessionId, sessions) || !alertsAllowed()) {
                    NotificationPosting.cancel(this, tag, 2); return@pacedSuspending
                }
                deliverAlert(device, event, latest, workspaces, sessions, silent, kind, tag, ticket, revision)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { NotificationPosting.logFailure(failure) }
        finally { NotificationPosting.release(ticket) }
    }
    private fun deliverAlert(device: String, event: NotificationEvent, local: NotificationLocal,
        workspaces: List<dev.dshmobile.app.model.Workspace>, sessions: List<dev.dshmobile.app.model.SessionSummary>,
        silent: Boolean, kind: String, tag: String, ticket: NotificationPosting.Ticket, revision: Long) {
        if (silent && sessions.none { it.id == event.sessionId }) return
        if (kind == ATTENTION) NotificationPosting.cancel(this, tag(device, event.sessionId, ANSWERS), 2)
        val alreadyShown = manager.activeNotifications.any { it.tag == tag && it.id == 2 }
        val quiet = notificationUpdateSilent(silent, alreadyShown)
        val channel = notificationChannel(event, local, quiet) ?: return
        val title = localizedString(if (event.kind == "answer-finished") R.string.notif_answer_ready else R.string.notif_needs_decision)
        val open = Intent(this, MainActivity::class.java).setAction("dev.dshmobile.OPEN_CHAT")
            .setData(android.net.Uri.Builder().scheme("dshmobile").authority("chat").appendPath(device).appendPath(event.sessionId).build())
            .putExtra("notificationDevice", device).putExtra("notificationChat", event.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(localizedString(R.string.app_name)).setContentText(title).build()
        val names = notificationNames(event, workspaces, sessions)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .addExtras(alertLanguageExtras(kind, names))
            .setContentTitle(title).setContentText(names ?: localizedString(R.string.notif_generic))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(tap)
            .setOnlyAlertOnce(true).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setSilent(quiet).build()
        NotificationPosting.commit(ticket) {
            // No suspension between the final visibility/permission check and notify.
            if (NotificationStore(this).changes.value == revision && shouldDisplayNotification(event, local, VisibleNotificationChat.sessionId, sessions) && alertsAllowed())
                manager.notify(tag, 2, notification)
        }
    }
    override fun onDestroy() {
        running?.cancel(); api?.close(); scope.cancel(); super.onDestroy()
    }
    companion object {
        const val STOP = "dev.dshmobile.NOTIFICATIONS_STOP"
        const val CONNECTION = "connection"
        const val ANSWERS = "answers"
        const val ATTENTION = "attention"
        private fun tag(device: String, chat: String, kind: String) = "$device:$chat:$kind"
    }
}
