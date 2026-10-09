package com.example.uhf.manager;

import android.os.Handler;
import android.os.Looper;
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
 * ═══════════════════════════════════════════════════════════════════════════
 *  PosCartManager v2.0 — Single Source of Truth
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغييرات الجوهرية عن v1.0:
 *
 *   1. ✅ Notification واحدة لكل عملية (ChangeType بدلاً من 4 دوال منفصلة)
 *      → يمنع التنازع بين notifyItemInserted و notifyDataSetChanged.
 *
 *   2. ✅ Thread-safe: كل notifications على main thread.
 *      → يمنع "Cannot call this method while RecyclerView is computing a layout".
 *
 *   3. ✅ getItems() يعيد immutable snapshot.
 *      → CartAdapter لن يقرأ من قائمة تتغيّر تحته.
 *
 *   4. ✅ دعم مستمعين متعددين (addListener / removeListener).
 *      → لا يوجد استيلاء من شاشة على أخرى.
 *
 *   5. ✅ فصل واضح: ChangeType + position + snapshot في notification واحدة.
 *
 *   6. ✅ updateItemAt() جديد — لتحديث عنصر (scaleWeight).
 */
public final class PosCartManager {

    private static final String TAG = "PosCartManager";

    // ============================================
    //  Singleton
    // ============================================

    private static volatile PosCartManager INSTANCE;

    public static PosCartManager getInstance() {
        if (INSTANCE == null) {
            synchronized (PosCartManager.class) {
                if (INSTANCE == null) INSTANCE = new PosCartManager();
            }
        }
        return INSTANCE;
    }

    /** إعادة تعيين كاملة (Logout / Reset) */
    public static void resetInstance() {
        synchronized (PosCartManager.class) {
            if (INSTANCE != null) {
                INSTANCE.cartList.clear();
                INSTANCE.scannedIds.clear();
                INSTANCE.listeners.clear();
                INSTANCE = null;
            }
        }
        Log.d(TAG, "🔄 PosCartManager reset");
    }

    // ============================================
    //  أنواع التغيير
    // ============================================

    public enum ChangeType {
        ITEM_ADDED,       // position = فهرس الإضافة (دائماً 0)
        ITEM_REMOVED,     // position = فهرس الحذف
        ITEM_UPDATED,     // position = فهرس التحديث
        CART_CLEARED      // position = -1 (لا يُهم)
    }

    // ============================================
    //  Snapshot — نسخة ثابتة للقراءة
    // ============================================

    /**
     * نسخة ثابتة من السلة — للاستخدام في الـ UI.
     * لا تعكس أي تعديل لاحق.
     */
    public static final class CartSnapshot {
        /** نسخة immutable من القائمة */
        @NonNull public final List<CartItem> items;
        public final int count;
        public final double totalUsd;
        public final boolean hasUnavailable;

        private CartSnapshot(@NonNull List<CartItem> items,
                             double totalUsd,
                             boolean hasUnavailable) {
            this.items = Collections.unmodifiableList(items);
            this.count = items.size();
            this.totalUsd = totalUsd;
            this.hasUnavailable = hasUnavailable;
        }
    }

    // ============================================
    //  Listener interface
    // ============================================

    public interface OnCartChangedListener {
        /**
         * يُستدعى مرة واحدة لكل عملية — على main thread.
         *
         * @param snapshot صورة ثابتة للحالة الجديدة
         * @param type     نوع التغيير
         * @param position فهرس التغيير (أو -1 لـ CART_CLEARED)
         */
        void onCartChanged(@NonNull CartSnapshot snapshot,
                           @NonNull ChangeType type,
                           int position);
    }

    // ============================================
    //  الحالة الداخلية
    // ============================================

    private final Object lock = new Object();

    /** القائمة الأساسية — لا تُكشف مباشرة */
    private final List<CartItem> cartList = new ArrayList<>();

    /** معرّفات لمنع المسح المكرر */
    private final Set<String> scannedIds = new CopyOnWriteArraySet<>();

    /** مستمعون متعددون */
    private final List<OnCartChangedListener> listeners = new CopyOnWriteArrayList<>();

    /** main thread handler لكل notifications */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PosCartManager() {}

    // ============================================
    //  Listeners
    // ============================================

    public void addListener(@NonNull OnCartChangedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(@Nullable OnCartChangedListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** للتوافق العكسي: يستبدل كل المستمعين بمستمع واحد */
    @Deprecated
    public void setListener(@Nullable OnCartChangedListener listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    // ============================================
    //  الاستعلامات — snapshot-based
    // ============================================

    /**
     * ✅ snapshot immutable — آمن للاستخدام من أي مكان.
     * لا تعكس التعديلات اللاحقة.
     */
    @NonNull
    public List<CartItem> getItems() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(cartList));
        }
    }

    /** نسخة كاملة مع الحسابات */
    @NonNull
    public CartSnapshot snapshot() {
        synchronized (lock) {
            List<CartItem> copy = new ArrayList<>(cartList);
            return new CartSnapshot(copy, computeTotal(copy), computeHasUnavailable(copy));
        }
    }

    public int size() {
        synchronized (lock) { return cartList.size(); }
    }

    public boolean isEmpty() {
        synchronized (lock) { return cartList.isEmpty(); }
    }

    public double getTotalUsd() {
        synchronized (lock) { return computeTotal(cartList); }
    }

    public boolean hasUnavailableItems() {
        synchronized (lock) { return computeHasUnavailable(cartList); }
    }

