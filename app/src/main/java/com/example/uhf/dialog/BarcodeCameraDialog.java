package com.example.uhf.dialog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.example.uhf.R;
import com.example.uhf.scanner.BarcodeScanHandler;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ حوار مسح الباركود بالكاميرا
 * - يستخدم CameraX + ML Kit
 * - يعرض Preview مباشر
 * - يحلل الإطارات في الوقت الفعلي
 */
public class BarcodeCameraDialog extends Dialog {
    
    private static final String TAG = "BarcodeCameraDialog";
    private static final int CAMERA_PERMISSION_CODE = 2001;
    
    // ==================== Callbacks ====================
    
    public interface OnBarcodeScannedListener {
        void onBarcodeScanned(String rawBarcode);
        void onClosed();
    }
    
    // ==================== Views ====================
    
    private PreviewView previewView;
    private TextView tvStatus;
    private TextView tvLastScan;
    private TextView tvHint;
    private TextView btnClose;
    private View scanningFrame;
    
    // ==================== Camera ====================
    
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private boolean isProcessing = false;
    private boolean firstScanDone = false;
    
    // ==================== State ====================
    
    private final OnBarcodeScannedListener listener;
    private final boolean isArabic;
    private final BarcodeScanHandler scanHandler;
    private ToneGenerator toneGenerator;
    
    // منع التكرار
    private String lastScannedValue = "";
    private long lastScanTime = 0;
    private static final long DUPLICATE_WINDOW_MS = 2000;
    
    // ==================== Constructor ====================
    
    public BarcodeCameraDialog(@NonNull Context context,
                               boolean isArabic,
                               OnBarcodeScannedListener listener) {
        super(context);
        this.listener = listener;
        this.isArabic = isArabic;
        this.scanHandler = new BarcodeScanHandler(context);
    }
    
