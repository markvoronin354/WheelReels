package com.markvoronin.reelsonthego.util

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

object ShizukuManager {

    private const val REQUEST_CODE = 2001
    private var isListenersRegistered = false
    private val gestureExecutor = Executors.newSingleThreadExecutor()

    fun init() {
        if (isListenersRegistered) return
        try {
            Shizuku.addBinderReceivedListener {
                Logger.log("Shizuku/Shevery Binder received & connected!")
            }
            Shizuku.addBinderDeadListener {
                Logger.log("Shizuku/Shevery Binder died", isError = true)
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    Logger.log("Shizuku permission result: granted=$granted")
                }
            }
            isListenersRegistered = true
            Logger.log("ShizukuManager initialized (Binder status: ${if (isAvailable) "Available" else "Unavailable"})")
        } catch (e: Throwable) {
            Logger.log("Error initializing Shizuku listeners: ${e.message}", isError = true)
        }
    }

    val isAvailable: Boolean
        get() {
            return try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }
        }

    val isGranted: Boolean
        get() {
            return try {
                if (!isAvailable) false
                else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }

    val isRootAvailable: Boolean
        get() {
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
                    } else {
                        execRootCmd(command)
                    }
                } catch (e: Exception) {
                    Logger.log("Shizuku doubleTap error: ${e.message}", isError = true)
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
                    } else {
                        executeRootSwipe(command)
                    }
                } catch (e: Exception) {
                    Logger.log("Shizuku swipe error, trying Root: ${e.message}", isError = true)
                    executeRootSwipe(command)
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

        gestureExecutor.execute {
            if (isGranted) {
                try {
                    Logger.log("Executing Shizuku grant commands...")
                    execCmd(cmd1)
                    execCmd(cmd2)
                    execCmd(cmd3)
                    Logger.log("System MediaKey permissions granted via Shizuku!")
                } catch (e: Exception) {
                    Logger.log("Error granting via Shizuku, trying Root: ${e.message}", isError = true)
                    execRootCmd(cmd1)
                    execRootCmd(cmd2)
                    execRootCmd(cmd3)
                }
            } else if (isRootAvailable) {
                Logger.log("Executing Root grant commands...")
                execRootCmd(cmd1)
                execRootCmd(cmd2)
                execRootCmd(cmd3)
                Logger.log("System MediaKey permissions granted via Root!")
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
