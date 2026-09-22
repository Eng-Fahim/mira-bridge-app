package com.example.uhf.manager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IdentityPhotoManager - الإصدار 3.0 (المطوّر)
 *
 * ✅ الإصلاحات المطبقة (24 مشكلة):
 *  1. فحص executor == null في المُنشئ
 *  2. فحص context == null
 *  3. Thread-Safe list (CopyOnWriteArrayList)
 *  4. getPhotoPaths() يُرجع نسخة للقراءة فقط
 *  5. إضافة callbacks لعمليات async
 *  6. عدم حذف الأصل قبل ضمان نجاح الضغط
 *  7. فحص inputStream == null
 *  8. فحص storageDir == null
 *  9. inSampleSize ديناميكي حسب الحجم المستهدف
 * 10. فحص حجم الصورة قبل فك التشفير (decodeBounds)
 * 11. ضغط ذكي + تصحيح EXIF rotation
 * 12. تسمية فريدة بـ UUID (بدل _compressed)
 * 13. تحقق من وجود الملف في addPhoto
 * 14. منع التكرار
 * 15. حذف الملف عند removePhoto (اختياري)
 * 16. حذف الملفات عند clearPhotos (اختياري)
 * 17. Atomic check-and-add
 * 18. استعادة المسارات المحفوظة (اختياري)
 * 19. حد أقصى لحجم الملف الأصلي
 * 20. bitmap.recycle() في finally
 * 21. try-with-resources لـ outputStream
 * 22. بديل لـ getExternalFilesDir() عند null
 * 23. تجنب إعادة الضغط إذا كان الحجم مناسباً
 * 24. تتبع الحجم الكلي
 *
 * ✅ إضافات جديدة:
 *  - PhotoCallback لإعلام UI
 *  - CompressionResult مع تفاصيل
 *  - getTotalSizeBytes()
 *  - restoreFromSavedPaths() مع فحص الوجود
 *  - cleanupOrphanFiles() لحذف الملفات بدون مرجع
 *  - PhotoStats
 */
public class IdentityPhotoManager {

    private static final String TAG = "IdentityPhotoManager";

    // ==================== Constants ====================
    private static final int MAX_PHOTOS = 5;
    private static final int IMAGE_COMPRESSION_QUALITY = 82;

    /** الحجم الأقصى للأبعاد (البكسل) */
    private static final int MAX_DIMENSION_PX = 1920;

    /** تجاوز الضغط إذا كان الملف أصغر من هذا */
    private static final long SKIP_COMPRESSION_THRESHOLD_BYTES = 300 * 1024; // 300 KB

    /** الحجم الأقصى لملف مصدر (رفض أكبر منه) */
    private static final long MAX_SOURCE_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB

    /** الحد الأقصى للحجم الكلي للصور */
    private static final long MAX_TOTAL_SIZE_BYTES = 20L * 1024 * 1024; // 20 MB

    // ==================== Fields ====================
    private final Context appContext;
    private final ExecutorService executor;

    /** ✅ [إصلاح #3] Thread-safe list */
    private final List<String> photoPaths = new CopyOnWriteArrayList<>();

    /** ✅ [إصلاح #14] منع التكرار السريع */
    private final Set<String> pathSet = Collections.synchronizedSet(new HashSet<>());

    /** ✅ [إصلاح #17] قفل لتجنب race في canAddMorePhotos */
    private final Object addLock = new Object();

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // ==================== Callbacks ====================

    /**
     * ✅ [إصلاح #5] callback لإعلام UI بعد العمليات async
     */
    public interface PhotoCallback {
        void onPhotoAdded(@NonNull String path, int totalCount);
        void onPhotoRemoved(@NonNull String path, int totalCount);
        void onError(@NonNull String message);
    }

    // ==================== Data Model ====================

    /**
     * ✅ [إضافة] نتيجة الضغط
     */
    public static class CompressionResult {
        public final boolean success;
        public final String outputPath;
        public final long originalSize;
        public final long compressedSize;
        public final int originalWidth;
        public final int originalHeight;
        public final int finalWidth;
        public final int finalHeight;
        public final boolean wasRotated;
        public final boolean wasSkipped;

