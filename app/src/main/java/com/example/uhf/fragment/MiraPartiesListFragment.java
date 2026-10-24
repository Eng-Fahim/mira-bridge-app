package com.example.uhf.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraPartiesListFragment extends Fragment {

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvParties;
    private TextView tvEmpty;
    private TextInputEditText etSearch;
    private TabLayout tabTypes;
    private ExtendedFloatingActionButton fabNew;

    private String currentType = "";
    private final List<JSONObject> partiesList = new ArrayList<>();
    private PartyAdapter adapter;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_parties_list, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        rvParties = v.findViewById(R.id.rvParties);
        tvEmpty = v.findViewById(R.id.tvEmptyParties);
        etSearch = v.findViewById(R.id.etPartySearch);
        tabTypes = v.findViewById(R.id.tabPartyTypes);
        fabNew = v.findViewById(R.id.fabNewParty);

        rvParties.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new PartyAdapter(partiesList);
        rvParties.setAdapter(adapter);

        tabTypes.addTab(tabTypes.newTab().setText("الكل"));
        tabTypes.addTab(tabTypes.newTab().setText("👤 عملاء"));
        tabTypes.addTab(tabTypes.newTab().setText("🏭 موردين"));
        tabTypes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0: currentType = ""; break;
                    case 1: currentType = "customer"; break;
                    case 2: currentType = "vendor"; break;
                }
                loadParties();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        fabNew.setOnClickListener(view -> showCreatePartyDialog());

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                mainHandler.removeCallbacksAndMessages(null);
                mainHandler.postDelayed(() -> loadParties(), 500);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        loadParties();
        return v;
    }

    private void loadParties() {
        executor.execute(() -> {
            String search = etSearch.getText() != null ? etSearch.getText().toString().trim() : "";
            MiraApiClient.ApiResponse res = apiClient.getPartiesList(currentType, search);

            mainHandler.post(() -> {
                partiesList.clear();
                if (res.isSuccess && res.data != null) {
                    JSONArray data = res.data.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject p = data.optJSONObject(i);
                            if (p != null) partiesList.add(p);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(partiesList.isEmpty() ? View.VISIBLE : View.GONE);
                rvParties.setVisibility(partiesList.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void showCreatePartyDialog() {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_create_party);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextInputLayout tilName = dialog.findViewById(R.id.tilNewPartyName);
        TextInputLayout tilPhone = dialog.findViewById(R.id.tilNewPartyPhone);
        TextInputLayout tilEmail = dialog.findViewById(R.id.tilNewPartyEmail);
        TextInputLayout tilCompany = dialog.findViewById(R.id.tilNewPartyCompany);
        android.widget.Spinner spType = dialog.findViewById(R.id.spNewPartyType);
        android.widget.Button btnSave = dialog.findViewById(R.id.btnSaveNewParty);
        android.widget.Button btnCancel = dialog.findViewById(R.id.btnCancelNewParty);

        String[] types = {"👤 عميل", "🏭 مورد", "🌐 كلاهما"};
        spType.setAdapter(new android.widget.ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, types));

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("name", tilName.getEditText().getText().toString().trim());
                payload.put("phone", tilPhone.getEditText().getText().toString().trim());
                payload.put("email", tilEmail.getEditText().getText().toString().trim());
                payload.put("company_name", tilCompany.getEditText().getText().toString().trim());
                int pos = spType.getSelectedItemPosition();
                payload.put("party_type", pos == 0 ? "customer" : (pos == 1 ? "vendor" : "both"));

                if (payload.optString("name").isEmpty()) {
                    tilName.setError("الاسم مطلوب");
                    return;
                }

                executor.execute(() -> {
                    MiraApiClient.ApiResponse res = apiClient.createParty(payload);
                    mainHandler.post(() -> {
                        if (res.isSuccess) {
                            Toast.makeText(getContext(), "✅ تم إنشاء الحساب", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            loadParties();
                        } else {
                            Toast.makeText(getContext(), "❌ " + (res.error != null ? res.error : "فشل"), Toast.LENGTH_LONG).show();
                        }
                    });
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }

    private class PartyAdapter extends RecyclerView.Adapter<PartyAdapter.VH> {
        private final List<JSONObject> items;
        PartyAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_party, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject p = items.get(position);
            String type = p.optString("party_type", "customer");
            String name = p.optString("name", "");
            String phone = p.optString("phone", "");
            double balance = p.optDouble("current_balance_money", 0);
            double gold = p.optDouble("current_balance_gold_24k_grams", 0);
            int id = p.optInt("id", 0);

            h.tvName.setText(name);
            h.tvPhone.setText(phone.isEmpty() ? "—" : "📞 " + phone);

            String typeLabel = "customer".equals(type) ? "👤 عميل" : ("vendor".equals(type) ? "🏭 مورد" : "🌐 كلاهما");
            h.tvType.setText(typeLabel);
            h.tvIcon.setText("customer".equals(type) ? "👤" : "🏭");

            String balText;
            if (Math.abs(balance) < 0.01) {
                balText = "0.00 YER";
                h.tvBalance.setTextColor(0xFF78909C);
            } else if (balance > 0) {
                balText = String.format(Locale.US, "%,.2f YER", balance);
                h.tvBalance.setTextColor(0xFF16A34A); // أخضر (له رصيد)
            } else {
                balText = String.format(Locale.US, "%,.2f YER", Math.abs(balance));
                h.tvBalance.setTextColor(0xFFDC2626); // أحمر (عليه رصيد)
            }
            h.tvBalance.setText(balText);
            h.tvBalanceLabel.setText(balance >= 0 ? "الرصيد له" : "الرصيد عليه");

            h.itemView.setOnClickListener(v -> {
                if (id > 0 && getActivity() instanceof UHFMainActivity) {
                    MiraPartyStatementFragment frag = MiraPartyStatementFragment.newInstance(id);
                    ((UHFMainActivity) getActivity()).loadFragment(frag, "PartyStatement");
                }
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone, tvType, tvBalance, tvBalanceLabel, tvIcon;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvPartyName);
                tvPhone = v.findViewById(R.id.tvPartyPhone);
                tvType = v.findViewById(R.id.tvPartyType);
                tvBalance = v.findViewById(R.id.tvPartyBalance);
                tvBalanceLabel = v.findViewById(R.id.tvPartyBalanceLabel);
                tvIcon = v.findViewById(R.id.tvPartyIcon);
            }
        }
    }
}
