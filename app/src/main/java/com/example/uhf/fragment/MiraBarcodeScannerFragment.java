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

/**
 * MIRA Barcode Scanner Fragment
 * 
 * ماسح باركود احترافي يدعم:
 * - GS1 DataMatrix, QR Code, EAN-13, Code-128
 * - فلاش الكاميرا
 * - خط مسح متحرك
 * - دعم عربي/إنجليزي كامل
 * - توحيد الهوية مع باقي التطبيق
 */
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

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
        }

        // ربط العناصر - مطابقة للـ XML
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

        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        startScanAnimation();
    }

    @Override
    public void onResume() {
        super.onResume();
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
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (tvScanStatus != null) {
            tvScanStatus.setText(isArabic ? "📷 جاهز للمسح..." : "📷 Ready to scan...");
        }
        if (btnFlash != null) {
            btnFlash.setContentDescription(isArabic ? "فلاش" : "Flash");
        }
        if (btnClose != null) {
            btnClose.setContentDescription(isArabic ? "إغلاق" : "Close");
        }
    }

    // ==================== Barcode Scanner Init ====================

    private void initBarcodeScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_AZTEC, Barcode.FORMAT_PDF417,
                        Barcode.FORMAT_ALL_FORMATS
                ).build();
        barcodeScanner = BarcodeScanning.getClient(options);
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
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(requireContext());
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
                cameraControl = camera.getCameraControl();
            } catch (Exception e) {
                Log.e(TAG, "Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    // ==================== Image Analysis ====================

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isScanning) { imageProxy.close(); return; }

        @SuppressWarnings("UnsafeOptInUsageError")
        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty() && isScanning) {
                        for (Barcode barcode : barcodes) {
                            String rawValue = barcode.getRawValue();
                            if (rawValue != null && !rawValue.isEmpty()) {
                                onBarcodeDetected(rawValue, barcode.getFormat());
                                break;
                            }
                        }
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Scan: " + e.getMessage());
                    imageProxy.close();
                });
    }

    // ==================== Detection ====================

    private void onBarcodeDetected(String data, int format) {
        long now = System.currentTimeMillis();
        if (now - lastScanTime < SCAN_COOLDOWN_MS) return;
        lastScanTime = now;

        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);
        String formatName = getBarcodeFormatName(format);

        safeRun(() -> {
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
        });

        if (barcodeListener != null) barcodeListener.onBarcodeScanned(data, formatName);
        forwardToPOS(data);
    }

    private void forwardToPOS(String data) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            Fragment pos = activity.getSupportFragmentManager().findFragmentByTag("POS Mode");
            if (pos instanceof MiraPosFragment && pos.isVisible()) {
                ((MiraPosFragment) pos).onBarcodeScanned(data);
            } else {
                activity.onTagRead(data);
            }
        }
    }

    // ==================== Close ====================

    private void closeScanner() {
        try {
            isScanning = false;
            if (cameraControl != null) cameraControl.enableTorch(false);
            if (cameraProvider != null) cameraProvider.unbindAll();
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        } catch (Exception e) {
            if (getActivity() != null) getActivity().onBackPressed();
        }
    }

    // ==================== Animation ====================

    private void startScanAnimation() {
        if (scanLine == null || scannerOverlay == null) return;
        scanLine.animate().translationY(scannerOverlay.getHeight()).setDuration(2000)
            .withEndAction(() -> {
                scanLine.setTranslationY(0);
                if (isScanning) startScanAnimation();
            }).start();
    }

    // ==================== Flash ====================

    public void toggleFlash() {
        if (cameraControl != null) {
            isFlashOn = !isFlashOn;
            cameraControl.enableTorch(isFlashOn);
            Toast.makeText(getContext(),
                isFlashOn ? (isArabic ? "🔦 تم تشغيل الفلاش" : "🔦 Flash On")
                          : (isArabic ? "🔌 تم إطفاء الفلاش" : "🔌 Flash Off"),
                Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) {
        if (getActivity() != null) getActivity().runOnUiThread(r);
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
        if (cameraControl != null) cameraControl.enableTorch(false);
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (barcodeScanner != null) barcodeScanner.close();
        if (toneGenerator != null) toneGenerator.release();
    }
    }
