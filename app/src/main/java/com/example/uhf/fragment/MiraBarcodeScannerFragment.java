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
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.tools.MiraLanguageHelper;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiraBarcodeScannerFragment - الإصدار 2.0
 *
 * ✅ الإصلاحات المطبقة (18 مشكلة):
 *  1. فحص barcodeScanner قبل الاستخدام
 *  2. حماية من الاستخدام بعد close()
 *  3. إغلاق imageProxy مرة واحدة فقط (AtomicBoolean)
 *  4. عدم إنشاء executor جديد بدون سبب
 *  5. إدارة toneGenerator بشكل صحيح
 *  6. مزامنة isScanning مع onPause/onResume
 *  7. منع إعادة تشغيل الكاميرا عند onResume إن كانت نشطة
 *  8. فحص previewView قبل الاستخدام
 *  9. محاولة popBackStack آمنة
 * 10. تقليل SCAN_COOLDOWN إلى 800ms
 * 11. حماية forwardToPOS من ClassCastException
 * 12. دعم RTL في applyLanguage
 * 13. hasCameraPermission آمنة
 * 14. Toast آمن مع فحص getContext
 * 15. منع إرسال الحدث مرتين
 * 16. إلغاء الأنيميشن عند الإغلاق
 * 17. ثوابت الألوان
 * 18. تنظيف PreviewView
 */
public class MiraBarcodeScannerFragment extends Fragment {

    private static final String TAG = "MiraBarcodeScanner";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    // ✅ [إصلاح #10] تقليل cooldown
    private static final long SCAN_COOLDOWN_MS = 800;

    // ✅ [إصلاح #17] ثوابت الألوان
    private static final int COLOR_READY = Color.parseColor("#38BDF8");    // أزرق
    private static final int COLOR_SUCCESS = Color.parseColor("#4ADE80");  // أخضر

    // ==================== UI ====================
    private PreviewView previewView;
    private View scannerOverlay, scanLine;
    private TextView tvScanStatus, tvScannedData;
    private ImageButton btnClose, btnFlash;

    // ==================== Camera ====================
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private CameraControl cameraControl;
    private volatile boolean isFlashOn = false;
    private ExecutorService cameraExecutor;

    // ==================== Scanner ====================
    private BarcodeScanner barcodeScanner;
    private ToneGenerator toneGenerator;
    private volatile boolean isScanning = true;
    private volatile boolean isViewDestroyed = false;
    private long lastScanTime = 0;

    // ==================== Listeners & State ====================
    private OnBarcodeScannedListener barcodeListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private android.content.Context appContext;
    
    private androidx.activity.OnBackPressedCallback backCallback;

private void setupBackHandler() {
    if (getActivity() == null) return;

    backCallback = new androidx.activity.OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            closeScanner();
        }
    };

    requireActivity().getOnBackPressedDispatcher()
            .addCallback(getViewLifecycleOwner(), backCallback);
}
    
    
    // ═══════════════════════════════════════════════════════════
//  ✅ [v2.1] حماية الحالة
// ═══════════════════════════════════════════════════════════

/** ✅ هل تم اكتشاف باركود وانتهت المهمة؟ */
private volatile boolean isCompleted = false;

