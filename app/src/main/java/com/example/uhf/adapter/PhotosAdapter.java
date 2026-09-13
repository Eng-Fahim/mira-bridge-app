package com.example.uhf.adapter;

import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.uhf.R;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ✅ PhotosAdapter — عرض صور القطعة مع دعم الحذف
 * 
 * الميزات:
 * - تحميل الصور عبر Glide (Async + Cache)
 * - زر حذف على كل صورة
 * - clearListeners() لتنظيف المراجع عند onDestroyView
 */
public class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.PhotoViewHolder> {

    private static final String TAG = "PhotosAdapter";

    // ✅ واجهة المستمع
    public interface OnPhotoRemoveListener {
        void onRemove(int position);
    }

    private final List<String> photoPaths;
    private final Executor imageExecutor;
    private OnPhotoRemoveListener onRemoveListener;

    public PhotosAdapter(List<String> photoPaths,
                         Executor imageExecutor,
                         OnPhotoRemoveListener onRemoveListener) {
        this.photoPaths = photoPaths;
        this.imageExecutor = imageExecutor;
        this.onRemoveListener = onRemoveListener;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo_preview, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        if (position < 0 || position >= photoPaths.size()) return;

        String path = photoPaths.get(position);
        if (path == null || path.isEmpty()) return;

        // ✅ تحميل الصورة (يدعم المسار العادي و content://)
        try {
            Object source = path.startsWith("content://") ? Uri.parse(path) : new File(path);
            Glide.with(holder.ivPhoto.getContext())
                    .load(source)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .centerCrop()
                    .into(holder.ivPhoto);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load image: " + path, e);
        }

        // ✅ زر الحذف
        holder.btnDeletePhoto.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (onRemoveListener != null) {
                onRemoveListener.onRemove(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return photoPaths != null ? photoPaths.size() : 0;
    }

    // ==================== ✅ دالة التنظيف ====================

    /**
     * ✅ تنظيف المستمع لمنع التسريبات
     * يُستدعى من Fragment.onDestroyView
     */
    public void clearListeners() {
        this.onRemoveListener = null;
        Log.d(TAG, "Photo listeners cleared");
    }

    // ==================== ViewHolder ====================

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPhoto;
        ImageView btnDeletePhoto;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.imgThumbnail);
            btnDeletePhoto = itemView.findViewById(R.id.btnDeletePhoto);
        }
    }
}
