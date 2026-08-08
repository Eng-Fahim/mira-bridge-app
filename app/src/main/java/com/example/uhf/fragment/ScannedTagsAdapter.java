package com.example.uhf.fragment;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import java.util.ArrayList;
import java.util.List;

public class ScannedTagsAdapter extends RecyclerView.Adapter<ScannedTagsAdapter.TagViewHolder> {

    private List<ScannedItemModel> itemList = new ArrayList<>();

    public void updateData(List<ScannedItemModel> newList) {
        this.itemList = new ArrayList<>(newList);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scanned_tag, parent, false);
        return new TagViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
        try {
            if (position < itemList.size()) {
                ScannedItemModel item = itemList.get(position);
                holder.tvTagTitle.setText(item.getCategory());
                holder.tvTagEPC.setText(item.getEpc());
                holder.tvTagDetails.setText("الوزن: " + item.getWeight() + " جم | التكرار: " + item.getReadCount());
                holder.tvTagStatus.setText(item.getStatus());
                holder.tvTagTime.setText("--:--");
            }
        } catch (Exception e) {
            Log.e("ScannedTagsAdapter", "Bind error", e);
        }
    }

    @Override
    public int getItemCount() {
        return itemList != null ? itemList.size() : 0;
    }

    static class TagViewHolder extends RecyclerView.ViewHolder {
        TextView tvTagTitle, tvTagEPC, tvTagDetails, tvTagStatus, tvTagTime;

        public TagViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTagTitle = itemView.findViewById(R.id.tvTagTitle);
            tvTagEPC = itemView.findViewById(R.id.tvTagEPC);
            tvTagDetails = itemView.findViewById(R.id.tvTagDetails);
            tvTagStatus = itemView.findViewById(R.id.tvTagStatus);
            tvTagTime = itemView.findViewById(R.id.tvTagTime);
        }
    }

    public static class SafeLinearLayoutManager extends LinearLayoutManager {
        public SafeLinearLayoutManager(Context context) {
            super(context);
        }

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            try {
                super.onLayoutChildren(recycler, state);
            } catch (Exception e) {
                Log.e("SafeLayoutManager", "Prevented layout crash", e);
            }
        }
    }
}
