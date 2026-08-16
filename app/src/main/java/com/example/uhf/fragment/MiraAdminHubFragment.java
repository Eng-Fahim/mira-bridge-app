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

        // 1. إدارة الفروع والشركات (استخدام MiraBranchesManagementFragment بالجمع)
        bindCard(view, R.id.cardAdminBranches, new MiraBranchesManagementFragment(), "BranchesManagement");

        // 2. إعدادات القارئ والتجهيزات
        bindCard(view, R.id.cardAdminReaderSetup, new MiraReaderSetupFragment(), "ReaderSetup");

        // 3. إعدادات البوابات الأمنية
        bindCard(view, R.id.cardAdminGateSetup, new MiraGateSettingsFragment(), "GateSettings");

        // 4. إدارة المستخدمين والصلاحيات (استخدام UsersManagementFragment بدلاً من Profile المفقود)
        bindCard(view, R.id.cardAdminProfile, new UsersManagementFragment(), "UsersManagement");

        // 5. التشفير والمصادقة المتقدمة (NXP UCode DNA)
        bindCard(view, R.id.cardAdminSecurity, new AuthenticateNxpUcodeDnaFragment(), "NxpSecurity");

        // 6. إعدادات الهوائيات والترددات (UHF Set)
        bindCard(view, R.id.cardAdminUhfSet, new UHFSetFragment(), "UHFSet");

        return view;
    }

    private void bindCard(View root, int cardId, Fragment fragment, String tag) {
        if (root == null) return;
        View card = root.findViewById(cardId);
        if (card != null) {
            card.setOnClickListener(v -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
                }
            });
        }
    }
}
