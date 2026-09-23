package com.markvoronin.reelsonthego.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import com.markvoronin.reelsonthego.MainActivity
import com.markvoronin.reelsonthego.R
import com.markvoronin.reelsonthego.data.PreferencesRepository
import com.markvoronin.reelsonthego.data.PrevAction
import com.markvoronin.reelsonthego.data.getAppDisplayName
import com.markvoronin.reelsonthego.data.isBluetoothAudioConnected
import com.markvoronin.reelsonthego.util.Logger
import com.markvoronin.reelsonthego.util.ShizukuManager

class MediaButtonService : Service() {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var silentAudioTrack: AudioTrack? = null
    private var isReceiverRegistered = false
    private lateinit var prefsRepository: PreferencesRepository

    @Volatile
    private var currentForegroundPackage: String = ""

    @Volatile
    private var wasBackgroundMusicPlaying: Boolean = false

    @Volatile
    private var lastQueryTimestamp: Long = 0L

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appMonitorRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            bgHandler?.postDelayed(this, 1000L)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Logger.log("Bluetooth device connected -> Resuming app monitor check")
                    bgHandler?.post(appMonitorRunnable)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (prefsRepository.isRequireBluetoothEnabled) {
                        Logger.log("Bluetooth device disconnected -> Stopping MediaButtonService")
                        stopSelf()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Logger.log("Screen turned OFF -> Pausing polling & deactivating MediaSession for Deep Sleep")
                    bgHandler?.removeCallbacks(appMonitorRunnable)
                    lastQueryTimestamp = 0L
                    currentForegroundPackage = ""
                    deactivateMediaSession()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    Logger.log("Screen turned ON -> Resuming background monitoring")
                    bgHandler?.removeCallbacks(appMonitorRunnable)
                    lastQueryTimestamp = 0L
                    bgHandler?.post(appMonitorRunnable)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.log("MediaButtonService created (App-Dynamic Mode via UsageStats)")
        prefsRepository = PreferencesRepository(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        registerSystemReceiver()

        // Auto-grant permissions via Shizuku if connected
        ShizukuManager.grantMediaKeyPermissions(this)

        registerSystemMediaKeyListeners()

        // Start real-time Shizuku Logcat MediaKey Monitor for YouTube Shorts/MediaSession hijacking
        startLogcatMediaKeyMonitor()

        // Start foreground app monitoring on background thread
        startAppMonitorThread()
    }

    private fun registerSystemMediaKeyListeners() {
        try {
            val msm = (getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager) ?: return
            val msmClass = msm::class.java

            // 1. System MediaKeyDispatchedListener
            try {
                val listenerClass = Class.forName("android.media.session.MediaSessionManager${'$'}OnMediaKeyEventDispatchedListener")
                val proxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, _, args ->
                    if ((args != null) && (args.isNotEmpty())) {
                        val keyEvent = args[0] as? KeyEvent
                        if (keyEvent != null && isCurrentAppTargeted()) {
                            val isMediaKey = when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                                else -> false
                            }

                            if (isMediaKey) {
                                if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                                    Logger.log("System MediaKeyDispatchedListener (ACTION_DOWN): keyCode=${keyEvent.keyCode}")
                                    handleInterceptedMediaKey(keyEvent.keyCode)
                                }
                                return@newProxyInstance true
                            }
                        }
                    }
                    null
                }
                val addMethod = msmClass.getMethod("addOnMediaKeyEventDispatchedListener", Executor::class.java, listenerClass)
                val executor = ContextCompat.getMainExecutor(this)
                addMethod.invoke(msm, executor, proxy)
                Logger.log("Registered addOnMediaKeyEventDispatchedListener!")
            } catch (e: Exception) {
                Logger.log("Failed addOnMediaKeyEventDispatchedListener: ${e.message}")
            }

