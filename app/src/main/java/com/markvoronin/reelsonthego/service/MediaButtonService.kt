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
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.markvoronin.reelsonthego.MainActivity
import com.markvoronin.reelsonthego.R
import com.markvoronin.reelsonthego.data.PreferencesRepository
import com.markvoronin.reelsonthego.data.PrevAction
import com.markvoronin.reelsonthego.util.Logger
import com.markvoronin.reelsonthego.util.ShizukuManager

class MediaButtonService : Service() {

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var silentAudioTrack: AudioTrack? = null
    private var isReceiverRegistered = false
    private lateinit var prefsRepository: PreferencesRepository

    private var currentForegroundPackage: String = ""
    private val appMonitorHandler = Handler(Looper.getMainLooper())
    private val appMonitorRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            appMonitorHandler.postDelayed(this, 1000L)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Logger.log("Bluetooth device disconnected -> Stopping MediaButtonService")
                    stopSelf()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Logger.log("Screen turned OFF -> Pausing polling & deactivating MediaSession for Deep Sleep")
                    appMonitorHandler.removeCallbacks(appMonitorRunnable)
                    deactivateMediaSession()
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    Logger.log("Screen turned ON -> Resuming background monitoring")
                    appMonitorHandler.removeCallbacks(appMonitorRunnable)
                    appMonitorHandler.post(appMonitorRunnable)
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

        // Start foreground app monitoring
        appMonitorHandler.post(appMonitorRunnable)
    }

    private fun checkForegroundApp() {
        if (!prefsRepository.isServiceEnabled) return

        val foregroundPkg = getForegroundPackageName() ?: currentForegroundPackage
        if (foregroundPkg.isEmpty()) return

        currentForegroundPackage = foregroundPkg
        val isTargetApp = prefsRepository.isGlobalSwipeEnabled || prefsRepository.isPackageEnabled(foregroundPkg)

        if (isTargetApp) {
            if (mediaSession?.isActive != true) {
                Logger.log("Foreground Target App Detected ($foregroundPkg) -> Activating MediaSession & AudioFocus")
                activateMediaSession()
            }
        } else {
            if (mediaSession?.isActive == true) {
                Logger.log("Non-Target App in Foreground ($foregroundPkg) -> Deactivating MediaSession & AudioFocus")
                deactivateMediaSession()
            }
        }
    }

    private fun getForegroundPackageName(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60000L
        val events = usm.queryEvents(startTime, endTime) ?: return null

        var lastResumedPkg: String? = null
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
        return lastResumedPkg
    }

    private fun registerSystemReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
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
            Logger.log("Registered system receiver for Screen Off/On & Bluetooth Disconnect")
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

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "ReelsMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

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
        val pkg = getForegroundPackageName() ?: currentForegroundPackage
        if (pkg.isEmpty()) return false
        return prefsRepository.isPackageEnabled(pkg)
    }

    private fun performSwipeUp() {
        if (!isCurrentAppTargeted()) {
            Logger.log("Swipe Up ignored: Current app ($currentForegroundPackage) is not an enabled target app")
            return
        }
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
        if (ShizukuManager.isGranted) {
            val displayMetrics = resources.displayMetrics
            ShizukuManager.doubleTap(displayMetrics.widthPixels, displayMetrics.heightPixels)
        } else {
            Logger.log("Double Tap failed: Shizuku permission is not granted!", isError = true)
        }
    }

    private fun activateMediaSession() {
        if (mediaSession?.isActive == true) return

        requestAudioFocus()
        startSilentAudio()

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
        Logger.log("Activated MediaSession & AudioFocus for Target App")
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
        Logger.log("Deactivated MediaSession & Released AudioFocus")
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Logger.log("AudioFocus change: $focusChange")
                }
                .build()

            audioFocusRequest = focusRequest
            val res = am.requestAudioFocus(focusRequest)
            Logger.log("Requested Audio Focus (AUDIOFOCUS_GAIN): result=$res")
        } else {
            @Suppress("DEPRECATION")
            val res = am.requestAudioFocus(
                { focus -> Logger.log("AudioFocus change: $focus") },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Logger.log("Requested Audio Focus: result=$res")
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

        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        checkForegroundApp()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        appMonitorHandler.removeCallbacks(appMonitorRunnable)
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

    private fun buildNotification(): Notification {
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
            .setContentTitle(getString(R.string.service_running_notification_title))
            .setContentText(getString(R.string.service_running_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
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
