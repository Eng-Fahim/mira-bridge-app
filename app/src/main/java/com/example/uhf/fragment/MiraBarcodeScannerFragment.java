package com.example.uhf.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.navigation.NavigationManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraBarcodeScannerFragment v4.0 — Micro-Tag Precision Edition
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * مهيأ خصيصاً لبطاقات الذهب والمجوهرات متناهية الصغر (3mm - 8mm):
 *   ✅ Micro-ROI Center Crop: تركيز التحليل على منطقة الهدف لرفع دقة الفك
 *   ✅ Quick Optical/Digital Zoom Bar: أزرار 1x / 2x / 3x / 4x / 5x + Pinch-to-Zoom
 *   ✅ Smart Auto-Zoom: تكبير تلقائي ذكي فور اكتشاف كود صغير
 *   ✅ Continuous AF + Tap-to-Focus + Anti-Glare Torch Control
 *   ✅ Multi-frame Confirmation لمنع القراءات الخاطئة
 */
public class MiraBarcodeScannerFragment extends Fragment {

    private static final String TAG = "MiraBarcodeScanner";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    private static final long SCAN_COOLDOWN_MS = 800L;

    private static final int COLOR_READY   = Color.parseColor("#38BDF8");
    private static final int COLOR_SUCCESS = Color.parseColor("#10B981");
    private static final int COLOR_GOLD    = Color.parseColor("#D4AF37");

    private static final float MIN_ZOOM = 1.0f;
    private static final float DEFAULT_MICRO_ZOOM = 2.0f;
    private static final float MAX_ZOOM_FALLBACK = 5.0f;

    private static final long CONFIRM_WINDOW_MS = 220L;
    private static final int REQUIRED_CONFIRMATIONS = 2;

    // Auto-Zoom للبطاقات الدقيقة
    private static final long AUTO_ZOOM_COOLDOWN_MS = 650L;
    private static final float MIN_BARCODE_RATIO = 0.018f;
    private static final float TARGET_BARCODE_RATIO = 0.095f;

    private static final float[] ZOOM_PRESETS = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

    // ==================== UI ====================
    private PreviewView previewView;
    private View scannerOverlay, scanLine;
    private TextView tvScanStatus, tvScannedData;
    private TextView tvZoomLevel;
    private ImageButton btnClose, btnFlash;

    private final List<TextView> zoomChipViews = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
    private long lastScanTime = 0L;

    // ==================== Listeners & State ====================
    private OnBarcodeScannedListener barcodeListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private Context appContext;

    private OnBackPressedCallback backCallback;

    private volatile boolean isCompleted = false;
    private volatile boolean isCameraBound = false;

    // ==================== Zoom & Focus ====================
    private volatile float currentZoom = 1.0f;
    private volatile float maxSupportedZoom = MAX_ZOOM_FALLBACK;
    private ScaleGestureDetector scaleDetector;
    private volatile long lastAutoZoomTime = 0L;

    // ==================== Multi-frame Confirmation ====================
    @Nullable private volatile String candidateCode = null;
    private volatile long candidateTime = 0L;
    private volatile int candidateConfirmations = 0;

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
        isCompleted = false;
        appContext = requireContext().getApplicationContext();

