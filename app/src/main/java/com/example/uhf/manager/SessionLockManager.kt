package com.example.uhf.security

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.uhf.tools.SessionManager

/**
 * مدير شاشة قفل الجلسة المركزي — متوافق مع Java و Kotlin
 */
object SessionLockManager {

    private const val TAG = "SessionLockManager"
    private const val DIALOG_TAG = "MIRA_SESSION_LOCK_DIALOG"

    interface LockCallback {
        fun onSessionUnlocked()
        fun onLogoutRequested()
    }

    @JvmStatic
    fun show(
        activity: FragmentActivity,
        sessionManager: SessionManager,
        isArabic: Boolean,
        callback: LockCallback
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val fm = activity.supportFragmentManager
        if (fm.isStateSaved) {
            Log.w(TAG, "FragmentManager state already saved; skipping lock dialog")
            return
        }

        // منع فتح أكثر من شاشة قفل في نفس الوقت
        val existing = fm.findFragmentByTag(DIALOG_TAG) as? SessionLockDialogFragment
        if (existing != null && existing.isAdded) {
            existing.attachDependencies(sessionManager, callback)
            return
        }

        val dialog = SessionLockDialogFragment.newInstance(isArabic).apply {
            attachDependencies(sessionManager, callback)
        }
        dialog.show(fm, DIALOG_TAG)
    }

    @JvmStatic
    fun dismiss(activity: FragmentActivity) {
        try {
            val existing = activity.supportFragmentManager
                .findFragmentByTag(DIALOG_TAG) as? SessionLockDialogFragment
            if (existing != null && existing.isAdded) {
                existing.dismissAllowingStateLoss()
            }
        } catch (e: Exception) {
            Log.w(TAG, "dismiss error: ${e.message}")
        }
    }

    @JvmStatic
    fun isShowing(activity: FragmentActivity): Boolean {
        val existing = activity.supportFragmentManager
            .findFragmentByTag(DIALOG_TAG) as? SessionLockDialogFragment
        return existing != null && existing.dialog?.isShowing == true
    }
}
