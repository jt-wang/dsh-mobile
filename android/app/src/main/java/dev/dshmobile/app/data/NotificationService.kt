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
    @Volatile private var api: HostApi? = null
    private val reconnect = NotificationReconnect()
    private val networkChanges = NotificationNetworkChanges { reconnect.networkChanged() }
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = networkChanges.available(network)
        override fun onLost(network: android.net.Network) = networkChanges.lost(network)
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
            // Transport/validation changes also matter: a VPN may keep its Network ID across Wi-Fi/LTE.
            val route = listOf(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            networkChanges.capabilities(network, route)
        }
    }
    private val enrichments = mutableMapOf<String, Job>()
    private val enrichmentSlots = kotlinx.coroutines.sync.Semaphore(2)
    @Volatile private var workspaceNames = emptyList<dev.dshmobile.app.model.Workspace>()
    @Volatile private var sessionNames = emptyList<dev.dshmobile.app.model.SessionSummary>()
    @Volatile private var connectionStatus = "initializing"
        set(value) { field = value; NotificationStreamStatus.status.value = value }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private fun localizedString(id: Int) = dev.dshmobile.app.locale.appLanguageContext(this).getString(id)
    private fun refreshLanguage() = refreshNotificationLanguage(this)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        NotificationPosting.safely {
            // Same stable ID; Android preserves explicit user overrides on existing channels.
            manager.createNotificationChannel(NotificationChannel(CONNECTION, localizedString(R.string.notif_connection_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        }
        refreshLanguage()
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android's foreground-start obligation must be fulfilled before any cancellation/stop path.
        try {
            // Retire queued language snapshots before synchronously promoting the latest status.
            NotificationPosting.invalidate(null, 1)
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
        if (running?.isActive != true) running = scope.launch { reconnect.run { monitor() } }
        return START_NOT_STICKY
    }
    private fun connectionNotification(status: String): android.app.Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, NotificationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val settings = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(NOTIFICATION_ACTIVITY_FLAGS).putExtra("notificationSettings", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CONNECTION).addExtras(connectionLanguageExtras(status)).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(localizedString(connectionTitleResource(status)))
            .setContentText(connectionBody(dev.dshmobile.app.locale.appLanguageContext(this), status))
            .setGroup("dev.dshmobile.monitoring").setSortKey("monitoring")
            .setContentIntent(notificationChatListIntent(this))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, localizedString(R.string.notif_disable), stop)
            .addAction(0, localizedString(R.string.notif_open_settings), settings).build()
    }
    private fun alertsAllowed() = try {
        manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    } catch (failure: Exception) { NotificationPosting.logFailure(failure); false }
    private suspend fun monitor() {
        val store = NotificationStore(this)
        ensureNotificationChannels(dev.dshmobile.app.locale.appLanguageContext(this))
        connectionStatus = "reconnecting"
        store.update { it.copy(monitoringStatus = connectionStatus) }
        postNotification(this, null, 1, connectionNotification(connectionStatus))
        var attempt = 0
        var resyncedDegradedEpoch: String? = null
        while (currentCoroutineContext().isActive) {
            var retryAfter = 0L
            var terminal = false
            var resyncRequested = false
            var observer: okhttp3.sse.EventSource? = null
            var namesJob: Job? = null
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
                // Optional presentation metadata never delays opening or reading the event stream.
                workspaceNames = emptyList(); sessionNames = emptyList()
                var namesAt = 0L
                fun refreshNames() {
                    if (namesJob?.isActive == true) return
                    namesAt = System.currentTimeMillis()
                    namesJob = scope.launch {
                        try {
                            val workspaces = transport.workspaces(); val sessions = transport.sessions().items
                            if (api === transport) { workspaceNames = workspaces; sessionNames = sessions }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Generic fallback is sufficient for delivery. */ }
                    }
                }
                refreshNames()
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
                    notificationTrace("page_received count=${page.items.size}")
                    page.items.forEach { notificationEventTrace("received", it.eventId, it.sequence) }
                    if (EncryptedStateStore(this).read().host?.deviceId != host.deviceId) break
                    if (page.resetRequired || System.currentTimeMillis() - namesAt > 60000) refreshNames()
                    // Stream liveness is independent of paced alert posting / optional metadata.
                    if (connectionStatus != page.coverage) {
                        connectionStatus = page.coverage
                        postNotification(this, null, 1, connectionNotification(connectionStatus))
                    }
                    val updated = deliverNotificationPage(page, System.currentTimeMillis(), store::read, store::update,
                        post = { event, projected, silent -> show(host.deviceId, event, projected, silent) },
                        reset = { before -> for (chat in before.attention.keys) NotificationPosting.cancel(this, tag(host.deviceId, chat, ATTENTION), 2) })
                    notificationTrace("page_persisted count=${page.items.size}")
                    page.items.forEach { notificationEventTrace("persisted", it.eventId, it.sequence) }
                    for (chat in updated.attention.keys) {
                        if (updated.settings.chats.find { it.sessionId == chat }?.enabled == false)
                            NotificationPosting.cancel(this, tag(host.deviceId, chat, ATTENTION), 2)
                    }
                    // Coverage is feed history, not connectivity. One silent current-state reset per
                    // degraded epoch recovers attention without replaying old answers or reconnect loops.
                    val resync = page.coverage == "degraded" && !page.hasMore && !page.resetRequired && resyncedDegradedEpoch != page.epoch
                    if (page.coverage == "ready") resyncedDegradedEpoch = null
                    if (page.coverage == "degraded" && (resync || page.resetRequired)) resyncedDegradedEpoch = page.epoch
                    if (connectionStatus != updated.monitoringStatus) {
                        connectionStatus = updated.monitoringStatus
                        postNotification(this, null, 1, connectionNotification(connectionStatus))
                    }
                    local = updated; attempt = 0
                    if (resync) { store.update { it.copy(cursor = null) }; resyncRequested = true; break }
                    if (page.hasMore && (++catchupPages >= 4 || System.currentTimeMillis() - passStarted >= 30000)) { retryAfter = 5000; break }
                    if (!page.hasMore) { catchupPages = 0; passStarted = System.currentTimeMillis() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                terminal = failure is MobileFailure && !notificationRetryable(failure.key)
                if (failure is MobileFailure && failure.key in setOf("revoked", "unauthorized", "forbidden"))
                    runCatching { EncryptedHistoryStore(this).reject(failure.key) }
            }
            finally { namesJob?.cancel(); observer?.cancel(); api?.close(); api = null; synchronized(enrichments) { enrichments.values.forEach { it.cancel() }; enrichments.clear() } }
            if (resyncRequested) continue
            if (terminal) {
                NotificationPosting.cancelAlerts(this@NotificationService)
                runCatching { store.update { it.copy(enabled = false, monitoringStatus = "blocked") } }
                connectionStatus = "blocked"
                // Leave one paced, dismissible explanation, not an unproductive foreground
                // service or a second unpaced notify that Android can silently shed.
                stopForeground(STOP_FOREGROUND_DETACH)
                postNotification(this, null, 1, android.app.Notification.Builder.recoverBuilder(this, connectionNotification(connectionStatus)).setOngoing(false).build())
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
    private suspend fun show(device: String, event: NotificationEvent, local: NotificationLocal, silent: Boolean) {
        val kind = if (event.kind == "answer-finished") ANSWERS else ATTENTION
        val tag = tag(device, event.sessionId, kind)
        synchronized(enrichments) { enrichments.remove(tag)?.cancel() }
        if (event.kind == "attention-cleared") { NotificationPosting.cancel(this, tag, 2); return }
        NotificationPosting.invalidate(tag, 2)
        val ticket = NotificationPosting.ticket(tag, 2)
        var retained = false
        try {
            if (!postAlert(device, event, silent, kind, tag, ticket, null, local)) return
            if (event.kind == "attention-needed") {
                val transport = api ?: return
                val job = synchronized(enrichments) {
                    if (enrichments.size >= 32) return@synchronized null
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val question = withTimeoutOrNull(3000) {
                                enrichmentSlots.acquire()
                                try {
                                    notificationTrace("enrichment_begin")
                                    transport.snapshot(event.sessionId).pendingQuestions.firstOrNull()
                                } finally { enrichmentSlots.release(); notificationTrace("enrichment_end") }
                            } ?: return@launch
                            // Same event, same durable episode, and still in the desired set. Dismiss,
                            // resolution, a newer event and foreground visibility all invalidate this ticket.
                            if (desiredAlertEvent(tag) == event.eventId) postAlert(device, event, true, kind, tag, ticket, question)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Generic attention already delivered. */ }
                        finally {
                            NotificationPosting.release(ticket)
                            synchronized(enrichments) { if (enrichments[tag] === coroutineContext[Job]) enrichments.remove(tag) }
                        }
                    }.also { enrichments[tag] = it }
                }
                if (job != null) { retained = true; job.start() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { NotificationPosting.logFailure(failure); throw failure }
        finally { if (!retained) NotificationPosting.release(ticket) }
    }
    private suspend fun postAlert(device: String, event: NotificationEvent, silent: Boolean, kind: String, tag: String,
        ticket: NotificationPosting.Ticket, question: dev.dshmobile.app.model.PendingQuestion?, projected: NotificationLocal? = null): Boolean {
        // Disk and network are outside the non-suspending posting lane.
        val store = NotificationStore(this)
        while (true) {
            currentCoroutineContext().ensureActive()
            val revision = store.changes.value
            val durable = store.read()
            val latest = projected?.let { durable.copy(attention = it.attention) } ?: durable
            val host = EncryptedStateStore(this).read().host
            if (host?.deviceId != device || !latest.enabled || latest.deviceId != device ||
                (event.kind == "attention-needed" && latest.attention[event.sessionId] != event.attentionId)) return false
            if (revision != store.changes.value) continue
            val posted: Boolean? = NotificationPosting.paced {
                if (revision != store.changes.value) null // Re-read current policy outside the lane, never lose a durable event to an unrelated write.
                else if (!shouldDisplayNotification(event, latest, VisibleNotificationChat.sessionId) || !alertsAllowed()) false
                else deliverAlert(device, event, latest, workspaceNames, sessionNames, silent, kind, tag, ticket, revision, question)
                    .takeIf { revision == store.changes.value }
            }
            if (posted != null) return posted
        }
    }
    private fun deliverAlert(device: String, event: NotificationEvent, local: NotificationLocal,
        workspaces: List<dev.dshmobile.app.model.Workspace>, sessions: List<dev.dshmobile.app.model.SessionSummary>,
        silent: Boolean, kind: String, tag: String, ticket: NotificationPosting.Ticket, revision: Long, question: dev.dshmobile.app.model.PendingQuestion?): Boolean {
        if (kind == ATTENTION) NotificationPosting.cancel(this, tag(device, event.sessionId, ANSWERS), 2)
        val previous = desiredAlertNotification(tag) ?: manager.activeNotifications.find { it.tag == tag && it.id == 2 }?.notification
        val sameEvent = previous?.extras?.getString(ALERT_EVENT) == event.eventId
        val quiet = notificationUpdateSilent(silent, event.eventId, previous?.extras?.getString(ALERT_EVENT))
        // Enrichment updates the original channel in place; moving channels could re-alert
        // or bypass a user's channel override. Recovery of a new event still uses silent mode.
        val channel = previous?.channelId?.takeIf { sameEvent } ?: notificationChannel(event, local, quiet) ?: return false
        val title = localizedString(if (event.kind == "answer-finished") R.string.notif_answer_ready else R.string.notif_needs_decision)
        val open = Intent(this, MainActivity::class.java).setAction("dev.dshmobile.OPEN_CHAT")
            .setData(android.net.Uri.Builder().scheme("dshmobile").authority("chat").appendPath(device).appendPath(event.sessionId).build())
            .putExtra("notificationDevice", device).putExtra("notificationChat", event.sessionId).putExtra("notificationQuestion", question?.callId)
            .addFlags(NOTIFICATION_ACTIVITY_FLAGS)
        val tap = notificationChatIntent(this, device, event.sessionId, question?.callId)
        val dismissed = PendingIntent.getBroadcast(this, 0, Intent(this, NotificationDismissedReceiver::class.java)
            .setData(open.data?.buildUpon()?.appendPath(kind)?.build()).putExtra("removedTag", tag), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val knownChatName = sessions.find { it.id == event.sessionId }?.title?.trim()?.takeIf { it.isNotEmpty() }
        val chatName = knownChatName ?: localizedString(R.string.notif_unnamed_chat)
        val public = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (question != null) chatName else localizedString(R.string.app_name)).setContentText(if (question != null) null else title).build()
        val names = notificationNames(event, workspaces, sessions)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .addExtras(alertLanguageExtras(kind, names)).addExtras(android.os.Bundle().apply { putString(ALERT_EVENT, event.eventId); putString(ALERT_DEVICE, device); putString(ALERT_CHAT, event.sessionId) })
            .apply { if (question != null) { addExtras(android.os.Bundle().apply { putBoolean("dev.dshmobile.questionAlert", true); putBoolean("dev.dshmobile.unnamedChat", knownChatName == null) }); addAction(0, localizedString(R.string.notif_answer), tap) } }
            .setContentTitle(if (question != null) chatName else title).setContentText(question?.questions?.firstOrNull()?.question?.take(180) ?: names ?: localizedString(R.string.notif_open_chat_fallback))
            .setGroup(ALERT_GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(tap).setDeleteIntent(dismissed)
            .setOnlyAlertOnce(quiet).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setSilent(quiet).build()
        var posted = false
        NotificationPosting.commit(ticket) {
            // No suspension between the final visibility/permission check and notify.
            if (NotificationStore(this).changes.value == revision && shouldDisplayNotification(event, local, VisibleNotificationChat.sessionId, sessions) && alertsAllowed())
                { manager.notify(tag, 2, notification); updateAlertSummary(this, added = tag to notification); posted = true; notificationEventTrace("notify", event.eventId, event.sequence) }
        }
        return posted
    }
    override fun onDestroy() {
        NotificationStreamStatus.status.value = null
        connectivity.unregisterNetworkCallback(networkCallback)
        running?.cancel(); api?.close(); scope.cancel(); super.onDestroy()
    }
    companion object {
        private const val ALERT_EVENT = "dev.dshmobile.alertEvent"
        const val STOP = "dev.dshmobile.NOTIFICATIONS_STOP"
        const val CONNECTION = "connection"
        const val ANSWERS = "answers"
        const val ATTENTION = "attention"
        private fun tag(device: String, chat: String, kind: String) = "$device:$chat:$kind"
    }
}
