package com.example.uhf.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.model.CartItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  MiraPosViewModel — العقل المُدبِّر لشاشة البيع الاحترافية
 * ═══════════════════════════════════════════════════════════════════
 *
 *  🎯 الهدف:
 *  حفظ كل بيانات POS في مكان آمن يعيش أطول من دورة حياة الـ Fragment:
 *  - سلة المشتريات (cartList)
 *  - EPCs الممسوحة (scannedEpcs)
 *  - حالات البحث والمسح (pendingEpcs, lastScannedTimes)
 *  - حالة RFID و الإرجاع
 *  - وزن الميزان الحي
 *  - آخر استجابة بيع (للإرجاع)
 *  - حالة حوار الدفع
 *
 *  📌 ملاحظة: لا نحتاج Context حقيقي — نستخدم AndroidViewModel
 *  للاتساق مع ViewModels الأخرى، وحتى نتمكن من الوصول إلى
 *  Application مستقبلاً إن احتجنا.
 */
public class MiraPosViewModel extends AndroidViewModel {

    private static final String TAG = "MiraPosVM";

    // ═══════════════════════════════════════════════════════════════════
    //  1. البيانات الحية (LiveData)
    // ═══════════════════════════════════════════════════════════════════

    /** 🛒 سلة المشتريات — المصدر الوحيد للحقيقة */
    private final MutableLiveData<List<CartItem>> cartListLiveData =
            new MutableLiveData<>(new ArrayList<>());

    /** 💰 المجموع الكلي (مشتق — مخزّن لتسريع UI) */
    private final MutableLiveData<Double> cartTotalLiveData =
            new MutableLiveData<>(0.0);

    /** 🔢 عدد القطع في السلة */
    private final MutableLiveData<Integer> cartItemCountLiveData =
            new MutableLiveData<>(0);

    /** 📡 هل وضع RFID نشط؟ */
    private final MutableLiveData<Boolean> isRFIDModeActiveLiveData =
            new MutableLiveData<>(false);

    /** ↩️ هل آخر بيع قابل للإرجاع؟ */
    private final MutableLiveData<Boolean> isRefundableLiveData =
            new MutableLiveData<>(false);

    /** ⚖️ وزن الميزان الحي (جرام) */
    private final MutableLiveData<Double> liveScaleWeightLiveData =
            new MutableLiveData<>(0.0);

    /** 📋 استجابة آخر عملية بيع (JSON string للطباعة/الإرجاع) */
    private final MutableLiveData<String> lastSaleResponseJsonLiveData =
            new MutableLiveData<>(null);

    /** ⏳ هل حوار الدفع مفتوح؟ */
    private final MutableLiveData<Boolean> isCheckoutDialogShowingLiveData =
            new MutableLiveData<>(false);

    /** 🔍 هل البحث جارٍ حالياً؟ (اختياري — يمكن استخدامه) */
    private final MutableLiveData<Boolean> isSearchingLiveData =
            new MutableLiveData<>(false);

    // ═══════════════════════════════════════════════════════════════════
    //  2. المجموعات المباشرة (Sets/Maps) — thread-safe
    // ═══════════════════════════════════════════════════════════════════

    /** 🔒 EPCs/Serials/GTINs المضافة للسلة (لمنع التكرار) */
    private final Set<String> scannedEpcs = ConcurrentHashMap.newKeySet();

    /** ⏳ EPCs قيد البحث حالياً (لمنع طلبات متوازية) */
    private final Set<String> pendingEpcs = ConcurrentHashMap.newKeySet();

    /** ⏱️ سجل أوقات المسح الأخير (Debounce) */
    private final Map<String, Long> lastScannedTimes = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    //  3. ثوابت
    // ═══════════════════════════════════════════════════════════════════

    public static final long SCAN_DEBOUNCE_MS = 1500;

    // ═══════════════════════════════════════════════════════════════════
    //  4. البنّاء
    // ═══════════════════════════════════════════════════════════════════

