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
        
        recyclerView = view.findViewById(R.id.rvAccountingList);
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
        viewModel = new ViewModelProvider(this).get(MiraAccountingViewModel.class);

        if (viewModel != null && adapter != null) {
            viewModel.getItemsLiveData().observe(getViewLifecycleOwner(), items -> {
                if (items != null) {
                    adapter.setItems(items);
                }
            });
            viewModel.loadAccountingData();
        }
    }
}
