package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;

public class MiraAdminHubFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_admin_hub, container, false);

        // 1. الأجهزة والشبكة
        bindCard(view, R.id.cardAdminSettings, new UHFSetFragment(), "Settings");
        bindCard(view, R.id.cardAdminReader, new MiraReaderSetupFragment(), "ReaderSetup");
        bindCard(view, R.id.cardAdminGateSettings, new MiraGateSettingsFragment(), "GateSettings");
        bindCard(view, R.id.cardAdminGoldRates, new MiraGoldCurrencyFragment(), "GoldRates");

        // 2. الهيكل والإدارة
        bindCard(view, R.id.cardAdminBranches, new MiraBranchManagementFragment(), "Branches");
        bindCard(view, R.id.cardAdminUsers, new UsersManagementFragment(), "Users");
        bindCard(view, R.id.cardAdminProfile, new MiraProfileFragment(), "Profile");

        return view;
    }

    private void bindCard(View root, int resId, Fragment fragment, String tag) {
        View card = root.findViewById(resId);
        if (card != null) {
            card.setOnClickListener(v -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
                }
            });
        }
    }
}
