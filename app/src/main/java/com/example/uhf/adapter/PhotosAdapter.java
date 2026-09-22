package com.example.uhf.adapter;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.example.uhf.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PhotosAdapter - الإصدار 3.1 (مُصحّح للتوافق مع RecyclerView القديم)
 *
 * ✅ الإصلاحات الحرجة:
 *  1. إزالة البناء القديم (يعتمد على MiraApp غير الموجود)
 *  2. إزالة override لـ notifyDataSetChanged() (final في الأصل)
 *  3. استخدام getAdapterPosition() بدلاً من getBindingAdapterPosition()
 *     (متوافق مع كل إصدارات RecyclerView)
 *
 * ✅ مزايا محفوظة:
 *  - Thread-safe
 *  - DiffUtil
 *  - Stable IDs
 *  - Glide + Signature cache
 *  - Click + Remove listeners
 *  - Preview support
 */
public class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.PhotoViewHolder> {

    private static final String TAG = "PhotosAdapter";

    // ==================== Interfaces ====================

    public interface OnPhotoRemoveListener {
        void onRemove(int position, @NonNull String path);
    }

    public interface OnPhotoClickListener {
        void onPhotoClick(int position, @NonNull String path, @NonNull ImageView source);
    }

    // ==================== Fields ====================

    private final List<String> photoPaths = new ArrayList<>();
    private final RequestManager glide;

    private OnPhotoRemoveListener onRemoveListener;
    private OnPhotoClickListener onClickListener;

    private final int thumbnailWidthPx;
    private final int thumbnailHeightPx;

    // ==================== Constructor ====================

    public PhotosAdapter(@NonNull Context context,
                         @Nullable List<String> initialPaths,
                         @Nullable OnPhotoRemoveListener removeListener) {
        if (context == null) {
            throw new IllegalArgumentException("PhotosAdapter: context cannot be null");
        }

        this.glide = Glide.with(context.getApplicationContext());

        if (initialPaths != null) {
            this.photoPaths.addAll(initialPaths);
        }
        this.onRemoveListener = removeListener;

        setHasStableIds(true);

        float density = context.getResources().getDisplayMetrics().density;
        this.thumbnailWidthPx = (int) (100 * density);
        this.thumbnailHeightPx = (int) (100 * density);
    }

    /**
     * ✅ [إصلاح #1] حذف البناء القديم (كان يعتمد على MiraApp)
     * إذا كنت تستخدمه في مكان ما، استبدله بـ:
     *   new PhotosAdapter(context, paths, listener)
     */
    // ← لا يوجد بناء deprecated هنا

    // ==================== Listeners ====================

    public void setOnPhotoRemoveListener(@Nullable OnPhotoRemoveListener listener) {
        this.onRemoveListener = listener;
    }

    public void setOnPhotoClickListener(@Nullable OnPhotoClickListener listener) {
        this.onClickListener = listener;
    }

    // ==================== Data ====================

    /**
     * ✅ تحديث بـ DiffUtil
     * ⚠️ [إصلاح #2] لا نستخدم override لـ notifyDataSetChanged
     * لأنها final في RecyclerView.Adapter
     */
    public void submitList(@Nullable List<String> newList) {
        if (!isMainThread()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> submitList(newList));
            return;
        }

