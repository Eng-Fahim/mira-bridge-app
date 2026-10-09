package com.example.uhf.manager;

import android.animation.ObjectAnimator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  PosHeroController v2.0 — Thread-safe State Machine
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغييرات الجوهرية عن v1.0:
 *
 *   1. ✅ setState() thread-safe — كل التعديلات على main thread.
 *   2. ✅ refresh() جديد لإعادة تطبيق visuals دون تغيير الحالة.
 *   3. ✅ حماية ضد setState المتكرر بنفس الحالة (لا تراكم animations).
 *   4. ✅ cleanup() آمن ضد الاستدعاء المتكرر.
 *   5. ✅ عرض الحالة الحالية في السجل عند الطلب.
 */
public final class PosHeroController {

    private static final String TAG = "PosHeroController";

    // ═══════════════════════════════════════════════════════
    //  الحالات
    // ═══════════════════════════════════════════════════════

    public enum State {
        IDLE,
        GS1_SCANNING,
        RFID_LISTENING,
        MANUAL_ENTRY,
        ITEM_ADDED,
        ERROR
    }

    // ═══════════════════════════════════════════════════════
    //  Views
    // ═══════════════════════════════════════════════════════

    private final View glowCircle;
    private final View ring;
    private final ImageView heroIcon;
    private final ImageView sparkleTop;
    private final ImageView sparkleBottom;
    private final TextView titleAr;
    private final TextView titleEn;
    private final View divider;
    private final TextView message;
    private final TextView hint;
    private final TextView rfidCount;

    private final View btnGs1;
    private final View btnRfid;
    private final View btnManual;
    private final ImageView iconGs1;
    private final ImageView iconRfid;
    private final ImageView iconManual;
    private final TextView labelGs1;
    private final TextView labelRfid;
    private final TextView labelManual;

    // ═══════════════════════════════════════════════════════
    //  Animations
    // ═══════════════════════════════════════════════════════

    @Nullable private Animation animGlowPulse;
    @Nullable private Animation animCartBounce;
    @Nullable private Animation animSparkleFade;
    private boolean animationsInitialized = false;

    // ═══════════════════════════════════════════════════════
    //  الحالة
    // ═══════════════════════════════════════════════════════

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile State currentState = State.IDLE;
    private volatile int rfidScanCount = 0;
    private volatile boolean cleanedUp = false;

    // ═══════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════

    public PosHeroController(
            @NonNull View glowCircle,
            @NonNull View ring,
            @NonNull ImageView heroIcon,
            @NonNull ImageView sparkleTop,
            @NonNull ImageView sparkleBottom,
            @NonNull TextView titleAr,
            @NonNull TextView titleEn,
            @NonNull View divider,
            @NonNull TextView message,
            @NonNull TextView hint,
            @NonNull TextView rfidCount,
            @NonNull View btnGs1,
            @NonNull View btnRfid,
            @NonNull View btnManual,
            @NonNull ImageView iconGs1,
            @NonNull ImageView iconRfid,
            @NonNull ImageView iconManual,
            @NonNull TextView labelGs1,
            @NonNull TextView labelRfid,
            @NonNull TextView labelManual) {
        this.glowCircle = glowCircle;
        this.ring = ring;
        this.heroIcon = heroIcon;
        this.sparkleTop = sparkleTop;
        this.sparkleBottom = sparkleBottom;
        this.titleAr = titleAr;
        this.titleEn = titleEn;
        this.divider = divider;
        this.message = message;
        this.hint = hint;
        this.rfidCount = rfidCount;
        this.btnGs1 = btnGs1;
        this.btnRfid = btnRfid;
        this.btnManual = btnManual;
        this.iconGs1 = iconGs1;
        this.iconRfid = iconRfid;
        this.iconManual = iconManual;
        this.labelGs1 = labelGs1;
        this.labelRfid = labelRfid;
        this.labelManual = labelManual;
    }

    // ═══════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════

    public State getState() {
        return currentState;
    }

    public int getRfidScanCount() {
        return rfidScanCount;
    }