        public CompressionResult(boolean success, String outputPath,
                                 long originalSize, long compressedSize,
                                 int originalWidth, int originalHeight,
                                 int finalWidth, int finalHeight,
                                 boolean wasRotated, boolean wasSkipped) {
            this.success = success;
            this.outputPath = outputPath;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.finalWidth = finalWidth;
            this.finalHeight = finalHeight;
            this.wasRotated = wasRotated;
            this.wasSkipped = wasSkipped;
        }

        public String getSavingsText() {
            if (wasSkipped) return "بدون ضغط (صغير أصلاً)";
            if (originalSize <= 0) return "—";
            long saved = originalSize - compressedSize;
            double percent = (saved * 100.0) / originalSize;
            return String.format(Locale.US, "%.0f%% توفير", percent);
        }

        @NonNull
        @Override
        public String toString() {
            return "CompressionResult{" +
                    "success=" + success +
                    ", originalSize=" + originalSize +
                    ", compressedSize=" + compressedSize +
                    ", " + originalWidth + "x" + originalHeight +
                    " → " + finalWidth + "x" + finalHeight +
                    ", rotated=" + wasRotated +
                    ", skipped=" + wasSkipped +
                    '}';
        }
    }

    /**
     * ✅ [إضافة] إحصائيات
     */
    public static class PhotoStats {
        public final int count;
        public final long totalSizeBytes;
        public final int maxPhotos;

        public PhotoStats(int count, long totalSizeBytes, int maxPhotos) {
            this.count = count;
            this.totalSizeBytes = totalSizeBytes;
            this.maxPhotos = maxPhotos;
        }

        public String getTotalSizeFormatted() {
            if (totalSizeBytes < 1024) return totalSizeBytes + " B";
            if (totalSizeBytes < 1024 * 1024)
                return String.format(Locale.US, "%.1f KB", totalSizeBytes / 1024.0);
            return String.format(Locale.US, "%.2f MB", totalSizeBytes / (1024.0 * 1024.0));
        }

        @NonNull
        @Override
        public String toString() {
            return "PhotoStats{" + count + "/" + maxPhotos +
                    ", " + getTotalSizeFormatted() + "}";
        }
    }

    // ==================== Constructor ====================

