package com.markvoronin.reelsonthego.util

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.Executors

object ShizukuManager {

    private const val REQUEST_CODE = 2001
    private var isListenersRegistered = false
    private val gestureExecutor = Executors.newSingleThreadExecutor()
    private val capabilityExecutor = Executors.newSingleThreadExecutor()

    private val gestureQueueLock = Any()

    @Volatile
    private var pendingGestureRunnable: Runnable? = null

    @Volatile
    private var isGestureExecuting: Boolean = false

    @Volatile
    private var cachedIsAvailable: Boolean = false

    @Volatile
    private var cachedIsGranted: Boolean = false

    @Volatile
    private var cachedIsRootAvailable: Boolean = false

    private val _isAvailable = MutableStateFlow(value = false)
    val isAvailableFlow: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isGranted = MutableStateFlow(value = false)
    val isGrantedFlow: StateFlow<Boolean> = _isGranted.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(value = false)

    init {
        init()
    }

    fun init() {
        if (!isListenersRegistered) {
            try {
                Shizuku.addBinderReceivedListener {
                    Logger.log("Shizuku/Shevery Binder received & connected!")
                    refreshCapabilitiesAsync()
                }
                Shizuku.addBinderDeadListener {
                    Logger.log("Shizuku/Shevery Binder died", isError = true)
                    refreshCapabilitiesAsync()
                }
                Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode == REQUEST_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        Logger.log("Shizuku permission result: granted=$granted")
                        refreshCapabilitiesAsync()
                    }
                }
                isListenersRegistered = true
                Logger.log("ShizukuManager initialized")
            } catch (e: Throwable) {
                Logger.log("Error initializing Shizuku listeners: ${e.message}", isError = true)
            }
        }
        refreshCapabilitiesAsync()
    }

    val isAvailable: Boolean
        get() = _isAvailable.value

    val isGranted: Boolean
        get() = _isGranted.value

    val isRootAvailable: Boolean
        get() = _isRootAvailable.value

    fun refreshCapabilities(): Boolean {
        val available = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        val granted = try {
            if (available) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }

        val rootAvailable = probeRootAvailable()

        cachedIsAvailable = available
        cachedIsGranted = granted
        cachedIsRootAvailable = rootAvailable

        _isAvailable.value = available
        _isGranted.value = granted
        _isRootAvailable.value = rootAvailable

        Logger.log("Shizuku capabilities updated: available=$available, granted=$granted, root=$rootAvailable")
        return granted
    }

    fun refreshCapabilitiesAsync() {
        capabilityExecutor.execute {
            refreshCapabilities()
        }
    }

    private fun probeRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.drainAndWaitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!isAvailable) {
                refreshCapabilities()
            }
            if (isAvailable && !isGranted) {
                Shizuku.requestPermission(REQUEST_CODE)
                Logger.log("Requested Shizuku permission")
            } else if (!isAvailable) {
                Logger.log("Shizuku binder is not available. Ensure Shizuku/Shevery is running.", isError = true)
            }
        } catch (e: Exception) {
            Logger.log("Error requesting Shizuku permission: ${e.message}", isError = true)
        }
    }

    @Volatile
    private var lastGestureEnqueueTime: Long = 0L

    private const val GESTURE_DEBOUNCE_MS = 300L

    fun swipeUp(width: Int, height: Int, durationMs: Long = 180L) {
        val startX = width / 2
        val startY = (height * 0.75f).toInt()
        val endX = width / 2
        val endY = (height * 0.40f).toInt()
        executeSwipe(startX, startY, endX, endY, durationMs)
    }

    fun swipeDown(width: Int, height: Int, durationMs: Long = 180L) {
        val startX = width / 2
        val startY = (height * 0.40f).toInt()
        val endX = width / 2
        val endY = (height * 0.75f).toInt()
        executeSwipe(startX, startY, endX, endY, durationMs)
    }

    fun doubleTap(width: Int, height: Int) {
        val x = width / 2
        val y = height / 2
        val command = "input tap $x $y && sleep 0.08 && input tap $x $y"

        enqueueGesture {
            if (isGranted) {
                try {
                    Logger.log("Executing Double Tap via Shizuku: $command")
                    val process = execShizuku(command)
                    if (process != null) {
                        val exitCode = process.drainAndWaitFor()
                        Logger.log("Shizuku doubleTap completed with exit code: $exitCode")
                    } else if (isRootAvailable) {
                        execRootCmd(command)
                    }
                } catch (e: Exception) {
                    Logger.log("Shizuku doubleTap error: ${e.message}", isError = true)
                    if (isRootAvailable) {
                        execRootCmd(command)
                    }
                }
            } else if (isRootAvailable) {
                execRootCmd(command)
            } else {
                Logger.log("Shizuku / Root doubleTap skipped: Permission not granted", isError = true)
            }
        }
    }

    private fun executeSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
        val command = "input swipe $startX $startY $endX $endY $durationMs"

        enqueueGesture {
            if (isGranted) {
                try {
                    Logger.log("Executing via Shizuku: $command")
                    val process = execShizuku(command)
                    if (process != null) {
                        val exitCode = process.drainAndWaitFor()
                        Logger.log("Shizuku swipe completed with exit code: $exitCode")
                    } else if (isRootAvailable) {
                        executeRootSwipe(command)
                    }
                } catch (e: Exception) {
                    Logger.log("Shizuku swipe error, trying Root: ${e.message}", isError = true)
                    if (isRootAvailable) {
                        executeRootSwipe(command)
                    }
                }
            } else if (isRootAvailable) {
                executeRootSwipe(command)
            } else {
                Logger.log("Shizuku / Root swipe skipped: Neither Shizuku nor Root is granted", isError = true)
            }
        }
    }

    private fun enqueueGesture(runnable: Runnable) {
        val now = System.currentTimeMillis()
        synchronized(gestureQueueLock) {
            if (now - lastGestureEnqueueTime < GESTURE_DEBOUNCE_MS) {
                Logger.log("ShizukuManager: Dropping gesture request within ${now - lastGestureEnqueueTime}ms debounce window")
                return
            }
            lastGestureEnqueueTime = now

            if ((isGestureExecuting) && (pendingGestureRunnable != null)) {
                Logger.log("Dropping stale pending gesture in favor of newest gesture")
            }
            pendingGestureRunnable = runnable
            if (!isGestureExecuting) {
                isGestureExecuting = true
                scheduleNextGesture()
            }
        }
    }

    private fun scheduleNextGesture() {
        gestureExecutor.execute {
            val nextRunnable: Runnable?
            synchronized(gestureQueueLock) {
                nextRunnable = pendingGestureRunnable
                pendingGestureRunnable = null
            }

            if (nextRunnable != null) {
                try {
                    nextRunnable.run()
                } catch (e: Throwable) {
                    Logger.log("Error executing gesture: ${e.message}", isError = true)
                } finally {
                    synchronized(gestureQueueLock) {
                        if (pendingGestureRunnable != null) {
                            scheduleNextGesture()
                        } else {
                            isGestureExecuting = false
                        }
                    }
                }
            } else {
                synchronized(gestureQueueLock) {
                    isGestureExecuting = false
                }
            }
        }
    }

    private fun executeRootSwipe(command: String) {
        try {
            Logger.log("Executing via Root (su): $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.drainAndWaitFor()
            Logger.log("Root swipe completed with exit code: $exitCode")
        } catch (e: Exception) {
            Logger.log("Root swipe failed: ${e.message}", isError = true)
        }
    }

    fun grantMediaKeyPermissions(context: Context) {
        val pkg = context.packageName
        val cmd1 = "pm grant $pkg android.permission.SET_MEDIA_KEY_LISTENER"
        val cmd2 = "appops set $pkg SET_MEDIA_KEY_LISTENER allow"
        val cmd3 = "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        val cmd4 = "appops set $pkg GET_USAGE_STATS allow"
        val cmd5 = "pm grant $pkg android.permission.PACKAGE_USAGE_STATS"

        capabilityExecutor.execute {
            if (isGranted) {
                try {
                    Logger.log("Executing Shizuku grant commands...")
                    execCmd(cmd1)
                    execCmd(cmd2)
                    execCmd(cmd3)
                    execCmd(cmd4)
                    execCmd(cmd5)
                    Logger.log("System MediaKey & UsageStats permissions granted via Shizuku!")
                } catch (e: Exception) {
                    Logger.log("Error granting via Shizuku, trying Root: ${e.message}", isError = true)
                    if (isRootAvailable) {
                        execRootCmd(cmd1)
                        execRootCmd(cmd2)
                        execRootCmd(cmd3)
                        execRootCmd(cmd4)
                        execRootCmd(cmd5)
                    }
                }
            } else if (isRootAvailable) {
                Logger.log("Executing Root grant commands...")
                execRootCmd(cmd1)
                execRootCmd(cmd2)
                execRootCmd(cmd3)
                execRootCmd(cmd4)
                execRootCmd(cmd5)
                Logger.log("System MediaKey & UsageStats permissions granted via Root!")
            } else {
                Logger.log("Cannot grant permissions: Neither Shizuku nor Root is granted", isError = true)
            }
        }
    }

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            Logger.log("Error resolving Shizuku.newProcess method: ${e.message}", isError = true)
            null
        }
    }

    fun execShizuku(command: String): Process? {
        val method = newProcessMethod ?: return null
        return try {
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (e: Exception) {
            Logger.log("Shizuku exec error: ${e.message}", isError = true)
            null
        }
    }

    private fun execCmd(command: String) {
        val process = execShizuku(command)
        process?.drainAndWaitFor()
    }

    private fun execRootCmd(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.drainAndWaitFor()
        } catch (e: Exception) {
            Logger.log("Root exec error: ${e.message}", isError = true)
        }
    }

    private fun Process.drainAndWaitFor(): Int {
        return try {
            val stdoutThread = Thread {
                try { inputStream.use { it.readBytes() } } catch (_: Throwable) {}
            }
            val stderrThread = Thread {
                try { errorStream.use { it.readBytes() } } catch (_: Throwable) {}
            }
            stdoutThread.start()
            stderrThread.start()

            val exitCode = waitFor()
            stdoutThread.join(500)
            stderrThread.join(500)
            exitCode
        } catch (_: Exception) {
            -1
        }
    }

    private val TRANSIENT_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.google.android.gms",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.setupwizard",
        "com.google.android.as",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    fun getTopPackageName(): String? {
        if (!cachedIsGranted) return null
        return try {
            val process = execShizuku("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            if (process != null) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.drainAndWaitFor()
                val match = Regex("""([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.$]+""").find(output)
                val pkg = match?.groupValues?.get(1)
                if ((pkg != null) && (!TRANSIENT_PACKAGES.contains(pkg)) && (!pkg.contains("keyboard"))) {
                    pkg
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
