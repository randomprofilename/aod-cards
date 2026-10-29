package com.aodcards

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** A widget tap requests normal system authentication, then leaves the previous screen in place. */
class UnlockActivity : Activity() {
    private var requested = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        // Stay behind Keyguard: showing even a transparent window over it changes
        // the system's normal unlock transition into an app-occlusion transition.
        // Request here because a non-showWhenLocked activity may not resume yet.
        requestUnlock()
    }

    override fun onResume() {
        super.onResume()
        requestUnlock()
    }

    private fun requestUnlock() {
        if (requested || completed) return
        requested = true
        // A host may still hold an older click action after the setting is disabled.
        if (!SettingsRepository(this).tapToUnlockEnabled) {
            close()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) {
            close()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = close()
            override fun onDismissCancelled() = close()
            override fun onDismissError() = close()
        })
    }

    private fun close() {
        if (completed || isFinishing || isDestroyed) return
        completed = true
        finishAndRemoveTask()
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, UnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            // Only this explicit, immutable capability is handed to the widget host.
            // The app can be in the background when LockStar handles the user's tap.
            val options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
            return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
        }
    }
}
