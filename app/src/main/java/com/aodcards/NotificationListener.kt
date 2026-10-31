package com.aodcards

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.graphics.Canvas
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {
    private var screenReceiverRegistered = false
    private var displayListenerRegistered = false
    private var privacyObserverRegistered = false
    private val privacyObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshSnapshot(this@NotificationListener) }
    }
    @Volatile private var listenerConnected = false
    @Volatile private var screenInteractive: Boolean? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("AODCards-parser").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val refreshQueue = RefreshQueue(
        post = { task, delay -> refreshHandler.postDelayed(task, delay); Unit },
        remove = { task -> refreshHandler.removeCallbacks(task) },
        start = ::startRefresh
    )
    private val sourceChanges = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var sourceChangeSequence = 0L // Notification callbacks run on the main thread.
    @Volatile private var rankingRevision = 0L
    private var parsingChanges: Map<String, Long> = emptyMap() // One parser worker.
    private var parsingRankingRevision = 0L
    @Volatile private var sourceCount = 0
    @Volatile private var lastFailure: String? = null
    private val mediaTracker by lazy { MediaTracker(this, refreshHandler) { scheduleRefresh() } }
    private val labelCache = android.util.LruCache<String, String>(128)
    private data class CachedCard(val postTime: Long, val withPreview: Boolean, val mode: Int, val size: Int, val withSender: Boolean, val customEnabled: Boolean, val card: NotificationCard)
    private val cardCache = object : android.util.LruCache<String, CachedCard>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CachedCard): Int {
            val card = value.card
            val characters = key.length.toLong() + card.title.length + card.text.length +
                card.appName.length + (card.senderName?.length ?: 0)
            return (1024L + characters * 2 + (card.previewImage?.allocationByteCount ?: 0) +
                (card.senderImage?.allocationByteCount ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
    private val cardCacheHits = java.util.concurrent.atomic.AtomicLong()
    private val cardCacheMisses = java.util.concurrent.atomic.AtomicLong()
    private val canceledRenders = java.util.concurrent.atomic.AtomicLong()
    private val screenOffRefresh = Runnable {
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        AodCardsWidgetProvider.renderAll(this)
        scheduleRefresh()
    }
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val displayStates = mutableMapOf<Int, Int>()
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            displayStates[displayId] = displayManager.getDisplay(displayId)?.state ?: android.view.Display.STATE_UNKNOWN
            AodCardsWidgetProvider.renderAll(this@NotificationListener)
            scheduleRefresh()
            refreshAfterScreenOff()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (displayStates.remove(displayId) != null) {
                AodCardsWidgetProvider.renderAll(this@NotificationListener)
                scheduleRefresh()
                refreshAfterScreenOff()
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            val newState = displayManager.getDisplay(displayId)?.state ?: android.view.Display.STATE_UNKNOWN
            if (displayStates.put(displayId, newState) != newState) {
                AodCardsWidgetProvider.renderAll(this@NotificationListener)
                scheduleRefresh()
                refreshAfterScreenOff()
            }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED") {
                refreshSnapshot(this@NotificationListener)
                return
            }
            val interactive = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                Intent.ACTION_USER_PRESENT, Intent.ACTION_USER_UNLOCKED ->
                    (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                else -> return
            }
            // Trust the screen event; an AOD/secondary display may still report STATE_ON.
            screenInteractive = interactive
            refreshHandler.removeCallbacks(screenOffRefresh)
            refreshSnapshot(this@NotificationListener)
            refreshAfterScreenOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("AODCards", "Notification listener created")
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        active = this
        registerScreenReceiver()
        registerDisplayListener()
        try {
            NotificationPrivacy.settingNames.forEach { name ->
                contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor(name), false, privacyObserver)
            }
            privacyObserverRegistered = true
        } catch (_: Exception) { /* Policy reads still fail closed at each render. */ }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleRefresh(0L, invalidate = true)
        clearCardCache(); MusicSessionSnapshot.clearCache()
        WidgetLayout.clearCache()
    }
    override fun onDestroy() {
        Log.i("AODCards", "Notification listener destroyed")
        refreshQueue.cancel()
        refreshHandler.removeCallbacks(screenOffRefresh)
        if (privacyObserverRegistered) contentResolver.unregisterContentObserver(privacyObserver)
        if (displayListenerRegistered) try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        if (screenReceiverRegistered) try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        listenerConnected = false
        clearCardCache(); MusicSessionSnapshot.clearCache()
        mediaTracker.stop()
        workerThread.quitSafely()
        WidgetLayout.clearCache()
        CardState.publish(CardsSnapshot(connection = ConnectionState.DISCONNECTED))
        AodCardsWidgetProvider.renderAll(this)
        if (active === this) active = null
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // RECEIVER_EXPORTED is needed to receive protected system broadcasts on newer targets.
                Context::class.java.getMethod("registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType)
                    .invoke(this, screenReceiver, filter, 2)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("AODCards", "Screen state receiver could not be registered", e)
        }
    }

    private fun registerDisplayListener() {
        try {
            displayManager.displays.forEach { displayStates[it.displayId] = it.state }
            displayManager.registerDisplayListener(displayListener, refreshHandler)
            displayListenerRegistered = true
        } catch (e: Exception) {
            Log.w("AODCards", "Display state listener could not be registered", e)
        }
    }
    override fun onListenerConnected() {
        Log.i("AODCards", "Notification listener connected")
        active = this
        listenerConnected = true
        CardState.publish(CardState.value.copy(connection = ConnectionState.CONNECTING))
        mediaTracker.start()
        scheduleRefresh(0L)
    }
    override fun onListenerDisconnected() {
        Log.i("AODCards", "Notification listener disconnected")
        refreshQueue.cancel()
        refreshHandler.removeCallbacks(screenOffRefresh)
        listenerConnected = false
        clearCardCache(); MusicSessionSnapshot.clearCache()
        mediaTracker.stop()
        WidgetLayout.clearCache()
        CardState.publish(CardsSnapshot(connection = ConnectionState.DISCONNECTED))
        AodCardsWidgetProvider.renderAll(this)
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotificationListener::class.java))
        } catch (e: Exception) {
            Log.w("AODCards", "Could not request notification listener rebind", e)
        }
        super.onListenerDisconnected()
    }
    private fun markSourceChanged(key: String) {
        // Bound metadata even if a provider keeps the single parser blocked indefinitely.
        if (sourceChanges.size >= 1024) {
            refreshQueue.cancel()
            sourceChanges.clear()
            clearCardCache()
        }
        sourceChanges[key] = ++sourceChangeSequence
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        markSourceChanged(sbn.key)
        synchronized(cardCache) { cardCache.remove(sbn.key) }
        val replaces = CardState.value.cards.any { it.key == sbn.key }
        if (replaces) {
            WidgetLayout.clearCache()
            CardState.publish(CardState.value.copy(cards = CardState.value.cards.filterNot { it.key == sbn.key }, music = null))
            AodCardsWidgetProvider.renderAll(this)
        }
        scheduleRefresh(invalidate = replaces)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        markSourceChanged(sbn.key)
        // Removal must not wait for image decoding, nor be undone by an old parse.
        scheduleRefresh(invalidate = true)
        synchronized(cardCache) { cardCache.remove(sbn.key) }
        CardState.publish(CardState.value.copy(cards = CardState.value.cards.filterNot { it.key == sbn.key }, music = null))
        WidgetLayout.clearCache()
        AodCardsWidgetProvider.renderAll(this)
    }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        rankingRevision++
        MusicSessionSnapshot.clearCache()
        // Update restrictions without waiting behind an attachment provider on the worker.
        val cards = CardState.value.cards.map { card ->
            val ranking = Ranking()
            val override = if (rankingMap.getRanking(card.key, ranking)) ranking.lockscreenVisibilityOverride
                else Notification.VISIBILITY_PRIVATE
            card.copy(lockscreenVisibilityOverride = override)
        }
        if (cards != CardState.value.cards) {
            val policy = NotificationPrivacy.read(this)
            val prefs = SettingsRepository(this)
            CardState.publish(CardState.value.copy(cards = policy.cards(cards, prefs), music = null))
            refreshSnapshot(this)
        } else scheduleRefresh()
    }

    private fun scheduleRefresh(delay: Long = 120L, invalidate: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            refreshHandler.post { scheduleRefresh(delay, invalidate) }
            return
        }
        if (listenerConnected) refreshQueue.request(delay, invalidate)
    }

    private fun startRefresh(token: Int) {
        if (!worker.post {
            try {
                if (token == refreshQueue.revision) loadActive(token)
            } finally {
                refreshHandler.post { refreshQueue.complete() }
            }
        }) refreshQueue.complete()
    }

    private fun refreshAfterScreenOff() {
        // Recheck after the display has transitioned into its dozing state.
        refreshHandler.removeCallbacks(screenOffRefresh)
        refreshHandler.postDelayed(screenOffRefresh, 400L)
    }

    private fun loadActive(token: Int) {
        try {
            val changes = sourceChanges.toMap()
            parsingChanges = changes
            parsingRankingRevision = rankingRevision
            val active = activeNotifications.orEmpty()
            sourceCount = active.size
            val rankingMap = currentRanking
            val systemRanks = rankingMap?.orderedKeys.orEmpty().withIndex().associate { it.value to it.index }
            val prefs = SettingsRepository(this)
            val privacy = NotificationPrivacy.read(this)
            val candidate = if (prefs.musicPillEnabled && prefs.contentMode == 0 &&
                (!privacy.locked || privacy.showNotifications && privacy.showPrivateContent))
                MusicSessionSnapshot.get(this, prefs.blockedPackages) {
                    token == refreshQueue.revision && parsingRankingRevision == rankingRevision
                } else null
            val mediaNotifications = active.filter { it.packageName == candidate?.packageName && isMediaNotification(it.notification) }
            val mediaAllowed = mediaNotifications.isNotEmpty() && mediaNotifications.all { sbn ->
                val rank = Ranking()
                sbn.user == android.os.Process.myUserHandle() && rankingMap?.getRanking(sbn.key, rank) == true &&
                    privacy.copy(locked = true).mode(sbn.notification.visibility, rank.lockscreenVisibilityOverride) == 0
            }
            val music = privacy.music(candidate?.copy(lockscreenAllowed = mediaAllowed), prefs)
            if (music == null) MusicSessionSnapshot.clearCache()
            val blockedPackages = prefs.blockedPackages
            val parsed = active.asSequence()
                .filterNot { sbn ->
                    music != null && sbn.packageName == music.packageName && isMediaNotification(sbn.notification)
                }
                .mapIndexedNotNull { index, sbn ->
                    if (token != refreshQueue.revision || sbn.user != android.os.Process.myUserHandle()) return@mapIndexedNotNull null
                    val ranking = Ranking()
                    val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
                    val notification = sbn.notification
                    val conversation = notification.category == Notification.CATEGORY_MESSAGE ||
                        (hasRanking && ranking.isConversation) ||
                        notification.extras?.getString("android.template")?.contains("MessagingStyle") == true
                    val overrideKey = if (hasRanking) ranking.overrideGroupKey else null
                    val visibilityOverride = if (hasRanking) ranking.lockscreenVisibilityOverride else Notification.VISIBILITY_PRIVATE
                    val systemMode = privacy.mode(notification.visibility, visibilityOverride)
                    if (systemMode == null) {
                        cardCache.remove(sbn.key)
                        return@mapIndexedNotNull null
                    }
                    readCard(sbn, blockedPackages, index < 24, maxOf(systemMode, prefs.contentMode), token)?.copy(
                        lockscreenVisibility = notification.visibility,
                        lockscreenVisibilityOverride = visibilityOverride,
                        systemRank = systemRanks[sbn.key] ?: Int.MAX_VALUE,
                        systemGroupKey = NotificationGrouping.groupKey(conversation, notification.group,
                            overrideKey?.takeIf { it.isNotBlank() } ?: sbn.groupKey, sbn.isGroup),
                        isConversation = conversation,
                        isSystemGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
                    )
                }
                .toList()
            // Keep sources separate until policy and package filtering at the render boundary.
            val visible = parsed
            refreshHandler.post {
                if (token == refreshQueue.revision && listenerConnected && CardState.hasAccess(this)) {
                    val latestPolicy = NotificationPrivacy.read(this)
                    val latestPrefs = SettingsRepository(this)
                    val latestRanking = currentRanking
                    val currentCards = visible.filter { (sourceChanges[it.key] ?: 0L) == (changes[it.key] ?: 0L) }
                        .map { card ->
                            val rank = Ranking()
                            card.copy(lockscreenVisibilityOverride = if (latestRanking?.getRanking(card.key, rank) == true)
                                rank.lockscreenVisibilityOverride else Notification.VISIBILITY_PRIVATE)
                        }
                    val sourceChanged = active.any { (sourceChanges[it.key] ?: 0L) != (changes[it.key] ?: 0L) }
                    CardState.publish(CardsSnapshot(latestPolicy.cards(currentCards, latestPrefs),
                        latestPolicy.music(music.takeUnless { sourceChanged || parsingRankingRevision != rankingRevision }, latestPrefs), ConnectionState.CONNECTED,
                        System.currentTimeMillis(), active.map { it.packageName }.toSet(),
                        active.count { it.packageName in blockedPackages }))
                    val activeKeys = active.mapTo(hashSetOf()) { it.key }
                    sourceChanges.entries.removeIf { it.key !in activeKeys && it.value == changes[it.key] }
                    lastFailure = null
                    AodCardsWidgetProvider.renderAll(this)
                }
            }
        } catch (e: Exception) {
            lastFailure = e.javaClass.simpleName
            Log.w("AODCards", "Unable to refresh notification cards", e)
            refreshHandler.post {
                if (token == refreshQueue.revision && listenerConnected) {
                    CardState.publish(CardsSnapshot(connection = ConnectionState.ERROR))
                    clearCardCache()
                    MusicSessionSnapshot.clearCache()
                    WidgetLayout.clearCache()
                    AodCardsWidgetProvider.renderAll(this)
                }
            }
        }
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        val snapshot = CardState.value
        writer.println("listenerConnected=$listenerConnected access=${CardState.hasAccess(this)}")
        writer.println("state=${snapshot.connection} sourceCount=$sourceCount cards=${snapshot.cards.size} music=${snapshot.music != null} blocked=${snapshot.blockedCount}")
        writer.println("revision=${refreshQueue.revision} running=${refreshQueue.running} pending=${refreshQueue.pending} workerAlive=${workerThread.isAlive} lastFailure=$lastFailure")
        writer.println("updatedAt=${snapshot.updatedAt} screenInteractive=$screenInteractive powerInteractive=${(getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive} receiverRegistered=$screenReceiverRegistered")
        val stats = CustomNotificationSnapshot.stats
        writer.println("cardCacheBytes=${cardCache.size()} entries=${cardCache.snapshot().size} hits=${cardCacheHits.get()} misses=${cardCacheMisses.get()}")
        writer.println("customRenders=${stats.attempts} successes=${stats.successes} totalMs=${stats.totalNanos / 1_000_000.0} maxMs=${stats.maxNanos / 1_000_000.0} lastBitmapBytes=${stats.lastBitmapBytes} canceled=$canceledRenders")
        val manager = android.appwidget.AppWidgetManager.getInstance(this)
        val settings = SettingsRepository(this)
        manager.getAppWidgetIds(ComponentName(this, AodCardsWidgetProvider::class.java)).forEach { id ->
            val (width, height) = WidgetRenderer.sizeFor(manager.getAppWidgetOptions(id))
            val plan = WidgetRenderModel.build(snapshot.cards, settings, snapshot.music, width, height, this)
            writer.println("widget=$id size=${width}x$height planCards=${plan.cards.size} planMusic=${plan.music != null} overflow=${plan.overflow}")
        }
    }

    private fun isMediaNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras
        if (extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        return extras?.getString("android.template")?.contains("MediaStyle", ignoreCase = true) == true
    }

    private fun clearCardCache() = synchronized(cardCache) { cardCache.evictAll() }

    private fun readCard(sbn: StatusBarNotification, blockedPackages: Set<String>, withImages: Boolean, mode: Int, token: Int): NotificationCard? {
        if (sbn.packageName == packageName || sbn.packageName in blockedPackages) {
            cardCache.remove(sbn.key)
            return null
        }
        return try {
            if (token != refreshQueue.revision) return null
            val prefs = SettingsRepository(this)
            val size = prefs.effectiveCustomNotificationScale
            val ongoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            val withSender = mode != 2 && !prefs.compactMode && (!ongoing || prefs.ongoingMode == 0)
            val withPreview = withImages && mode == 0 && withSender
            val sourceVersion = sourceChanges[sbn.key] ?: 0L
            val cached = cardCache.get(sbn.key)
            if (cached?.postTime == sbn.postTime && cached.mode == mode &&
                cached.customEnabled == prefs.customNotificationsEnabled &&
                (!cached.card.isCustomContent || cached.size == size) &&
                (!withPreview || cached.withPreview) && (!withSender || cached.withSender)) {
                cardCacheHits.incrementAndGet()
                cached.card.copy(previewImage = cached.card.previewImage.takeIf { withPreview },
                    senderImage = cached.card.senderImage.takeIf { withSender })
            } else {
                cardCacheMisses.incrementAndGet()
                // Parsing can now discard the notification; do not retain its old snapshot.
                cardCache.remove(sbn.key)
                val card = if (mode == 2) NotificationCard(sbn.key, sbn.packageName, appLabel(sbn.packageName),
                    null, "", "", sbn.postTime, sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0, null, null)
                else toCard(sbn, withPreview, withSender, size, prefs.customNotificationsEnabled,
                    keepImageOnly = prefs.compactMode && mode == 0 && withImages) {
                    token == refreshQueue.revision && (sourceChanges[sbn.key] ?: 0L) == sourceVersion
                }?.let { NotificationPrivacy.redact(it, mode) }
                card?.also {
                    synchronized(cardCache) {
                        if (token == refreshQueue.revision && parsingRankingRevision == rankingRevision &&
                            (sourceChanges[sbn.key] ?: 0L) == (parsingChanges[sbn.key] ?: 0L)) {
                            cardCache.put(sbn.key, CachedCard(sbn.postTime, withPreview, mode, size, withSender, prefs.customNotificationsEnabled, it))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AODCards", "Could not parse notification from ${sbn.packageName}", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun toCard(sbn: StatusBarNotification, withImages: Boolean, withSender: Boolean, size: Int, customEnabled: Boolean,
        keepImageOnly: Boolean,
        isCurrent: () -> Boolean): NotificationCard? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == packageName) return null
        val extras = n.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val appName = appLabel(sbn.packageName)
        var body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (body.isBlank()) body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (body.isBlank()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!lines.isNullOrEmpty()) body = lines.last().toString()
        }
        // MessagingStyle often places the sender in the latest message title.
        val messages = extractMessages(extras)
        fun senderOf(message: Notification.MessagingStyle.Message): String =
            senderPersonName(message)?.takeIf { it.isNotBlank() } ?: message.sender?.toString().orEmpty()
        val latest = messages.lastOrNull()
        fun usefulLabel(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf {
            it.isNotBlank() && !it.equals(appName, ignoreCase = true)
        }
        val notificationAuthor = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
        ).mapNotNull(::usefulLabel).firstOrNull()
        val senderName = messages.asReversed().firstNotNullOfOrNull { senderOf(it).takeIf(String::isNotBlank) }
            ?: notificationAuthor
        if (messages.isNotEmpty()) {
            val messagePreviews = messages.takeLast(3).mapNotNull { message ->
                message.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
            if (messagePreviews.isNotEmpty()) body = messagePreviews.joinToString("\n")
        }
        if (senderName != null && title == senderName) title = ""
        val hasCustomContent = customEnabled && title.isBlank() && body.isBlank() && n.contentView != null
        val customContent = if (withImages && hasCustomContent)
            renderCustomContent(n, sbn.packageName, WidgetRows.scaleFactor(size), isCurrent) else null
        val image = customContent ?: if (withImages) extractImage(n, latest) else null
        // Compact mode hides attachments, not the notification that carries them.
        // Inspect metadata only: do not decode a hidden image or open its content URI.
        val hiddenAttachment = keepImageOnly &&
            (extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) != null ||
                latest?.let { it.dataMimeType?.startsWith("image/") == true && it.dataUri != null } == true)
        // The preview budget only limits large attachments. A sender's 64px avatar
        // is still needed when this notification appears late in the system list.
        val senderImage = if (withSender) extractSenderImage(n, messages) else null
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (ongoing && title.isBlank() && body.isBlank() && !hasCustomContent) body = getString(R.string.localized_002)
        // Keep the app identity when custom content is hidden, unsupported or over budget.
        if (title.isBlank() && body.isBlank() && image == null && !hasCustomContent && !hiddenAttachment) return null
        return NotificationCard(sbn.key, sbn.packageName, appName, senderName, title, body, sbn.postTime,
            ongoing, image, senderImage,
            isCustomContent = hasCustomContent,
            groupCount = maxOf(1, n.number, messages.size),
            displayTime = n.`when`.takeIf { it > 0 && extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true) })
    }

    private fun extractMessages(extras: Bundle): List<Notification.MessagingStyle.Message> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        if (Build.VERSION.SDK_INT >= 30) {
            return Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        }
        return bundles.mapNotNull { parcelable ->
            val bundle = parcelable as? Bundle ?: return@mapNotNull null
            val text = bundle.getCharSequence("text") ?: return@mapNotNull null
            val sender = bundle.getCharSequence("sender")
            val message = Notification.MessagingStyle.Message(text, bundle.getLong("time"), sender)
            val mime = bundle.getString("type")
            val uri = bundle.getString("uri")
            if (!mime.isNullOrBlank() && !uri.isNullOrBlank()) message.setData(mime, Uri.parse(uri))
            message
        }
    }

    private fun senderPersonName(message: Notification.MessagingStyle.Message): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val person = message.javaClass.getMethod("getSenderPerson").invoke(message) ?: return null
            person.javaClass.getMethod("getName").invoke(person)?.toString()
        } catch (_: Exception) { null }
    }

    private fun senderPersonIcon(message: Notification.MessagingStyle.Message): android.graphics.drawable.Icon? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val person = message.javaClass.getMethod("getSenderPerson").invoke(message) ?: return null
            person.javaClass.getMethod("getIcon").invoke(person) as? android.graphics.drawable.Icon
        } catch (_: Exception) { null }
    }

    /** RemoteViews must be inflated on the main thread; parsing otherwise stays on the worker. */
    @Suppress("DEPRECATION")
    private fun renderCustomContent(notification: Notification, packageName: String, scale: Float,
        isCurrent: () -> Boolean): Bitmap? {
        val remote = notification.contentView ?: return null
        fun renderIfCurrent(): Bitmap? {
            if (!isCurrent()) {
                canceledRenders.incrementAndGet()
                return null
            }
            return CustomNotificationSnapshot.render(this, remote, packageName, scale)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return renderIfCurrent()
        val result = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        val done = java.util.concurrent.CountDownLatch(1)
        val task = Runnable {
            try { result.set(renderIfCurrent()) }
            finally { done.countDown() }
        }
        if (!refreshHandler.post(task)) return null
        if (!done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            refreshHandler.removeCallbacks(task)
            Log.w("AODCards", "custom content timed out for $packageName")
            return null
        }
        return result.get()
    }

    @Suppress("DEPRECATION")
    private fun extractSenderImage(notification: Notification, messages: List<Notification.MessagingStyle.Message>): Bitmap? {
        val personIcon = messages.asReversed().firstNotNullOfOrNull { senderPersonIcon(it) }
        if (personIcon != null) drawableToAvatar(personIcon.loadDrawable(this))?.let { return it }
        val extras = notification.extras
        // Prefer the normal notification icon used in its collapsed presentation; BIG is
        // intended for expanded BigPicture-style notifications and can look unlike LockStar.
        val largeIcon = notification.getLargeIcon()
            ?: extras.getParcelable<Parcelable>(Notification.EXTRA_LARGE_ICON)
            ?: extras.getParcelable<Parcelable>(Notification.EXTRA_LARGE_ICON_BIG)
        return when (largeIcon) {
            is Bitmap -> scaleAvatar(largeIcon)
            is android.graphics.drawable.Icon -> drawableToAvatar(largeIcon.loadDrawable(this))
            else -> null
        }
    }

    private fun drawableToAvatar(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        val side = 64
        return try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, side, side)
                drawable.draw(canvas)
            }
        } catch (_: Exception) { null }
    }

    private fun scaleAvatar(bitmap: Bitmap): Bitmap {
        val cropSide = minOf(bitmap.width, bitmap.height)
        val cropX = (bitmap.width - cropSide) / 2
        val cropY = (bitmap.height - cropSide) / 2
        val square = Bitmap.createBitmap(bitmap, cropX, cropY, cropSide, cropSide)
        return Bitmap.createScaledBitmap(square, 64, 64, true)
    }

    @Suppress("DEPRECATION")
    private fun extractImage(notification: Notification, message: Notification.MessagingStyle.Message?): Bitmap? {
        val extras = notification.extras
        val picture = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        val uri = message?.takeIf { it.dataMimeType?.startsWith("image/") == true }?.dataUri
        val bitmap = picture ?: uri?.let { decodeImage(it) } ?: return null
        val maxSide = 320
        if (bitmap.width <= maxSide && bitmap.height <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) { null }
    }

    private fun appLabel(pkg: String): String = labelCache.get(pkg) ?: (try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }).also { labelCache.put(pkg, it) }


    companion object {
        @Volatile private var active: NotificationListener? = null
        fun snapshot(): List<NotificationCard> = CardState.value.cards
        fun isScreenInteractive(context: Context): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        fun refreshSnapshot(context: Context) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { refreshSnapshot(context) }
                return
            }
            val service = active
            // Invalidate in-flight work before applying the latest policy.
            if (service?.listenerConnected == true) service.scheduleRefresh(0L, invalidate = true)
            // Rendering policy is checked again below and on every cache read. Screen,
            // ranking and privacy events do not change the notification's pixels.
            MusicSessionSnapshot.clearCache()
            WidgetLayout.clearCache()
            val prefs = SettingsRepository(context)
            val privacy = NotificationPrivacy.read(context)
            val old = CardState.value
            CardState.publish(old.copy(cards = privacy.cards(old.cards, prefs), music = privacy.music(old.music, prefs)))
            AodCardsWidgetProvider.renderAll(context)
            if (service?.listenerConnected != true) forceRefresh(context)
        }
        fun clearUnavailable(context: Context) {
            active?.let { service ->
                service.refreshQueue.cancel()
                service.clearCardCache(); MusicSessionSnapshot.clearCache()
            }
            WidgetLayout.clearCache()
            CardState.publish(CardsSnapshot())
            AodCardsWidgetProvider.renderAll(context)
        }
        fun forceRefresh(context: Context): Int? {
            val service = active
            if (service?.listenerConnected == true) {
                service.scheduleRefresh(0L, invalidate = true)
                return CardState.value.cards.size
            }
            if (!CardState.hasAccess(context)) {
                clearUnavailable(context)
                return null
            }
            CardState.publish(CardsSnapshot(connection = ConnectionState.CONNECTING))
            return try {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationListener::class.java))
                null
            } catch (e: Exception) {
                Log.w("AODCards", "Unable to request notification listener rebind", e)
                null
            }
        }
    }
}
