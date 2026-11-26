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
 *  PosCartManager v2.1 — Single Source of Truth
 * ═══════════════════════════════════════════════════════════════════════════
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
                if (INSTANCE == null) {
                    INSTANCE = new PosCartManager();
                }
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

    public static final class CartSnapshot {
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
        void onCartChanged(@NonNull CartSnapshot snapshot,
                           @NonNull ChangeType type,
                           int position);
    }

    // ============================================
    //  الحالة الداخلية
    // ============================================

    private final Object lock = new Object();
    private final List<CartItem> cartList = new ArrayList<>();
    private final Set<String> scannedIds = new CopyOnWriteArraySet<>();
    private final List<OnCartChangedListener> listeners = new CopyOnWriteArrayList<>();
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
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /** للتوافق العكسي: يستبدل كل المستمعين بمستمع واحد */
    @Deprecated
    public void setListener(@Nullable OnCartChangedListener listener) {
        listeners.clear();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ============================================
    //  الاستعلامات — snapshot-based
    // ============================================

    @NonNull
    public List<CartItem> getItems() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(cartList));
        }
    }

    @NonNull
    public CartSnapshot snapshot() {
        synchronized (lock) {
            List<CartItem> copy = new ArrayList<>(cartList);
            return new CartSnapshot(copy, computeTotal(copy), computeHasUnavailable(copy));
        }
    }

    public int size() {
        synchronized (lock) {
            return cartList.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return cartList.isEmpty();
        }
    }

    public double getTotalUsd() {
        synchronized (lock) {
            return computeTotal(cartList);
        }
    }

    public boolean hasUnavailableItems() {
        synchronized (lock) {
            return computeHasUnavailable(cartList);
        }
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

    public int addItem(@NonNull CartItem item) {
        if (item == null) return -1;

        int insertPosition;
        synchronized (lock) {
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

    public boolean removeItemAt(int position) {
        boolean removed;
        synchronized (lock) {
            if (position < 0 || position >= cartList.size()) return false;
            CartItem removedItem = cartList.remove(position);
            if (removedItem != null) {
                unregisterIdsLocked(removedItem);
            }
            removed = (removedItem != null);
        }

        if (!removed) return false;

        Log.d(TAG, "🗑️ Item removed at " + position);
        dispatch(ChangeType.ITEM_REMOVED, position);
        return true;
    }

    public boolean removeItem(@NonNull CartItem item) {
        int index;
        synchronized (lock) {
            index = cartList.indexOf(item);
        }
        return index >= 0 && removeItemAt(index);
    }

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

    private void registerIdsLocked(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.add(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.add(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.add(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.add(item.rfidEpc);
    }

    private void unregisterIdsLocked(@NonNull CartItem item) {
        if (notEmpty(item.epc)) scannedIds.remove(item.epc);
        if (notEmpty(item.serialNo)) scannedIds.remove(item.serialNo);
        if (notEmpty(item.gtin)) scannedIds.remove(item.gtin);
        if (notEmpty(item.rfidEpc)) scannedIds.remove(item.rfidEpc);
    }

    private double computeTotal(List<CartItem> list) {
        double t = 0.0;
        for (CartItem i : list) {
            if (i != null && Double.isFinite(i.price) && i.price > 0.0) {
                t += i.price;
            }
        }
        return t;
    }

    private boolean computeHasUnavailable(List<CartItem> list) {
        for (CartItem i : list) {
            if (i != null && !i.isAvailable) return true;
        }
        return false;
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

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

        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifier.run();
        } else {
            mainHandler.post(notifier);
        }
    }

    /**
     * يحدّث سعر قطعة في السلة (بعد prepare-checkout).
     */
    public boolean updateItemPrice(int itemId, double newPriceUsd) {
        if (!Double.isFinite(newPriceUsd) || newPriceUsd < 0.0) return false;

        int position = -1;
        double oldPrice = 0.0;

        synchronized (lock) {
            for (int i = 0; i < cartList.size(); i++) {
                CartItem item = cartList.get(i);
                if (item != null && item.itemId == itemId) {
                    if (Math.abs(item.price - newPriceUsd) <= 0.01) {
                        return false;
                    }
                    oldPrice = item.price;
                    item.price = newPriceUsd;
                    position = i;
                    break;
                }
            }
        }

        if (position >= 0) {
            Log.d(TAG, "💰 Price updated [itemId=" + itemId + "]: "
                    + oldPrice + " → " + newPriceUsd);
            dispatch(ChangeType.ITEM_UPDATED, position);
            return true;
        }
        return false;
    }

    /**
     * يزيل قطعة أو أكثر بمعرّف itemId مع إطلاق إشعار واحد عند وجود حذف فعلي.
     */
    public void removeItemByItemId(int itemId) {
        int lastRemovedPos = -1;
        synchronized (lock) {
            for (int i = cartList.size() - 1; i >= 0; i--) {
                CartItem item = cartList.get(i);
                if (item != null && item.itemId == itemId) {
                    cartList.remove(i);
                    unregisterIdsLocked(item);
                    lastRemovedPos = i;
                }
            }
        }

        if (lastRemovedPos >= 0) {
            dispatch(ChangeType.ITEM_REMOVED, lastRemovedPos);
        }
    }
}
