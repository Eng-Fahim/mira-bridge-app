package com.example.uhf.manager;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.model.CartItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * PosCartManager — إدارة سلة البيع
 *
 * ✅ Thread-safe: يستخدم CopyOnWriteArrayList / CopyOnWriteArraySet
 * ✅ منع التكرار عبر EPC / serialNo / gtin
 * ✅ إشعار المستمعين (Listener) بأي تغيير
 * ✅ Singleton — يحافظ على السلة عبر Lifecycle (لا تُفقد عند تبديل الشاشات)
 */
public final class PosCartManager {

    private static final String TAG = "PosCartManager";

    // ============================================
    // ✅ Singleton Instance
    // ============================================

    private static volatile PosCartManager INSTANCE;

    /**
     * ✅ الحصول على النسخة الوحيدة (Thread-Safe)
     */
    public static PosCartManager getInstance() {
        if (INSTANCE == null) {
            synchronized (PosCartManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PosCartManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * ✅ إعادة التعيين الكاملة
     * تُستخدم فقط عند: Logout / Reset System
     */
    public static void resetInstance() {
        synchronized (PosCartManager.class) {
            if (INSTANCE != null) {
                try {
                    INSTANCE.cartList.clear();
                    INSTANCE.scannedIds.clear();
                    INSTANCE.listener = null;
                } catch (Exception e) {
                    Log.e(TAG, "resetInstance cleanup error: " + e.getMessage());
                }
                INSTANCE = null;
            }
        }
        Log.d(TAG, "🔄 PosCartManager reset");
    }

    // ============================================
    // الحالة
    // ============================================

    private final List<CartItem> cartList = new CopyOnWriteArrayList<>();
    private final Set<String> scannedIds = new CopyOnWriteArraySet<>();

    @Nullable
    private OnCartChangedListener listener;

    // ============================================
    // ✅ Constructor — private (Singleton)
    // ============================================

    private PosCartManager() {
        // ✅ لا شيء — Singleton
    }

    // ============================================
    // واجهة الإشعار
    // ============================================

    public interface OnCartChangedListener {
        /** يُستدعى عند أي تغيير في السلة */
        void onCartChanged(int itemCount, double totalUsd, boolean hasUnavailable);

        /** يُستدعى عند إضافة عنصر جديد (لإشعار RecyclerView) */
        void onItemAdded(int position);

        /** يُستدعى عند حذف عنصر */
        void onItemRemoved(int position);

        /** يُستدعى عند تفريغ السلة كاملة */
        void onCartCleared();
    }

    public void setListener(@Nullable OnCartChangedListener listener) {
        this.listener = listener;
    }

    public
    @Nullable
    OnCartChangedListener getListener() {
        return listener;
    }

    // ============================================
    // الاستعلامات
    // ============================================

    @NonNull
    public List<CartItem> getItems() {
        return cartList;
    }

    public int size() {
        return cartList.size();
    }

    public boolean isEmpty() {
        return cartList.isEmpty();
    }

    /** الإجمالي بالدولار */
    public double getTotalUsd() {
        double total = 0;
        for (CartItem item : cartList) {
            if (item != null) total += item.price;
        }
        return total;
    }

    /** هل هناك قطعة غير متاحة في السلة؟ */
    public boolean hasUnavailableItems() {
        for (CartItem item : cartList) {
            if (item != null && !item.isAvailable) return true;
        }
        return false;
    }

    /** هل القطعة موجودة مسبقاً في السلة؟ */
    public boolean containsItem(@Nullable String epc,
                                @Nullable String serialNo,
                                @Nullable String gtin) {
        for (CartItem item : cartList) {
            if (item == null) continue;

            if (notEmpty(epc) && notEmpty(item.epc)
                    && item.epc.equalsIgnoreCase(epc)) {
                return true;
            }
            if (notEmpty(serialNo) && notEmpty(item.serialNo)
                    && item.serialNo.equalsIgnoreCase(serialNo)) {
                return true;
            }
            if (notEmpty(gtin) && notEmpty(item.gtin)
                    && item.gtin.equalsIgnoreCase(gtin)) {
                return true;
            }
        }
        return false;
    }

    /** هل الكود الممسوح موجود؟ (فحص سريع قبل المعالجة) */
    public boolean isIdScanned(@Nullable String id) {
        return notEmpty(id) && scannedIds.contains(id);
    }

    // ============================================
    // العمليات
    // ============================================

    /**
     * إضافة قطعة إلى السلة
     *
     * @return موضع الإضافة (دائماً 0)، أو -1 إذا فشلت
     */
    public int addItem(@NonNull CartItem item) {
        if (item == null) return -1;

        // منع التكرار
        if (containsItem(item.epc, item.serialNo, item.gtin)) {
            Log.w(TAG, "⚠️ Item already in cart: " + item.epc);
            return -1;
        }

        // إضافة في المقدمة
        cartList.add(0, item);

        // تسجيل المُعرّفات لمنع المسح المكرر
        registerIds(item);

        // إشعار
        notifyItemAdded(0);
        notifyChanged();

        Log.d(TAG, "✅ Item added. Cart size = " + cartList.size()
                + " | totalUsd=" + getTotalUsd());
        return 0;
    }

    /** حذف عنصر بموضعه */
    public boolean removeItemAt(int position) {
        if (position < 0 || position >= cartList.size()) return false;

        CartItem removed = cartList.remove(position);
        if (removed != null) {
            unregisterIds(removed);
        }

        notifyItemRemoved(position);
        notifyChanged();

        Log.d(TAG, "🗑️ Item removed at " + position
                + ". Cart size = " + cartList.size());
        return true;
    }

    /** حذف عنصر بكائن (للبحث) */
    public boolean removeItem(@NonNull CartItem item) {
        int index = cartList.indexOf(item);
        return index >= 0 && removeItemAt(index);
    }

    /** تفريغ السلة */
    public void clear() {
        int size = cartList.size();
        if (size == 0) return;

        cartList.clear();
        scannedIds.clear();

        notifyCartCleared();
        notifyChanged();

        Log.d(TAG, "🧹 Cart cleared. Removed " + size + " items");
    }

    // ============================================
    // مُعرّفات المسح
    // ============================================

    /** تسجيل مُعرّفات القطعة (لمنع المسح المكرر) */
    public void registerScannedId(@Nullable String id) {
        if (notEmpty(id)) {
            scannedIds.add(id);
        }
    }

    public void unregisterScannedId(@Nullable String id) {
        if (notEmpty(id)) {
            scannedIds.remove(id);
        }
    }

    public void clearScannedIds() {
        scannedIds.clear();
    }

    public Set<String> getScannedIds() {
        return scannedIds;
    }

    // ============================================
    // أدوات داخلية
    // ============================================

    private void registerIds(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.add(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.add(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.add(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.add(item.rfidEpc);
    }

    private void unregisterIds(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.remove(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.remove(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.remove(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.remove(item.rfidEpc);
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    private void notifyChanged() {
        if (listener != null) {
            try {
                listener.onCartChanged(
                        cartList.size(),
                        getTotalUsd(),
                        hasUnavailableItems()
                );
            } catch (Exception e) {
                Log.e(TAG, "notifyChanged error: " + e.getMessage());
            }
        }
    }

    private void notifyItemAdded(int position) {
        if (listener != null) {
            try {
                listener.onItemAdded(position);
            } catch (Exception e) {
                Log.e(TAG, "notifyItemAdded error: " + e.getMessage());
            }
        }
    }

    private void notifyItemRemoved(int position) {
        if (listener != null) {
            try {
                listener.onItemRemoved(position);
            } catch (Exception e) {
                Log.e(TAG, "notifyItemRemoved error: " + e.getMessage());
            }
        }
    }

    private void notifyCartCleared() {
        if (listener != null) {
            try {
                listener.onCartCleared();
            } catch (Exception e) {
                Log.e(TAG, "notifyCartCleared error: " + e.getMessage());
            }
        }
    }
}
