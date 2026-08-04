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
import androidx.camera.core.Camera;
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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📷 MIRA GS1 DATAMATRIX Scanner
 * ماسح باركود احترافي يدعم:
 * - GS1 DataMatrix
 * - QR Code
 * - EAN-13 / GTIN
 * - Code-128
 * - جميع صيغ GS1
 */
public class MiraBarcodeScannerFragment extends Fragment {

    private static final String TAG = "MiraBarcodeScanner";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    private PreviewView previewView;
    private View scannerOverlay;
    private View scanLine;
    private TextView tvScanStatus;
    private TextView tvScannedData;
    private ImageButton btnCloseScanner;
    private ImageButton btnToggleFlash;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ToneGenerator toneGenerator;

    private boolean isScanning = true;
    private boolean isFlashOn = false;
    private long lastScanTime = 0;
    private static final long SCAN_COOLDOWN_MS = 1500;

    // مستمع الباركود
    private OnBarcodeScannedListener barcodeListener;

    public interface OnBarcodeScannedListener {
        void onBarcodeScanned(String barcodeData, String format);
    }

    public void setOnBarcodeScannedListener(OnBarcodeScannedListener listener) {
        this.barcodeListener = listener;
    }

    // ===================== Lifecycle =====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_barcode_scanner, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        previewView = view.findViewById(R.id.previewView);
        scannerOverlay = view.findViewById(R.id.scannerOverlay);
        scanLine = view.findViewById(R.id.scanLine);
        tvScanStatus = view.findViewById(R.id.tvScanStatus);
        tvScannedData = view.findViewById(R.id.tvScannedData);
        btnCloseScanner = view.findViewById(R.id.btnCloseScanner);
        btnToggleFlash = view.findViewById(R.id.btnToggleFlash);

        // ✅ زر الإغلاق (×)
        if (btnCloseScanner != null) {
            btnCloseScanner.setOnClickListener(v -> closeScanner());
        }

        // ✅ زر الفلاش
        if (btnToggleFlash != null) {
            btnToggleFlash.setOnClickListener(v -> toggleFlash());
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);

        // تهيئة ماسح الباركود
        initBarcodeScanner();

        // طلب صلاحية الكاميرا
        if (hasCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        // تشغيل Animation خط المسح
        startScanAnimation();
    }

    @Override
    public void onResume() {
        super.onResume();

        // ✅ معالجة زر الرجوع للجهاز
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

    // ===================== Barcode Scanner Init =====================

    private void initBarcodeScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_AZTEC,
                        Barcode.FORMAT_PDF417,
                        Barcode.FORMAT_ALL_FORMATS
                )
                .build();

        barcodeScanner = BarcodeScanning.getClient(options);
    }

    // ===================== Camera =====================

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(requireContext());

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Camera start error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    // ===================== Image Analysis =====================

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isScanning) {
            imageProxy.close();
            return;
        }

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
                    Log.e(TAG, "Scan error: " + e.getMessage());
                    imageProxy.close();
                });
    }

    // ===================== Barcode Detection =====================

    private void onBarcodeDetected(String data, int format) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastScanTime < SCAN_COOLDOWN_MS) {
            return;
        }
        lastScanTime = currentTime;

        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);

        String formatName = getBarcodeFormatName(format);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvScannedData.setText(formatName + ": " + data);
                tvScannedData.setVisibility(View.VISIBLE);
                tvScanStatus.setText("✅ تم المسح");
                tvScanStatus.setTextColor(Color.parseColor("#4ADE80"));

                tvScannedData.postDelayed(() -> {
                    tvScannedData.setVisibility(View.GONE);
                    tvScanStatus.setText("📷 جاهز للمسح...");
                    tvScanStatus.setTextColor(Color.parseColor("#38BDF8"));
                }, 2000);
            });
        }

        if (barcodeListener != null) {
            barcodeListener.onBarcodeScanned(data, formatName);
        }

        forwardToPOS(data);

        Log.d(TAG, "Barcode detected [" + formatName + "]: " + data);
    }

    private void forwardToPOS(String data) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();

            Fragment posFragment = activity.getSupportFragmentManager()
                    .findFragmentByTag("POS Mode");
            if (posFragment instanceof MiraPosFragment && posFragment.isVisible()) {
                ((MiraPosFragment) posFragment).onBarcodeScanned(data);
            } else {
                activity.onTagRead(data);
            }
        }
    }

    // ===================== ✅ الإغلاق الآمن =====================

    /**
     * إغلاق الماسح والعودة للصفحة السابقة بأمان تام
     */
    private void closeScanner() {
        try {
            // إيقاف المسح
            isScanning = false;

            // إيقاف الكاميرا
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }

            // العودة للصفحة السابقة
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing scanner: " + e.getMessage());
            // محاولة أخيرة للرجوع
            try {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            } catch (Exception ignored) {}
        }
    }

    // ===================== فلاش =====================

    private void toggleFlash() {
        if (cameraProvider == null) return;

        isFlashOn = !isFlashOn;
        // ملاحظة: تفعيل الفلاش يحتاج CameraControl من CameraX
        // هذه نسخة مبسطة
        if (btnToggleFlash != null) {
            btnToggleFlash.setImageResource(isFlashOn ?
                    android.R.drawable.ic_menu_manage :
                    android.R.drawable.ic_menu_manage);
        }
        Toast.makeText(getContext(), isFlashOn ? "فلاش يعمل" : "فلاش متوقف", Toast.LENGTH_SHORT).show();
    }

    // ===================== Animation =====================

    private void startScanAnimation() {
        if (scanLine == null || scannerOverlay == null) return;

        float maxTranslation = scannerOverlay.getHeight() - scanLine.getHeight();
        scanLine.animate()
                .translationY(maxTranslation)
                .setDuration(2000)
                .withEndAction(() -> {
                    scanLine.setTranslationY(0);
                    if (isScanning && isAdded()) {
                        startScanAnimation();
                    }
                })
                .start();
    }

    // ===================== Utilities =====================

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

    public void pauseScanning() {
        isScanning = false;
    }

    public void resumeScanning() {
        isScanning = true;
        lastScanTime = 0;
    }

    // ===================== Cleanup =====================

    @Override
    public void onDestroy() {
        super.onDestroy();
        isScanning = false;
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
    }
                                            }
