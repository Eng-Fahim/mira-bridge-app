package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.model.CartItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

public class POSManager {

    private static final String TAG = "POSManager";
    private static final String PREFS_OFFLINE = "pos_offline_sales_branch_";
    
    public interface CheckoutCallback {
        void onSuccess(MiraApiClient.ApiResponse response);
        void onOfflineSuccess(String saleId);
        void onError(String error);
    }

    private Context context;
    private MiraApiClient apiClient;
    private boolean isArabic;

    public POSManager(Context context) {
        this.context = context;
        this.apiClient = MiraApiClient.getInstance(context);
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }

    // ✅ تنفيذ البيع
    public void executeCheckout(List<CartItem> cartList, POSCheckoutDialog.POSCheckoutData data, 
                                int branchId, CheckoutCallback callback) {
        try {
            JSONObject payload = createPayload(cartList, data, branchId);
            String localSaleId = saveSaleLocally(payload, data, cartList.size(), branchId);
            
            // ✅ استخدام Checkout مع Timeout طويل (60 ثانية)
            MiraApiClient.ApiResponse response = apiClient.checkoutWithLongTimeout(payload);
            
            Log.d(TAG, "=== CHECKOUT RESPONSE ===");
            Log.d(TAG, "Response code: " + (response != null ? response.code : "null"));
            Log.d(TAG, "Is success: " + (response != null && response.isSuccess));
            Log.d(TAG, "Is network error: " + (response != null && response.isNetworkError));
            Log.d(TAG, "Error: " + (response != null ? response.error : "null"));
            
            if (response != null && response.isSuccess) {
                // ✅ نجح — احذف السجل المحلي
                removeSaleFromLocal(localSaleId, branchId);
                callback.onSuccess(response);
                
            } else if (response != null && response.isNetworkError) {
                // ✅ تمييز بين Timeout وبين عدم الاتصال
                String err = response.error != null ? response.error : "";
                
                if (err.contains("انتهت المهلة") || err.toLowerCase().contains("timeout")) {
                    // ⏱️ Timeout — قد يكون البيع نجح على السيرفر!
                    // ❌ لا تحفظ Offline — أبلغ المستخدم
                    Log.w(TAG, "⚠️ CHECKOUT TIMEOUT — Sale may have succeeded server-side");
                    callback.onError(isArabic 
                        ? "⚠️ انتهت مهلة الاتصال — يرجى التحقق من سجل المبيعات قبل إعادة المحاولة"
                        : "⚠️ Connection timeout — Check sales history before retrying");
                        
                } else {
                    // 🌐 UnknownHost / ConnectException — لم يصل الطلب
                    // ✅ آمن للحفظ Offline
                    Log.i(TAG, "📴 CHECKOUT OFFLINE (network unreachable) — Sale saved locally");
                    callback.onOfflineSuccess(localSaleId);
                }
                
            } else if (response == null) {
                // ✅ لم يصل الطلب — آمن للحفظ
                Log.w(TAG, "⚠️ NULL RESPONSE — Saving offline");
                callback.onOfflineSuccess(localSaleId);
                
            } else {
                // ❌ السيرفر رفض الطلب (400, 500) — احذفه من المحلي
                Log.e(TAG, "❌ SERVER REJECTED — " + response.code + ": " + response.error);
                removeSaleFromLocal(localSaleId, branchId);
                callback.onError(response.error != null ? response.error : "Unknown error");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "executeCheckout error: " + e.getMessage(), e);
            callback.onError(e.getMessage());
        }
    }