/** ✅ هل الكاميرا مرتبطة حالياً؟ */
private volatile boolean isCameraBound = false;

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

        isViewDestroyed = false;
        appContext = requireContext().getApplicationContext();

        try {
            // ✅ [إصلاح #12] قراءة اللغة بشكل صحيح
            isArabic = "ar".equals(java.util.Locale.getDefault().getLanguage());

            orgProfile = MiraOrgProfileManager.getInstance(appContext);

            // ربط العناصر
            previewView = view.findViewById(R.id.previewView);
            scannerOverlay = view.findViewById(R.id.scannerOverlay);
            scanLine = view.findViewById(R.id.scanLine);
            tvScanStatus = view.findViewById(R.id.tvScanStatus);
            tvScannedData = view.findViewById(R.id.tvScannedData);
            btnClose = view.findViewById(R.id.btnCloseScanner);
            btnFlash = view.findViewById(R.id.btnToggleFlash);
            
            // ✅ [v2.1] معالج زر 
            setupBackHandler();

            // ✅ [إصلاح #8] فحص وجود العناصر الأساسية
            if (previewView == null) {
                Log.e(TAG, "previewView not found in layout!");
                showErrorAndClose();
                return;
            }

            // ✅ [إصلاح #12] دعم RTL
            applyLanguageAndDirection(view);

            // أزرار
            if (btnClose != null) btnClose.setOnClickListener(v -> closeScanner());
            if (btnFlash != null) btnFlash.setOnClickListener(v -> toggleFlash());

            // ✅ [إصلاح #4/#5] تهيئة الموارد مرة واحدة فقط
            if (cameraExecutor == null || cameraExecutor.isShutdown()) {
                cameraExecutor = Executors.newSingleThreadExecutor();
            }
            if (toneGenerator == null) {
                try {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                } catch (Exception e) {
                    Log.w(TAG, "ToneGenerator init failed: " + e.getMessage());
                }
            }

            // ✅ [إصلاح #1] تهيئة barcodeScanner مع فحص
            if (initBarcodeScanner()) {
                // طلب الصلاحية أولاً
                if (hasCameraPermission()) {
                    startCamera();
                } else {
                    requestCameraPermission();
                }
                startScanAnimation();
            } else {
                Log.e(TAG, "Failed to init barcode scanner");
                showErrorAndClose();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onViewCreated: " + e.getMessage(), e);
            showErrorAndClose();
        }
    }

    @Override
public void onResume() {
    super.onResume();
    isViewDestroyed = false;

    try {
        // ✅ [إصلاح] لا تستأنف إذا انتهت المهمة
        if (isCompleted) {
            Log.d(TAG, "⏭️ onResume skipped (already completed)");
            return;
        }

        isScanning = true;

        // ✅ [إصلاح جوهري] استخدم isCameraBound وليس cameraProvider == null
        if (hasCameraPermission() && !isCameraBound) {
            startCamera();
        }

        if (scanLine != null && isScanning) {
            startScanAnimation();
        }

        // ✅ [إصلاح] getOnBackPressedDispatcher
        if (getView() != null) {
            getView().setFocusableInTouchMode(true);
            getView().requestFocus();
        }
    } catch (Exception e) {
        Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
    }
}