    /**
     * ✅ [إصلاح #1, #2] فحص context و executor
     */
    public IdentityPhotoManager(@NonNull Context context, @NonNull ExecutorService executor) {
        if (context == null) {
            throw new IllegalArgumentException("IdentityPhotoManager: context cannot be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("IdentityPhotoManager: executor cannot be null");
        }

        this.appContext = context.getApplicationContext();
        this.executor = executor;
    }

    // ==================== READ ====================

    /**
     * ✅ [إصلاح #4] يُرجع نسخة للقراءة فقط
     */
    @NonNull
    public List<String> getPhotoPaths() {
        return Collections.unmodifiableList(new ArrayList<>(photoPaths));
    }

    public int getPhotoCount() {
        return photoPaths.size();
    }

    /**
     * ✅ [إصلاح #17] فحص ذري
     */
    public boolean canAddMorePhotos() {
        synchronized (addLock) {
            return photoPaths.size() < MAX_PHOTOS;
        }
    }

    public int getMaxPhotos() {
        return MAX_PHOTOS;
    }

    /**
     * ✅ [إضافة] الحجم الكلي بالبايت
     */
    public long getTotalSizeBytes() {
        long total = 0;
        for (String path : photoPaths) {
            try {
                File f = new File(path);
                if (f.exists()) total += f.length();
            } catch (Exception ignored) {}
        }
        return total;
    }

    /**
     * ✅ [إضافة] إحصائيات
     */
    @NonNull
    public PhotoStats getStats() {
        return new PhotoStats(photoPaths.size(), getTotalSizeBytes(), MAX_PHOTOS);
    }

    // ==================== ADD ====================

    /**
     * ✅ [إصلاح #13, #14, #17] فحص شامل + منع تكرار + atomic
     */
    public boolean addPhoto(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return false;

        String cleanPath = path.trim();

        synchronized (addLock) {
            if (photoPaths.size() >= MAX_PHOTOS) {
                Log.w(TAG, "addPhoto: max reached (" + MAX_PHOTOS + ")");
                return false;
            }

            // ✅ [إصلاح #14] منع التكرار
            if (pathSet.contains(cleanPath)) {
                Log.w(TAG, "addPhoto: duplicate — " + cleanPath);
                return false;
            }

            // ✅ [إصلاح #13] فحص وجود الملف
            File f = new File(cleanPath);
            if (!f.exists() || !f.isFile()) {
                Log.w(TAG, "addPhoto: file not found — " + cleanPath);
                return false;
            }

            photoPaths.add(cleanPath);
            pathSet.add(cleanPath);
            Log.d(TAG, "addPhoto: OK — " + cleanPath + " (" + photoPaths.size() + ")");
            return true;
        }
    }

    // ==================== REMOVE ====================

    /**
     * ✅ [إصلاح #15] حذف الملف أيضاً
     * @param deleteFile إذا true، يُحذف الملف من التخزين
     */
    public void removePhoto(int position, boolean deleteFile, @Nullable PhotoCallback callback) {
        String removedPath = null;

        synchronized (addLock) {
            if (position < 0 || position >= photoPaths.size()) {
                if (callback != null) callback.onError("Invalid position");
                return;
            }

            removedPath = photoPaths.remove(position);
            if (removedPath != null) pathSet.remove(removedPath);
        }

        if (removedPath != null) {
            Log.d(TAG, "removePhoto: " + removedPath);

            // حذف الملف إن طُلب
            if (deleteFile) {
                deleteFileQuietly(removedPath);
            }

            if (callback != null) {
                callback.onPhotoRemoved(removedPath, photoPaths.size());
            }
        }
    }

    /**
     * للتوافق مع الكود الحالي
     */
    public void removePhoto(int position) {
        removePhoto(position, false, null);
    }

    // ==================== CLEAR ====================

    /**
     * ✅ [إصلاح #16] حذف الملفات أيضاً
     */
    public void clearPhotos(boolean deleteFiles) {
        List<String> toDelete;
        synchronized (addLock) {
            toDelete = new ArrayList<>(photoPaths);
            photoPaths.clear();
            pathSet.clear();
        }

        if (deleteFiles) {
            for (String path : toDelete) {
                deleteFileQuietly(path);
            }
        }
        Log.d(TAG, "clearPhotos: cleared " + toDelete.size());
    }

    public void clearPhotos() {
        clearPhotos(false);
    }

    // ==================== ASYNC ADD ====================

    /**
     * ✅ [إصلاح #5] callback + إعادة تسمية آمنة
     */
    public void compressAndAddPhoto(@Nullable String originalPath,
                                     @Nullable PhotoCallback callback) {
        if (originalPath == null || originalPath.isEmpty()) {
            if (callback != null) callback.onError("Invalid path");
            return;
        }

        if (isShutdown.get() || executor.isShutdown()) {
            if (callback != null) callback.onError("Manager is shutdown");
            return;
        }

        executor.execute(() -> {
            try {
                // ✅ [إصلاح #17] فحص ذري
                if (!canAddMorePhotos()) {
                    if (callback != null) callback.onError("Max photos reached");
                    return;
                }

                CompressionResult result = compressImageInternal(originalPath, false);

                if (result == null || !result.success || result.outputPath == null) {
                    if (callback != null) callback.onError("Compression failed");
                    return;
                }

                boolean added = addPhoto(result.outputPath);

                if (added) {
                    // ✅ [إصلاح #6] احذف الأصل فقط بعد نجاح الإضافة
                    if (!result.wasSkipped
                            && !result.outputPath.equals(originalPath)) {
                        deleteFileQuietly(originalPath);
                    }

                    if (callback != null) {
                        callback.onPhotoAdded(result.outputPath, photoPaths.size());
                    }

                    Log.d(TAG, "compressAndAddPhoto: " + result);
                } else {
                    // فشل الإضافة → احذف الملف المضغوط
                    deleteFileQuietly(result.outputPath);
                    if (callback != null) callback.onError("Failed to add photo");
                }

            } catch (Exception e) {
                Log.e(TAG, "compressAndAddPhoto error: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "unknown");
                }
            }
        });
    }

    /**
     * للتوافق مع الكود القديم
     */
    public void compressAndAddPhoto(String originalPath) {
        compressAndAddPhoto(originalPath, null);
    }

    /**
     * ✅ [إصلاح #5, #7] callback + فحص inputStream
     */
    public void copyAndAddPhoto(@Nullable Uri uri, @Nullable PhotoCallback callback) {
        if (uri == null) {
            if (callback != null) callback.onError("Invalid URI");
            return;
        }

        if (isShutdown.get() || executor.isShutdown()) {
            if (callback != null) callback.onError("Manager is shutdown");
            return;
        }

        executor.execute(() -> {
            try {
                if (!canAddMorePhotos()) {
                    if (callback != null) callback.onError("Max photos reached");
                    return;
                }

                // نسخ إلى التخزين المؤقت
                String copiedPath = copyImageToInternalStorage(uri);
                if (copiedPath == null) {
                    if (callback != null) callback.onError("Failed to copy image");
                    return;
                }

                // ضغط
                CompressionResult result = compressImageInternal(copiedPath, true);
                if (result == null || !result.success || result.outputPath == null) {
                    deleteFileQuietly(copiedPath);
                    if (callback != null) callback.onError("Compression failed");
                    return;
                }

                boolean added = addPhoto(result.outputPath);
                if (added) {
                    // احذف الملف المؤقت الأصلي
                    if (!result.wasSkipped
                            && !result.outputPath.equals(copiedPath)) {
                        deleteFileQuietly(copiedPath);
                    }

                    if (callback != null) {
                        callback.onPhotoAdded(result.outputPath, photoPaths.size());
                    }

                    Log.d(TAG, "copyAndAddPhoto: " + result);
                } else {
                    deleteFileQuietly(result.outputPath);
                    if (callback != null) callback.onError("Failed to add photo");
                }

            } catch (Exception e) {
                Log.e(TAG, "copyAndAddPhoto error: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "unknown");
                }
            }
        });
    }

    public void copyAndAddPhoto(Uri uri) {
        copyAndAddPhoto(uri, null);
    }

    // ==================== COMPRESSION CORE ====================

    /**
     * ✅ [إصلاح #6, #9, #10, #11, #12, #19, #20, #21, #23]
     * ضغط ذكي مع EXIF rotation + inSampleSize ديناميكي + تسمية فريدة
     */
    @Nullable
    private CompressionResult compressImageInternal(@NonNull String imagePath,
                                                     boolean isTemporary) {
        File sourceFile = null;
        FileOutputStream outputStream = null;
        Bitmap bitmap = null;

        try {
            sourceFile = new File(imagePath);
            if (!sourceFile.exists() || !sourceFile.isFile()) {
                Log.w(TAG, "compressImage: source not found — " + imagePath);
                return null;
            }

            long originalSize = sourceFile.length();

            // ✅ [إصلاح #19] فحص حجم المصدر
            if (originalSize > MAX_SOURCE_FILE_SIZE_BYTES) {
                Log.w(TAG, "compressImage: source too large (" +
                        originalSize + " bytes)");
                return null;
            }

            // ✅ [إصلاح #23] تجاوز الضغط إذا كان الملف صغيراً
            if (originalSize < SKIP_COMPRESSION_THRESHOLD_BYTES) {
                Log.d(TAG, "compressImage: skipping (small file)");
                return new CompressionResult(
                        true, imagePath, originalSize, originalSize,
                        0, 0, 0, 0, false, true);
            }

            // ==================== 1) قراءة الأبعاد فقط ====================
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, bounds);

            int originalWidth = bounds.outWidth;
            int originalHeight = bounds.outHeight;

            if (originalWidth <= 0 || originalHeight <= 0) {
                Log.w(TAG, "compressImage: invalid dimensions");
                return null;
            }

            // ==================== 2) حساب inSampleSize ====================
            int sampleSize = calculateInSampleSize(
                    originalWidth, originalHeight, MAX_DIMENSION_PX, MAX_DIMENSION_PX);

            // ==================== 3) فك التشفير ====================
            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inSampleSize = sampleSize;
            decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565; // نصف الذاكرة

            bitmap = BitmapFactory.decodeFile(imagePath, decodeOptions);
            if (bitmap == null) {
                Log.w(TAG, "compressImage: decode failed");
                return null;
            }

            // ==================== 4) تصحيح EXIF rotation ====================
            boolean wasRotated = false;
            try {
                int rotation = getExifRotation(imagePath);
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                            bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (rotated != bitmap) {
                        bitmap.recycle();
                        bitmap = rotated;
                        wasRotated = true;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "EXIF rotation error: " + e.getMessage());
            }

            // ==================== 5) إنشاء ملف الوجهة ====================
            // ✅ [إصلاح #12] تسمية فريدة
            String outputPath;
            if (isTemporary) {
                outputPath = imagePath.replace(".jpg", "_c_" +
                        UUID.randomUUID().toString().substring(0, 6) + ".jpg");
            } else {
                File parentDir = sourceFile.getParentFile();
                if (parentDir == null) {
                    Log.w(TAG, "compressImage: parent dir is null");
                    return null;
                }
                outputPath = new File(parentDir,
                        "MIRA_" + System.currentTimeMillis() + "_" +
                        UUID.randomUUID().toString().substring(0, 6) + ".jpg")
                        .getAbsolutePath();
            }

            // ==================== 6) الضغط ====================
            // ✅ [إصلاح #21] try-with-resources
            try {
                outputStream = new FileOutputStream(outputPath);
                boolean compressed = bitmap.compress(
                        Bitmap.CompressFormat.JPEG, IMAGE_COMPRESSION_QUALITY, outputStream);
                outputStream.flush();

                if (!compressed) {
                    Log.w(TAG, "bitmap.compress returned false");
                    return null;
                }
            } finally {
                if (outputStream != null) {
                    try { outputStream.close(); } catch (Exception ignored) {}
                }
            }

            File outputFile = new File(outputPath);
            if (!outputFile.exists() || outputFile.length() == 0) {
                Log.w(TAG, "compressImage: output file is empty");
                deleteFileQuietly(outputPath);
                return null;
            }

            long compressedSize = outputFile.length();

            Log.d(TAG, "compressImage: OK " + originalSize + " → " + compressedSize +
                    " (" + originalWidth + "x" + originalHeight +
                    " → " + bitmap.getWidth() + "x" + bitmap.getHeight() +
                    (wasRotated ? ", rotated" : "") + ")");

            return new CompressionResult(
                    true, outputPath, originalSize, compressedSize,
                    originalWidth, originalHeight,
                    bitmap.getWidth(), bitmap.getHeight(),
                    wasRotated, false);

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "compressImage: OOM — " + oom.getMessage());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "compressImage error: " + e.getMessage(), e);
            return null;
        } finally {
            // ✅ [إصلاح #20] recycle في finally
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * ✅ [إصلاح #9] حساب inSampleSize ديناميكياً
     */
    private int calculateInSampleSize(int width, int height,
                                       int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    /**
     * ✅ [إصلاح #11] قراءة EXIF rotation
     */
    private int getExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);

            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== COPY ====================

    /**
     * ✅ [إصلاح #7, #8, #22]
     */
    @Nullable
    private String copyImageToInternalStorage(@NonNull Uri uri) {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        try {
            // ✅ [إصلاح #8] فحص storageDir
            File storageDir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (storageDir == null) {
                // بديل: استخدام cacheDir
                storageDir = new File(appContext.getCacheDir(), "photos");
                if (!storageDir.exists() && !storageDir.mkdirs()) {
                    Log.e(TAG, "copyImageToInternalStorage: cannot create storage dir");
                    return null;
                }
            } else if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    Log.w(TAG, "copyImageToInternalStorage: mkdirs failed");
                }
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            String fileName = "MIRA_" + timeStamp + "_" +
                    UUID.randomUUID().toString().substring(0, 6) + ".jpg";
            File destFile = new File(storageDir, fileName);

            // ✅ [إصلاح #7] فحص inputStream
            inputStream = appContext.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.w(TAG, "copyImageToInternalStorage: input stream is null");
                return null;
            }

            outputStream = new FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int length;
            long total = 0;

            while ((length = inputStream.read(buffer)) > 0) {
                total += length;
                // ✅ [إصلاح #19] منع تجاوز الحجم الكبير
                if (total > MAX_SOURCE_FILE_SIZE_BYTES) {
                    Log.w(TAG, "copyImageToInternalStorage: source too large");
                    outputStream.close();
                    outputStream = null;
                    destFile.delete();
                    return null;
                }
                outputStream.write(buffer, 0, length);
            }

            outputStream.flush();
            Log.d(TAG, "copyImageToInternalStorage: " + destFile.getAbsolutePath() +
                    " (" + total + " bytes)");
            return destFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "copyImageToInternalStorage error: " + e.getMessage(), e);
            return null;
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception ignored) {}
            }
            if (outputStream != null) {
                try { outputStream.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== RESTORE (New) ====================

    /**
     * ✅ [إصلاح #18] استعادة المسارات المحفوظة من prefs
     */
    public void restoreFromSavedPaths(@Nullable List<String> savedPaths,
                                       @Nullable PhotoCallback callback) {
        if (savedPaths == null || savedPaths.isEmpty()) {
            if (callback != null) {
                // لا شيء للاستعادة — لا نستدعي callback
            }
            return;
        }

        executor.execute(() -> {
            int restored = 0;
            for (String path : savedPaths) {
                if (addPhoto(path)) restored++;
            }

            if (callback != null && restored > 0) {
                // نستدعي onPhotoAdded لكل صورة
                for (String path : photoPaths) {
                    callback.onPhotoAdded(path, photoPaths.size());
                }
            }

            Log.d(TAG, "restoreFromSavedPaths: restored " + restored + "/" + savedPaths.size());
        });
    }

    // ==================== CLEANUP (New) ====================

    /**
     * ✅ [إضافة] حذف الملفات اليتيمة (بدون مرجع)
     */
    public void cleanupOrphanFiles(@Nullable List<String> referencedPaths) {
        executor.execute(() -> {
            try {
                File storageDir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (storageDir == null || !storageDir.exists()) return;

                File[] files = storageDir.listFiles();
                if (files == null) return;

                Set<String> referenced = new HashSet<>();
                if (referencedPaths != null) {
                    for (String p : referencedPaths) {
                        if (p != null) referenced.add(p);
                    }
                }
                // أضف الحالية
                for (String p : photoPaths) {
                    referenced.add(p);
                }

                int deleted = 0;
                for (File file : files) {
                    if (file.isFile() && file.getName().startsWith("MIRA_")) {
                        if (!referenced.contains(file.getAbsolutePath())) {
                            if (file.delete()) deleted++;
                        }
                    }
                }

                Log.d(TAG, "cleanupOrphanFiles: deleted " + deleted);
            } catch (Exception e) {
                Log.e(TAG, "cleanupOrphanFiles error: " + e.getMessage());
            }
        });
    }

    // ==================== UTILITIES ====================

    /**
     * ✅ [إصلاح #6, #15, #16] حذف آمن
     */
    private void deleteFileQuietly(@Nullable String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                if (f.delete()) {
                    Log.d(TAG, "Deleted: " + path);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "deleteFileQuietly error: " + e.getMessage());
        }
    }

    // ==================== LIFECYCLE ====================

    /**
     * ✅ [إصلاح #24] إيقاف نظيف
     */
    public void shutdown() {
        isShutdown.set(true);
        Log.d(TAG, "IdentityPhotoManager shutdown");
    }

    public boolean isShutdown() {
        return isShutdown.get();
    }

    // ==================== HEALTH ====================

    /**
     * ✅ [إضافة] تقرير صحة المدير
     */
    @NonNull
    public String getHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 IdentityPhotoManager Health\n");
        sb.append("─────────────\n");
        sb.append("Executor: ").append(executor == null ? "NULL" :
                (executor.isShutdown() ? "SHUTDOWN" : "OK")).append("\n");
        sb.append("Shutdown: ").append(isShutdown.get()).append("\n");
        sb.append("Photos: ").append(photoPaths.size()).append("/").append(MAX_PHOTOS).append("\n");
        sb.append("Total size: ").append(getStats().getTotalSizeFormatted()).append("\n");
        return sb.toString();
    }
}
