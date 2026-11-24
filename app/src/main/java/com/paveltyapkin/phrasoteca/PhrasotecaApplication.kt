
package com.paveltyapkin.phrasoteca

import android.app.Application
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class PhrasotecaApplication : Application() {

    companion object {
        private const val POSTHOG_HOST =
            "https://us.i.posthog.com"
    }

    override fun onCreate() {
        super.onCreate()

        // Disable analytics in debug builds
        if (BuildConfig.DEBUG) {
            return
        }

        val token = BuildConfig.POSTHOG_PROJECT_TOKEN

        // Disable analytics if no token is provided
        if (token.isBlank()) {
            return
        }

        val config = PostHogAndroidConfig(
            projectToken = token,
            host = POSTHOG_HOST
        ).apply {
            captureApplicationLifecycleEvents = true
            captureScreenViews = false
            captureDeepLinks = false
            sessionReplay = false
            capturePushNotificationSubscriptions = false
            capturePushNotificationOpened = false
        }

        PostHogAndroid.setup(this, config)
    }
}
