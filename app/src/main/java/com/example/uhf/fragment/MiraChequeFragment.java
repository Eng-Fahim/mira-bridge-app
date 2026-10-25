package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraChequeFragment extends Fragment {

    private RecyclerView rvCheques;
    private ExtendedFloatingActionButton fabAdd;
    private ProgressBar pbLoading;
    private ChipGroup chipGroupFilter;

    private ChequeAdapter adapter;
    private List<JSONObject> chequeList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // حسابات البنوك (يُفضل جلبها ديناميكياً لكن نضع الأساس هنا)
    private static final String[] BANK_NAMES = {"صندوق (نقدي)", "بنك التضامن (112001)", "بنك الكريمي (112004)", "البنك الأهلي (112003)"};
    private static final int[] BANK_IDS = {1, 25, 28, 27}; // IDs من جدول الحسابات

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_cheques, container, false);

        rvCheques = v.findViewById(R.id.rvCheques);
        fabAdd = v.findViewById(R.id.fabAddCheque);
        pbLoading = v.findViewById(R.id.pbLoading);
        chipGroupFilter = v.findViewById(R.id.chipGroupFilter);

        rvCheques.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChequeAdapter();
        rvCheques.setAdapter(adapter);

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> fetchCheques());
        fabAdd.setOnClickListener(view -> showAddChequeDialog());

        fetchCheques();
        return v;
    }

    private void fetchCheques() {
        pbLoading.setVisibility(View.VISIBLE);
        String type = "";
        if (chipGroupFilter.getCheckedChipId() == R.id.chipIncoming) type = "incoming";
        else if (chipGroupFilter.getCheckedChipId() == R.id.chipOutgoing) type = "outgoing";

        final String finalType = type;
        new Thread(() -> {
            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).getCheques(finalType, null);
            mainHandler.post(() -> {
                pbLoading.setVisibility(View.GONE);
                if (res != null && res.isSuccess && res.data != null) {
                    JSONArray arr = res.data.optJSONArray("data");
                    chequeList.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            try { chequeList.add(arr.getJSONObject(i)); } catch (Exception ignored) {}
                        }
                    }
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(getContext(), "فشل تحميل الشيكات", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void showAddChequeDialog() {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_cheque, null);
        TextInputEditText etNumber = v.findViewById(R.id.etChequeNumber);
        TextInputEditText etAmount = v.findViewById(R.id.etAmount);
        TextInputEditText etParty = v.findViewById(R.id.etPartyName);
        TextInputEditText etBank = v.findViewById(R.id.etBankName);
        TextInputEditText etDate = v.findViewById(R.id.etDate);
        MaterialAutoCompleteTextView spinnerType = v.findViewById(R.id.spinnerType);

        String[] types = {"شيك وارد (من عميل)", "شيك صادر (لمورد)"};
        spinnerType.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, types));
        spinnerType.setText(types[0], false);

        etDate.setText(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime()));
        etDate.setOnClickListener(view -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(requireContext(), (dp, y, m, d) -> {
                etDate.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d));
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("إضافة شيك جديد")
                .setView(v)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("cheque_number", etNumber.getText().toString());
                        payload.put("amount", Double.parseDouble(etAmount.getText().toString()));
                        payload.put("cheque_date", etDate.getText().toString());
                        payload.put("bank_name", etBank.getText().toString());
                        payload.put("drawer_name", etParty.getText().toString());
                        payload.put("cheque_type", spinnerType.getText().toString().contains("وارد") ? "incoming" : "outgoing");
                        
                        saveCheque(payload);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "تأكد من إدخال المبلغ كأرقام", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void saveCheque(JSONObject payload) {
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).createCheque(payload);
            mainHandler.post(() -> {
                pbLoading.setVisibility(View.GONE);
                if (res != null && res.isSuccess) {
                    Toast.makeText(getContext(), "✅ تم حفظ الشيك", Toast.LENGTH_SHORT).show();
                    fetchCheques();
                } else {
                    String err = (res != null && res.error != null) ? res.error : "فشل الحفظ";
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("⛔ خطأ")
                            .setMessage(err)
                            .setPositiveButton("حسناً", null).show();
                }
            });
        }).start();
    }

    // ═══════════════════════════════════════════════════════════
    //  Adapter الداخلي
    // ═══════════════════════════════════════════════════════════
    private class ChequeAdapter extends RecyclerView.Adapter<ChequeAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cheque, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject c = chequeList.get(position);
            h.tvNumber.setText("رقم: " + c.optString("cheque_number"));
            h.tvParty.setText(c.optString("party_name", c.optString("drawer_name", "غير محدد")));
            h.tvAmount.setText(String.format(Locale.US, "%,.2f %s", c.optDouble("amount", 0), c.optString("currency", "YER")));
            h.tvDate.setText(c.optString("cheque_date"));

            String status = c.optString("status", "pending");
            int color = Color.parseColor("#F39C12"); // pending
            String statusAr = "معلق ⏳";
            
            if (status.equals("deposited")) { color = Color.parseColor("#3498DB"); statusAr = "مودع 🏦"; }
            else if (status.equals("collected") || status.equals("paid")) { color = Color.parseColor("#27AE60"); statusAr = "محصل ✅"; }
            else if (status.equals("returned")) { color = Color.parseColor("#E74C3C"); statusAr = "مرتجع ❌"; }
            else if (status.equals("cancelled")) { color = Color.parseColor("#7F8C8D"); statusAr = "ملغي 🚫"; }

            h.tvStatus.setText(statusAr);
            h.tvStatus.setBackgroundColor(color);

            h.itemView.setOnClickListener(v -> {
                if (status.equals("pending") && c.optString("cheque_type").equals("incoming")) {
                    showDepositDialog(c.optInt("id"));
                } else if (status.equals("deposited") || status.equals("pending")) {
                    showReturnDialog(c.optInt("id"));
                }
            });
        }

        @Override public int getItemCount() { return chequeList.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvParty, tvAmount, tvDate, tvStatus;
            public VH(@NonNull View itemView) {
                super(itemView);
                tvNumber = itemView.findViewById(R.id.tvChequeNumber);
                tvParty = itemView.findViewById(R.id.tvPartyName);
                tvAmount = itemView.findViewById(R.id.tvAmount);
                tvDate = itemView.findViewById(R.id.tvDate);
                tvStatus = itemView.findViewById(R.id.tvStatus);
            }
        }
    }

    private void showDepositDialog(int id) {
        View v = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_1, null);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("إيداع الشيك في البنك")
                .setItems(BANK_NAMES, (dialog, which) -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("bank_account_id", BANK_IDS[which]);
                        executeAction(id, "deposit", payload);
                    } catch (Exception ignored) {}
                }).show();
    }

    private void showReturnDialog(int id) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("⚠️ ارتداد الشيك")
                .setMessage("هل أنت متأكد من ارتداد هذا الشيك؟ سيتم عكس القيد وإرجاع المبلغ للعميل.")
                .setPositiveButton("تأكيد الارتداد", (dialog, which) -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("reason", "رصيد غير كافٍ");
                        payload.put("return_fee", 0);
                        executeAction(id, "return", payload);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void executeAction(int id, String action, JSONObject payload) {
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse res = action.equals("deposit") ? client.depositCheque(id, payload) : client.returnCheque(id, payload);
            mainHandler.post(() -> {
                pbLoading.setVisibility(View.GONE);
                if (res != null && res.isSuccess) {
                    Toast.makeText(getContext(), "✅ تمت العملية بنجاح", Toast.LENGTH_SHORT).show();
                    fetchCheques();
                } else {
                    Toast.makeText(getContext(), "❌ فشل", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
}
