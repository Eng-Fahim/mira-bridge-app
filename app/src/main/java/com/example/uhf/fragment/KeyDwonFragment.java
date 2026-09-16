package com.example.uhf.fragment;

import android.view.KeyEvent;

import androidx.fragment.app.Fragment;

/**
 * ✅ KeyDwonFragment — النسخة المحدّثة
 *
 * يدعم كلا النسختين:
 * - myOnKeyDwon()                    [القديمة — للتوافق العكسي]
 * - myOnKeyDwon(int, KeyEvent)       [الجديدة — تُمرر keyCode + event]
 */
public class KeyDwonFragment extends Fragment {

    /**
     * النسخة القديمة — للتوافق العكسي
     */
    public boolean myOnKeyDwon() {
        return false;
    }

    /**
     * ✅ النسخة الجديدة — تُمرر keyCode + event
     * الافتراضي: يعتمد على النسخة القديمة
     */
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        return myOnKeyDwon();
    }
}
