package com.example.uhf.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
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
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
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
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraBarcodeScannerFragment v6.0 — Extreme Micro-Code & Harsh Conditions
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * مصمم لقراءة الأكواد المجهرية (≤ 5mm × 5mm) مهما كان بعدها أو رداءة طباعتها:
 *   1. True Pixel-Level Center ROI Cropping (44%) + 2.2x Bicubic Upscaling
 *   2. Integral-Image Adaptive Local Thresholding للطباعة الباهتة جداً أو المتقطعة
 *   3. Multi-Stage Pipeline: ML Kit + ZXing TRY_HARDER + Adaptive Binarized + Color-Inverted
 *   4. Anti-Glare Exposure Toggle لمنع وهج الفلاش وانعكاس الذهب والبطاقات اللامعة
 *   5. Hardware 2D Scanner Broadcast + HID Keyboard Wedge Support (Chainway / VH-C83)
 *   6. Continuous Batch Mode لدعم مسح شحنات التحويل بالكامل دفعة واحدة
 */
public class MiraBarcodeScannerFragment extends Fragment {

    private static final String TAG = "MiraExtremeMicro";
    private static final int CAMERA_PERMISSION_CODE = 1001;

    private static final long SINGLE_SCAN_COOLDOWN_MS = 700L;
    private static final long BATCH_SCAN_COOLDOWN_MS = 1050L;

    private static final int COLOR_READY   = Color.parseColor("#38BDF8");
    private static final int COLOR_SUCCESS = Color.parseColor("#10B981");
    private static final int COLOR_GOLD    = Color.parseColor("#D4AF37");

    private static final float MIN_ZOOM = 1.0f;
    private static final float DEFAULT_MICRO_ZOOM = 2.3f;
    private static final float MAX_ZOOM_FALLBACK = 6.5f;

    private static final float[] ZOOM_PRESETS = {1.0f, 2.3f, 3.6f, 5.2f};

    // ==================== UI ====================
    private PreviewView previewView;
    private View scannerOverlay, scanLine;
    private TextView tvScanStatus, tvScannedData;
    private TextView tvZoomLevel;
    private ImageButton btnClose, btnFlash;
    private TextView btnAntiGlareChip;

    private final List<TextView> zoomChipViews = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== Camera ====================
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private CameraControl cameraControl;
    private volatile boolean isFlashOn = false;
    private int exposureLevelStep = 1; // 0 = عادي، 1 = ضد الوهج (-1 EV)، 2 = إزالة انعكاس الذهب (-2 EV)
    private ExecutorService cameraExecutor;

    // ==================== Dual Scanner Engines ====================
    private BarcodeScanner barcodeScanner;
    private MultiFormatReader zxingReader;
    private ToneGenerator toneGenerator;
    private volatile boolean isScanning = true;
    private volatile boolean isViewDestroyed = false;
    private long lastScanTime = 0L;
    private long frameCounter = 0L;

    // ==================== Hardware 2D & HID Buffer ====================
    private final StringBuilder hidBuffer = new StringBuilder();
    private long lastHidKeyTime = 0L;
    private boolean isHardwareReceiverRegistered = false;