    public MiraPosViewModel(@NonNull Application application) {
        super(application);
        Log.d(TAG, "MiraPosViewModel initialized");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Getters — LiveData
    // ═══════════════════════════════════════════════════════════════════

    public LiveData<List<CartItem>> getCartList() {
        return cartListLiveData;
    }

    public LiveData<Double> getCartTotal() {
        return cartTotalLiveData;
    }

    public LiveData<Integer> getCartItemCount() {
        return cartItemCountLiveData;
    }

    public LiveData<Boolean> getIsRFIDModeActive() {
        return isRFIDModeActiveLiveData;
    }

    public LiveData<Boolean> getIsRefundable() {
        return isRefundableLiveData;
    }

    public LiveData<Double> getLiveScaleWeight() {
        return liveScaleWeightLiveData;
    }

    public LiveData<String> getLastSaleResponseJson() {
        return lastSaleResponseJsonLiveData;
    }

    public LiveData<Boolean> getIsCheckoutDialogShowing() {
        return isCheckoutDialogShowingLiveData;
    }

    public LiveData<Boolean> getIsSearching() {
        return isSearchingLiveData;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Getters — Sets/Maps المباشرة
    // ═══════════════════════════════════════════════════════════════════

    public Set<String> getScannedEpcs() {
        return scannedEpcs;
    }

    public Set<String> getPendingEpcs() {
        return pendingEpcs;
    }

    public Map<String, Long> getLastScannedTimes() {
        return lastScannedTimes;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. عمليات السلة (Cart Operations)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 🛒 إضافة قطعة إلى السلة (مع فحص التكرار الذكي).
     *
     * @return نتيجة الإضافة: ADDED أو DUPLICATE
     */
    @NonNull
    public CartAddResult addToCart(@NonNull CartItem newItem) {
        List<CartItem> current = cartListLiveData.getValue();
        if (current == null) current = new ArrayList<>();

        // ✅ فحص التكرار الشامل (EPC + Serial + GTIN)
        if (isItemAlreadyInCart(newItem.epc, newItem.serialNo, newItem.gtin)) {
            Log.d(TAG, "Item already in cart: " + newItem.epc);
            return CartAddResult.DUPLICATE;
        }

        // ✅ إضافة العنصر في المقدمة
        List<CartItem> updated = new ArrayList<>(current.size() + 1);
        updated.add(0, newItem);
        updated.addAll(current);

        cartListLiveData.setValue(updated);

        // ✅ إضافة كل المعرفات إلى scannedEpcs
        if (newItem.epc != null && !newItem.epc.isEmpty()) {
            scannedEpcs.add(newItem.epc);
        }
        if (newItem.serialNo != null && !newItem.serialNo.isEmpty()) {
            scannedEpcs.add(newItem.serialNo);
        }
        if (newItem.gtin != null && !newItem.gtin.isEmpty()) {
            scannedEpcs.add(newItem.gtin);
        }

        // ✅ تحديث المشتقات
        recalculateCartMetrics(updated);

        Log.d(TAG, "Item added to cart: " + newItem.epc + " (total: " + updated.size() + ")");
        return CartAddResult.ADDED;
    }

    /**
     * 🗑️ حذف قطعة من السلة بالموقع.
     */
    public void removeFromCart(int position) {
        List<CartItem> current = cartListLiveData.getValue();
        if (current == null || position < 0 || position >= current.size()) return;

        CartItem removed = current.get(position);

        // ✅ إزالة من scannedEpcs
        if (removed.epc != null) scannedEpcs.remove(removed.epc);
        if (removed.serialNo != null && !removed.serialNo.isEmpty()) {
            scannedEpcs.remove(removed.serialNo);
        }
        if (removed.gtin != null && !removed.gtin.isEmpty()) {
            scannedEpcs.remove(removed.gtin);
        }

        // ✅ إزالة من القائمة
        List<CartItem> updated = new ArrayList<>(current);
        updated.remove(position);

        cartListLiveData.setValue(updated);

        // ✅ تحديث المشتقات
        recalculateCartMetrics(updated);

        Log.d(TAG, "Item removed from cart at position: " + position);
    }

    /**
     * 🧹 تفريغ السلة بالكامل.
     */
    public void clearCart() {
        List<CartItem> current = cartListLiveData.getValue();
        if (current == null || current.isEmpty()) return;

        scannedEpcs.clear();
        pendingEpcs.clear();

        cartListLiveData.setValue(new ArrayList<>());

        cartTotalLiveData.setValue(0.0);
        cartItemCountLiveData.setValue(0);

        Log.d(TAG, "Cart cleared");
    }

    /**
     * 🔍 فحص شامل: هل القطعة موجودة في السلة؟
     */
    public boolean isItemAlreadyInCart(@Nullable String epc,
                                       @Nullable String serialNo,
                                       @Nullable String gtin) {
        List<CartItem> current = cartListLiveData.getValue();
        if (current == null || current.isEmpty()) return false;

        for (CartItem item : current) {
            // ✅ فحص EPC
            if (item.epc != null && epc != null && item.epc.equalsIgnoreCase(epc)) {
                return true;
            }
            // ✅ فحص Serial
            if (item.serialNo != null && serialNo != null && !serialNo.isEmpty()
                    && item.serialNo.equalsIgnoreCase(serialNo)) {
                return true;
            }
            // ✅ فحص GTIN
            if (item.gtin != null && gtin != null && !gtin.isEmpty()
                    && item.gtin.equalsIgnoreCase(gtin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 💰 إعادة حساب المجموع والعدد.
     */
    private void recalculateCartMetrics(@NonNull List<CartItem> items) {
        double total = 0.0;
        for (CartItem item : items) {
            total += item.price;
        }

        cartTotalLiveData.setValue(total);
        cartItemCountLiveData.setValue(items.size());
    }

    /**
     * 💰 حساب المجموع الحالي فورياً (من LiveData).
     */
    public double getCartTotalValue() {
        Double total = cartTotalLiveData.getValue();
        return total != null ? total : 0.0;
    }

    /**
     * 🔢 الحصول على عدد القطع فورياً.
     */
    public int getCartItemCountValue() {
        Integer count = cartItemCountLiveData.getValue();
        return count != null ? count : 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. عمليات Debounce والبحث
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ⏱️ فحص: هل مر وقت كافٍ منذ آخر قراءة لهذا الكود؟
     */
    public boolean isDebounceActive(@NonNull String code) {
        Long lastTime = lastScannedTimes.get(code);
        if (lastTime == null) return false;

        long currentTime = System.currentTimeMillis();
        return (currentTime - lastTime) < SCAN_DEBOUNCE_MS;
    }

    /**
     * ⏱️ تحديث وقت آخر قراءة لهذا الكود.
     */
    public void updateScanTime(@NonNull String code) {
        lastScannedTimes.put(code, System.currentTimeMillis());
    }

    /**
     * 🧹 تنظيف السجلات القديمة (Debounce map).
     */
    public void cleanupOldScanTimes() {
        long currentTime = System.currentTimeMillis();
        long maxAge = SCAN_DEBOUNCE_MS * 4;

        lastScannedTimes.entrySet().removeIf(entry ->
                (currentTime - entry.getValue()) > maxAge);
    }

    /**
     * ⏳ تسجيل أن البحث جارٍ لهذا الكود.
     *
     * @return true إذا كان البحث جديداً، false إذا كان موجوداً بالفعل
     */
    public boolean startPendingSearch(@NonNull String code) {
        return pendingEpcs.add(code);
    }

    /**
     * ⏳ إنهاء البحث لهذا الكود.
     */
    public void finishPendingSearch(@NonNull String code) {
        pendingEpcs.remove(code);
    }

    /**
     * 🔍 هل البحث جارٍ لهذا الكود؟
     */
    public boolean isPendingSearch(@NonNull String code) {
        return pendingEpcs.contains(code);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. إعدادات الحالة (State Setters)
    // ═══════════════════════════════════════════════════════════════════

    public void setRFIDMode(boolean active) {
        isRFIDModeActiveLiveData.setValue(active);
    }

    public boolean isRFIDModeActiveValue() {
        Boolean active = isRFIDModeActiveLiveData.getValue();
        return Boolean.TRUE.equals(active);
    }

    public void setRefundable(boolean refundable) {
        isRefundableLiveData.setValue(refundable);
    }

    public void setScaleWeight(double weight) {
        liveScaleWeightLiveData.setValue(weight);
    }

    public double getScaleWeightValue() {
        Double w = liveScaleWeightLiveData.getValue();
        return w != null ? w : 0.0;
    }

    public void setLastSaleResponseJson(@Nullable String json) {
        lastSaleResponseJsonLiveData.setValue(json);
    }

    @Nullable
    public String getLastSaleResponseJsonValue() {
        return lastSaleResponseJsonLiveData.getValue();
    }

    public void setCheckoutDialogShowing(boolean showing) {
        isCheckoutDialogShowingLiveData.setValue(showing);
    }

    public boolean isCheckoutDialogShowingValue() {
        Boolean showing = isCheckoutDialogShowingLiveData.getValue();
        return Boolean.TRUE.equals(showing);
    }

    public void setSearching(boolean searching) {
        isSearchingLiveData.setValue(searching);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. تحديث الأوزان عند تغير الميزان
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ⚖️ تحديث وزن القطعة الأولى (الأحدث) في السلة عند تغير الميزان.
     *
     * @param newWeight الوزن الجديد
     * @param priceCalculator دالة لحساب السعر (تُمرّر من الـ Fragment)
     * @return true إذا تغير شيء
     */
    public boolean updateTopItemScaleWeight(double newWeight,
                                             @NonNull PriceCalculator priceCalculator) {
        List<CartItem> current = cartListLiveData.getValue();
        if (current == null || current.isEmpty()) return false;

        CartItem topItem = current.get(0);
        if (topItem.scaleWeight == newWeight) return false;

        topItem.scaleWeight = newWeight;

        if (topItem.weight > 0) {
            topItem.price = priceCalculator.calculatePrice(topItem.karat, newWeight);
        }

        // ✅ نُعيد نفس المرجع (نفس القائمة) لإجبار DiffUtil على المقارنة
        cartListLiveData.setValue(new ArrayList<>(current));

        // ✅ نُعيد حساب المجموع
        recalculateCartMetrics(current);

        return true;
    }

    /**
     * 🧮 واجهة لحساب السعر (لتجنب الاعتماد على MiraPriceManager في ViewModel).
     */
    public interface PriceCalculator {
        double calculatePrice(String karat, double weight);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. دورة الحياة
    // ═══════════════════════════════════════════════════════════════════

    @Override
    protected void onCleared() {
        super.onCleared();
        Log.d(TAG, "MiraPosViewModel cleared");

        // ✅ تنظيف صريح
        cartListLiveData.setValue(new ArrayList<>());
        cartTotalLiveData.setValue(0.0);
        cartItemCountLiveData.setValue(0);
        scannedEpcs.clear();
        pendingEpcs.clear();
        lastScannedTimes.clear();
        lastSaleResponseJsonLiveData.setValue(null);
        liveScaleWeightLiveData.setValue(0.0);
        isRFIDModeActiveLiveData.setValue(false);
        isRefundableLiveData.setValue(false);
        isCheckoutDialogShowingLiveData.setValue(false);
        isSearchingLiveData.setValue(false);

        Log.d(TAG, "MiraPosViewModel cleanup complete");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. أنواع مساعدة
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 🎯 نتيجة إضافة قطعة للسلة.
     */
    public enum CartAddResult {
        /** ✅ تمت الإضافة بنجاح */
        ADDED,
        /** ⚠️ القطعة موجودة مسبقاً */
        DUPLICATE
    }
}