    // ==================== Lifecycle ====================
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_barcode_camera);
        
        // ✅ حجم كامل
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            getWindow().setDimAmount(0.8f);
        }
        
        setCancelable(true);
        setCanceledOnTouchOutside(false);
        
        initViews();
        initToneGenerator();
        setupBarcodeScanner();
        
        // ✅ اطلب صلاحية الكاميرا
        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }
    
    @Override
    public void dismiss() {
        stopCamera();
        releaseResources();
        super.dismiss();
        
        if (listener != null) {
            listener.onClosed();
        }
    }
    
    // ==================== Init ====================
    
    private void initViews() {
        previewView = findViewById(R.id.previewView);
        tvStatus = findViewById(R.id.tvBarcodeStatus);
        tvLastScan = findViewById(R.id.tvLastScan);
        tvHint = findViewById(R.id.tvBarcodeHint);
        btnClose = findViewById(R.id.btnCloseCamera);
        scanningFrame = findViewById(R.id.scanningFrame);
        
        // ✅ النصوص
        if (isArabic) {
            tvStatus.setText("وجّه الكاميرا نحو الباركود");
            tvHint.setText("GS1 DataMatrix أو QR Code");
        } else {
            tvStatus.setText("Point camera at barcode");
            tvHint.setText("GS1 DataMatrix or QR Code");
        }
        
        // ✅ زر الإغلاق
        btnClose.setOnClickListener(v -> dismiss());
    }
    
    private void initToneGenerator() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (Exception e) {
            Log.w(TAG, "ToneGenerator init failed: " + e.getMessage());
        }
    }
    
    private void setupBarcodeScanner() {
        // ✅ إعداد ML Kit Barcode Scanner
        barcodeScanner = BarcodeScanning.getClient();
        cameraExecutor = Executors.newSingleThreadExecutor();
    }
    
    // ==================== Permissions ====================
    
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(
            getContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestCameraPermission() {
        // ملاحظة: هذه الطريقة تعمل فقط إذا كان الـ Context هو Activity
        // في حالة Dialog، نستخدم الواجهة النشطة
        Context ctx = getContext();
        if (ctx instanceof android.app.Activity) {
            ActivityCompat.requestPermissions(
                (android.app.Activity) ctx,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_CODE
            );
        } else {
            Toast.makeText(getContext(),
                isArabic ? "لا يمكن طلب الصلاحية" : "Cannot request permission",
                Toast.LENGTH_SHORT).show();
            dismiss();
        }
    }
    
    // ==================== Camera ====================
    
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(getContext());
        
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "startCamera error: " + e.getMessage());
                tvStatus.setText(isArabic ? "خطأ في الكاميرا" : "Camera error");
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }
    
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        
        try {
            // ✅ Unbind أي استخدام سابق
            cameraProvider.unbindAll();
            
            // 1) Preview
            Preview preview = new Preview.Builder()
                .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            
            // 2) Image Analysis
            imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
            
            imageAnalysis.setAnalyzer(
                cameraExecutor,
                this::analyzeImage
            );
            
            // 3) Camera Selector
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            
            // 4) احصل على LifecycleOwner
            LifecycleOwner lifecycleOwner = getLifecycleOwner();
            if (lifecycleOwner == null) {
                Log.e(TAG, "No LifecycleOwner found");
                tvStatus.setText(isArabic ? "خطأ: لا يوجد Lifecycle" : "Error: No Lifecycle");
                return;
            }
            
            // 5) اربط Use Cases
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            );
            
            Log.d(TAG, "✅ Camera bound successfully");
            
            // ✅ حرّك مربع المسح لجذب الانتباه
            animateScanFrame();
            
        } catch (Exception e) {
            Log.e(TAG, "bindCameraUseCases error: " + e.getMessage());
            tvStatus.setText(isArabic ? "خطأ في تشغيل الكاميرا" : "Camera error");
        }
    }
    
    private LifecycleOwner getLifecycleOwner() {
        Context ctx = getContext();
        
        if (ctx instanceof LifecycleOwner) {
            return (LifecycleOwner) ctx;
        }
        
        // حاول الوصول للـ Activity
        if (ctx instanceof android.content.ContextWrapper) {
            Context base = ((android.content.ContextWrapper) ctx).getBaseContext();
            if (base instanceof LifecycleOwner) {
                return (LifecycleOwner) base;
            }
        }
        
        return null;
    }
    
    private void stopCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            if (imageAnalysis != null) {
                imageAnalysis.clearAnalyzer();
                imageAnalysis = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopCamera error: " + e.getMessage());
        }
    }
    
    private void releaseResources() {
        try {
            if (barcodeScanner != null) {
                barcodeScanner.close();
                barcodeScanner = null;
            }
            if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
                cameraExecutor.shutdown();
                cameraExecutor = null;
            }
            if (toneGenerator != null) {
                toneGenerator.release();
                toneGenerator = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "releaseResources error: " + e.getMessage());
        }
    }
    
    // ==================== Image Analysis ====================
    
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        // ✅ منع المعالجة المتوازية
        if (isProcessing) {
            imageProxy.close();
            return;
        }
        
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        
        isProcessing = true;
        
        try {
            InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
            );
            
            barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        handleBarcodes(barcodes);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Barcode scan failed: " + e.getMessage());
                })
                .addOnCompleteListener(task -> {
                    isProcessing = false;
                    imageProxy.close();
                });
            
        } catch (Exception e) {
            Log.e(TAG, "analyzeImage error: " + e.getMessage());
            isProcessing = false;
            imageProxy.close();
        }
    }
    
    // ==================== Handle Barcode ====================
    
    private void handleBarcodes(List<Barcode> barcodes) {
        for (Barcode barcode : barcodes) {
            String rawValue = barcode.getRawValue();
            int format = barcode.getFormat();
            
            if (rawValue == null || rawValue.trim().isEmpty()) continue;
            
            // ✅ تجاهل المكرر خلال 2 ثانية
            long now = System.currentTimeMillis();
            if (rawValue.equals(lastScannedValue)
                && (now - lastScanTime) < DUPLICATE_WINDOW_MS) {
                continue;
            }
            
            lastScannedValue = rawValue;
            lastScanTime = now;
            
            // ✅ سجّل الصيغة
            String formatName = getFormatName(format);
            Log.d(TAG, "✅ Barcode detected: " + rawValue + " [" + formatName + "]");
            
            // ✅ صوت
            playBeep();
            
            // ✅ اهتزاز (اختياري)
            vibrate();
            
            // ✅ حدّث الواجهة
            updateUIOnScan(rawValue, formatName);
            
            // ✅ أرسل للمعالج
            if (listener != null) {
                listener.onBarcodeScanned(rawValue);
            }
            
            // ✅ إذا كانت أول قراءة — أغلق الحوار بعد تأخير
            if (!firstScanDone) {
                firstScanDone = true;
                previewView.postDelayed(() -> {
                    if (isShowing()) {
                        dismiss();
                    }
                }, 800);
            }
            
            break; // كفى قراءة واحدة
        }
    }
    
    private String getFormatName(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE: return "QR";
            case Barcode.FORMAT_DATA_MATRIX: return "DataMatrix";
            case Barcode.FORMAT_CODE_128: return "Code128";
            case Barcode.FORMAT_CODE_39: return "Code39";
            case Barcode.FORMAT_EAN_13: return "EAN13";
            case Barcode.FORMAT_EAN_8: return "EAN8";
            case Barcode.FORMAT_UPC_A: return "UPC-A";
            case Barcode.FORMAT_UPC_E: return "UPC-E";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "Aztec";
            default: return "Unknown";
        }
    }
    
    private void updateUIOnScan(String rawValue, String formatName) {
        if (tvStatus == null) return;
        
        String display = rawValue.length() > 30
            ? rawValue.substring(0, 30) + "..."
            : rawValue;
        
        if (isArabic) {
            tvStatus.setText("✅ تم المسح");
            tvLastScan.setText("[" + formatName + "] " + display);
        } else {
            tvStatus.setText("✅ Scanned");
            tvLastScan.setText("[" + formatName + "] " + display);
        }
        
        // ✅ لون أخضر
        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
        tvLastScan.setVisibility(View.VISIBLE);
    }
    
    // ==================== Feedback ====================
    
    private void playBeep() {
        try {
            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
            }
        } catch (Exception ignored) {}
    }
    
    private void vibrate() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator)
                getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            80,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    );
                } else {
                    vibrator.vibrate(80);
                }
            }
        } catch (Exception ignored) {}
    }
    
    // ==================== Animations ====================
    
    private void animateScanFrame() {
        if (scanningFrame == null) return;
        
        scanningFrame.post(() -> {
            scanningFrame.setScaleX(1.0f);
            scanningFrame.setScaleY(1.0f);
            
            scanningFrame.animate()
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(1000)
                .withEndAction(() -> {
                    scanningFrame.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(1000)
                        .withEndAction(this::animateScanFrame)
                        .start();
                })
                .start();
        });
    }
}
