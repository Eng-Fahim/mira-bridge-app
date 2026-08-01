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

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ToneGenerator toneGenerator;

    private boolean isScanning = true;
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

    // ===================== Barcode Scanner Init =====================

    private void initBarcodeScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_DATA_MATRIX,      // GS1 DataMatrix
                        Barcode.FORMAT_QR_CODE,           // QR Code
                        Barcode.FORMAT_EAN_13,            // GTIN-13
                        Barcode.FORMAT_EAN_8,             // GTIN-8
                        Barcode.FORMAT_CODE_128,          // Code-128
                        Barcode.FORMAT_CODE_39,           // Code-39
                        Barcode.FORMAT_UPC_A,             // UPC-A
                        Barcode.FORMAT_UPC_E,             // UPC-E
                        Barcode.FORMAT_AZTEC,             // Aztec
                        Barcode.FORMAT_PDF417,            // PDF417
                        Barcode.FORMAT_ALL_FORMATS        // جميع الصيغ
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

                // Preview
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Image Analysis
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                // Camera Selector (الكاميرا الخلفية)
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                // Unbind before binding
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis);

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

        // منع المسح المتكرر
        if (currentTime - lastScanTime < SCAN_COOLDOWN_MS) {
            return;
        }
        lastScanTime = currentTime;

        // تشغيل صوت النجاح
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);

        String formatName = getBarcodeFormatName(format);

        // عرض البيانات الممسوحة
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvScannedData.setText(formatName + ": " + data);
                tvScannedData.setVisibility(View.VISIBLE);
                tvScanStatus.setText("✅ تم المسح");
                tvScanStatus.setTextColor(Color.parseColor("#4ADE80"));

                // إخفاء بعد 2 ثانية
                tvScannedData.postDelayed(() -> {
                    tvScannedData.setVisibility(View.GONE);
                    tvScanStatus.setText("📷 جاهز للمسح...");
                    tvScanStatus.setTextColor(Color.parseColor("#38BDF8"));
                }, 2000);
            });
        }

        // إرسال البيانات
        if (barcodeListener != null) {
            barcodeListener.onBarcodeScanned(data, formatName);
        }

        // أيضاً إرسال إلى POS Mode
        forwardToPOS(data);

        Log.d(TAG, "Barcode detected [" + formatName + "]: " + data);
    }

    private void forwardToPOS(String data) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();

            // البحث عن MiraPosFragment
            Fragment posFragment = activity.getSupportFragmentManager()
                    .findFragmentByTag("POS Mode");
            if (posFragment instanceof MiraPosFragment && posFragment.isVisible()) {
                ((MiraPosFragment) posFragment).onBarcodeScanned(data);
            } else {
                // إذا لم يكن POS مرئياً، استخدم onTagRead
                activity.onTagRead(data);
            }
        }
    }

    // ===================== Animation =====================

    private void startScanAnimation() {
        if (scanLine == null) return;

        scanLine.animate()
                .translationY(scannerOverlay.getHeight())
                .setDuration(2000)
                .withEndAction(() -> {
                    scanLine.setTranslationY(0);
                    if (isScanning) {
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

    public void toggleFlash() {
        // TODO: إضافة تشغيل/إطفاء الفلاش
    }

    // ===================== Cleanup =====================

    @Override
    public void onDestroy() {
        super.onDestroy();
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