    public boolean containsItem(@Nullable String epc,
                                @Nullable String serialNo,
                                @Nullable String gtin) {
        synchronized (lock) {
            for (CartItem item : cartList) {
                if (item == null) continue;

                if (notEmpty(epc) && notEmpty(item.epc)
                        && item.epc.equalsIgnoreCase(epc)) return true;
                if (notEmpty(serialNo) && notEmpty(item.serialNo)
                        && item.serialNo.equalsIgnoreCase(serialNo)) return true;
                if (notEmpty(gtin) && notEmpty(item.gtin)
                        && item.gtin.equalsIgnoreCase(gtin)) return true;
            }
            return false;
        }
    }

    public boolean isIdScanned(@Nullable String id) {
        return notEmpty(id) && scannedIds.contains(id);
    }

    // ============================================
    //  العمليات
    // ============================================

    /**
     * إضافة قطعة إلى السلة — تُطلق notification واحدة (ITEM_ADDED).
     * @return 0 إذا نجحت، -1 إذا فشلت (مكررة)
     */
    public int addItem(@NonNull CartItem item) {
        if (item == null) return -1;

        int insertPosition;
        synchronized (lock) {
            // منع التكرار
            if (containsLocked(item.epc, item.serialNo, item.gtin)) {
                Log.w(TAG, "⚠️ Item already in cart: " + item.epc);
                return -1;
            }

            cartList.add(0, item);
            registerIdsLocked(item);
            insertPosition = 0;
        }

        Log.d(TAG, "✅ Item added. Cart size = " + size());
        dispatch(ChangeType.ITEM_ADDED, insertPosition);
        return 0;
    }

    /** حذف بموضع */
    public boolean removeItemAt(int position) {
        boolean removed;
        synchronized (lock) {
            if (position < 0 || position >= cartList.size()) return false;
            CartItem removedItem = cartList.remove(position);
            if (removedItem != null) unregisterIdsLocked(removedItem);
            removed = (removedItem != null);
        }

        if (!removed) return false;

        Log.d(TAG, "🗑️ Item removed at " + position);
        dispatch(ChangeType.ITEM_REMOVED, position);
        return true;
    }

    /** حذف بكائن */
    public boolean removeItem(@NonNull CartItem item) {
        int index;
        synchronized (lock) { index = cartList.indexOf(item); }
        return index >= 0 && removeItemAt(index);
    }

    /**
     * تحديث عنصر موجود (مثل scaleWeight) — دون إعادة ترتيب.
     * @return true إذا وُجد وتم التحديث
     */
    public boolean updateItemAt(int position, @NonNull CartItem newItem) {
        if (newItem == null) return false;

        boolean updated;
        synchronized (lock) {
            if (position < 0 || position >= cartList.size()) return false;
            cartList.set(position, newItem);
            updated = true;
        }

        if (updated) {
            dispatch(ChangeType.ITEM_UPDATED, position);
        }
        return updated;
    }

    /** تفريغ السلة — يُطلق notification واحدة (CART_CLEARED) */
    public void clear() {
        int sizeBefore;
        synchronized (lock) {
            sizeBefore = cartList.size();
            if (sizeBefore == 0) return;
            cartList.clear();
            scannedIds.clear();
        }

        Log.d(TAG, "🧹 Cart cleared. Removed " + sizeBefore);
        dispatch(ChangeType.CART_CLEARED, -1);
    }

    // ============================================
    //  مُعرّفات المسح
    // ============================================

    public void registerScannedId(@Nullable String id) {
        if (notEmpty(id)) scannedIds.add(id);
    }

    public void unregisterScannedId(@Nullable String id) {
        if (notEmpty(id)) scannedIds.remove(id);
    }

    public void clearScannedIds() {
        scannedIds.clear();
    }

    public Set<String> getScannedIds() {
        return Collections.unmodifiableSet(scannedIds);
    }

    // ============================================
    //  دوال داخلية
    // ============================================

    /** ⚠️ يُستدعى داخل synchronized(lock) */
    private boolean containsLocked(String epc, String serialNo, String gtin) {
        for (CartItem item : cartList) {
            if (item == null) continue;
            if (notEmpty(epc) && notEmpty(item.epc)
                    && item.epc.equalsIgnoreCase(epc)) return true;
            if (notEmpty(serialNo) && notEmpty(item.serialNo)
                    && item.serialNo.equalsIgnoreCase(serialNo)) return true;
            if (notEmpty(gtin) && notEmpty(item.gtin)
                    && item.gtin.equalsIgnoreCase(gtin)) return true;
        }
        return false;
    }

    /** ⚠️ يُستدعى داخل synchronized(lock) */
    private void registerIdsLocked(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.add(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.add(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.add(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.add(item.rfidEpc);
    }

    /** ⚠️ يُستدعى داخل synchronized(lock) */
    private void unregisterIdsLocked(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.remove(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.remove(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.remove(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.remove(item.rfidEpc);
    }

    private double computeTotal(List<CartItem> list) {
        double t = 0;
        for (CartItem i : list) if (i != null) t += i.price;
        return t;
    }

    private boolean computeHasUnavailable(List<CartItem> list) {
        for (CartItem i : list) if (i != null && !i.isAvailable) return true;
        return false;
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * ✅ Dispatch واحد لكل عملية — على main thread دائماً.
     */
    private void dispatch(@NonNull final ChangeType type, final int position) {
        final CartSnapshot snapshot = snapshot();

        Runnable notifier = () -> {
            for (OnCartChangedListener listener : listeners) {
                try {
                    listener.onCartChanged(snapshot, type, position);
                } catch (Exception e) {
                    Log.e(TAG, "listener error", e);
                }
            }
        };

        // ✅ إذا كنا على main thread → نفّذ مباشرة
        //    وإلا → post إلى main
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifier.run();
        } else {
            mainHandler.post(notifier);
        }
    }
}