    private final BroadcastReceiver hardware2DReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !isScanning || isViewDestroyed) return;
            String code = extractBarcodeFromHardwareIntent(intent);
            if (code != null && !code.trim().isEmpty()) {
                onBarcodeDetected(code.trim(), Barcode.FORMAT_DATA_MATRIX, true);
            }
        }
    };

    // ==================== Listeners & Mode ====================
    private OnBarcodeScannedListener barcodeListener;
    private boolean continuousBatchMode = false;
    private int batchScannedCount = 0;
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

    // ==================== Confirmation ====================
    @Nullable private volatile String candidateCode = null;
    private volatile long candidateTime = 0L;
    private volatile int candidateConfirmations = 0;

    private final Runnable periodicFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isViewDestroyed && isScanning && isCameraBound) {
                triggerCenterAutoFocus();
                mainHandler.postDelayed(this, 2200L);
            }
        }
    };

    public interface OnBarcodeScannedListener {
        void onBarcodeScanned(String barcodeData, String format);
    }

    public void setOnBarcodeScannedListener(OnBarcodeScannedListener listener) {
        this.barcodeListener = listener;
    }

    public void setContinuousBatchMode(boolean continuous) {
        this.continuousBatchMode = continuous;
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
        batchScannedCount = 0;
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
            setupHidKeyboardCapture(view);

            if (previewView == null) {
                Log.e(TAG, "previewView not found in layout!");
                showErrorAndClose();
                return;
            }

            applyLanguageAndDirection(view);
            injectMicroControlOverlay(view);

            if (btnClose != null) btnClose.setOnClickListener(v -> closeScanner());
            if (btnFlash != null) btnFlash.setOnClickListener(v -> toggleFlash());

            if (cameraExecutor == null || cameraExecutor.isShutdown()) {
                cameraExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "MiraExtremeMicroAnalyzer");
                    t.setPriority(Thread.MAX_PRIORITY);
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

            registerHardware2DScannerReceiver();

            if (initDualBarcodeEngines()) {
                if (hasCameraPermission()) {
                    startCamera();
                } else {
                    requestCameraPermission();
                }
                startScanAnimation();
            } else {
                showErrorAndClose();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onViewCreated: " + e.getMessage(), e);
            showErrorAndClose();
        }
    }

    /**
     * شريط التحكم المجهري: مستويات البعد (1x - 5.2x) + زر إزالة وهج الذهب/البطاقات اللامعة
     */
    private void injectMicroControlOverlay(@NonNull View root) {
        if (!(root instanceof ViewGroup)) return;
        try {
            ViewGroup container = (ViewGroup) root;
            zoomChipViews.clear();

            LinearLayout barWrapper = new LinearLayout(requireContext());
            barWrapper.setOrientation(LinearLayout.VERTICAL);
            barWrapper.setGravity(Gravity.CENTER_HORIZONTAL);

            // زر التبديل السريع لإزالة وهج الإضاءة والانعكاس على الذهب
            btnAntiGlareChip = new TextView(requireContext());
            btnAntiGlareChip.setTextSize(11.5f);
            btnAntiGlareChip.setTypeface(null, Typeface.BOLD);
            btnAntiGlareChip.setGravity(Gravity.CENTER);
            btnAntiGlareChip.setPadding(dp(14), dp(6), dp(14), dp(6));

            GradientDrawable glareBg = new GradientDrawable();
            glareBg.setColor(Color.parseColor("#CC0F172A"));
            glareBg.setCornerRadius(dp(20));
            glareBg.setStroke(dp(1), Color.parseColor("#8838BDF8"));
            btnAntiGlareChip.setBackground(glareBg);
            btnAntiGlareChip.setTextColor(Color.parseColor("#38BDF8"));
            updateAntiGlareButtonText();

            btnAntiGlareChip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                exposureLevelStep = (exposureLevelStep + 1) % 3;
                applyExposureStep();
                updateAntiGlareButtonText();
            });

            LinearLayout.LayoutParams glareLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            glareLp.bottomMargin = dp(8);
            barWrapper.addView(btnAntiGlareChip, glareLp);

            TextView hintLabel = new TextView(requireContext());
            hintLabel.setText(isArabic
                    ? (continuousBatchMode
                        ? "مسح متتالي للأكواد المجهرية (≤5مم) • امسح القطع تباعاً"
                        : "عدسة الأكواد المجهرية (≤5مم) • اختر مستوى التقريب أو المس للتركيز")
                    : (continuousBatchMode
                        ? "Batch Micro-Code Scan (≤5mm) • Scan items continuously"
                        : "Extreme Micro Lens (≤5mm) • Select Zoom or Tap to Focus"));
            hintLabel.setTextColor(Color.parseColor("#E2E8F0"));
            hintLabel.setTextSize(11f);
            hintLabel.setGravity(Gravity.CENTER);
            hintLabel.setPadding(dp(12), dp(2), dp(12), dp(6));
            barWrapper.addView(hintLabel);

            LinearLayout pillRow = new LinearLayout(requireContext());
            pillRow.setOrientation(LinearLayout.HORIZONTAL);
            pillRow.setGravity(Gravity.CENTER);
            pillRow.setPadding(dp(8), dp(6), dp(8), dp(6));

            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(Color.parseColor("#CC0A1124"));
            pillBg.setCornerRadius(dp(28));
            pillBg.setStroke(dp(1), Color.parseColor("#66D4AF37"));
            pillRow.setBackground(pillBg);

            for (final float zoomFactor : ZOOM_PRESETS) {
                TextView chip = new TextView(requireContext());
                String label = String.format(Locale.US, "%.1fx", zoomFactor).replace(".0x", "x");
                chip.setText(label);
                chip.setTextSize(12f);
                chip.setTypeface(null, Typeface.BOLD);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), dp(6), dp(14), dp(6));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.setMargins(dp(3), 0, dp(3), 0);
                chip.setLayoutParams(lp);

                chip.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    applyZoomRatio(zoomFactor, true);
                    triggerCenterAutoFocus();
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
                params.bottomMargin = dp(32);
                container.addView(barWrapper, params);
            } else {
                container.addView(barWrapper);
            }

            updateZoomChipsSelection(DEFAULT_MICRO_ZOOM);
        } catch (Exception e) {
            Log.w(TAG, "injectMicroControlOverlay error: " + e.getMessage());
        }
    }

    private void updateAntiGlareButtonText() {
        if (btnAntiGlareChip == null) return;
        String text;
        switch (exposureLevelStep) {
            case 1:
                text = isArabic ? "🌗 مضاد وهج البطاقات (نشط)" : "🌗 Anti-Glare Mode (Active)";
                break;
            case 2:
                text = isArabic ? "🌑 إزالة انعكاس الذهب والليزر" : "🌑 Gold/Laser Glare Shield";
                break;
            default:
                text = isArabic ? "☀️ إضاءة قياسية (للظلام)" : "☀️ Standard Exposure (Low Light)";
                break;
        }
        btnAntiGlareChip.setText(text);
    }

    private void applyExposureStep() {
        try {
            if (camera == null || cameraControl == null) return;
            ExposureState expState = camera.getCameraInfo().getExposureState();
            if (!expState.isExposureCompensationSupported()) return;

            Range<Integer> range = expState.getExposureCompensationRange();
            int target;
            if (exposureLevelStep == 0) {
                target = 0;
            } else if (exposureLevelStep == 1) {
                target = Math.max(range.getLower(), -1);
            } else {
                target = Math.max(range.getLower(), -2);
            }
            cameraControl.setExposureCompensationIndex(target);
        } catch (Exception ignored) {}
    }

    private void updateZoomChipsSelection(float activeZoom) {
        try {
            for (int i = 0; i < zoomChipViews.size() && i < ZOOM_PRESETS.length; i++) {
                TextView chip = zoomChipViews.get(i);
                float preset = ZOOM_PRESETS[i];
                boolean isSelected = Math.abs(activeZoom - preset) < 0.55f;

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

    // ==================== Hardware 2D Scanner & HID Support ====================

    private void registerHardware2DScannerReceiver() {
        if (appContext == null || isHardwareReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            // أشهر مسارات البث للماسحات الليزرية المدمجة في Chainway / VH-C83 / Honeywell / Zebra
            filter.addAction("com.scanner.broadcast");
            filter.addAction("com.android.server.scannerservice.broadcast");
            filter.addAction("android.intent.ACTION_DECODE_DATA");
            filter.addAction("com.rscja.scanner.action.BARCODE");
            filter.addAction("nlscan.action.SCANNER_RESULT");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(hardware2DReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(hardware2DReceiver, filter);
            }
            isHardwareReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Hardware 2D receiver registration skipped: " + e.getMessage());
        }
    }

    private void unregisterHardware2DScannerReceiver() {
        if (appContext == null || !isHardwareReceiverRegistered) return;
        try {
            appContext.unregisterReceiver(hardware2DReceiver);
        } catch (Exception ignored) {}
        isHardwareReceiverRegistered = false;
    }

    @Nullable
    private String extractBarcodeFromHardwareIntent(@NonNull Intent intent) {
        String[] keys = {"data", "scannerdata", "barcode_string", "barcode", "SCAN_BARCODE1", "value"};
        for (String key : keys) {
            String val = intent.getStringExtra(key);
            if (val != null && !val.trim().isEmpty()) return val.trim();
        }
        byte[] rawBytes = intent.getByteArrayExtra("barcode");
        if (rawBytes != null && rawBytes.length > 0) {
            return new String(rawBytes).trim();
        }
        return null;
    }

    private void setupHidKeyboardCapture(@NonNull View root) {
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            long now = System.currentTimeMillis();
            if (now - lastHidKeyTime > 250L) {
                hidBuffer.setLength(0);
            }
            lastHidKeyTime = now;

            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) {
                if (hidBuffer.length() >= 3) {
                    String scanned = hidBuffer.toString().trim();
                    hidBuffer.setLength(0);
                    onBarcodeDetected(scanned, Barcode.FORMAT_DATA_MATRIX, true);
                    return true;
                }
                hidBuffer.setLength(0);
                return false;
            }

            int unicode = event.getUnicodeChar();
            if (unicode > 31) {
                hidBuffer.append((char) unicode);
                return true;
            }
            return false;
        });
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
            if (isCompleted && !continuousBatchMode) return;

            isScanning = true;
            if (hasCameraPermission() && !isCameraBound) {
                startCamera();
            }
            if (scanLine != null && isScanning) {
                startScanAnimation();
            }
            mainHandler.removeCallbacks(periodicFocusRunnable);
            mainHandler.postDelayed(periodicFocusRunnable, 1200L);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isScanning = false;
        mainHandler.removeCallbacks(periodicFocusRunnable);

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
                        ? "🎯 ضع الكود المجهري (≤5×5 مم) داخل المربع"
                        : "🎯 Center micro-code (≤5×5 mm) in box");
                tvScanStatus.setTextColor(COLOR_READY);
            }
            if (btnFlash != null) btnFlash.setContentDescription(isArabic ? "فلاش" : "Flash");
            if (btnClose != null) btnClose.setContentDescription(isArabic ? "إغلاق" : "Close");
        } catch (Exception e) {
            Log.e(TAG, "Error applying language: " + e.getMessage());
        }
    }

    private boolean initDualBarcodeEngines() {
        try {
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                            Barcode.FORMAT_QR_CODE,
                            Barcode.FORMAT_DATA_MATRIX,
                            Barcode.FORMAT_CODE_128,
                            Barcode.FORMAT_EAN_13,
                            Barcode.FORMAT_EAN_8,
                            Barcode.FORMAT_CODE_39,
                            Barcode.FORMAT_AZTEC
                    )
                    .enableAllPotentialBarcodes()
                    .build();
            barcodeScanner = BarcodeScanning.getClient(options);

            zxingReader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.AZTEC
            ));
            zxingReader.setHints(hints);

            return barcodeScanner != null;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing barcode engines: " + e.getMessage());
            return false;
        }
    }

    private boolean hasCameraPermission() {
        try {
            return appContext != null && ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestCameraPermission() {
        try {
            if (isAdded() && getActivity() != null) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            }
        } catch (Exception ignored) {}
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void startCamera() {
        try {
            if (previewView == null || cameraExecutor == null || cameraExecutor.isShutdown()) {
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

                    ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1920, 1080))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

                    try {
                        Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(analysisBuilder);
                        ext.setCaptureRequestOption(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        );
                        ext.setCaptureRequestOption(
                                CaptureRequest.EDGE_MODE,
                                CaptureRequest.EDGE_MODE_HIGH_QUALITY
                        );
                        ext.setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
                        );
                        ext.setCaptureRequestOption(
                                CaptureRequest.CONTROL_SCENE_MODE,
                                CaptureRequest.CONTROL_SCENE_MODE_BARCODE
                        );
                    } catch (Exception ignored) {}

                    ImageAnalysis imageAnalysis = analysisBuilder.build();
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeMicroFrame);

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
                            maxSupportedZoom = Math.min(8.5f,
                                    info.getZoomState().getValue().getMaxZoomRatio());
                        }
                    } catch (Exception ignored) {}

                    applyExposureStep();

                    isCameraBound = true;
                    isScanning = true;

                    setupFocusAndZoom();
                    applyZoomRatio(DEFAULT_MICRO_ZOOM, false);

                    mainHandler.removeCallbacks(periodicFocusRunnable);
                    mainHandler.postDelayed(periodicFocusRunnable, 1000L);

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
            triggerCenterAutoFocus();

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
                        showHintSafe(isArabic ? "🎯 تم قفل البؤرة المجهرية" : "🎯 Micro Focus Locked", COLOR_READY);
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
        } catch (Exception ignored) {}
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

    // ═══════════════════════════════════════════════════════════════════
    //  4-Stage Extreme Micro Image Pipeline
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("UnsafeOptInUsageError")
    private void analyzeMicroFrame(@NonNull ImageProxy imageProxy) {
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
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            long currentFrame = ++frameCounter;

            // 1. استخراج منطقة المركز (44%) وتضخيمها 2.2x بدقة حادة
            Bitmap roiBitmap = extractCenterMicroBitmap(imageProxy, rotation);

            if (roiBitmap != null) {
                try {
                    // المسار 1: صورة مقصوصة عالية التباين عبر ML Kit
                    Bitmap enhancedBitmap = enhanceMicroContrast(roiBitmap);
                    List<Barcode> mlResults = Tasks.await(
                            barcodeScanner.process(InputImage.fromBitmap(enhancedBitmap, 0))
                    );

                    for (Barcode b : mlResults) {
                        String raw = b.getRawValue();
                        if (raw != null && !raw.isEmpty()) {
                            enhancedBitmap.recycle();
                            roiBitmap.recycle();
                            closeOnce.run();
                            onBarcodeDetected(raw, b.getFormat(), true);
                            return;
                        }
                    }

                    // المسار 2: محرك ZXing TRY_HARDER
                    Result zxingResult = decodeWithZxing(enhancedBitmap);
                    if (zxingResult != null && zxingResult.getText() != null && !zxingResult.getText().isEmpty()) {
                        String text = zxingResult.getText();
                        int fmt = mapZxingFormatToMlKit(zxingResult.getBarcodeFormat());
                        enhancedBitmap.recycle();
                        roiBitmap.recycle();
                        closeOnce.run();
                        onBarcodeDetected(text, fmt, true);
                        return;
                    }

                    // المسار 3: تحويل ثنائي تكيفي (Adaptive Local Thresholding) للطباعة الرديئة/الباهتة
                    if (currentFrame % 2 == 0) {
                        Bitmap binarizedBitmap = applyAdaptiveBinarization(roiBitmap);
                        try {
                            List<Barcode> binMl = Tasks.await(
                                    barcodeScanner.process(InputImage.fromBitmap(binarizedBitmap, 0))
                            );
                            for (Barcode b : binMl) {
                                String raw = b.getRawValue();
                                if (raw != null && !raw.isEmpty()) {
                                    binarizedBitmap.recycle();
                                    enhancedBitmap.recycle();
                                    roiBitmap.recycle();
                                    closeOnce.run();
                                    onBarcodeDetected(raw, b.getFormat(), true);
                                    return;
                                }
                            }
                            Result binZxing = decodeWithZxing(binarizedBitmap);
                            if (binZxing != null && binZxing.getText() != null && !binZxing.getText().isEmpty()) {
                                String text = binZxing.getText();
                                int fmt = mapZxingFormatToMlKit(binZxing.getBarcodeFormat());
                                binarizedBitmap.recycle();
                                enhancedBitmap.recycle();
                                roiBitmap.recycle();
                                closeOnce.run();
                                onBarcodeDetected(text, fmt, true);
                                return;
                            }
                        } finally {
                            if (!binarizedBitmap.isRecycled()) binarizedBitmap.recycle();
                        }
                    }

                    // المسار 4: عكس الألوان للنقش بالليزر على الذهب والبطاقات الداكنة
                    if (currentFrame % 3 == 0) {
                        Bitmap invertedBitmap = invertBitmapColors(enhancedBitmap);
                        try {
                            List<Barcode> invMl = Tasks.await(
                                    barcodeScanner.process(InputImage.fromBitmap(invertedBitmap, 0))
                            );
                            for (Barcode b : invMl) {
                                String raw = b.getRawValue();
                                if (raw != null && !raw.isEmpty()) {
                                    invertedBitmap.recycle();
                                    enhancedBitmap.recycle();
                                    roiBitmap.recycle();
                                    closeOnce.run();
                                    onBarcodeDetected(raw, b.getFormat(), true);
                                    return;
                                }
                            }
                        } finally {
                            if (!invertedBitmap.isRecycled()) invertedBitmap.recycle();
                        }
                    }

                    if (!enhancedBitmap.isRecycled()) enhancedBitmap.recycle();
                } finally {
                    if (!roiBitmap.isRecycled()) roiBitmap.recycle();
                }
            }

            // المسار الاحتياطي: فحص الإطار الكامل للأكواد البعيدة
            if (currentFrame % 2 == 1) {
                InputImage fullImage = InputImage.fromMediaImage(mediaImage, rotation);
                List<Barcode> fullResults = Tasks.await(barcodeScanner.process(fullImage));
                for (Barcode b : fullResults) {
                    String raw = b.getRawValue();
                    if (raw != null && !raw.isEmpty()) {
                        closeOnce.run();
                        onBarcodeDetected(raw, b.getFormat(), false);
                        return;
                    }
                }
            }

        } catch (Exception e) {
            Log.d(TAG, "Frame analyze pass: " + e.getMessage());
        } finally {
            closeOnce.run();
        }
    }

    private Bitmap extractCenterMicroBitmap(@NonNull ImageProxy imageProxy, int rotationDegrees) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            int width = image.getWidth();
            int height = image.getHeight();

            // اقتصاص 44% من المنتصف لتركيز كامل الدقة على الكود 5x5mm
            int cropW = (int) (width * 0.44f);
            int cropH = (int) (height * 0.44f);
            int left = (width - cropW) / 2;
            int top = (height - cropH) / 2;
            Rect cropRect = new Rect(left, top, left + cropW, top + cropH);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(cropRect, 98, out);
            byte[] jpegBytes = out.toByteArray();

            Bitmap cropped = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (cropped == null) return null;

            Matrix matrix = new Matrix();
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees);
            }
            matrix.postScale(2.2f, 2.2f);

            Bitmap upscaled = Bitmap.createBitmap(
                    cropped, 0, 0, cropped.getWidth(), cropped.getHeight(), matrix, true
            );
            if (upscaled != cropped) {
                cropped.recycle();
            }
            return upscaled;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * خوارزمية Integral Image Adaptive Thresholding:
     * تحول الطباعة الباهتة أو غير المتساوية الإضاءة إلى أبيض وأسود حاد جداً
     */
    private Bitmap applyAdaptiveBinarization(@NonNull Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] gray = new int[w * h];
        long[] integral = new long[w * h];

        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int c = pixels[rowOffset + x];
                int lum = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
                gray[rowOffset + x] = lum;
                rowSum += lum;
                integral[rowOffset + x] = (y == 0) ? rowSum : (integral[(y - 1) * w + x] + rowSum);
            }
        }

        int s = Math.max(8, w / 16);
        int s2 = s / 2;
        int thresholdPercent = 88; // عتبة حساسة لالتقاط الحبر الباهت

        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - s2);
            int y2 = Math.min(h - 1, y + s2);
            int rowOffset = y * w;

            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - s2);
                int x2 = Math.min(w - 1, x + s2);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);

                long sum = integral[y2 * w + x2]
                        - (y1 > 0 ? integral[(y1 - 1) * w + x2] : 0)
                        - (x1 > 0 ? integral[y2 * w + (x1 - 1)] : 0)
                        + ((y1 > 0 && x1 > 0) ? integral[(y1 - 1) * w + (x1 - 1)] : 0);

                int idx = rowOffset + x;
                if (gray[idx] * count * 100 < sum * thresholdPercent) {
                    pixels[idx] = Color.BLACK;
                } else {
                    pixels[idx] = Color.WHITE;
                }
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private Bitmap enhanceMicroContrast(@NonNull Bitmap src) {
        Bitmap dest = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dest);
        Paint paint = new Paint();

        float contrast = 1.75f;
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return dest;
    }

    private Bitmap invertBitmapColors(@NonNull Bitmap src) {
        Bitmap dest = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dest);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix(new float[]{
                -1f, 0, 0, 0, 255f,
                0, -1f, 0, 0, 255f,
                0, 0, -1f, 0, 255f,
                0, 0, 0, 1f, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return dest;
    }

    @Nullable
    private Result decodeWithZxing(@NonNull Bitmap bitmap) {
        if (zxingReader == null) return null;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

            LuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            try {
                return zxingReader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            } catch (Exception ignored) {
                zxingReader.reset();
                return zxingReader.decodeWithState(new BinaryBitmap(new GlobalHistogramBinarizer(source)));
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            try { zxingReader.reset(); } catch (Exception ignored) {}
        }
    }

    private int mapZxingFormatToMlKit(@Nullable BarcodeFormat fmt) {
        if (fmt == null) return Barcode.FORMAT_UNKNOWN;
        switch (fmt) {
            case DATA_MATRIX: return Barcode.FORMAT_DATA_MATRIX;
            case QR_CODE: return Barcode.FORMAT_QR_CODE;
            case CODE_128: return Barcode.FORMAT_CODE_128;
            case EAN_13: return Barcode.FORMAT_EAN_13;
            case EAN_8: return Barcode.FORMAT_EAN_8;
            case CODE_39: return Barcode.FORMAT_CODE_39;
            default: return Barcode.FORMAT_UNKNOWN;
        }
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
                            ? String.format(Locale.US, "🔍 تكبير %.1fx — وضع العدسة المجهرية", finalZoom)
                            : String.format(Locale.US, "🔍 Zoom %.1fx — Extreme Micro Lens", finalZoom));
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
        } catch (Exception ignored) {}
    }

    // ==================== Detection ====================

    private void onBarcodeDetected(String rawData, int format, boolean fromHighPrecisionRoi) {
        if (rawData == null || rawData.trim().isEmpty()) return;
        String cleanData = extractCleanCodePayload(rawData.trim());

        long now = System.currentTimeMillis();
        long cooldown = continuousBatchMode ? BATCH_SCAN_COOLDOWN_MS : SINGLE_SCAN_COOLDOWN_MS;

        if (now - lastScanTime < cooldown) return;
        if (isCompleted && !continuousBatchMode) return;

        int requiredHits = fromHighPrecisionRoi ? 1 : 2;

        if (candidateCode == null || !candidateCode.equals(cleanData) || now - candidateTime > 320L) {
            candidateCode = cleanData;
            candidateTime = now;
            candidateConfirmations = 1;
        } else {
            candidateConfirmations++;
        }

        if (candidateConfirmations < requiredHits) return;

        lastScanTime = now;
        candidateCode = null;
        candidateConfirmations = 0;

        if (!continuousBatchMode) {
            isCompleted = true;
            isScanning = false;
        } else {
            batchScannedCount++;
        }

        Log.i(TAG, "✅ EXTREME MICRO CONFIRMED (" + format + "): " + cleanData);

        playBeepSafe();
        String formatName = getBarcodeFormatName(format);
        final int currentBatchCount = batchScannedCount;

        safeRun(() -> {
            try {
                if (tvScanStatus != null) {
                    if (continuousBatchMode) {
                        tvScanStatus.setText(isArabic
                                ? "✅ تم مسح (" + currentBatchCount + ") — جاهز للقطعة التالية"
                                : "✅ Scanned (" + currentBatchCount + ") — Ready for next");
                    } else {
                        tvScanStatus.setText(isArabic ? "✅ تم التقاط الكود المجهري بنجاح" : "✅ Micro-Code Captured");
                    }
                    tvScanStatus.setTextColor(COLOR_SUCCESS);
                }
                if (tvScannedData != null) {
                    tvScannedData.setText(cleanData);
                    tvScannedData.setVisibility(View.VISIBLE);
                }
            } catch (Exception ignored) {}
        });

        boolean delivered = false;
        if (barcodeListener != null) {
            try {
                barcodeListener.onBarcodeScanned(cleanData, formatName);
                delivered = true;
            } catch (Exception e) {
                Log.e(TAG, "barcodeListener error: " + e.getMessage());
            }
        }

        if (!delivered && !continuousBatchMode) {
            forwardToPOS(cleanData);
        }

        if (!continuousBatchMode) {
            mainHandler.postDelayed(() -> {
                if (isAdded() && !isViewDestroyed) {
                    closeScanner();
                }
            }, 420);
        }
    }

    private String extractCleanCodePayload(@NonNull String raw) {
        try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                int queryIdx = raw.indexOf("code=");
                if (queryIdx != -1) {
                    String sub = raw.substring(queryIdx + 5);
                    int amp = sub.indexOf('&');
                    return amp != -1 ? sub.substring(0, amp) : sub;
                }
                int lastSlash = raw.lastIndexOf('/');
                if (lastSlash != -1 && lastSlash < raw.length() - 1) {
                    String tail = raw.substring(lastSlash + 1).trim();
                    if (!tail.isEmpty() && !tail.equalsIgnoreCase("verify")) {
                        return tail;
                    }
                }
            }
            int ai21 = raw.indexOf("(21)");
            if (ai21 != -1) {
                String after21 = raw.substring(ai21 + 4);
                int nextParen = after21.indexOf('(');
                return nextParen != -1 ? after21.substring(0, nextParen).trim() : after21.trim();
            }
        } catch (Exception ignored) {}
        return raw;
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
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 130);
            }
        } catch (Exception ignored) {}
    }

    private void closeScanner() {
        if (isViewDestroyed) return;
        isViewDestroyed = true;
        isScanning = false;
        isCompleted = true;
        mainHandler.removeCallbacks(periodicFocusRunnable);
        unregisterHardware2DScannerReceiver();

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
        } catch (Exception ignored) {}
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
                        .setDuration(1500)
                        .withEndAction(() -> {
                            if (scanLine == null || isViewDestroyed || !isScanning) return;
                            scanLine.setTranslationY(0);
                            startScanAnimation();
                        })
                        .start();
            });
        } catch (Exception ignored) {}
    }

    public void toggleFlash() {
        try {
            if (cameraControl != null) {
                isFlashOn = !isFlashOn;
                cameraControl.enableTorch(isFlashOn);
                // عند تشغيل الفلاش نفعّل وضع ضد الوهج تلقائياً لمنع احتراق الكود الأبيض
                if (isFlashOn && exposureLevelStep == 0) {
                    exposureLevelStep = 1;
                    applyExposureStep();
                    updateAntiGlareButtonText();
                }
                showToastSafe(
                        isFlashOn ? (isArabic ? "🔦 تم تشغيل الإضاءة + حماية الوهج" : "🔦 Flash On + Anti-Glare")
                                  : (isArabic ? "🔌 تم إطفاء الإضاءة" : "🔌 Flash Off"),
                        Toast.LENGTH_SHORT);
            }
        } catch (Exception ignored) {}
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null && !isViewDestroyed) {
                getActivity().runOnUiThread(() -> {
                    try {
                        if (isAdded() && !isViewDestroyed) r.run();
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private void showToastSafe(String msg, int duration) {
        try {
            if (isAdded() && appContext != null) {
                Toast.makeText(appContext, msg, duration).show();
            }
        } catch (Exception ignored) {}
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
        unregisterHardware2DScannerReceiver();
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
            } catch (Exception ignored) {}
        }
        cameraControl = null;
        camera = null;

        scaleDetector = null;
        currentZoom = 1.0f;
        candidateCode = null;
        candidateConfirmations = 0;

        previewView = null;
        scannerOverlay = null;
        scanLine = null;
        tvScanStatus = null;
        tvScannedData = null;
        tvZoomLevel = null;
        btnClose = null;
        btnFlash = null;
        btnAntiGlareChip = null;

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (cameraControl != null) cameraControl.enableTorch(false);
        } catch (Exception ignored) {}

        if (cameraExecutor != null) {
            try { cameraExecutor.shutdown(); } catch (Exception ignored) {}
            cameraExecutor = null;
        }

        if (barcodeScanner != null) {
            try { barcodeScanner.close(); } catch (Exception ignored) {}
            barcodeScanner = null;
        }

        if (toneGenerator != null) {
            try { toneGenerator.release(); } catch (Exception ignored) {}
            toneGenerator = null;
        }
        barcodeListener = null;
        appContext = null;
    }
}
