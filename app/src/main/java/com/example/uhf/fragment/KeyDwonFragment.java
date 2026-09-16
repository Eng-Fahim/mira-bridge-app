package com.example.uhf.fragment;

import android.view.KeyEvent;

import androidx.fragment.app.Fragment;

/**
 * ✅ KeyDwonFragment — النسخة المتوافقة
 *
 * - myOnKeyDwon()                [void — للكود القديم]
 * - myOnKeyDwon(int, KeyEvent)   [boolean — الجديدة من BaseTabFragmentActivity]
 */
public class KeyDwonFragment extends Fragment {

    /**
     * النسخة القديمة — void (كما كانت في الكود الأصلي)
     * كل الـ Fragments القديمة تعتمد عليها
     */
    public void myOnKeyDwon() {
        // لا شيء افتراضياً
    }

    /**
     * ✅ النسخة الجديدة — boolean (تُستدعى من BaseTabFragmentActivity)
     * الافتراضي: تستدعي القديمة + ترجع true
     */
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        myOnKeyDwon();
        return true;
    }
}
