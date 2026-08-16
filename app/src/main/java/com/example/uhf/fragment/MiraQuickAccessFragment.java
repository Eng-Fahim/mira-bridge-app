package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * MIRA Quick Access Customization
 * واجهة تخصيص أزرار الوصول السريع
 */
public class MiraQuickAccessFragment extends Fragment {

    private MiraQuickAccessSettings settings;
    private QuickAccessAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_quick_access, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        settings = MiraQuickAccessSettings.getInstance(requireContext());

        RecyclerView rvActions = view.findViewById(R.id.rvQuickActions);
        MaterialButton btnSave = view.findViewById(R.id.btnSaveQuickAccess);

        rvActions.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new QuickAccessAdapter(settings.getAllActions());
        rvActions.setAdapter(adapter);

        btnSave.setOnClickListener(v -> {
            for (MiraQuickAccessSettings.QuickAction action : adapter.getActions()) {
                settings.setActionEnabled(action.id, action.enabled);
            }
            Toast.makeText(getContext(), "✅ تم حفظ إعدادات الوصول السريع", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * محول قائمة الإجراءات
     */
    private class QuickAccessAdapter extends RecyclerView.Adapter<QuickAccessAdapter.ViewHolder> {

        private final List<MiraQuickAccessSettings.QuickAction> actions;

        QuickAccessAdapter(List<MiraQuickAccessSettings.QuickAction> actions) {
            this.actions = actions;
        }

        public List<MiraQuickAccessSettings.QuickAction> getActions() {
            return actions;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_quick_access_action, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MiraQuickAccessSettings.QuickAction action = actions.get(position);
            holder.tvActionName.setText(action.titleAr);
            holder.switchEnabled.setChecked(action.enabled);
            holder.switchEnabled.setOnCheckedChangeListener((button, checked) -> {
                action.enabled = checked;
            });
        }

        @Override
        public int getItemCount() {
            return actions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView tvActionName;
            SwitchMaterial switchEnabled;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvActionName = itemView.findViewById(R.id.tvActionName);
                switchEnabled = itemView.findViewById(R.id.switchActionEnabled);
            }
        }
    }
}
