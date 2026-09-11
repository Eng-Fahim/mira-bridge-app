package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.uhf.R;
import com.example.uhf.manager.IdentitySyncManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

/**
 * 🏗️ EditSyncItemDialog - نافذة تعديل سجل المزامنة
 * 
 * الميزات:
 * - تعديل كل الحقول القابلة للتعديل
 * - التحقق من صحة المدخلات
 * - إرجاع السجل المُحدَّث عبر Callback
 * 
 * @author Mira Team
 * @version 1.0
 */
public class EditSyncItemDialog extends Dialog {

    private static final String TAG = "EditSyncItemDialog";

    public interface OnSaveListener {
        void onSave(IdentitySyncManager.SyncLogItem updatedItem);
    }

    private final IdentitySyncManager.SyncLogItem originalItem;
    private final boolean isArabic;
    private final OnSaveListener saveListener;

    // Views
    private TextInputEditText etTitle, etEpc, etWeight, etPrice, etKarat, 
            etCategory, etLocation;
    private MaterialButton btnCancel, btnSave;

    public EditSyncItemDialog(@NonNull Context context,
                              IdentitySyncManager.SyncLogItem item,
                              boolean isArabic,
                              OnSaveListener listener) {
        super(context);
        this.originalItem = item;
        this.isArabic = isArabic;
        this.saveListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_edit_sync_item);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        initViews();
        populateFields();
        setupListeners();
    }

    private void initViews() {
        etTitle = findViewById(R.id.etTitle);
        etEpc = findViewById(R.id.etEpc);
        etWeight = findViewById(R.id.etWeight);
        etPrice = findViewById(R.id.etPrice);
        etKarat = findViewById(R.id.etKarat);
        etCategory = findViewById(R.id.etCategory);
        etLocation = findViewById(R.id.etLocation);
        btnCancel = findViewById(R.id.btnCancel);
        btnSave = findViewById(R.id.btnSave);
    }

    private void populateFields() {
        if (originalItem == null) return;

        try {
            if (etTitle != null) etTitle.setText(originalItem.title);
            if (etEpc != null) etEpc.setText(originalItem.epc);
            if (etWeight != null) etWeight.setText(String.valueOf(originalItem.grossWeight));
            if (etPrice != null) etPrice.setText(String.valueOf(originalItem.priceUSD));
            if (etKarat != null) etKarat.setText(originalItem.karat);
            if (etCategory != null) etCategory.setText(originalItem.category);
            if (etLocation != null) etLocation.setText(originalItem.location);
        } catch (Exception e) {
            Log.e(TAG, "populateFields error: " + e.getMessage());
        }
    }

    private void setupListeners() {
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismiss());
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> handleSave());
        }

        // إغلاق بالضغط خارج النافذة
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    private void handleSave() {
        try {
            // ✅ قراءة القيم
            String newTitle = getText(etTitle);
            String newWeightStr = getText(etWeight);
            String newPriceStr = getText(etPrice);
            String newKarat = getText(etKarat);
            String newCategory = getText(etCategory);
            String newLocation = getText(etLocation);

            // ✅ التحقق
            if (newTitle.isEmpty()) {
                showError(isArabic ? "العنوان مطلوب" : "Title is required");
                return;
            }

            double newWeight;
            try {
                newWeight = Double.parseDouble(newWeightStr);
                if (newWeight <= 0) {
                    showError(isArabic ? "الوزن يجب أن يكون أكبر من صفر" : 
                                        "Weight must be greater than zero");
                    return;
                }
            } catch (NumberFormatException e) {
                showError(isArabic ? "وزن غير صحيح" : "Invalid weight");
                return;
            }

            double newPrice;
            try {
                newPrice = Double.parseDouble(newPriceStr);
                if (newPrice < 0) {
                    showError(isArabic ? "السعر لا يمكن أن يكون سالباً" : 
                                        "Price cannot be negative");
                    return;
                }
            } catch (NumberFormatException e) {
                showError(isArabic ? "سعر غير صحيح" : "Invalid price");
                return;
            }

            // ✅ بناء الـ Payload المحدّث
            JSONObject newPayload = new JSONObject(originalItem.payload.toString());
            newPayload.put("title", newTitle);
            newPayload.put("gross_weight", newWeight);
            newPayload.put("retail_price_usd", newPrice);
            newPayload.put("retail_price", newPrice);
            newPayload.put("purity_code", newKarat);
            newPayload.put("category", newCategory);
            newPayload.put("location", newLocation);

            // ✅ تحديث الـ Model
            originalItem.title = newTitle;
            originalItem.grossWeight = newWeight;
            originalItem.priceUSD = newPrice;
            originalItem.karat = newKarat;
            originalItem.category = newCategory;
            originalItem.location = newLocation;
            originalItem.payload = newPayload;

            // ✅ إبلاغ الـ Listener
            if (saveListener != null) {
                saveListener.onSave(originalItem);
            }

            dismiss();

        } catch (Exception e) {
            Log.e(TAG, "handleSave error: " + e.getMessage(), e);
            showError(isArabic ? "خطأ: " + e.getMessage() : "Error: " + e.getMessage());
        }
    }

    private String getText(TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private void showError(String message) {
        Toast.makeText(getContext(), "⚠️ " + message, Toast.LENGTH_SHORT).show();
    }
}
