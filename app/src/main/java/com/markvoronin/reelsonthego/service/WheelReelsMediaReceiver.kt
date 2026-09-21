package com.markvoronin.reelsonthego.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.markvoronin.reelsonthego.util.Logger

class WheelReelsMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }
        
        if (keyEvent != null) {
            val keyCode = keyEvent.keyCode
            val isMediaKey = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false
            }

            if (isMediaKey) {
                if (MediaButtonService.isRunning) {
                    val service = MediaButtonService.getInstance()
                    if (service != null && service.isTargetAppActive()) {
                        Logger.log("WheelReelsMediaReceiver: Intercepted Media Button (keyCode=$keyCode, action=${keyEvent.action}) -> ABORTING BROADCAST to ghost apps!")
                        // VITAL: This kills the broadcast so YouTube Music/Spotify NEVER receives it!
                        abortBroadcast()

                        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                            service.handleInterceptedMediaKey(keyCode)
                        }
                    }
                }
            }
        }
    }
}
