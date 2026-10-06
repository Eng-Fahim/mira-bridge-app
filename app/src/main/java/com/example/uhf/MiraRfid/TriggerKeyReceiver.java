package com.example.uhf.MiraRfid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ⚠️ DEPRECATED — لا تستخدم هذا الكلاس ⚠️
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  هذا الكلاس كان يستقبل زر الزناد الفيزيائي عبر BroadcastReceiver.
 *
 *  🚫 تم تعطيله بالكامل في الإصدار 1.5.1 للأسباب التالية:
 *
 *  1. ❌ كان يتعارض مع آليتين أخريين لالتقاط نفس الزر:
 *     - RFIDManager.registerTriggerKeyReceiver()
 *     - BaseTabFragmentActivity.onKeyDown/onKeyUp
 *
 *  2. ❌ كان يتسبب في تنفيذ startScan/stopScan مرتين لكل ضغطة زر.
 *
 *  3. ❌ كان يستخدم android:exported="true" — وهي ثغرة أمنية.
 *
 *  ✅ آلية التقاط الزر المعتمدة الآن (الوحيدة):
 *     → BaseTabFragmentActivity.onKeyDown()  (بدء المسح)
 *     → BaseTabFragmentActivity.onKeyUp()    (إيقاف المسح)
 *
 *  ⚠️ لا تُسجّل هذا الـ Receiver في AndroidManifest.xml ولا ديناميكياً.
 *  ⚠️ لا تستدعِ registerTriggerKeyReceiver() من RFIDManager.
 *
 *  الإبقاء على هذا الملف هو فقط:
 *  - لمنع كسر أي import قديم.
 *  - لتوثيق سبب إزالته.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Deprecated
public class TriggerKeyReceiver extends BroadcastReceiver {

    private static final String TAG = "TriggerKeyReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // ═══════════════════════════════════════════════════════════════════
        // ✅ STUB آمن — لا يفعل شيئاً
        // ═══════════════════════════════════════════════════════════════════
        //
        // إذا وصل التنفيذ هنا، فهذا يعني أن أحداً سجّل هذا الـ Receiver
        // بشكل خاطئ (في Manifest أو ديناميكياً).
        //
        // الحل: احذف التسجيل من المصدر.
        // ═══════════════════════════════════════════════════════════════════

        Log.w(TAG,
            "⚠️ TriggerKeyReceiver invoked! " +
            "This receiver is DEPRECATED and should NOT be registered. " +
            "Remove any registration in AndroidManifest.xml or via " +
            "registerReceiver(). Scanning is now handled by " +
            "BaseTabFragmentActivity.onKeyDown/onKeyUp only.");

        // ❌ لا نستدعي RFIDManager.startScan() أو stopScan()
        //    لأن هذا يُنشئ تعارضاً مع onKeyDown/onKeyUp.
    }
}
