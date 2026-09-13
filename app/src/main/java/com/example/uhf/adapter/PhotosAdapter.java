package com.example.uhf.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import java.util.List;
import java.util.concurrent.ExecutorService;

public class PhotosAdapter extends RecyclerView.Adapter<PhotosAdapter.PhotoViewHolder> {

    public interface OnPhotoDeleteListener {
        void onDelete(int position);
    }

    private final List<String> paths;
    private final ExecutorService executor;
    private final OnPhotoDeleteListener deleteListener;

    public PhotosAdapter(List<String> paths, ExecutorService executor, OnPhotoDeleteListener deleteListener) {
        this.paths = paths;
        this.executor = executor;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_preview, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        String path = paths.get(position);
        holder.imgThumbnail.setTag(path);
        holder.imgThumbnail.setImageResource(R.drawable.ic_photo);

        executor.execute(() -> {
            Bitmap thumbnail = decodeSampledBitmapFromFile(path, 100, 100);
            if (thumbnail != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (path.equals(holder.imgThumbnail.getTag())) {
                        holder.imgThumbnail.setImageBitmap(thumbnail);
                    }
                });
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (deleteListener != null) {
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    deleteListener.onDelete(adapterPosition);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return paths.size();
    }

    private Bitmap decodeSampledBitmapFromFile(String path, int reqWidth, int reqHeight) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception e) {
            return null;
        }
    }
    
    

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        ImageView btnDelete;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            btnDelete = itemView.findViewById(R.id.btnDeletePhoto);
        }
    }
}
