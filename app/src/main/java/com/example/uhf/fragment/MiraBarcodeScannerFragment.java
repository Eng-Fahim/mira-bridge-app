package com.example.uhf.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraBarcodeScannerFragment extends Fragment {

    private static final String TAG = "MiraBarcodeScanner";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    // ==================== UI ====================
    private PreviewView previewView;
    private View scannerOverlay, scanLine;
    private TextView tvScanStatus, tvScannedData;
    private ImageButton btnClose, btnFlash;

    // ==================== Camera ====================
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private CameraControl cameraControl;
    private boolean isFlashOn = false;
    private ExecutorService cameraExecutor;

    // ==================== Scanner ====================
    private BarcodeScanner barcodeScanner;
    private ToneGenerator toneGenerator;
    private boolean isScanning = true;
    private long lastScanTime = 0;
    private static final long SCAN_COOLDOWN_MS = 1500;

    // ==================== Listeners & State ====================
    private OnBarcodeScannedListener barcodeListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;

    // ==================== Interface ====================

    public interface OnBarcodeScannedListener {
        void onBarcodeScanned(String barcodeData, String format);
    }

    public void setOnBarcodeScannedListener(OnBarcodeScannedListener listener) {
        this.barcodeListener = listener;
    }

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_barcode_scanner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
            if (getContext() != null) {
                orgProfile = MiraOrgProfileManager.getInstance(getContext());
            }

            // ربط العناصر
            previewView = view.findViewById(R.id.previewView);
            scannerOverlay = view.findViewById(R.id.scannerOverlay);
            scanLine = view.findViewById(R.id.scanLine);
            tvScanStatus = view.findViewById(R.id.tvScanStatus);
            tvScannedData = view.findViewById(R.id.tvScannedData);
            btnClose = view.findViewById(R.id.btnCloseScanner);
            btnFlash = view.findViewById(R.id.btnToggleFlash);

            // تطبيق اللغة
            applyLanguage();

            // أزرار
            if (btnClose != null) btnClose.setOnClickListener(v -> closeScanner());
            if (btnFlash != null) btnFlash.setOnClickListener(v -> toggleFlash());

            // تهيئة
            cameraExecutor = Executors.newSingleThreadExecutor();
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            initBarcodeScanner();

            // ✅ طلب الصلاحية أولاً
            if (hasCameraPermission()) {
                startCamera();
            } else {
                requestCameraPermission();
            }

            startScanAnimation();

        } catch (Exception e) {
            Log.e(TAG, "Error in onViewCreated: " + e.getMessage(), e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            // ✅ إعادة تشغيل الكاميرا إذا كانت الصلاحية موجودة
            if (hasCameraPermission() && cameraProvider == null) {
                startCamera();
            }
            
            // معالجة زر الرجوع
            if (getView() != null) {
                getView().setFocusableInTouchMode(true);
                getView().requestFocus();
                getView().setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == android.view.KeyEvent.ACTION_UP
                        && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        closeScanner();
                        return true;
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // ✅ إيقاف الكاميرا مؤقتاً
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding camera: " + e.getMessage());
            }
        }
    }

    // ==================== Permission Handling ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
                Toast.makeText(getContext(),
                    isArabic ? "✅ تم منح صلاحية الكاميرا" : "✅ Camera permission granted",
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(),
                    isArabic ? "❌ صلاحية الكاميرا مطلوبة" : "❌ Camera permission required",
                    Toast.LENGTH_LONG).show();
                closeScanner();
            }
        }
    }

    // ==================== Language ====================

    private void applyLanguage() {
        try {
            if (tvScanStatus != null) {
                tvScanStatus.setText(isArabic ? "📷 جاهز للمسح..." : "📷 Ready to scan...");
            }
            if (btnFlash != null) {
                btnFlash.setContentDescription(isArabic ? "فلاش" : "Flash");
            }
            if (btnClose != null) {
                btnClose.setContentDescription(isArabic ? "إغلاق" : "Close");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying language: " + e.getMessage());
        }
    }

    // ==================== Barcode Scanner Init ====================

    private void initBarcodeScanner() {
        try {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                            Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE,
                            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                            Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                            Barcode.FORMAT_AZTEC, Barcode.FORMAT_PDF417
                    ).build();
            barcodeScanner = BarcodeScanning.getClient(options);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing barcode scanner: " + e.getMessage());
        }
    }

    // ==================== Camera ====================

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    private void startCamera() {
        try {
            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
            future.addListener(() -> {
                try {
                    cameraProvider = future.get();

                    // ✅ إعداد المعاينة
                    Preview preview = new Preview.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // ✅ إعداد التحليل
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    // ✅ اختيار الكاميرا الخلفية
                    CameraSelector selector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();

                    // ✅ ربط الكاميرا
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
                    cameraControl = camera.getCameraControl();

                    Log.d(TAG, "Camera started successfully");

                } catch (Exception e) {
                    Log.e(TAG, "Error starting camera: " + e.getMessage(), e);
                    showErrorAndClose();
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera provider: " + e.getMessage(), e);
            showErrorAndClose();
        }
    }

    // ==================== Image Analysis ====================

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isScanning || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        @SuppressWarnings("UnsafeOptInUsageError")
        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    try {
                        if (!barcodes.isEmpty() && isScanning) {
                            for (Barcode barcode : barcodes) {
                                String rawValue = barcode.getRawValue();
                                if (rawValue != null && !rawValue.isEmpty()) {
                                    onBarcodeDetected(rawValue, barcode.getFormat());
                                    break;
                                }
                            }
                        }
                    } finally {
                        imageProxy.close();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Scan error: " + e.getMessage());
                    imageProxy.close();
                });
    }

    // ==================== Detection ====================

    private void onBarcodeDetected(String data, int format) {
        long now = System.currentTimeMillis();
        if (now - lastScanTime < SCAN_COOLDOWN_MS) return;
        lastScanTime = now;

        try {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);
        } catch (Exception e) {
            Log.e(TAG, "Error playing tone: " + e.getMessage());
        }

        String formatName = getBarcodeFormatName(format);

        safeRun(() -> {
            try {
                if (tvScannedData != null) {
                    tvScannedData.setText(formatName + ": " + data);
                    tvScannedData.setVisibility(View.VISIBLE);
                }
                if (tvScanStatus != null) {
                    tvScanStatus.setText(isArabic ? "✅ تم المسح" : "✅ Scanned");
                    tvScanStatus.setTextColor(Color.parseColor("#4ADE80"));
                }
                tvScannedData.postDelayed(() -> {
                    if (tvScannedData != null) tvScannedData.setVisibility(View.GONE);
                    if (tvScanStatus != null) {
                        tvScanStatus.setText(isArabic ? "📷 جاهز للمسح..." : "📷 Ready to scan...");
                        tvScanStatus.setTextColor(Color.parseColor("#38BDF8"));
                    }
                }, 2000);
            } catch (Exception e) {
                Log.e(TAG, "Error updating UI: " + e.getMessage());
            }
        });

        if (barcodeListener != null) barcodeListener.onBarcodeScanned(data, formatName);
        forwardToPOS(data);
    }

    private void forwardToPOS(String data) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                Fragment pos = activity.getSupportFragmentManager().findFragmentByTag("POS Mode");
                if (pos instanceof MiraPosFragment && pos.isVisible()) {
                    ((MiraPosFragment) pos).onBarcodeScanned(data);
                } else {
                    activity.onTagRead(data);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error forwarding to POS: " + e.getMessage());
        }
    }

    // ==================== Close ====================

    private void closeScanner() {
        try {
            isScanning = false;
            if (cameraControl != null) {
                try {
                    cameraControl.enableTorch(false);
                } catch (Exception e) {
                    Log.e(TAG, "Error disabling torch: " + e.getMessage());
                }
            }
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                cameraProvider = null;
            }
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing scanner: " + e.getMessage());
            if (getActivity() != null) getActivity().onBackPressed();
        }
    }

    private void showErrorAndClose() {
        safeRun(() -> {
            Toast.makeText(getContext(),
                isArabic ? "❌ تعذر تشغيل الكاميرا" : "❌ Failed to start camera",
                Toast.LENGTH_LONG).show();
            closeScanner();
        });
    }

    // ==================== Animation ====================

    private void startScanAnimation() {
    if (scanLine == null || scannerOverlay == null) return;
    try {
        // ✅ إعادة تعيين الموضع الأولي
        scanLine.setTranslationY(0);
        
        // ✅ تحريك الخط من الأعلى إلى الأسفل داخل الإطار
        scanLine.post(() -> {
            scanLine.animate()
                .translationY(scannerOverlay.getHeight() - 2)
                .setDuration(2000)
                .withEndAction(() -> {
                    scanLine.setTranslationY(0);
                    if (isScanning) startScanAnimation();
                })
                .start();
        });
    } catch (Exception e) {
        Log.e(TAG, "Error starting animation: " + e.getMessage());
    }
}

    // ==================== Flash ====================

    public void toggleFlash() {
        try {
            if (cameraControl != null) {
                isFlashOn = !isFlashOn;
                cameraControl.enableTorch(isFlashOn);
                Toast.makeText(getContext(),
                    isFlashOn ? (isArabic ? "🔦 تم تشغيل الفلاش" : "🔦 Flash On")
                              : (isArabic ? "🔌 تم إطفاء الفلاش" : "🔌 Flash Off"),
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flash: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    private String getBarcodeFormatName(int format) {
        switch (format) {
            case Barcode.FORMAT_DATA_MATRIX: return "GS1 DataMatrix";
            case Barcode.FORMAT_QR_CODE: return "QR Code";
            case Barcode.FORMAT_EAN_13: return "EAN-13";
            case Barcode.FORMAT_EAN_8: return "EAN-8";
            case Barcode.FORMAT_CODE_128: return "Code-128";
            case Barcode.FORMAT_CODE_39: return "Code-39";
            case Barcode.FORMAT_UPC_A: return "UPC-A";
            case Barcode.FORMAT_UPC_E: return "UPC-E";
            case Barcode.FORMAT_AZTEC: return "Aztec";
            case Barcode.FORMAT_PDF417: return "PDF417";
            default: return "Barcode";
        }
    }

    public void pauseScanning() { isScanning = false; }
    public void resumeScanning() { isScanning = true; lastScanTime = 0; }

    // ==================== Cleanup ====================

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (cameraControl != null) {
                cameraControl.enableTorch(false);
            }
            if (cameraExecutor != null) {
                cameraExecutor.shutdown();
            }
            if (barcodeScanner != null) {
                barcodeScanner.close();
            }
            if (toneGenerator != null) {
                toneGenerator.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in cleanup: " + e.getMessage());
        }
    }
}