            // 2. System MediaKeyListener
            try {
                val listenerClass = Class.forName("android.media.session.MediaSessionManager\$OnMediaKeyListener")
                val proxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, _, args ->
                    if (args != null && args.isNotEmpty()) {
                        val keyEvent = args[0] as? KeyEvent
                        if (keyEvent != null && isCurrentAppTargeted()) {
                            val isMediaKey = when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                                else -> false
                            }

                            if (isMediaKey) {
                                if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                                    Logger.log("System MediaKeyListener (ACTION_DOWN): keyCode=${keyEvent.keyCode}")
                                    handleInterceptedMediaKey(keyEvent.keyCode)
                                }
                                // VITAL: Consume BOTH ACTION_DOWN and ACTION_UP!
                                // If we don't consume ACTION_UP, Android's MediaSessionService routes it to YT Music as a fallback!
                                return@newProxyInstance true
                            }
                        }
                    }
                    false
                }
                val setMethod = msmClass.getMethod("setOnMediaKeyListener", listenerClass, Handler::class.java)
                setMethod.invoke(msm, proxy, mainHandler)
                Logger.log("Registered setOnMediaKeyListener!")
            } catch (e: Exception) {
                Logger.log("Failed setOnMediaKeyListener: ${e.message}")
            }
        } catch (e: Exception) {
            Logger.log("Error in registerSystemMediaKeyListeners: ${e.message}", isError = true)
        }
    }

    fun isTargetAppActive(): Boolean {
        return isCurrentAppTargeted()
    }

    fun handleInterceptedMediaKey(keyCode: Int): Boolean {
        if (!isCurrentAppTargeted()) return false
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                Logger.log("Intercepted Media Key $keyCode -> Swipe Up")
                performSwipeUp()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (getActivePrevAction() == PrevAction.LIKE) {
                    Logger.log("Intercepted Media Key $keyCode -> Double Tap (Like)")
                    performDoubleTap()
                } else {
                    Logger.log("Intercepted Media Key $keyCode -> Swipe Down")
                    performSwipeDown()
                }
                return true
            }
        }
        return false
    }

    private fun startAppMonitorThread() {
        if (bgThread == null) {
            bgThread = HandlerThread("AppMonitorThread").apply { start() }
            bgHandler = Handler(bgThread!!.looper)
        }
        bgHandler?.removeCallbacks(appMonitorRunnable)
        bgHandler?.post(appMonitorRunnable)
    }

    private fun stopAppMonitorThread() {
        bgHandler?.removeCallbacks(appMonitorRunnable)
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    private fun checkForegroundApp() {
        if (!prefsRepository.isServiceEnabled) return

        if (prefsRepository.isRequireBluetoothEnabled && !isBluetoothAudioConnected(this)) {
            mainHandler.post {
                if (mediaSession?.isActive == true) {
                    Logger.log("Bluetooth not connected -> Deactivating MediaSession & Stopping MediaButtonService")
                    deactivateMediaSession()
                    stopSelf()
                }
            }
        } else {
            val foregroundPkg = getForegroundPackageName() ?: currentForegroundPackage
            if (foregroundPkg.isNotEmpty()) {
                val pkgChanged = foregroundPkg != currentForegroundPackage
                currentForegroundPackage = foregroundPkg
                val isTargetApp = prefsRepository.isGlobalSwipeEnabled || prefsRepository.isPackageEnabled(foregroundPkg)

                mainHandler.post {
                    if (isTargetApp) {
                        val bgMusicPlaying = isBackgroundMusicPlaying()
                        val musicStateChanged = bgMusicPlaying != wasBackgroundMusicPlaying
                        wasBackgroundMusicPlaying = bgMusicPlaying
                        
                        if (mediaSession?.isActive != true) {
                            Logger.log("Foreground Target App Detected ($foregroundPkg) -> Activating MediaSession")
                            activateMediaSession()
                        } else if (pkgChanged || musicStateChanged) {
                            val appName = getAppDisplayName(foregroundPkg)
                            updateNotificationText("Active: $appName (Bluetooth Connected)")
                            if (isYouTubeApp(foregroundPkg) || bgMusicPlaying) {
                                stopSilentAudio()
                                abandonAudioFocus()
                            } else {
                                val focusGranted = requestAudioFocus()
                                if (focusGranted) {
                                    startSilentAudio()
                                    forceStopGhostMediaApps()
                                }
                            }
                            val state = PlaybackState.Builder()
                                .setActions(
                                    PlaybackState.ACTION_PLAY or
                                            PlaybackState.ACTION_PAUSE or
                                            PlaybackState.ACTION_SKIP_TO_NEXT or
                                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                            PlaybackState.ACTION_FAST_FORWARD or
                                            PlaybackState.ACTION_REWIND
                                )
                                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                                .build()
                            mediaSession?.setPlaybackState(state)
                        }
                    } else {
                        if (mediaSession?.isActive == true) {
                            Logger.log("Non-Target App in Foreground ($foregroundPkg) -> Deactivating MediaSession & Stopping MediaButtonService")
                            deactivateMediaSession()
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun getForegroundPackageName(): String? {
        // 1. Shizuku Direct Window Focus Path (Fastest & Most Accurate when Shizuku is active)
        if (ShizukuManager.isGranted) {
            val shizukuTopPkg = ShizukuManager.getTopPackageName()
            if (!shizukuTopPkg.isNullOrEmpty()) {
                return shizukuTopPkg
            }
        }

        // 2. UsageStatsManager Query with a 15-second rolling window overlap to prevent missing tight transitions
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = if (lastQueryTimestamp == 0L) {
            endTime - 60000L
        } else {
            (lastQueryTimestamp - 15000L).coerceAtLeast(endTime - 60000L)
        }

        val events = usm.queryEvents(startTime, endTime)
        lastQueryTimestamp = endTime

        var lastResumedPkg: String? = null
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = event.packageName
                    if (pkg != null && !TRANSIENT_SYSTEM_PACKAGES.contains(pkg) && !pkg.contains("keyboard")) {
                        lastResumedPkg = pkg
                    }
                }
            }
        }

        if (lastResumedPkg != null) return lastResumedPkg

        // 3. Robust Fallback: queryUsageStats (maxByOrNull lastTimeUsed)
        try {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                endTime - 1000 * 60 * 10,
                endTime
            )
            if (!stats.isNullOrEmpty()) {
                val topStat = stats
                    .asSequence()
                    .filter {
                        val pkg = it.packageName
                        pkg != null &&
                                !TRANSIENT_SYSTEM_PACKAGES.contains(pkg) &&
                                !pkg.contains("keyboard") &&
                                it.lastTimeUsed > 0
                    }
                    .maxByOrNull { it.lastTimeUsed }

                if (topStat != null && (endTime - topStat.lastTimeUsed) < 1000 * 60 * 30) {
                    return topStat.packageName
                }
            }
        } catch (e: Exception) {
            Logger.log("Error querying fallback usage stats: ${e.message}", isError = true)
        }

        return null
    }

    private fun registerSystemReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(systemReceiver, filter)
            }
            isReceiverRegistered = true
            Logger.log("Registered system receiver for Screen Off/On & Bluetooth Connect/Disconnect")
        }
    }

    private fun unregisterSystemReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(systemReceiver)
            } catch (e: Exception) {
                Logger.log("Error unregistering system receiver: ${e.message}", isError = true)
            }
            isReceiverRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun initMediaSession() {
        mediaSession = MediaSession(this, "ReelsMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            // Force the system to route implicit media button broadcasts to OUR receiver
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(this@MediaButtonService, WheelReelsMediaReceiver::class.java)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this@MediaButtonService,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setMediaButtonReceiver(pendingIntent)

            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "WheelReels Control")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Active")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "WheelReels")
                .build()

            setMetadata(metadata)

            setCallback(object : MediaSession.Callback() {
                override fun onSkipToNext() {
                    Logger.log("MediaSession: onSkipToNext() received -> Swiping Up")
                    performSwipeUp()
                }

                override fun onSkipToPrevious() {
                    if (getActivePrevAction() == PrevAction.LIKE) {
                        Logger.log("MediaSession: onSkipToPrevious() received -> Double Tapping (Like)")
                        performDoubleTap()
                    } else {
                        Logger.log("MediaSession: onSkipToPrevious() received -> Swiping Down")
                        performSwipeDown()
                    }
                }

                override fun onFastForward() {
                    Logger.log("MediaSession: onFastForward() received -> Swiping Up")
                    performSwipeUp()
                }

                override fun onRewind() {
                    if (getActivePrevAction() == PrevAction.LIKE) {
                        Logger.log("MediaSession: onRewind() received -> Double Tapping (Like)")
                        performDoubleTap()
                    } else {
                        Logger.log("MediaSession: onRewind() received -> Swiping Down")
                        performSwipeDown()
                    }
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    }

                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        Logger.log("MediaSession MediaButtonEvent KeyCode: ${keyEvent.keyCode}")
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                            KeyEvent.KEYCODE_NAVIGATE_NEXT,
                            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                            KeyEvent.KEYCODE_CHANNEL_UP -> {
                                performSwipeUp()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND,
                            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                if (getActivePrevAction() == PrevAction.LIKE) {
                                    performDoubleTap()
                                } else {
                                    performSwipeDown()
                                }
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
        }
    }

    private fun getActivePrevAction(): PrevAction {
        return prefsRepository.getPrevActionForPackage(currentForegroundPackage)
    }

    private fun isCurrentAppTargeted(): Boolean {
        if (prefsRepository.isGlobalSwipeEnabled) return true
        val pkg = currentForegroundPackage
        if (pkg.isEmpty()) return false
        return prefsRepository.isPackageEnabled(pkg)
    }

    @Volatile
    private var serviceStartTime = System.currentTimeMillis()

    @Volatile
    private var lastActionTime = 0L

    private val actionDebounceMs = 350L

    private fun canPerformAction(): Boolean {
        val now = System.currentTimeMillis()
        if (now - serviceStartTime < 750L) {
            Logger.log("Media key action ignored: within initial 750ms service startup grace period")
            return false
        }
        return synchronized(this) {
            if (now - lastActionTime < actionDebounceMs) {
                Logger.log("Media key action ignored: duplicate event within ${now - lastActionTime}ms debounce window")
                false
            } else {
                lastActionTime = now
                true
            }
        }
    }

    private fun performSwipeUp() {
        if (!isCurrentAppTargeted()) {
            Logger.log("Swipe Up ignored: Current app ($currentForegroundPackage) is not an enabled target app")
            return
        }
        if (!canPerformAction()) return

        if (ShizukuManager.isGranted) {
            val displayMetrics = resources.displayMetrics
            ShizukuManager.swipeUp(displayMetrics.widthPixels, displayMetrics.heightPixels, prefsRepository.swipeDurationMs)
        } else {
            Logger.log("Swipe Up failed: Shizuku permission is not granted!", isError = true)
        }
    }

    private fun performSwipeDown() {
        if (!isCurrentAppTargeted()) {
            Logger.log("Swipe Down ignored: Current app ($currentForegroundPackage) is not an enabled target app")
            return
        }
        if (!canPerformAction()) return

        if (ShizukuManager.isGranted) {
            val displayMetrics = resources.displayMetrics
            ShizukuManager.swipeDown(displayMetrics.widthPixels, displayMetrics.heightPixels, prefsRepository.swipeDurationMs)
        } else {
            Logger.log("Swipe Down failed: Shizuku permission is not granted!", isError = true)
        }
    }

    private fun performDoubleTap() {
        if (!isCurrentAppTargeted()) {
            Logger.log("Double Tap ignored: Current app ($currentForegroundPackage) is not an enabled target app")
            return
        }
        if (!canPerformAction()) return

        if (ShizukuManager.isGranted) {
            val displayMetrics = resources.displayMetrics
            ShizukuManager.doubleTap(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } else {
            Logger.log("Double Tap failed: Shizuku permission is not granted!", isError = true)
        }
    }

    private fun isYouTubeApp(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return packageName == "com.google.android.youtube" ||
                packageName == "app.morphe.android.youtube" ||
                packageName.contains("youtube")
    }

    private fun isBackgroundMusicPlaying(): Boolean {
        val am = audioManager ?: return false
        return am.isMusicActive
    }

    private fun activateMediaSession() {
        if (mediaSession?.isActive == true) return

        val bgMusicPlaying = isBackgroundMusicPlaying()
        if (!bgMusicPlaying) {
            forceStopGhostMediaApps()
            val focusGranted = requestAudioFocus()
            if (focusGranted) {
                startSilentAudio()
            }
        } else {
            stopSilentAudio()
            abandonAudioFocus()
        }

        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
            )
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession?.apply {
            setPlaybackState(state)
            isActive = true
        }

        // We don't need the temporary hijack if we hold true audio focus, but we leave it as a fallback
        hijackMediaButtonRouting()

        val appName = getAppDisplayName(currentForegroundPackage)
        val notifTitle = if (appName.isNotEmpty() && appName != "App") "Active: $appName (Bluetooth Connected)" else "Control Active"
        startForeground(NOTIFICATION_ID, buildNotification(notifTitle))
        Logger.log("Activated MediaSession for Target App ($currentForegroundPackage)")
    }

    private fun forceStopGhostMediaApps() {
        if (!ShizukuManager.isGranted) return
        Thread {
            try {
                val process = ShizukuManager.execShizuku("dumpsys media_session")
                if (process != null) {
                    val reader = process.inputStream.bufferedReader()
                    val packagesToKill = mutableSetOf(
                        "com.google.android.apps.youtube.music", // YouTube Music
                        "com.google.android.youtube",            // YouTube
                        "app.morphe.android.youtube",            // YouTube Morphe
                        "app.morphe.android.youtube.music",      // YouTube Music Morphe
                        "com.spotify.music",                     // Spotify
                        "com.apple.android.music",               // Apple Music
                        "com.amazon.mp3",                        // Amazon Music
                        "com.soundcloud.android",                // SoundCloud
                        "deezer.android.app",                    // Deezer
                        "com.tidal.fi"                           // Tidal
                    )
                    
                    // Remove safe packages from the hardcoded list
                    packagesToKill.remove(currentForegroundPackage)
                    packagesToKill.remove(packageName)

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val match = Regex("""(?:package=|packages=|ownerPackageName=|ComponentInfo\{)([a-zA-Z0-9_.]+)""").find(line!!)
                        if (match != null) {
                            val pkg = match.groupValues[1]
                            val isSystemOrSafe = pkg.startsWith("com.android.") ||
                                    pkg == "android" ||
                                    pkg == currentForegroundPackage ||
                                    pkg == packageName ||
                                    pkg.contains("bluetooth") ||
                                    pkg.contains("telecom") ||
                                    pkg.contains("googlequicksearchbox")
                            
                            // If it's a music/media app, aggressively kill it
                            if (!isSystemOrSafe || pkg.contains("music") || pkg.contains("spotify") || pkg.contains("morphe")) {
                                packagesToKill.add(pkg)
                            }
                        }
                    }
                    process.destroy()

                    packagesToKill.forEach { pkg ->
                        Logger.log("Force-stopping ghost media app: $pkg")
                        ShizukuManager.execShizuku("am force-stop $pkg")
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to force stop ghost media apps: ${e.message}", isError = true)
            }
        }.apply {
            name = "GhostMediaKillerThread"
            start()
        }
    }

    private fun hijackMediaButtonRouting() {
        val am = audioManager ?: return
        try {
            val listener = AudioManager.OnAudioFocusChangeListener { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()

                am.requestAudioFocus(focusRequest)
                mainHandler.postDelayed({
                    am.abandonAudioFocusRequest(focusRequest)
                }, 100L)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                mainHandler.postDelayed({
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(listener)
                }, 100L)
            }
            Logger.log("Hijacked AudioFocus briefly to steal MediaButton routing from YT Music")
        } catch (e: Exception) {
            Logger.log("Failed to hijack MediaButton routing: ${e.message}")
        }
    }

    private fun deactivateMediaSession() {
        if (mediaSession?.isActive == false && silentAudioTrack == null) return

        stopSilentAudio()
        abandonAudioFocus()

        val state = PlaybackState.Builder()
            .setActions(0)
            .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f)
            .build()

        mediaSession?.apply {
            setPlaybackState(state)
            isActive = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Logger.log("Deactivated MediaSession & Released AudioFocus & Removed Live Notification")
    }

    private val onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Logger.log("AudioFocus change: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                stopSilentAudio()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                stopSilentAudio()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        val listener = onAudioFocusChangeListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(listener)
                .build()

            audioFocusRequest = focusRequest
            val res = am.requestAudioFocus(focusRequest)
            Logger.log("Requested Audio Focus (AUDIOFOCUS_GAIN): result=$res")
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = am.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Logger.log("Requested Audio Focus: result=$res")
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun startSilentAudio() {
        if (silentAudioTrack != null) return
        try {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            silentAudioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).apply {
                val silentBuffer = ByteArray(bufferSize)
                write(silentBuffer, 0, silentBuffer.size)
                setLoopPoints(0, silentBuffer.size / 4, -1)
                play()
            }
            Logger.log("Started silent AudioTrack for car Bluetooth focus")
        } catch (e: Exception) {
            Logger.log("Error starting silent audio track: ${e.message}", isError = true)
        }
    }

    private fun stopSilentAudio() {
        try {
            silentAudioTrack?.apply {
                stop()
                release()
            }
            silentAudioTrack = null
            Logger.log("Stopped silent AudioTrack")
        } catch (e: Exception) {
            Logger.log("Error stopping silent audio track: ${e.message}", isError = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            deactivateMediaSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        serviceStartTime = System.currentTimeMillis()
        val appName = getAppDisplayName(currentForegroundPackage)
        val initialText = if (appName.isNotEmpty() && appName != "App") {
            "Active: $appName (Bluetooth Connected)"
        } else {
            "Control Active"
        }
        startForeground(NOTIFICATION_ID, buildNotification(initialText))
        isRunning = true
        startAppMonitorThread()
        return START_STICKY
    }

    @Volatile
    private var logcatMonitorThread: Thread? = null

    @Volatile
    private var isLogcatMonitoring = false

    @Volatile
    private var lastMediaKeyTriggerTime = 0L

    private fun startLogcatMediaKeyMonitor() {
        if (isLogcatMonitoring) return
        isLogcatMonitoring = true

        logcatMonitorThread = Thread {
            Logger.log("Starting Shizuku Logcat MediaKey Monitor...")
            while (isLogcatMonitoring) {
                if (!ShizukuManager.isGranted) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    continue
                }

                try {
                    val process = ShizukuManager.execShizuku("logcat -v threadtime -T 1 -s MediaSessionService:D")
                    if (process != null) {
                        val monitorStartTime = System.currentTimeMillis()
                        val reader = process.inputStream.bufferedReader()
                        while (isLogcatMonitoring) {
                            val line = reader.readLine() ?: break
                            val now = System.currentTimeMillis()
                            if (now - monitorStartTime < 750L) {
                                continue
                            }
                            if (line.contains("dispatchMediaKeyEvent") && line.contains("action=ACTION_DOWN")) {
                                val match = Regex("""keyCode=(KEYCODE_[A-Z0-9_]+)""").find(line)
                                if (match != null) {
                                    val keyName = match.groupValues[1]
                                    if (now - lastMediaKeyTriggerTime > 250L) {
                                        lastMediaKeyTriggerTime = now
                                        mainHandler.post {
                                            handleLogcatMediaKey(keyName)
                                        }
                                    }
                                }
                            }
                        }
                        process.destroy()
                    }
                } catch (e: Throwable) {
                    Logger.log("Logcat MediaKey Monitor error: ${e.message}", isError = true)
                }

                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            Logger.log("Shizuku Logcat MediaKey Monitor stopped")
        }.apply {
            name = "LogcatMediaKeyThread"
            start()
        }
    }

    private fun stopLogcatMediaKeyMonitor() {
        isLogcatMonitoring = false
        logcatMonitorThread?.interrupt()
        logcatMonitorThread = null
    }

    private fun handleLogcatMediaKey(keyName: String) {
        if (!isCurrentAppTargeted()) return
        Logger.log("Logcat MediaKey intercepted: $keyName for target app $currentForegroundPackage")
        when (keyName) {
            "KEYCODE_MEDIA_NEXT",
            "KEYCODE_MEDIA_FAST_FORWARD",
            "KEYCODE_NAVIGATE_NEXT",
            "KEYCODE_MEDIA_STEP_FORWARD",
            "KEYCODE_CHANNEL_UP" -> {
                performSwipeUp()
            }
            "KEYCODE_MEDIA_PREVIOUS",
            "KEYCODE_MEDIA_REWIND",
            "KEYCODE_NAVIGATE_PREVIOUS",
            "KEYCODE_MEDIA_STEP_BACKWARD",
            "KEYCODE_CHANNEL_DOWN" -> {
                if (getActivePrevAction() == PrevAction.LIKE) {
                    performDoubleTap()
                } else {
                    performSwipeDown()
                }
            }
        }
    }

    private var lastNotificationText: String = ""

    private fun updateNotificationText(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogcatMediaKeyMonitor()
        stopAppMonitorThread()
        unregisterSystemReceiver()
        deactivateMediaSession()
        mediaSession?.release()
        mediaSession = null
        isRunning = false
        if (instance == this) {
            instance = null
        }
        Logger.log("MediaButtonService destroyed -> 0 Background Drain")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(subText: String = getString(R.string.service_running_notification_text)): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaButtonService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WheelReels Control")
            .setContentText(subText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WheelReels Control Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth media session active for steering wheel controls"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "reels_control_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.markvoronin.reelsonthego.action.START"
        const val ACTION_STOP = "com.markvoronin.reelsonthego.action.STOP"

        @Volatile
        private var instance: MediaButtonService? = null
        
        fun getInstance(): MediaButtonService? = instance

        var isRunning: Boolean = false
            private set

        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.google.android.gms",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.setupwizard",
            "com.google.android.as"
        )

        fun startService(context: Context) {
            val intent = Intent(context, MediaButtonService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaButtonService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
