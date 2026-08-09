package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraAccountingAdapter;
import com.example.uhf.viewmodel.MiraAccountingViewModel;

public class MiraAccountingFragment extends Fragment {

    private MiraAccountingViewModel viewModel;
    private MiraAccountingAdapter adapter;
    private RecyclerView recyclerView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting, container, false);

        // البحث عن RecyclerView بشكل ديناميكي لتفادي اختلاف مسميات الـ ID في الـ XML
        int rvId = requireContext().getResources().getIdentifier("rvAccountingList", "id", requireContext().getPackageName());
        if (rvId == 0) {
            rvId = requireContext().getResources().getIdentifier("rv_accounting_list", "id", requireContext().getPackageName());
        }

        if (rvId != 0) {
            recyclerView = view.findViewById(rvId);
        } else if (view instanceof RecyclerView) {
            recyclerView = (RecyclerView) view;
        }

        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new MiraAccountingAdapter();
            recyclerView.setAdapter(adapter);
        }

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            viewModel = new ViewModelProvider(this).get(MiraAccountingViewModel.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
