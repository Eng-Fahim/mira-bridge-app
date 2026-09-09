package com.example.uhf.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.uhf.R;
import com.example.uhf.model.NotificationModel;
import java.util.List;

public class UserNotificationAdapter extends RecyclerView.Adapter<UserNotificationAdapter.ViewHolder> {

    private List<NotificationModel> notifications;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(NotificationModel notification);
    }

    public UserNotificationAdapter(List<NotificationModel> notifications, OnItemClickListener listener) {
        this.notifications = notifications;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_notification, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NotificationModel notification = notifications.get(position);
        
        // استبدل دوال getTitle و getBody حسب المتوفر في NotificationModel لديك
        holder.tvTitle.setText(notification.getId()); // مؤقت لحين استخدام العنوان الفعلي
        holder.tvBody.setText(notification.getId());  // مؤقت لحين استخدام المحتوى الفعلي
        
        if (notification.isRead()) {
            holder.indicatorUnread.setVisibility(View.GONE);
            holder.tvTitle.setTypeface(null, Typeface.NORMAL);
        } else {
            holder.indicatorUnread.setVisibility(View.VISIBLE);
            holder.tvTitle.setTypeface(null, Typeface.BOLD);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(notification);
        });
    }

    @Override
    public int getItemCount() {
        return notifications != null ? notifications.size() : 0;
    }

    public void updateData(List<NotificationModel> newNotifications) {
        this.notifications = newNotifications;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvBody, tvDate;
        View indicatorUnread;

        ViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvNotificationTitle);
            tvBody = itemView.findViewById(R.id.tvNotificationBody);
            tvDate = itemView.findViewById(R.id.tvNotificationDate);
            indicatorUnread = itemView.findViewById(R.id.indicatorUnread);
        }
    }
}
