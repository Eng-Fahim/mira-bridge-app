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
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.manager.IdentitySyncManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

/**
 * EditSyncItemDialog - الإصدار 2.0
 *
 * ✅ يتوافق مع SyncLogItem الجديد (immutable)
 * ✅ يستخدم Builder لإنشاء نسخة جديدة بدلاً من تعديل الأصل
 *
 * @author Mira Team
 * @version 2.0
 */
public class EditSyncItemDialog extends Dialog {

    private static final String TAG = "EditSyncItemDialog";

    public interface OnSaveListener {
        void onSave(@NonNull IdentitySyncManager.SyncLogItem updatedItem);
    }

    private final IdentitySyncManager.SyncLogItem originalItem;
    private final boolean isArabic;
    private final OnSaveListener saveListener;

    // Views
    private TextInputEditText etTitle, etEpc, etWeight, etPrice, etKarat,
            etCategory, etLocation;
    private MaterialButton btnCancel, btnSave;

    public EditSyncItemDialog(@NonNull Context context,
                              @Nullable IdentitySyncManager.SyncLogItem item,
                              boolean isArabic,
                              @Nullable OnSaveListener listener) {
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

            // ✅ EPC غير قابل للتعديل
            if (etEpc != null) {
                etEpc.setEnabled(false);
                etEpc.setFocusable(false);
            }
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

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    private void handleSave() {
        try {
            if (originalItem == null) {
                showError(isArabic ? "السجل غير موجود" : "Item not found");
                return;
            }

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

            // ==================== ✅ بناء Payload جديد ====================
            JSONObject newPayload;
            try {
                newPayload = new JSONObject(originalItem.payload.toString());
            } catch (Exception e) {
                Log.w(TAG, "Failed to copy payload — creating new", e);
                newPayload = new JSONObject();
            }

            newPayload.put("title", newTitle);
            newPayload.put("gross_weight", newWeight);
            newPayload.put("retail_price_usd", newPrice);
            newPayload.put("retail_price", newPrice);
            newPayload.put("purity_code", newKarat);
            newPayload.put("category", newCategory);
            newPayload.put("location", newLocation);

            // ==================== ✅ [الإصلاح] استخدام Builder ====================
            // بدلاً من تعديل originalItem (immutable)
            IdentitySyncManager.SyncLogItem updatedItem =
                    new IdentitySyncManager.SyncLogItem.Builder()
                            .id(originalItem.id)
                            .epc(originalItem.epc)
                            .title(newTitle)
                            .karat(newKarat)
                            .grossWeight(newWeight)
                            .priceUSD(newPrice)
                            .category(newCategory)
                            .location(newLocation)
                            .branchId(originalItem.branchId)
                            .userId(originalItem.userId)
                            .savedAt(originalItem.savedAt)
                            .lastAttempt(originalItem.lastAttempt)
                            .nextRetryAt(originalItem.nextRetryAt)
                            .attempts(originalItem.attempts)
                            .status(originalItem.status)
                            .lastError(originalItem.lastError)
                            .payload(newPayload)
                            .build();

            // ✅ إبلاغ الـ Listener
            if (saveListener != null) {
                try {
                    saveListener.onSave(updatedItem);
                } catch (Exception e) {
                    Log.e(TAG, "saveListener.onSave error", e);
                }
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
        try {
            Toast.makeText(getContext(), "⚠️ " + message, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
