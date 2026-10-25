package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MiraPurchaseInvoiceFragment extends Fragment {

    private TextInputEditText etVendorName, etPaidAmount;
    private MaterialAutoCompleteTextView ddPaymentMethod;
    private TextView tvGrandTotal;
    private RecyclerView rvItems;
    private MaterialButton btnAddItem, btnSaveInvoice;

    private ItemAdapter adapter;
    private final List<ItemModel> itemsList = new ArrayList<>();
    private final String[] karats = {"24K", "22K", "21K", "18K"};
    private final String[] methods = {"نقداً (Cash)", "تحويل بنكي (Bank)", "آجل (Credit)"};
    private final String[] methodsCode = {"cash", "bank", "credit"};

    private double grandTotal = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_purchase_invoice, container, false);

        initViews(view);
        setupRecyclerView();
        setupListeners();

        addItem(); // إضافة سطر أولي

        return view;
    }

    private void initViews(View v) {
        etVendorName = v.findViewById(R.id.etVendorName);
        etPaidAmount = v.findViewById(R.id.etPaidAmount);
        ddPaymentMethod = v.findViewById(R.id.ddPaymentMethod);
        tvGrandTotal = v.findViewById(R.id.tvGrandTotal);
        rvItems = v.findViewById(R.id.rvItems);
        btnAddItem = v.findViewById(R.id.btnAddItem);
        btnSaveInvoice = v.findViewById(R.id.btnSaveInvoice);

        ArrayAdapter<String> methodAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, methods);
        ddPaymentMethod.setAdapter(methodAdapter);
        ddPaymentMethod.setText(methods[0], false);
    }

    private void setupRecyclerView() {
        rvItems.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ItemAdapter();
        rvItems.setAdapter(adapter);
    }

    private void setupListeners() {
        btnAddItem.setOnClickListener(v -> addItem());
        
        etPaidAmount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                // يمكن استخدام هذا الحقل للتحقق من المتبقي إذا أردت
            }
        });

        btnSaveInvoice.setOnClickListener(v -> saveInvoice());
    }

    private void addItem() {
        itemsList.add(new ItemModel());
        adapter.notifyItemInserted(itemsList.size() - 1);
        rvItems.scrollToPosition(itemsList.size() - 1);
    }

    private void calculateGrandTotal() {
        grandTotal = 0;
        for (ItemModel item : itemsList) {
            grandTotal += item.total;
        }
        tvGrandTotal.setText(String.format(Locale.US, "%,.2f", grandTotal));
    }

    private void saveInvoice() {
        String vendor = etVendorName.getText() != null ? etVendorName.getText().toString().trim() : "";
        if (vendor.isEmpty()) vendor = "مورد نقدي";

        if (itemsList.isEmpty() || grandTotal <= 0) {
            Toast.makeText(requireContext(), "الرجاء إدخال بيانات صحيحة للقطع", Toast.LENGTH_SHORT).show();
            return;
        }

        double paidAmount = 0;
        try { paidAmount = Double.parseDouble(etPaidAmount.getText().toString()); } catch (Exception ignored) {}
        
        if (paidAmount > grandTotal) {
            Toast.makeText(requireContext(), "المبلغ المدفوع أكبر من الإجمالي!", Toast.LENGTH_SHORT).show();
            return;
        }

        int methodPos = 0;
        for (int i=0; i<methods.length; i++) {
            if (methods[i].equals(ddPaymentMethod.getText().toString())) { methodPos = i; break; }
        }
        String paymentCode = methodsCode[methodPos];

        JSONArray jsonItems = new JSONArray();
        try {
            for (ItemModel item : itemsList) {
                if (item.weight <= 0 || item.price <= 0) continue; // تجاهل السطور الفارغة
                
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("weight", item.weight);
                obj.put("karat", item.karat.replace("K", ""));
                obj.put("price", item.price);
                obj.put("total", item.total);
                jsonItems.put(obj);
            }
        } catch (Exception ignored) {}

        if (jsonItems.length() == 0) {
            Toast.makeText(requireContext(), "لا توجد قطع صالحة للحفظ", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveInvoice.setEnabled(false);
        btnSaveInvoice.setText("جاري الحفظ...");

        JSONObject payload = new JSONObject();
        try {
            payload.put("vendor_name", vendor);
            payload.put("total_amount", grandTotal);
            payload.put("paid_amount", paidAmount);
            payload.put("payment_method", paymentCode);
            payload.put("items", jsonItems);
        } catch (Exception ignored) {}

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.savePurchaseInvoice(payload);

            requireActivity().runOnUiThread(() -> {
                btnSaveInvoice.setEnabled(true);
                btnSaveInvoice.setText("💾 حفظ الفاتورة");

                if (response != null && response.isSuccess) {
                    Toast.makeText(requireContext(), "✅ تم حفظ فاتورة المشتريات بنجاح", Toast.LENGTH_LONG).show();
                    // مسح الشاشة
                    itemsList.clear();
                    addItem();
                    etVendorName.setText("");
                    etPaidAmount.setText("");
                    calculateGrandTotal();
                    // يمكنك هنا الخروج من الشاشة أو البقاء لإدخال فاتورة أخرى
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ─── نماذج البيانات والأدابتر ───
    static class ItemModel {
        String name = "";
        double weight = 0;
        String karat = "21";
        double price = 0;
        double total = 0;
    }

    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_purchase_invoice_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ItemModel item = itemsList.get(position);

            h.etItemName.setText(item.name);
            h.etWeight.setText(item.weight > 0 ? String.valueOf(item.weight) : "");
            h.etPrice.setText(item.price > 0 ? String.valueOf(item.price) : "");
            h.tvRowTotal.setText(String.format(Locale.US, "%,.2f", item.total));

            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
            h.ddKarat.setAdapter(adapter);
            h.ddKarat.setText(item.karat + "K", false);

            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    item.name = h.etItemName.getText().toString();
                    try { item.weight = Double.parseDouble(h.etWeight.getText().toString()); } catch (Exception e) { item.weight = 0; }
                    try { item.price = Double.parseDouble(h.etPrice.getText().toString()); } catch (Exception e) { item.price = 0; }
                    
                    String k = h.ddKarat.getText().toString().replace("K", "");
                    if (!k.isEmpty()) item.karat = k;
                    
                    item.total = item.weight * item.price;
                    h.tvRowTotal.setText(String.format(Locale.US, "%,.2f", item.total));
                    calculateGrandTotal();
                }
            };

            h.etItemName.removeTextChangedListener(h.watcher);
            h.etWeight.removeTextChangedListener(h.watcher);
            h.etPrice.removeTextChangedListener(h.watcher);
            h.watcher = watcher;
            h.etItemName.addTextChangedListener(watcher);
            h.etWeight.addTextChangedListener(watcher);
            h.etPrice.addTextChangedListener(watcher);

            h.btnRemoveItem.setOnClickListener(v -> {
                if (itemsList.size() > 1) {
                    itemsList.remove(h.getAdapterPosition());
                    notifyItemRemoved(h.getAdapterPosition());
                    calculateGrandTotal();
                } else {
                    Toast.makeText(requireContext(), "يجب أن يبقى سطر واحد على الأقل", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override public int getItemCount() { return itemsList.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextInputEditText etItemName, etWeight, etPrice;
            MaterialAutoCompleteTextView ddKarat;
            TextView tvRowTotal;
            ImageButton btnRemoveItem;
            TextWatcher watcher;
            VH(View v) {
                super(v);
                etItemName = v.findViewById(R.id.etItemName);
                etWeight = v.findViewById(R.id.etWeight);
                etPrice = v.findViewById(R.id.etPrice);
                ddKarat = v.findViewById(R.id.ddKarat);
                tvRowTotal = v.findViewById(R.id.tvRowTotal);
                btnRemoveItem = v.findViewById(R.id.btnRemoveItem);
            }
        }
    }
}
