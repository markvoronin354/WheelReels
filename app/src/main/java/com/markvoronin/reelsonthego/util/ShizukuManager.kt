package com.markvoronin.reelsonthego.util

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object ShizukuManager {

    private const val REQUEST_CODE = 2001
    private var isListenersRegistered = false
    private val gestureExecutor = Executors.newSingleThreadExecutor()
    private val capabilityExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var cachedIsAvailable: Boolean = false

    @Volatile
    private var cachedIsGranted: Boolean = false

    @Volatile
    private var cachedIsRootAvailable: Boolean = false

    private val _isAvailable = MutableStateFlow(false)
    val isAvailableFlow: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isGranted = MutableStateFlow(false)
    val isGrantedFlow: StateFlow<Boolean> = _isGranted.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailableFlow: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

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
            val exitCode = process.waitFor()
            exitCode == 0
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

    fun swipeUp(width: Int, height: Int, durationMs: Long = 120L) {
        val startX = width / 2
        val startY = (height * 0.75f).toInt()
        val endX = width / 2
        val endY = (height * 0.25f).toInt()
        executeSwipe(startX, startY, endX, endY, durationMs)
    }

    fun swipeDown(width: Int, height: Int, durationMs: Long = 120L) {
        val startX = width / 2
        val startY = (height * 0.25f).toInt()
        val endX = width / 2
        val endY = (height * 0.75f).toInt()
        executeSwipe(startX, startY, endX, endY, durationMs)
    }

    fun doubleTap(width: Int, height: Int) {
        val x = width / 2
        val y = height / 2
        val command = "input tap $x $y && sleep 0.08 && input tap $x $y"

        gestureExecutor.execute {
            if (isGranted) {
                try {
                    Logger.log("Executing Double Tap via Shizuku: $command")
                    val process = execShizuku(command)
                    if (process != null) {
                        val exitCode = process.waitFor()
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

        gestureExecutor.execute {
            if (isGranted) {
                try {
                    Logger.log("Executing via Shizuku: $command")
                    val process = execShizuku(command)
                    if (process != null) {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Logger.log("Shizuku output: $line")
                        }
                        val exitCode = process.waitFor()
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

    private fun executeRootSwipe(command: String) {
        try {
            Logger.log("Executing via Root (su): $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
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

        gestureExecutor.execute {
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

    private fun execShizuku(command: String): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (e: Exception) {
            Logger.log("Shizuku exec error: ${e.message}", isError = true)
            null
        }
    }

    private fun execCmd(command: String) {
        val process = execShizuku(command)
        process?.waitFor()
    }

    private fun execRootCmd(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            Logger.log("Root exec error: ${e.message}", isError = true)
        }
    }
}