    /**
     * ✅ الانتقال إلى حالة جديدة — thread-safe.
     * كل التعديلات تُنفَّذ على main thread.
     */
    public void setState(@NonNull final State newState) {
        if (cleanedUp) return;

        // ✅ إذا كنا على main thread — نفّذ فوراً
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyStateInternal(newState);
        } else {
            mainHandler.post(() -> applyStateInternal(newState));
        }
    }

    /**
     * ✅ إعادة تطبيق visuals الحالة الحالية (دون تغييرها).
     * مفيد بعد تغييرات خارجية (مثل scale updates).
     */
    public void refresh() {
        if (cleanedUp) return;
        final State s = currentState;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyStateInternal(s);
        } else {
            mainHandler.post(() -> applyStateInternal(s));
        }
    }

    /**
     * زيادة عدّاد RFID.
     */
    public void incrementRfidCount() {
        if (cleanedUp) return;
        rfidScanCount++;
        updateRfidCountVisibilityOnMain();
    }

    /**
     * إعادة تعيين عدّاد RFID.
     */
    public void resetRfidCount() {
        if (cleanedUp) return;
        rfidScanCount = 0;
        updateRfidCountVisibilityOnMain();
    }

    /**
     * إيقاف كل الحركات — idempotent.
     */
    public void cleanup() {
        cleanedUp = true;
        Runnable cleanupTask = () -> {
            try {
                if (glowCircle != null) glowCircle.clearAnimation();
                if (heroIcon != null) heroIcon.clearAnimation();
                if (sparkleTop != null) sparkleTop.clearAnimation();
                if (sparkleBottom != null) sparkleBottom.clearAnimation();
                if (ring != null) ring.clearAnimation();
            } catch (Exception e) {
                Log.e(TAG, "cleanup error: " + e.getMessage());
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanupTask.run();
        } else {
            mainHandler.post(cleanupTask);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Internal — التطبيق الذرّي
    // ═══════════════════════════════════════════════════════

    private void applyStateInternal(@NonNull State newState) {
        if (cleanedUp) return;

        if (currentState == newState) {
            // ✅ الحالة نفسها — فقط حدّث visuals (لمنع الشاشة الفارغة)
            applyStateVisuals(newState);
            applyStateButtons(newState);
            return;
        }

        Log.d(TAG, "🔄 State: " + currentState + " → " + newState);
        currentState = newState;

        if (!animationsInitialized) {
            initAnimations();
        }

        applyStateVisuals(newState);
        applyStateButtons(newState);
        applyStateAnimations(newState);
    }

    // ═══════════════════════════════════════════════════════
    //  تطبيق العناصر البصرية
    // ═══════════════════════════════════════════════════════

    private void applyStateVisuals(@NonNull State state) {
        switch (state) {
            case IDLE:
                setIcon(R.drawable.ic_pos_cart_gold);
                setText(titleAr, R.string.pos_hero_title_ar);
                setText(titleEn, R.string.pos_hero_title_en);
                setText(message, R.string.pos_hero_idle_msg);
                setText(hint, R.string.pos_hero_idle_hint);
                break;

            case GS1_SCANNING:
                setIcon(R.drawable.ic_pos_gs1);
                setText(titleAr, R.string.pos_hero_gs1_title);
                setText(titleEn, R.string.pos_hero_gs1_subtitle);
                setText(message, R.string.pos_hero_gs1_hint);
                setText(hint, "");
                break;

            case RFID_LISTENING:
                setIcon(R.drawable.ic_pos_rfid);
                setText(titleAr, R.string.pos_hero_rfid_title);
                setText(titleEn, R.string.pos_hero_rfid_subtitle);
                setText(message, R.string.pos_hero_rfid_hint);
                setText(hint, "");
                break;

            case MANUAL_ENTRY:
                setIcon(R.drawable.ic_pos_manual);
                setText(titleAr, R.string.pos_hero_manual_title);
                setText(titleEn, R.string.pos_hero_manual_subtitle);
                setText(message, "");
                setText(hint, "");
                break;

            case ITEM_ADDED:
                setIcon(R.drawable.ic_pos_cart_gold);
                setText(titleAr, R.string.pos_hero_added_title);
                setText(titleEn, R.string.pos_hero_added_subtitle);
                setText(message, "");
                setText(hint, "");
                break;

            case ERROR:
                setIcon(R.drawable.ic_pos_cart_gold);
                setText(titleAr, R.string.pos_hero_error_title);
                setText(titleEn, R.string.pos_hero_error_subtitle);
                setText(message, "");
                setText(hint, "");
                break;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  تطبيق حالة الأزرار
    // ═══════════════════════════════════════════════════════

    private void applyStateButtons(@NonNull State state) {
        resetButton(btnGs1, iconGs1, labelGs1,
                R.drawable.bg_pos_scan_button_outlined, R.color.pos_gold_primary);
        resetButton(btnRfid, iconRfid, labelRfid,
                R.drawable.bg_pos_scan_button_outlined, R.color.pos_gold_primary);
        resetButton(btnManual, iconManual, labelManual,
                R.drawable.bg_pos_scan_button_outlined, R.color.pos_gold_primary);

        switch (state) {
            case IDLE:
                activatePrimary(btnGs1, iconGs1, labelGs1);
                setEnabled(btnGs1, true);
                setEnabled(btnRfid, true);
                setEnabled(btnManual, true);
                break;

            case GS1_SCANNING:
                setActiveButton(btnGs1, iconGs1, labelGs1, R.string.pos_btn_gs1_active);
                setEnabled(btnGs1, true);
                setEnabled(btnRfid, false);
                setEnabled(btnManual, false);
                break;

            case RFID_LISTENING:
                setActiveButton(btnRfid, iconRfid, labelRfid, R.string.pos_btn_rfid_active);
                setEnabled(btnGs1, false);
                setEnabled(btnRfid, true);
                setEnabled(btnManual, false);
                break;

            case MANUAL_ENTRY:
                setActiveButton(btnManual, iconManual, labelManual,
                        R.string.pos_btn_manual_active);
                setEnabled(btnGs1, false);
                setEnabled(btnRfid, false);
                setEnabled(btnManual, true);
                break;

            case ITEM_ADDED:
            case ERROR:
                setEnabled(btnGs1, false);
                setEnabled(btnRfid, false);
                setEnabled(btnManual, false);
                break;
        }
    }

    private void resetButton(View btn, ImageView icon, TextView label,
                             @DrawableRes int bgRes, @ColorRes int tintRes) {
        try {
            if (btn == null) return;
            btn.setBackgroundResource(bgRes);
            btn.setSelected(false);
            if (icon != null) {
                icon.setColorFilter(ContextCompat.getColor(icon.getContext(), tintRes));
            }
            if (label != null) {
                label.setTextColor(ContextCompat.getColor(label.getContext(), tintRes));
            }
        } catch (Exception e) {
            Log.e(TAG, "resetButton error: " + e.getMessage());
        }
    }

    private void activatePrimary(View btn, ImageView icon, TextView label) {
        try {
            if (btn == null) return;
            btn.setBackgroundResource(R.drawable.bg_pos_scan_button_primary);
            btn.setSelected(false);
            if (icon != null) {
                icon.setColorFilter(ContextCompat.getColor(icon.getContext(),
                        R.color.pos_text_on_gold));
            }
            if (label != null) {
                label.setTextColor(ContextCompat.getColor(label.getContext(),
                        R.color.pos_text_on_gold));
            }
        } catch (Exception e) {
            Log.e(TAG, "activatePrimary error: " + e.getMessage());
        }
    }

    private void setActiveButton(View btn, ImageView icon, TextView label, int labelResId) {
        try {
            if (btn == null) return;
            btn.setBackgroundResource(R.drawable.bg_pos_scan_button_primary);
            btn.setSelected(true);
            if (icon != null) {
                icon.setColorFilter(ContextCompat.getColor(icon.getContext(),
                        R.color.pos_text_on_gold));
            }
            if (label != null) {
                label.setText(labelResId);
                label.setTextColor(ContextCompat.getColor(label.getContext(),
                        R.color.pos_text_on_gold));
            }
        } catch (Exception e) {
            Log.e(TAG, "setActiveButton error: " + e.getMessage());
        }
    }

    private void setEnabled(View v, boolean enabled) {
        try {
            if (v == null) return;
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1.0f : 0.4f);
        } catch (Exception e) {
            Log.e(TAG, "setEnabled error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  الحركات
    // ═══════════════════════════════════════════════════════

    private void initAnimations() {
        try {
            if (glowCircle != null) {
                animGlowPulse = AnimationUtils.loadAnimation(
                        glowCircle.getContext(), R.anim.pos_glow_pulse);
            }
            if (heroIcon != null) {
                animCartBounce = AnimationUtils.loadAnimation(
                        heroIcon.getContext(), R.anim.pos_cart_bounce);
            }
            if (sparkleTop != null) {
                animSparkleFade = AnimationUtils.loadAnimation(
                        sparkleTop.getContext(), R.anim.pos_sparkle_fade);
            }
            animationsInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "initAnimations error: " + e.getMessage());
        }
    }

    private void applyStateAnimations(@NonNull State state) {
        stopAllAnimations();

        switch (state) {
            case IDLE:
                startIdleAnimations();
                break;
            case GS1_SCANNING:
            case RFID_LISTENING:
                startGlowPulseOnly();
                break;
            case ITEM_ADDED:
                startSparklesOnly();
                break;
            case MANUAL_ENTRY:
            case ERROR:
            default:
                // لا حركات
                break;
        }
    }

    private void startIdleAnimations() {
        try {
            if (glowCircle != null && animGlowPulse != null) {
                glowCircle.startAnimation(animGlowPulse);
            }
            if (heroIcon != null && animCartBounce != null) {
                heroIcon.startAnimation(animCartBounce);
            }
            if (sparkleTop != null && animSparkleFade != null) {
                sparkleTop.startAnimation(animSparkleFade);
            }
            if (sparkleBottom != null && animSparkleFade != null) {
                sparkleBottom.startAnimation(animSparkleFade);
            }
        } catch (Exception e) {
            Log.e(TAG, "startIdleAnimations error: " + e.getMessage());
        }
    }

    private void startGlowPulseOnly() {
        try {
            if (glowCircle != null && animGlowPulse != null) {
                glowCircle.startAnimation(animGlowPulse);
            }
        } catch (Exception e) {
            Log.e(TAG, "startGlowPulseOnly error: " + e.getMessage());
        }
    }

    private void startSparklesOnly() {
        try {
            if (sparkleTop != null && animSparkleFade != null) {
                sparkleTop.startAnimation(animSparkleFade);
            }
            if (sparkleBottom != null && animSparkleFade != null) {
                sparkleBottom.startAnimation(animSparkleFade);
            }
        } catch (Exception e) {
            Log.e(TAG, "startSparklesOnly error: " + e.getMessage());
        }
    }

    private void stopAllAnimations() {
        try {
            if (glowCircle != null) glowCircle.clearAnimation();
            if (heroIcon != null) heroIcon.clearAnimation();
            if (sparkleTop != null) sparkleTop.clearAnimation();
            if (sparkleBottom != null) sparkleBottom.clearAnimation();
            if (ring != null) ring.clearAnimation();
        } catch (Exception e) {
            Log.e(TAG, "stopAllAnimations error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  أدوات
    // ═══════════════════════════════════════════════════════

    private void setIcon(@DrawableRes int resId) {
        try {
            if (heroIcon != null) heroIcon.setImageResource(resId);
        } catch (Exception e) {
            Log.e(TAG, "setIcon error: " + e.getMessage());
        }
    }

    private void setText(TextView tv, int resId) {
        try {
            if (tv != null) tv.setText(resId);
        } catch (Exception e) {
            Log.e(TAG, "setText error: " + e.getMessage());
        }
    }

    private void setText(TextView tv, String text) {
        try {
            if (tv != null) tv.setText(text);
        } catch (Exception e) {
            Log.e(TAG, "setText error: " + e.getMessage());
        }
    }

    private void updateRfidCountVisibilityOnMain() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateRfidCountVisibilityInternal();
        } else {
            mainHandler.post(this::updateRfidCountVisibilityInternal);
        }
    }

    private void updateRfidCountVisibilityInternal() {
        try {
            if (rfidCount == null) return;
            if (rfidScanCount > 0) {
                rfidCount.setVisibility(View.VISIBLE);
                rfidCount.setText(rfidCount.getContext().getString(
                        R.string.pos_hero_rfid_count, rfidScanCount));
            } else {
                rfidCount.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateRfidCountVisibility error: " + e.getMessage());
        }
    }
}