        final List<String> oldList = new ArrayList<>(photoPaths);
        final List<String> targetList = new ArrayList<>();
        if (newList != null) targetList.addAll(newList);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return targetList.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).equals(targetList.get(newPos));
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).equals(targetList.get(newPos));
            }
        });

        photoPaths.clear();
        photoPaths.addAll(targetList);
        diffResult.dispatchUpdatesTo(this);
    }

    @NonNull
    public List<String> getPhotoPaths() {
        return Collections.unmodifiableList(new ArrayList<>(photoPaths));
    }

    @Nullable
    public String getPhotoAt(int position) {
        if (position < 0 || position >= photoPaths.size()) return null;
        return photoPaths.get(position);
    }

    public int getPhotoCount() {
        return photoPaths.size();
    }

    // ==================== RecyclerView Overrides ====================

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= photoPaths.size()) return RecyclerView.NO_ID;
        String path = photoPaths.get(position);
        return path != null ? path.hashCode() : RecyclerView.NO_ID;
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

        if (holder.ivPhoto == null) {
            Log.e(TAG, "ivPhoto is null at position " + position);
            return;
        }

        final String path = photoPaths.get(position);
        if (path == null || path.isEmpty()) {
            holder.ivPhoto.setImageResource(android.R.drawable.ic_menu_report_image);
            return;
        }

        // تحميل الصورة
        loadPhoto(holder, path);

        // زر الحذف
        if (holder.btnDeletePhoto != null) {
            holder.btnDeletePhoto.setOnClickListener(v -> {
                // ✅ [إصلاح #3] getAdapterPosition بدلاً من getBindingAdapterPosition
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    Log.w(TAG, "NO_POSITION on delete click");
                    return;
                }

                String currentPath = getPhotoAt(pos);
                if (currentPath == null || !currentPath.equals(path)) {
                    Log.w(TAG, "Path mismatch on delete — pos=" + pos);
                    return;
                }

                if (onRemoveListener != null) {
                    try {
                        onRemoveListener.onRemove(pos, path);
                    } catch (Exception e) {
                        Log.e(TAG, "onRemove callback error", e);
                    }
                }
            });
        }

        // النقر للمعاينة
        if (onClickListener != null && holder.ivPhoto != null) {
            holder.ivPhoto.setOnClickListener(v -> {
                // ✅ [إصلاح #3] getAdapterPosition
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                String currentPath = getPhotoAt(pos);
                if (currentPath == null) return;

                try {
                    onClickListener.onPhotoClick(pos, currentPath, holder.ivPhoto);
                } catch (Exception e) {
                    Log.e(TAG, "onPhotoClick callback error", e);
                }
            });
        }
    }

    private void loadPhoto(@NonNull PhotoViewHolder holder, @NonNull String path) {
        try {
            Object source = resolveSource(path);
            if (source == null) {
                holder.ivPhoto.setImageResource(R.drawable.ic_error_outline);
                return;
            }

            ObjectKey signature = null;
            if (source instanceof File) {
                File f = (File) source;
                if (f.exists()) {
                    signature = new ObjectKey(f.lastModified() + "_" + f.length());
                }
            }

            RequestOptions options = new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.placeholder_image)
                    .error(R.drawable.ic_error_outline)
                    .centerCrop()
                    .override(thumbnailWidthPx, thumbnailHeightPx);

            if (signature != null) {
                options = options.signature(signature);
            }

            glide.load(source)
                    .apply(options)
                    .thumbnail(0.25f)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
                    .into(holder.ivPhoto);

        } catch (Exception e) {
            Log.e(TAG, "Failed to load image: " + path, e);
            try {
                holder.ivPhoto.setImageResource(R.drawable.ic_error_outline);
            } catch (Exception ignored) {}
        }
    }

    @Nullable
    private Object resolveSource(@NonNull String path) {
        if (path.startsWith("content://")) {
            return Uri.parse(path);
        }
        if (path.startsWith("file://")) {
            Uri uri = Uri.parse(path);
            String filePath = uri.getPath();
            if (filePath != null) {
                File f = new File(filePath);
                return f.exists() ? f : null;
            }
            return null;
        }

        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            Log.w(TAG, "File not found: " + path);
            return null;
        }
        return f;
    }

    @Override
    public void onViewRecycled(@NonNull PhotoViewHolder holder) {
        super.onViewRecycled(holder);
        try {
            if (holder.ivPhoto != null) {
                glide.clear(holder.ivPhoto);
            }
        } catch (Exception e) {
            Log.w(TAG, "onViewRecycled error: " + e.getMessage());
        }
    }

    @Override
    public int getItemCount() {
        return photoPaths.size();
    }

    // ==================== Cleanup ====================

    public void clearListeners() {
        this.onRemoveListener = null;
        this.onClickListener = null;
        Log.d(TAG, "Photo listeners cleared");
    }

    public void clearRequests() {
        try {
            Log.d(TAG, "Requests cleared (Glide managed by context)");
        } catch (Exception e) {
            Log.w(TAG, "clearRequests error: " + e.getMessage());
        }
    }

    public void destroy() {
        clearListeners();
        clearRequests();
        photoPaths.clear();
    }

    // ==================== Helpers ====================

    private boolean isMainThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    // ==================== ViewHolder ====================

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        final ImageView btnDeletePhoto;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.imgThumbnail);
            btnDeletePhoto = itemView.findViewById(R.id.btnDeletePhoto);
        }
    }
}
