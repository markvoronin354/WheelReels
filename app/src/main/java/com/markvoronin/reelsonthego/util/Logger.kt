package com.markvoronin.reelsonthego.util

import android.util.Log
import com.markvoronin.reelsonthego.BuildConfig

object Logger {

    private const val TAG = "ReelsLogger"

    fun log(message: String, isError: Boolean = false) {
        if (!BuildConfig.DEBUG && !isError) return

        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
    }
}