        try {
            isArabic = "ar".equals(Locale.getDefault().getLanguage());
            orgProfile = MiraOrgProfileManager.getInstance(appContext);

            previewView = view.findViewById(R.id.previewView);
            scannerOverlay = view.findViewById(R.id.scannerOverlay);
            scanLine = view.findViewById(R.id.scanLine);
            tvScanStatus = view.findViewById(R.id.tvScanStatus);
            tvScannedData = view.findViewById(R.id.tvScannedData);
            tvZoomLevel = view.findViewById(R.id.tvZoomLevel);
            btnClose = view.findViewById(R.id.btnCloseScanner);
            btnFlash = view.findViewById(R.id.btnToggleFlash);

            setupBackHandler();

            if (previewView == null) {
                Log.e(TAG, "previewView not found in layout!");
                showErrorAndClose();
                return;
            }

            applyLanguageAndDirection(view);
            injectMicroZoomControlBar(view);

            if (btnClose != null) btnClose.setOnClickListener(v -> closeScanner());
            if (btnFlash != null) btnFlash.setOnClickListener(v -> toggleFlash());

            if (cameraExecutor == null || cameraExecutor.isShutdown()) {
                cameraExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "MiraMicroBarcodeAnalyzer");
                    t.setDaemon(true);
                    return t;
                });
            }

            if (toneGenerator == null) {
                try {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                } catch (Exception e) {
                    Log.w(TAG, "ToneGenerator init failed: " + e.getMessage());
                }
            }

            if (initBarcodeScanner()) {
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

    /**
     * إضافة شريط تحكم بالتكبير البصري السريع (1x - 5x) فوق واجهة الكاميرا
     * لتسهيل قراءة الباركود وQR الصغير جداً بدون اهتزاز اليد.
     */
    private void injectMicroZoomControlBar(@NonNull View root) {
        if (!(root instanceof ViewGroup)) return;
        try {
            ViewGroup container = (ViewGroup) root;
            zoomChipViews.clear();

            LinearLayout barWrapper = new LinearLayout(requireContext());
            barWrapper.setOrientation(LinearLayout.VERTICAL);
            barWrapper.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView hintLabel = new TextView(requireContext());
            hintLabel.setText(isArabic
                    ? "وضع البطاقات الدقيقة • اختر التكبير أو المس الشاشة للتركيز"
                    : "Micro-Tag Mode • Select Zoom or Tap Screen to Focus");
            hintLabel.setTextColor(Color.parseColor("#E2E8F0"));
            hintLabel.setTextSize(11f);
            hintLabel.setGravity(Gravity.CENTER);
            hintLabel.setPadding(dp(12), dp(4), dp(12), dp(8));
            barWrapper.addView(hintLabel);

            LinearLayout pillRow = new LinearLayout(requireContext());
            pillRow.setOrientation(LinearLayout.HORIZONTAL);
            pillRow.setGravity(Gravity.CENTER);
            pillRow.setPadding(dp(8), dp(6), dp(8), dp(6));

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(Color.parseColor("#CC0A1124"));
            pillBg.setCornerRadius(dp(28));
            pillBg.setStroke(dp(1), Color.parseColor("#55D4AF37"));
            pillRow.setBackground(pillBg);

            for (final float zoomFactor : ZOOM_PRESETS) {
                TextView chip = new TextView(requireContext());
                String label = String.format(Locale.US, "%.0fx", zoomFactor);
                chip.setText(label);
                chip.setTextSize(12f);
                chip.setTypeface(null, Typeface.BOLD);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(13), dp(6), dp(13), dp(6));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.setMargins(dp(3), 0, dp(3), 0);
                chip.setLayoutParams(lp);

                chip.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    applyZoomRatio(zoomFactor, true);
                });

                zoomChipViews.add(chip);
                pillRow.addView(chip);
            }

            barWrapper.addView(pillRow);

            if (container instanceof FrameLayout) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                params.bottomMargin = dp(36);
                container.addView(barWrapper, params);
            } else {
                container.addView(barWrapper);
            }

            updateZoomChipsSelection(DEFAULT_MICRO_ZOOM);
        } catch (Exception e) {
            Log.w(TAG, "injectMicroZoomControlBar error: " + e.getMessage());
        }
    }

    private void updateZoomChipsSelection(float activeZoom) {
        try {
            for (int i = 0; i < zoomChipViews.size() && i < ZOOM_PRESETS.length; i++) {
                TextView chip = zoomChipViews.get(i);
                float preset = ZOOM_PRESETS[i];
                boolean isSelected = Math.abs(activeZoom - preset) < 0.45f;

                GradientDrawable chipBg = new GradientDrawable();
                chipBg.setCornerRadius(dp(20));
                if (isSelected) {
                    chipBg.setColor(COLOR_GOLD);
                    chip.setTextColor(Color.parseColor("#060B19"));
                } else {
                    chipBg.setColor(Color.TRANSPARENT);
                    chip.setTextColor(Color.parseColor("#CBD5E1"));
                }
                chip.setBackground(chipBg);
            }
        } catch (Exception ignored) {}
    }

    private int dp(int value) {
        try {
            return Math.round(value * getResources().getDisplayMetrics().density);
        } catch (Exception e) {
            return value;
        }
    }

    private void setupBackHandler() {
        if (getActivity() == null) return;

        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeScanner();
            }
        };

        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        isViewDestroyed = false;

        try {
            if (isCompleted) {
                Log.d(TAG, "⏭️ onResume skipped (already completed)");
                return;
            }

            isScanning = true;

            if (hasCameraPermission() && !isCameraBound) {
                startCamera();
            }

            if (scanLine != null && isScanning) {
                startScanAnimation();
            }

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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
                showToastSafe(isArabic ? "✅ تم منح صلاحية الكاميرا" : "✅ Camera permission granted",
                        Toast.LENGTH_SHORT);
            } else {
                showToastSafe(isArabic ? "❌ صلاحية الكاميرا مطلوبة" : "❌ Camera permission required",
                        Toast.LENGTH_LONG);
                closeScanner();
            }
        }
    }

    private void applyLanguageAndDirection(View root) {
        try {
            if (root != null) {
                root.setLayoutDirection(isArabic ?
                        View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            }
            if (tvScanStatus != null) {
                tvScanStatus.setText(isArabic
                        ? "🎯 ضع البطاقة الصغيرة داخل الإطار"
                        : "🎯 Place micro-tag inside frame");
                tvScanStatus.setTextColor(COLOR_READY);
            }
            if (btnFlash != null) btnFlash.setContentDescription(isArabic ? "فلاش" : "Flash");
            if (btnClose != null) btnClose.setContentDescription(isArabic ? "إغلاق" : "Close");
        } catch (Exception e) {
            Log.e(TAG, "Error applying language: " + e.getMessage());
        }
    }

    private boolean initBarcodeScanner() {
        try {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                            Barcode.FORMAT_QR_CODE,
                            Barcode.FORMAT_DATA_MATRIX,
                            Barcode.FORMAT_CODE_128,
                            Barcode.FORMAT_EAN_13,
                            Barcode.FORMAT_EAN_8,
                            Barcode.FORMAT_CODE_39
                    )
                    .enableAllPotentialBarcodes()
                    .build();
            barcodeScanner = BarcodeScanning.getClient(options);
            Log.i(TAG, "✅ ML Kit Micro-Tag Scanner ready — QR + DataMatrix + 1D");
            return barcodeScanner != null;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing barcode scanner: " + e.getMessage());
            return false;
        }
    }

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

                    Preview preview = new Preview.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // دقة عالية لالتقاط تفاصيل الكود الصغير جداً
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1920, 1080))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                    CameraSelector selector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();

                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(
                            MiraBarcodeScannerFragment.this, selector, preview, imageAnalysis);
                    cameraControl = camera.getCameraControl();

                    try {
                        CameraInfo info = camera.getCameraInfo();
                        if (info.getZoomState().getValue() != null) {
                            maxSupportedZoom = Math.min(8.0f,
                                    info.getZoomState().getValue().getMaxZoomRatio());
                        }
                    } catch (Exception ignored) {}

                    isCameraBound = true;
                    isScanning = true;

                    setupFocusAndZoom();

                    // تفعيل تكبير ابتدائي 2.0x لتسهيل قراءة بطاقات المجوهرات الصغيرة فوراً
                    applyZoomRatio(DEFAULT_MICRO_ZOOM, false);

                    Log.d(TAG, "✅ Camera bound at 1920×1080 with Micro-Zoom 2.0x");

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

    @SuppressLint("ClickableViewAccessibility")
    private void setupFocusAndZoom() {
        if (cameraControl == null || previewView == null) return;

        try {
            // 1) تركيز تلقائي مستمر في مركز الإطار
            triggerCenterAutoFocus();

            // 2) اللمس للتركيز الفوري + التكبير بالسحب (Pinch-to-Zoom)
            scaleDetector = new ScaleGestureDetector(
                    requireContext(),
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(@NonNull ScaleGestureDetector d) {
                            try {
                                float newZoom = currentZoom * d.getScaleFactor();
                                applyZoomRatio(newZoom, true);
                            } catch (Exception ignored) {}
                            return true;
                        }
                    });

            previewView.setOnTouchListener((v, event) -> {
                if (scaleDetector != null) {
                    scaleDetector.onTouchEvent(event);
                }
                if (event.getAction() == MotionEvent.ACTION_UP && event.getPointerCount() == 1) {
                    try {
                        FocusMeteringAction focus =
                                new FocusMeteringAction.Builder(
                                        previewView.getMeteringPointFactory()
                                                .createPoint(event.getX(), event.getY()),
                                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
                                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                        cameraControl.startFocusAndMetering(focus);
                        showHintSafe(isArabic ? "🎯 تم ضبط البؤرة" : "🎯 Focus locked", COLOR_READY);
                    } catch (Exception ignored) {}
                }
                return true;
            });

        } catch (Exception e) {
            Log.w(TAG, "setupFocusAndZoom error: " + e.getMessage());
        }
    }

    private void triggerCenterAutoFocus() {
        if (cameraControl == null || previewView == null) return;
        try {
            FocusMeteringAction autofocus =
                    new FocusMeteringAction.Builder(
                            previewView.getMeteringPointFactory().createPoint(0.5f, 0.5f),
                            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(2, TimeUnit.SECONDS).build();
            cameraControl.startFocusAndMetering(autofocus);
        } catch (Exception e) {
            Log.w(TAG, "triggerCenterAutoFocus failed: " + e.getMessage());
        }
    }

    private void showHintSafe(@NonNull String hint, int color) {
        safeRun(() -> {
            try {
                if (tvScanStatus != null && isScanning) {
                    tvScanStatus.setText(hint);
                    tvScanStatus.setTextColor(color);
                }
            } catch (Exception ignored) {}
        });
    }

    // ==================== Image Analysis with Micro-ROI ====================

    @SuppressWarnings("UnsafeOptInUsageError")
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (barcodeScanner == null || !isScanning || isViewDestroyed || mediaImage == null) {
            closeImageProxySafely(imageProxy);
            return;
        }

        final AtomicBoolean closed = new AtomicBoolean(false);
        final Runnable closeOnce = () -> {
            if (closed.compareAndSet(false, true)) {
                closeImageProxySafely(imageProxy);
            }
        };

        try {
            // اقتصاص منطقة التركيز المركزية (Micro-ROI) لرفع حساسية ML Kit مع الرموز الصغيرة
            int imgW = mediaImage.getWidth();
            int imgH = mediaImage.getHeight();
            if (imgW > 200 && imgH > 200) {
                int cropW = (int) (imgW * 0.68f);
                int cropH = (int) (imgH * 0.68f);
                int left = (imgW - cropW) / 2;
                int top = (imgH - cropH) / 2;
                imageProxy.setCropRect(new Rect(left, top, left + cropW, top + cropH));
            }

            InputImage inputImage = InputImage.fromMediaImage(
                    mediaImage, imageProxy.getImageInfo().getRotationDegrees());

            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        try {
                            if (barcodes.isEmpty() || !isScanning || isViewDestroyed) {
                                return;
                            }

                            Barcode decodable = null;
                            Barcode largestDetected = null;
                            int largestArea = 0;

                            for (Barcode b : barcodes) {
                                String rawValue = b.getRawValue();
                                if (rawValue != null && !rawValue.isEmpty()) {
                                    decodable = b;
                                    break;
                                }

                                Rect box = b.getBoundingBox();
                                if (box != null) {
                                    int area = box.width() * box.height();
                                    if (area > largestArea) {
                                        largestArea = area;
                                        largestDetected = b;
                                    }
                                }
                            }

                            if (decodable != null) {
                                onBarcodeDetected(decodable.getRawValue(), decodable.getFormat());
                                return;
                            }

                            if (largestDetected != null) {
                                tryAutoZoomForSmallBarcode(largestDetected, inputImage);
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

    private void tryAutoZoomForSmallBarcode(@NonNull Barcode barcode,
                                            @NonNull InputImage image) {
        long now = System.currentTimeMillis();
        if (now - lastAutoZoomTime < AUTO_ZOOM_COOLDOWN_MS) return;
        if (cameraControl == null) return;

        Rect box = barcode.getBoundingBox();
        if (box == null) return;

        int imageW = image.getWidth();
        int imageH = image.getHeight();
        if (imageW <= 0 || imageH <= 0) return;

        float barcodeRatio = ((float) box.width() * box.height())
                / ((float) imageW * imageH);

        if (barcodeRatio >= MIN_BARCODE_RATIO) return;

        float neededZoomFactor = (float) Math.sqrt(TARGET_BARCODE_RATIO / Math.max(barcodeRatio, 0.002f));
        float newZoom = Math.min(maxSupportedZoom, currentZoom * neededZoomFactor);
        newZoom = Math.max(MIN_ZOOM, newZoom);

        if (newZoom < currentZoom * 1.15f) return;

        lastAutoZoomTime = now;
        applyZoomRatio(newZoom, true);
        triggerCenterAutoFocus();

        Log.i(TAG, "🔍 Auto-zoom: " + currentZoom + "x → " + newZoom + "x");
    }

    private void applyZoomRatio(float requestedZoom, boolean showHudHint) {
        try {
            if (cameraControl == null) return;

            final float finalZoom = Math.max(MIN_ZOOM, Math.min(maxSupportedZoom, requestedZoom));
            currentZoom = finalZoom;
            cameraControl.setZoomRatio(finalZoom);

            mainHandler.post(() -> {
                if (isViewDestroyed) return;
                if (tvZoomLevel != null) {
                    tvZoomLevel.setText(String.format(Locale.US, "%.1fx", finalZoom));
                }
                updateZoomChipsSelection(finalZoom);
                if (showHudHint && tvScanStatus != null && isScanning) {
                    tvScanStatus.setText(isArabic
                            ? String.format(Locale.US, "🔍 تكبير %.1fx — ثبّت اليد قليلاً", finalZoom)
                            : String.format(Locale.US, "🔍 Zoom %.1fx — Hold steady", finalZoom));
                    tvScanStatus.setTextColor(COLOR_READY);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "applyZoomRatio error: " + e.getMessage());
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

        if (candidateCode == null || !candidateCode.equals(data)
                || now - candidateTime > CONFIRM_WINDOW_MS) {
            candidateCode = data;
            candidateTime = now;
            candidateConfirmations = 1;
            return;
        }

        candidateConfirmations++;

        if (candidateConfirmations < REQUIRED_CONFIRMATIONS) {
            return;
        }

        lastScanTime = now;
        isCompleted = true;
        isScanning = false;
        candidateCode = null;
        candidateConfirmations = 0;

        Log.i(TAG, "✅ CONFIRMED (" + format + "): " + data);

        playBeepSafe();
        String formatName = getBarcodeFormatName(format);

        safeRun(() -> {
            try {
                if (tvScanStatus != null) {
                    tvScanStatus.setText(isArabic ? "✅ تم التقاط الرمز بنجاح" : "✅ Code Captured");
                    tvScanStatus.setTextColor(COLOR_SUCCESS);
                }
                if (tvScannedData != null) {
                    tvScannedData.setText(data);
                    tvScannedData.setVisibility(View.VISIBLE);
                }
            } catch (Exception ignored) {}
        });

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

        mainHandler.postDelayed(() -> {
            if (isAdded() && !isViewDestroyed) {
                closeScanner();
            }
        }, 550);
    }

    private void forwardToPOS(String data) {
        try {
            if (!isAdded() || getActivity() == null) return;
            if (!(getActivity() instanceof UHFMainActivity)) return;

            UHFMainActivity activity = (UHFMainActivity) getActivity();
            Fragment pos = activity.getSupportFragmentManager()
                    .findFragmentByTag(NavigationManager.TAG_POS);

            if (pos instanceof MiraPosFragment) {
                try {
                    ((MiraPosFragment) pos).onBarcodeScanned(data);
                } catch (Exception e) {
                    Log.w(TAG, "POS forward failed, falling back", e);
                    activity.onTagRead(data);
                }
            } else {
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

    private void closeScanner() {
        if (isViewDestroyed) return;
        isViewDestroyed = true;
        isScanning = false;
        isCompleted = true;

        if (scanLine != null) {
            try { scanLine.animate().cancel(); } catch (Exception ignored) {}
        }

        if (cameraControl != null) {
            try {
                cameraControl.enableTorch(false);
                isFlashOn = false;
            } catch (Exception ignored) {}
        }

        if (isCameraBound && cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                isCameraBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding: " + e.getMessage());
            }
        }

        if (backCallback != null) {
            try {
                backCallback.setEnabled(false);
                backCallback.remove();
                backCallback = null;
            } catch (Exception ignored) {}
        }

        if (!isAdded()) return;

        FragmentManager fm = getParentFragmentManager();
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

    private void startScanAnimation() {
        if (scanLine == null || scannerOverlay == null || isViewDestroyed || !isScanning) return;

        try {
            scanLine.animate().cancel();
            scanLine.setTranslationY(0);

            scanLine.post(() -> {
                if (isViewDestroyed || !isScanning || scanLine == null || scannerOverlay == null) return;

                int height = scannerOverlay.getHeight();
                if (height <= 0) {
                    scanLine.postDelayed(this::startScanAnimation, 100);
                    return;
                }

                scanLine.animate()
                        .translationY(height - 2)
                        .setDuration(1800)
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
        candidateCode = null;
        candidateConfirmations = 0;
        startScanAnimation();
    }

    @Override
    public void onDestroyView() {
        isViewDestroyed = true;
        isScanning = false;

        mainHandler.removeCallbacksAndMessages(null);
        zoomChipViews.clear();

        if (scanLine != null) {
            try { scanLine.animate().cancel(); } catch (Exception ignored) {}
        }

        if (backCallback != null) {
            backCallback.setEnabled(false);
            backCallback.remove();
            backCallback = null;
        }

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

        scaleDetector = null;
        currentZoom = 1.0f;
        candidateCode = null;
        candidateConfirmations = 0;
        lastAutoZoomTime = 0L;

        previewView = null;
        scannerOverlay = null;
        scanLine = null;
        tvScanStatus = null;
        tvScannedData = null;
        tvZoomLevel = null;
        btnClose = null;
        btnFlash = null;

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        try {
            if (cameraControl != null) {
                cameraControl.enableTorch(false);
            }
        } catch (Exception ignored) {}

        if (cameraExecutor != null) {
            try {
                cameraExecutor.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "executor shutdown error: " + e.getMessage());
            }
            cameraExecutor = null;
        }

        if (barcodeScanner != null) {
            try {
                barcodeScanner.close();
            } catch (Exception e) {
                Log.e(TAG, "barcodeScanner close error: " + e.getMessage());
            }
            barcodeScanner = null;
        }

        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception e) {
                Log.e(TAG, "toneGenerator release error: " + e.getMessage());
            }
            toneGenerator = null;
        }
        barcodeListener = null;
        appContext = null;
    }
}
