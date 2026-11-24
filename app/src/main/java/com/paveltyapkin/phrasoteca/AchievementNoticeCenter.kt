package com.paveltyapkin.phrasoteca

import android.os.Handler
import android.os.Looper

/** In-process notification signal. Only HomeActivity registers as a visible host.
 *  Unseen unlocks are persisted in Room, so background activities never display popups.
 */
object AchievementNoticeCenter {
    private val handler = Handler(Looper.getMainLooper())
    private var homeListener: (() -> Unit)? = null

    fun setHomeListener(listener: (() -> Unit)?) {
        homeListener = listener
    }

    fun onUnlocked() {
        handler.post { homeListener?.invoke() }
    }
}