    private JSONObject createPayload(List<CartItem> cartList,
                                     POSCheckoutDialog.POSCheckoutData data,
                                     int branchId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("action", "COMPLETE_POS_SALE");
        payload.put("branch_id", branchId);

        // ✅ إضافة معرف العملية (local_uuid) لمنع التكرار
        if (data.localUuid == null || data.localUuid.isEmpty()) {
            data.localUuid = UUID.randomUUID().toString();
        }
        payload.put("local_uuid", data.localUuid);

        // ✅ على الجذر (للتوافق مع الكود القديم)
        payload.put("customer_name", data.customerName);
        payload.put("customer_phone", data.customerPhone);

        // ✅ داخل customer object (للفهم الموحد)
        JSONObject customer = new JSONObject();
        customer.put("name", data.customerName);
        customer.put("phone", data.customerPhone);
        customer.put("email", data.customerEmail != null ? data.customerEmail : "");
        customer.put("address", data.customerAddress != null ? data.customerAddress : "");
        customer.put("notes", data.customerNotes != null ? data.customerNotes : "");
        customer.put("payment_method", data.paymentMethod != null ? data.paymentMethod : "cash");
        customer.put("discount_amount_usd", data.discountAmount); // ✅ إضافة الخصم
        payload.put("customer", customer);

        // ✅ العناصر
        JSONArray itemsArr = new JSONArray();
        for (CartItem item : cartList) {
            JSONObject obj = new JSONObject();
            obj.put("epc", item.epc);
            obj.put("title", item.name);
            obj.put("karat", item.karat);
            obj.put("weight", item.scaleWeight > 0 ? item.scaleWeight : item.weight);
            obj.put("price_usd", item.price);
            obj.put("item_id", item.itemId);
            obj.put("serial_no", item.serialNo);
            itemsArr.put(obj);
        }
        payload.put("items", itemsArr);

        // ═══════════════════════════════════════════════════════
        // ✅ [جديد] إرسال مصفوفة المدفوعات المتعددة (عملات + طرق)
        // ═══════════════════════════════════════════════════════
        JSONArray paymentsArray = new JSONArray();
        if (data.payments != null && data.payments.length() > 0) {
            paymentsArray = data.payments;
        } else {
            // احتياطي: إذا لم يتم إرسال مصفوفة، نرسل دفعة واحدة
            JSONObject pObj = new JSONObject();
            pObj.put("currency", "YER");
            pObj.put("amount", data.finalAmount);
            pObj.put("exchange_rate", 1.0);
            pObj.put("base_amount", data.finalAmount);
            pObj.put("method", data.paymentMethod != null ? data.paymentMethod : "cash");
            paymentsArray.put(pObj);
        }
        payload.put("payments", paymentsArray);

        // ✅ إضافة الحقول القديمة للتوافق مع السيرفر (payment object)
        JSONObject payment = new JSONObject();
        payment.put("subtotal_usd", data.originalTotal);
        payment.put("discount_amount_usd", data.discountAmount);
        payment.put("final_amount_usd", data.finalAmount);
        payment.put("payment_method", data.paymentMethod != null ? data.paymentMethod : "cash");
        // إضافة إجمالي المدفوعات كـ base_amount للتوافق مع التحققات القديمة إن وجدت
        double totalBase = 0;
        for (int i = 0; i < paymentsArray.length(); i++) {
            totalBase += paymentsArray.optJSONObject(i).optDouble("base_amount", 0);
        }
        payment.put("total_paid_base", totalBase);
        payload.put("payment", payment);

        return payload;
    }

    private String saveSaleLocally(JSONObject payload, POSCheckoutDialog.POSCheckoutData data, 
                                   int itemCount, int branchId) {
        try {
            String saleId = "SALE_" + System.currentTimeMillis();
            JSONObject localSale = new JSONObject();
            localSale.put("sale_id", saleId);
            localSale.put("payload", payload);
            localSale.put("customer_name", data.customerName);
            localSale.put("payment_method", data.paymentMethod);
            localSale.put("final_amount", data.finalAmount);
            localSale.put("item_count", itemCount);
            localSale.put("created_at", System.currentTimeMillis());
            localSale.put("branch_id", branchId);
            localSale.put("sync_status", "pending");
            
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("pending_sales", "[]");
            JSONArray arr = new JSONArray(existingJson);
            arr.put(localSale);
            prefs.edit().putString("pending_sales", arr.toString()).apply();
            
            return saleId;
        } catch (Exception e) {
            Log.e(TAG, "saveSaleLocally error: " + e.getMessage());
            return "";
        }
    }

    public void removeSaleFromLocal(String saleId, int branchId) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existingJson = prefs.getString("pending_sales", "[]");
            JSONArray arr = new JSONArray(existingJson);
            JSONArray newArr = new JSONArray();
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale != null && !sale.optString("sale_id").equals(saleId)) {
                    newArr.put(sale);
                }
            }
            
            prefs.edit().putString("pending_sales", newArr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "removeSaleFromLocal error: " + e.getMessage());
        }
    }
}
