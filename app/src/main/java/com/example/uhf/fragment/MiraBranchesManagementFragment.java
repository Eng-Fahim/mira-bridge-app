package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;

public class MiraBranchesManagementFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_branches_simple, container, false);

        TextView tvTitle = view.findViewById(R.id.tvBranchMgmtTitle);
        Button btnAdd = view.findViewById(R.id.btnAddBranchSimple);
        LinearLayout branchesContainer = view.findViewById(R.id.branchesContainer);

        // إضافة فروع افتراضية
        addBranchCard(branchesContainer, "🏢 الفرع الرئيسي", "MAIN", "📍 صنعاء - شارع الزبيري", true);
        addBranchCard(branchesContainer, "🏢 فرع عدن", "ADN", "📍 عدن - خور مكسر", true);
        addBranchCard(branchesContainer, "🏢 فرع تعز", "TAZ", "📍 تعز - شارع جمال", false);

        btnAdd.setOnClickListener(v -> {
            Toast.makeText(getContext(), "جاري فتح نافذة إضافة فرع...", Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    private void addBranchCard(LinearLayout container, String name, String code, String address, boolean active) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.item_branch, container, false);
        
        TextView tvName = card.findViewById(R.id.tvBranchName);
        TextView tvCode = card.findViewById(R.id.tvBranchCode);
        TextView tvAddress = card.findViewById(R.id.tvBranchAddress);
        View vStatus = card.findViewById(R.id.vBranchStatus);

        if (tvName != null) tvName.setText(name);
        if (tvCode != null) tvCode.setText("🏷️ " + code);
        if (tvAddress != null) tvAddress.setText(address);
        if (vStatus != null) vStatus.setBackgroundResource(active ? R.drawable.circle_green : R.drawable.circle_red);

        card.setOnClickListener(v -> {
            Toast.makeText(getContext(), "تعديل: " + name, Toast.LENGTH_SHORT).show();
        });

        container.addView(card);
    }
}