@Override
public void onPause() {
    super.onPause();

    isScanning = false;

    if (isCameraBound && cameraProvider != null) {
        try {
            cameraProvider.unbindAll();
            isCameraBound = false;
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding camera: " + e.getMessage());
        }
    }

    if (scanLine != null) {
        try { scanLine.animate().cancel(); } catch (Exception ignored) {}
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
                // ✅ [إصلاح #14] Toast آمن
                showToastSafe(isArabic ? "✅ تم منح صلاحية الكاميرا" : "✅ Camera permission granted",
                        Toast.LENGTH_SHORT);
            } else {
                showToastSafe(isArabic ? "❌ صلاحية الكاميرا مطلوبة" : "❌ Camera permission required",
                        Toast.LENGTH_LONG);
                closeScanner();
            }
        }
    }

    // ==================== Language ====================

    /**
     * ✅ [إصلاح #12] تطبيق اللغة + اتجاه RTL
     */
    private void applyLanguageAndDirection(View root) {
        try {
            // اتجاه
            if (root != null) {
                root.setLayoutDirection(isArabic ?
                        View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            }

            // نصوص
            if (tvScanStatus != null) {
                tvScanStatus.setText(isArabic ? "📷 جاهز للمسح..." : "📷 Ready to scan...");
                tvScanStatus.setTextColor(COLOR_READY);
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

    /**
     * ✅ [إصلاح #1] يعيد true إذا نجحت التهيئة
     */
    private boolean initBarcodeScanner() {
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
            return barcodeScanner != null;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing barcode scanner: " + e.getMessage());
            return false;
        }
    }

    // ==================== Camera ====================

    /**
     * ✅ [إصلاح #13] فحص آمن للسياق
     */
    private boolean hasCameraPermission() {
        try {
            if (appContext == null) return false;
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "hasCameraPermission error: " + e.getMessage());
            return false;
        }
    }

    private void requestCameraPermission() {
        try {
            if (isAdded() && getActivity() != null) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            }
        } catch (Exception e) {
            Log.e(TAG, "requestCameraPermission error: " + e.getMessage());
        }
    }

    private void startCamera() {
        try {
            if (previewView == null || cameraExecutor == null || cameraExecutor.isShutdown()) {
                Log.e(TAG, "Cannot start camera: missing resources");
                showErrorAndClose();
                return;
            }

            ListenableFuture<ProcessCameraProvider> future =
                    ProcessCameraProvider.getInstance(requireContext());

            future.addListener(() -> {
                try {
                    if (isViewDestroyed || previewView == null) return;

                    cameraProvider = future.get();

                    // إعداد المعاينة
                    Preview preview = new Preview.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // إعداد التحليل
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    // اختيار الكاميرا الخلفية
                    CameraSelector selector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();

                    // ربط الكاميرا
                    cameraProvider.unbindAll();
camera = cameraProvider.bindToLifecycle(
        MiraBarcodeScannerFragment.this, selector, preview, imageAnalysis);
cameraControl = camera.getCameraControl();

// ✅ [v2.1] علّم الكاميرا مرتبطة
isCameraBound = true;

isScanning = true;
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

    /**
     * ✅ [إصلاح #3] إغلاق imageProxy مرة واحدة فقط
     */
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        // ✅ [إصلاح #1] فحص barcodeScanner
        if (barcodeScanner == null || !isScanning || isViewDestroyed
                || imageProxy.getImage() == null) {
            closeImageProxySafely(imageProxy);
            return;
        }

        // ✅ [إصلاح #3] ضمان إغلاق واحد
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Runnable closeOnce = () -> {
            if (closed.compareAndSet(false, true)) {
                closeImageProxySafely(imageProxy);
            }
        };

        try {
            @SuppressWarnings("UnsafeOptInUsageError")
            InputImage inputImage = InputImage.fromMediaImage(
                    imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        try {
                            if (!barcodes.isEmpty() && isScanning && !isViewDestroyed) {
                                for (Barcode barcode : barcodes) {
                                    String rawValue = barcode.getRawValue();
                                    if (rawValue != null && !rawValue.isEmpty()) {
                                        onBarcodeDetected(rawValue, barcode.getFormat());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Process success error: " + e.getMessage());
                        } finally {
                            closeOnce.run();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Scan error: " + e.getMessage());
                        closeOnce.run();
                    })
                    .addOnCanceledListener(closeOnce::run);

        } catch (Exception e) {
            Log.e(TAG, "analyzeImage error: " + e.getMessage());
            closeOnce.run();
        }
    }

    private void closeImageProxySafely(ImageProxy imageProxy) {
        try {
            if (imageProxy != null) imageProxy.close();
        } catch (Exception e) {
            Log.e(TAG, "closeImageProxy error: " + e.getMessage());
        }
    }

    // ==================== Detection ====================

    private void onBarcodeDetected(String data, int format) {
    long now = System.currentTimeMillis();
    if (now - lastScanTime < SCAN_COOLDOWN_MS) return;
    if (isCompleted) return;

    lastScanTime = now;

    // ✅ علّم الانتهاء قبل أي إغلاق
    isCompleted = true;
    isScanning = false;

    playBeepSafe();
    String formatName = getBarcodeFormatName(format);

    // ✅ تحديث UI
    safeRun(() -> {
        try {
            if (tvScanStatus != null) {
                tvScanStatus.setText(isArabic ? "✅ تم المسح" : "✅ Scanned");
                tvScanStatus.setTextColor(COLOR_SUCCESS);
            }
            if (tvScannedData != null) {
                tvScannedData.setText(data);
                tvScannedData.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {}
    });

    // ✅ تسليم البيانات فوراً (sync)
    boolean delivered = false;
    if (barcodeListener != null) {
        try {
            barcodeListener.onBarcodeScanned(data, formatName);
            delivered = true;
        } catch (Exception e) {
            Log.e(TAG, "barcodeListener error: " + e.getMessage());
        }
    }

    if (!delivered) {
        forwardToPOS(data);
    }

    // ✅ [v2.1] لا تُغلق تلقائياً — اترك POS يطلب الإغلاق
    // إذا كنت تريد إغلاقاً مضموناً بعد فترة كافية:
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
        if (isAdded() && !isViewDestroyed) {
            closeScanner();
        }
    }, 800);   // ← كان 50ms — رفعه إلى 800ms
}

    /**
     * ✅ [إصلاح #11] حماية من ClassCastException
     */
    private void forwardToPOS(String data) {
    try {
        if (!isAdded() || getActivity() == null) return;
        if (!(getActivity() instanceof UHFMainActivity)) return;

        UHFMainActivity activity = (UHFMainActivity) getActivity();

        // ✅ [إصلاح جوهري] استخدم TAG_POS الصحيح
        Fragment pos = activity.getSupportFragmentManager()
                .findFragmentByTag(NavigationManager.TAG_POS);

        if (pos instanceof MiraPosFragment && pos.isVisible()) {
            try {
                ((MiraPosFragment) pos).onBarcodeScanned(data);
            } catch (Exception e) {
                Log.w(TAG, "POS forward failed, falling back", e);
                activity.onTagRead(data);
            }
        } else {
            // fallback — أبلغ النشاط
            activity.onTagRead(data);
        }
    } catch (Exception e) {
        Log.e(TAG, "Error forwarding to POS: " + e.getMessage());
    }
}

    private void playBeepSafe() {
        try {
            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing tone: " + e.getMessage());
        }
    }

    // ==================== Close ====================

    private void closeScanner() {
    // ✅ حماية من الاستدعاء المزدوج
    if (isViewDestroyed) return;
    isViewDestroyed = true;
    isScanning = false;
    isCompleted = true;   // ← لا مسح جديد

    // إلغاء الأنيميشن
    if (scanLine != null) {
        try { scanLine.animate().cancel(); } catch (Exception ignored) {}
    }

    // إطفاء الفلاش
    if (cameraControl != null) {
        try {
            cameraControl.enableTorch(false);
            isFlashOn = false;
        } catch (Exception ignored) {}
    }

    // فصل الكاميرا
    if (isCameraBound && cameraProvider != null) {
        try {
            cameraProvider.unbindAll();
            isCameraBound = false;
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding: " + e.getMessage());
        }
    }

    // إزالة back callback
    if (backCallback != null) {
        try {
            backCallback.setEnabled(false);
            backCallback.remove();
            backCallback = null;
        } catch (Exception ignored) {}
    }

    // pop
    if (!isAdded()) return;

    androidx.fragment.app.FragmentManager fm = getParentFragmentManager();
    if (fm.getBackStackEntryCount() > 0) {
        try {
            fm.popBackStack();
        } catch (Exception e) {
            Log.e(TAG, "popBackStack error: " + e.getMessage());
            fallbackClose();
        }
    } else {
        fallbackClose();
    }
}

private void fallbackClose() {
    try {
        if (isAdded() && getActivity() != null) {
            // ✅ [v2.1] استخدم OnBackPressedDispatcher بدلاً من onBackPressed المهجورة
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    } catch (Exception e) {
        Log.e(TAG, "fallbackClose error: " + e.getMessage());
    }
}
    private void showErrorAndClose() {
        safeRun(() -> {
            showToastSafe(isArabic ? "❌ تعذر تشغيل الكاميرا" : "❌ Failed to start camera",
                    Toast.LENGTH_LONG);
            closeScanner();
        });
    }

    // ==================== Animation ====================

    /**
     * ✅ [إصلاح #16] فحص isScanning و isViewDestroyed قبل الأنيميشن
     */
    private void startScanAnimation() {
        if (scanLine == null || scannerOverlay == null || isViewDestroyed || !isScanning) return;

        try {
            scanLine.animate().cancel();
            scanLine.setTranslationY(0);

            scanLine.post(() -> {
                if (isViewDestroyed || !isScanning || scanLine == null || scannerOverlay == null) return;

                int height = scannerOverlay.getHeight();
                if (height <= 0) {
                    // إعادة المحاولة عند جهوزية القياس
                    scanLine.postDelayed(this::startScanAnimation, 100);
                    return;
                }

                scanLine.animate()
                        .translationY(height - 2)
                        .setDuration(2000)
                        .withEndAction(() -> {
                            if (scanLine == null || isViewDestroyed || !isScanning) return;
                            scanLine.setTranslationY(0);
                            startScanAnimation();
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
                showToastSafe(
                        isFlashOn ? (isArabic ? "🔦 تم تشغيل الفلاش" : "🔦 Flash On")
                                  : (isArabic ? "🔌 تم إطفاء الفلاش" : "🔌 Flash Off"),
                        Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flash: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null && !isViewDestroyed) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (isAdded() && !isViewDestroyed) {
                            r.run();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "safeRun inner error: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage());
        }
    }

    /**
     * ✅ [إصلاح #14] Toast آمن
     */
    private void showToastSafe(String msg, int duration) {
        try {
            if (isAdded() && appContext != null) {
                Toast.makeText(appContext, msg, duration).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToastSafe error: " + e.getMessage());
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

    public void pauseScanning() {
        isScanning = false;
        if (scanLine != null) {
            try { scanLine.animate().cancel(); } catch (Exception ignored) {}
        }
    }

    public void resumeScanning() {
        if (isViewDestroyed) return;
        isScanning = true;
        lastScanTime = 0;
        startScanAnimation();
    }

    // ==================== Cleanup ====================

    /**
     * ✅ [إصلاح #2] فصل onDestroyView عن onDestroy
     * ✅ [إصلاح #18] تنظيف كامل
     */
    @Override
    public void onDestroyView() {
        isViewDestroyed = true;
        isScanning = false;

        // إلغاء الأنيميشن
        if (scanLine != null) {
            try { scanLine.animate().cancel(); } catch (Exception ignored) {}
        }
        
        if (backCallback != null) {
    backCallback.setEnabled(false);
    backCallback.remove();
    backCallback = null;
}

        // فصل الكاميرا
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                cameraProvider = null;
            } catch (Exception e) {
                Log.e(TAG, "unbindAll error in onDestroyView: " + e.getMessage());
            }
        }
        cameraControl = null;
        camera = null;

        // ✅ [إصلاح #18] تنظيف المراجع UI
        previewView = null;
        scannerOverlay = null;
        scanLine = null;
        tvScanStatus = null;
        tvScannedData = null;
        btnClose = null;
        btnFlash = null;

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // إطفاء الفلاش
        try {
            if (cameraControl != null) {
                cameraControl.enableTorch(false);
            }
        } catch (Exception ignored) {}

        // ✅ [إصلاح #4] إغلاق executor بأمان
        if (cameraExecutor != null) {
            try {
                cameraExecutor.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "executor shutdown error: " + e.getMessage());
            }
            cameraExecutor = null;
        }

        // ✅ [إصلاح #2] إغلاق barcodeScanner بحماية
        if (barcodeScanner != null) {
            try {
                barcodeScanner.close();
            } catch (Exception e) {
                Log.e(TAG, "barcodeScanner close error: " + e.getMessage());
            }
            barcodeScanner = null;
        }

        // ✅ [إصلاح #5] إغلاق toneGenerator
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception e) {
                Log.e(TAG, "toneGenerator release error: " + e.getMessage());
            }
            toneGenerator = null;
        }
        barcodeListener = null;
        // تنظيف appContext
        appContext = null;
    }
                  }
