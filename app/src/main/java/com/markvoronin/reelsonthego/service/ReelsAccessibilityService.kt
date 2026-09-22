package com.markvoronin.reelsonthego.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.markvoronin.reelsonthego.data.PreferencesRepository
import com.markvoronin.reelsonthego.data.isBluetoothAudioConnected
import com.markvoronin.reelsonthego.util.Logger
import com.markvoronin.reelsonthego.util.ShizukuManager
import java.lang.ref.WeakReference

class ReelsAccessibilityService : AccessibilityService() {

    private lateinit var prefsRepository: PreferencesRepository
    private var currentPackageName: String = ""
    private var isBluetoothReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Logger.log("Bluetooth device connected -> Re-evaluating foreground app ($currentPackageName)")
                    if (currentPackageName.isNotEmpty()) {
                        handleAppLifecycleChange(currentPackageName)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (prefsRepository.isRequireBluetoothEnabled && !isBluetoothAudioConnected(this@ReelsAccessibilityService)) {
                        Logger.log("Bluetooth device disconnected -> Stopping MediaButtonService")
                        if (MediaButtonService.isRunning) {
                            MediaButtonService.stopService(this@ReelsAccessibilityService)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefsRepository = PreferencesRepository(this)
        instance = WeakReference(this)
        registerBluetoothReceiver()
        Logger.log("ReelsAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        registerBluetoothReceiver()
        Logger.log("ReelsAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        if (instance?.get() == this) {
            instance = null
        }
        Logger.log("ReelsAccessibilityService destroyed")
    }

    private fun registerBluetoothReceiver() {
        if (!isBluetoothReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
            isBluetoothReceiverRegistered = true
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (isBluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Logger.log("Error unregistering Bluetooth receiver in AccessibilityService: ${e.message}", isError = true)
            }
            isBluetoothReceiverRegistered = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrEmpty() && !isTransientSystemPackage(pkg)) {
                    if (currentPackageName != pkg) {
                        currentPackageName = pkg
                        Logger.log("Foreground App Changed via Accessibility: $currentPackageName")
                        handleAppLifecycleChange(currentPackageName)
                    }
                }
            }
        }
    }

    private fun handleAppLifecycleChange(packageName: String) {
        if (!prefsRepository.isServiceEnabled) return

        val isTargetApp = prefsRepository.isPackageEnabled(packageName) || prefsRepository.isGlobalSwipeEnabled

        if (isTargetApp) {
            if (prefsRepository.isRequireBluetoothEnabled && !isBluetoothAudioConnected(this)) {
                Logger.log("Target app opened ($packageName), but Bluetooth audio is NOT connected -> Skipping MediaButtonService start")
                return
            }

            if (!MediaButtonService.isRunning) {
                Logger.log("Target app opened ($packageName) -> Starting MediaButtonService")
                MediaButtonService.startService(this)
            }
        }
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        val systemPackages = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.google.android.gms",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.setupwizard"
        )
        return systemPackages.contains(pkg) || pkg.contains("keyboard") || pkg.contains("inputmethod")
    }

    override fun onInterrupt() {
        Logger.log("Accessibility Service interrupted", isError = true)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        if (!prefsRepository.isServiceEnabled) {
            return super.onKeyEvent(event)
        }

        val isTargetAppActive = isAppTargeted(force = false)

        if (!isTargetAppActive) {
            return super.onKeyEvent(event)
        }

        val isNextKey = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_CHANNEL_UP -> true
            else -> false
        }

        val isPrevKey = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> true
            else -> false
        }

        if (!isNextKey && !isPrevKey) {
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isNextKey) {
                Logger.log("Accessibility onKeyEvent: Intercepted NEXT KeyCode ${event.keyCode} -> Swiping Up")
                swipeUp(force = true)
            } else {
                Logger.log("Accessibility onKeyEvent: Intercepted PREVIOUS KeyCode ${event.keyCode} -> Swiping Down")
                swipeDown(force = true)
            }
        }

        // CONSUME BOTH DOWN AND UP EVENTS TO TOTALLY BLOCK OS FALLBACK!
        return true
    }

    private fun isAppTargeted(force: Boolean): Boolean {
        if (prefsRepository.isGlobalSwipeEnabled) return true

        val rootPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

        if (!rootPkg.isNullOrEmpty() && !isTransientSystemPackage(rootPkg)) {
            if (currentPackageName != rootPkg) {
                currentPackageName = rootPkg
                Logger.log("Active Window Package: $currentPackageName")
                handleAppLifecycleChange(currentPackageName)
            }
        }

        if (force && currentPackageName == packageName) return true
        return prefsRepository.isPackageEnabled(currentPackageName)
    }

    fun swipeUp(force: Boolean = false) {
        if (!isAppTargeted(force)) {
            Logger.log("swipeUp ignored: $currentPackageName is not an enabled target app")
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val duration = prefsRepository.swipeDurationMs

        if (ShizukuManager.isGranted) {
            ShizukuManager.swipeUp(width, height, duration)
            return
        }

        val startX = width / 2f
        val startY = height * 0.75f
        val endX = width / 2f
        val endY = height * 0.25f

        dispatchSwipeGesture(startX, startY, endX, endY, duration)
    }

    fun swipeDown(force: Boolean = false) {
        if (!isAppTargeted(force)) {
            Logger.log("swipeDown ignored: $currentPackageName is not an enabled target app")
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val duration = prefsRepository.swipeDurationMs

        if (ShizukuManager.isGranted) {
            ShizukuManager.swipeDown(width, height, duration)
            return
        }

        val startX = width / 2f
        val startY = height * 0.25f
        val endX = width / 2f
        val endY = height * 0.75f

        dispatchSwipeGesture(startX, startY, endX, endY, duration)
    }

    fun doubleTap(force: Boolean = false) {
        if (!isAppTargeted(force)) {
            Logger.log("doubleTap ignored: $currentPackageName is not an enabled target app")
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        if (ShizukuManager.isGranted) {
            ShizukuManager.doubleTap(width, height)
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f

        val tapPath = Path().apply {
            moveTo(centerX, centerY)
        }

        val tap1 = GestureDescription.StrokeDescription(tapPath, 0L, 50L)
        val tap2 = GestureDescription.StrokeDescription(tapPath, 100L, 50L)

        val gesture = GestureDescription.Builder()
            .addStroke(tap1)
            .addStroke(tap2)
            .build()

        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Logger.log("Accessibility Double Tap (Like) completed successfully")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Logger.log("Accessibility Double Tap cancelled", isError = true)
            }
        }, null)

        Logger.log("Dispatched Accessibility Double Tap: success=$success")
    }

    private fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Logger.log("Accessibility Swipe completed successfully (${durationMs}ms)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Logger.log("Accessibility Swipe cancelled", isError = true)
            }
        }, null)

        Logger.log("Dispatched Accessibility Swipe (${durationMs}ms): success=$success")
    }

    companion object {
        @Volatile
        private var instance: WeakReference<ReelsAccessibilityService>? = null

        val isServiceRunning: Boolean
            get() = instance?.get() != null

        fun getInstance(): ReelsAccessibilityService? {
            return instance?.get()
        }
    }
}
