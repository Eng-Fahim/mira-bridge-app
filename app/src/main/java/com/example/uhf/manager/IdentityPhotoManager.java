package com.example.uhf.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class IdentityPhotoManager {

    private static final String TAG = "IdentityPhotoManager";
    private static final int MAX_PHOTOS = 5;
    private static final int IMAGE_COMPRESSION_QUALITY = 75;

    private final Context context;
    private final ExecutorService executor;
    private final List<String> photoPaths = new ArrayList<>();

    public IdentityPhotoManager(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
    }

    public List<String> getPhotoPaths() {
        return photoPaths;
    }

    public int getPhotoCount() {
        return photoPaths.size();
    }

    public boolean canAddMorePhotos() {
        return photoPaths.size() < MAX_PHOTOS;
    }

    public void addPhoto(String path) {
        if (path != null && !path.isEmpty() && canAddMorePhotos()) {
            photoPaths.add(path);
        }
    }

    public void removePhoto(int position) {
        if (position >= 0 && position < photoPaths.size()) {
            photoPaths.remove(position);
        }
    }

    public void clearPhotos() {
        photoPaths.clear();
    }

    public void compressAndAddPhoto(String originalPath) {
        executor.execute(() -> {
            String compressedPath = compressImage(originalPath);
            if (compressedPath != null) {
                addPhoto(compressedPath);
            }
        });
    }

    public void copyAndAddPhoto(Uri uri) {
        executor.execute(() -> {
            String path = copyImageToInternalStorage(uri);
            if (path != null) {
                String compressedPath = compressImage(path);
                if (compressedPath != null) {
                    addPhoto(compressedPath);
                }
            }
        });
    }

    private String compressImage(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) return null;
            if (imageFile.length() < 500 * 1024) return imagePath;
            
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath, options);
            if (bitmap == null) return imagePath;
            
            String compressedPath = imagePath.replace(".jpg", "_compressed.jpg");
            FileOutputStream outputStream = new FileOutputStream(compressedPath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_COMPRESSION_QUALITY, outputStream);
            outputStream.close();
            bitmap.recycle();
            
            if (!compressedPath.equals(imagePath) && imageFile.exists()) {
                imageFile.delete();
            }
            return compressedPath;
        } catch (Exception e) {
            Log.e(TAG, "compressImage error: " + e.getMessage());
            return imagePath;
        }
    }

    private String copyImageToInternalStorage(Uri uri) {
        try {
            if (context == null) return null;
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File destFile = new File(storageDir, "MIRA_" + timeStamp + "_" + System.currentTimeMillis() + ".jpg");

            try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {
                
                byte[] buffer = new byte[4096];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyImageToInternalStorage error: " + e.getMessage());
            return null;
        }
    }
}
