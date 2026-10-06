package com.example.uhf.fragment;

import android.view.KeyEvent;

import androidx.fragment.app.Fragment;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  ✅ KeyDwonFragment — الواجهة الأساسية لاستقبال أحداث زر الزناد
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  ملاحظة هامة حول التهجئة:
 *  → الاسم الأصلي في الكود القديم كان "myOnKeyDwon" (خطأ إملائي في "Down")
 *  → حافظنا على نفس التهجئة للتوافق مع الـ Fragments القديمة
 *
 *  الدوال المتوفرة:
 *  - myOnKeyDwon()                [void — للتوارث من الكود القديم]
 *  - myOnKeyDwon(int, KeyEvent)   [boolean — الجديدة من BaseTabFragmentActivity]
 *  - myOnKeyUp(int, KeyEvent)     [boolean — تُستدعى عند إفلات الزر]
 *
 *  السلوك الافتراضي:
 *  - myOnKeyDwon: لا تفعل شيئاً (يجب على الـ Fragment الابن تجاوزها)
 *  - myOnKeyUp:   لا تفعل شيئاً (الإيقاف يتم عبر RFIDManager مباشرة)
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class KeyDwonFragment extends Fragment {

    /**
     * النسخة القديمة — void
     * متوافقة مع الـ Fragments القديمة التي تعتمد عليها
     */
    public void myOnKeyDwon() {
        // لا شيء افتراضياً — تجاوزها في الـ Fragment الابن
    }

    /**
     * ✅ النسخة الجديدة — boolean
     * تُستدعى من BaseTabFragmentActivity.onKeyDown()
     *
     * @param keyCode رمز الزر
     * @param event   حدث الزر
     * @return true إذا تم التعامل مع الحدث
     */
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        myOnKeyDwon();  // استدعاء النسخة القديمة للتوافق
        return true;
    }

    /**
     * ✅ onKeyUp — تُستدعى عند إفلات زر الزناد
     *
     * ملاحظة معمارية:
     * الإيقاف الفعلي للقارئ يتم في BaseTabFragmentActivity عبر RFIDManager
     * مباشرة، لذا هذه الدالة افتراضيتها "لا تفعل شيئاً".
     *
     * إذا احتاج الـ Fragment الابن لتحديث واجهته عند الإفلات
     * (مثل تغيير لون زر، إخفاء مؤشر المسح)، فليقم بتجاوزها.
     *
     * @param keyCode رمز الزر
     * @param event   حدث الزر
     * @return true إذا تم التعامل مع الحدث
     */
    public boolean myOnKeyUp(int keyCode, KeyEvent event) {
        // لا شيء افتراضياً — تجاوزها في الـ Fragment الابن عند الحاجة
        return true;
    }
}
