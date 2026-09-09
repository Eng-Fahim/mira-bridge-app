package com.example.uhf.fragment;

import com.example.uhf.tools.SessionManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.POSCheckoutDialog;
import com.example.uhf.engine.SmartScaleConnector;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.fragment.MiraReceiptFragment;

import com.example.uhf.manager.MiraLocalItemCache;
import com.example.uhf.manager.MiraItemSyncManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraPosFragment extends MiraBaseFragment {

    private static final String TAG = "MiraPosFragment";
    private static final String PENDING_SALES_PREFS = "pos_pending_sales";
    private static final String PREFS_SCALE_MAC = "scale_mac_address";
    private static final String PREFS_SCALE_NAME = "scale_name";
    private static final int REQUEST_BLUETOOTH_PERMISSION = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    
    private boolean isSyncing = false;
    private boolean isCheckoutDialogShowing = false; 
    private AlertDialog searchLoadingDialog;
    private SessionManager sessionManager;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MiraPriceManager priceManager;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    private RFIDManager rfidManager;
    private boolean isRFIDScanActive = false;
    private final Set<String> pendingEpcs = new HashSet<>();
    private final Map<String, Long> lastScannedTimes = new HashMap<>();

    private TextView tvScaleStatus, tvItemCount, tvTotalAmount, tvCartStatus;
    private TextInputLayout tilManualEpc;
    private EditText etManualEpc;
    private Button btnAddManual, btnCheckout, btnClearCart;
    private Button btnOpenScanner, btnPosRFIDMode;
    private RecyclerView rvCartItems;
    private View layoutCartEmptyState;

    private boolean isRFIDModeActive = false;
    private boolean isArabic;
    
    private Button btnPosRefund;
    
    private MiraApiClient.ApiResponse lastSaleResponse = null;
    
    private EditText etRefundCode;
    private Dialog refundDialog;
    private TextView tvRefundItemTitle;
    private TextView tvRefundItemStatus;
    private View cardRefundItemInfo;
    private boolean isRefundable = false;

    // متغيرات الميزان
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;
    private ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private BroadcastReceiver bluetoothReceiver;

    public static class CartItem {
        public String epc, name, karat, status, serialNo, gtin, location;
        public double weight, scaleWeight, price;
        public boolean isAvailable;
        public int itemId;

        public CartItem(String epc, String name, String karat, double weight,
                        double scaleWeight, double price, boolean isAvailable, String status) {
            this.epc = epc;
            this.name = name;
            this.karat = karat;
            this.weight = weight;
            this.scaleWeight = scaleWeight;
            this.price = price;
            this.isAvailable = isAvailable;
            this.status = status;
        }
    }

    private final List<CartItem> cartList = new ArrayList<>();
    private final Set<String> scannedEpcs = new HashSet<>();
    private CartAdapter cartAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_pos, container, false);
        } catch (Exception e) {
            Log.e(TAG, "onCreateView error: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            Context ctx = requireContext();
            branchesManager = MiraBranchesManager.getInstance(ctx);
            priceManager = MiraPriceManager.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            sessionManager = new SessionManager(ctx);
            
            rfidManager = RFIDManager.getInstance(ctx);
            if (rfidManager != null) {
                rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (isRFIDScanActive) onRingScanned(epc);
                    }
                    @Override
                    public void onScanStarted() {}
                    @Override
                    public void onScanStopped() {}
                    @Override
                    public void onConnectionChanged(boolean connected) {
                        safeRun(() -> {
                            if (btnPosRFIDMode != null && isRFIDModeActive) {
                                btnPosRFIDMode.setAlpha(connected ? 1.0f : 0.5f);
                            }
                        });
                    }
                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "RFID Error: " + error);
                    }
                });
            }
            
            apiClient = MiraApiClient.getInstance(ctx);
            bindViews(view);
            setupRecyclerView();
            setupListeners();
            applyPermissions();
            initSmartScale();
            checkPendingSales();
            updateCartSummary();
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(this);
            }
            if (scaleConnector != null) {
                autoConnectScale();
            }
            if (tvCartStatus != null && cartList.isEmpty()) {
                tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
            }
            
            MiraItemSyncManager syncManager = MiraItemSyncManager.getInstance(requireContext());
            long lastSync = MiraLocalItemCache.getInstance(requireContext()).getLastSyncTime();
            
            if (System.currentTimeMillis() - lastSync > 3600000) {
                syncManager.syncAllItems(new MiraItemSyncManager.SyncCallback() {
                    @Override
                    public void onSyncComplete(int itemCount) {
                        Log.d(TAG, "✅ Synced " + itemCount + " items for offline use");
                    }
                    
                    @Override
                    public void onSyncError(String error) {
                        Log.e(TAG, "Sync error: " + error);
                    }
                });
            }
            
            checkAndPromptPendingSales();
            
        } catch (Exception e) {
            Log.e(TAG, "onResume error: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            stopRFIDScan();
            isCheckoutDialogShowing = false;
            if (scaleConnector != null) {
                // لا تقم بفصل الاتصال هنا حتى لا ينقطع أثناء التنقل
            }
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActivePOSFragment(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            stopRFIDScan();
            isCheckoutDialogShowing = false;
            dismissSearchLoadingDialog();
            if (rfidManager != null) rfidManager.setListener(null);
            // إلغاء تسجيل Bluetooth receiver إذا كان موجوداً
            if (bluetoothReceiver != null) {
                try {
                    requireContext().unregisterReceiver(bluetoothReceiver);
                } catch (Exception e) {
                    Log.e(TAG, "Unregister receiver error: " + e.getMessage());
                }
                bluetoothReceiver = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopRFIDScan();
            if (scaleConnector != null) scaleConnector.disconnect();
            if (rfidManager != null) rfidManager.release();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
    }

    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private int getKaratNumber(String karat) {
        if (karat == null) return 21;
        switch (karat.toUpperCase()) {
            case "24K": return 24;
            case "22K": return 22;
            case "21K": return 21;
            case "18K": return 18;
            case "14K": return 14;
            default: return 21;
        }
    }

    private void safeRun(Runnable r) {
        try {
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (isAdded()) r.run();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "safeRun error: " + e.getMessage());
        }
    }

    private void showToast(String m) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToast error: " + e.getMessage());
        }
    }

    private void playAlertSound(int id) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).playSound(id);
            }
        } catch (Exception e) {
            Log.e(TAG, "playAlertSound error: " + e.getMessage());
        }
    }

    private void bindViews(View view) {
        tvScaleStatus = view.findViewById(R.id.tvPosScaleStatus);
        tvItemCount = view.findViewById(R.id.tvPosItemCount);
        tvTotalAmount = view.findViewById(R.id.tvPosTotalAmount);
        tvCartStatus = view.findViewById(R.id.tvPosCartStatus);
        tilManualEpc = view.findViewById(R.id.tilPosManualEpc);
        etManualEpc = view.findViewById(R.id.etPosManualEpc);
        btnAddManual = view.findViewById(R.id.btnPosAddManual);
        btnCheckout = view.findViewById(R.id.btnPosCheckout);
        btnClearCart = view.findViewById(R.id.btnPosClearCart);
        btnOpenScanner = view.findViewById(R.id.btnOpenScanner);
        btnPosRFIDMode = view.findViewById(R.id.btnPosRFIDMode);
        rvCartItems = view.findViewById(R.id.rvPosCartItems);
        layoutCartEmptyState = view.findViewById(R.id.layoutCartEmptyState);
        btnPosRefund = view.findViewById(R.id.btnPosRefund);
        
        // إضافة مستمع للنقر على حالة الميزان
        if (tvScaleStatus != null) {
            tvScaleStatus.setOnClickListener(v -> showScaleConnectionDialog());
        }
        
        applyLanguage();
    }

    private void applyLanguage() {
        try {
            if (isArabic) {
                if (tilManualEpc != null) tilManualEpc.setHint(getString(R.string.pos_manual_hint));
                if (btnCheckout != null) btnCheckout.setText(R.string.pos_checkout);
                if (btnClearCart != null) btnClearCart.setText(R.string.pos_clear);
                if (btnOpenScanner != null) btnOpenScanner.setText(R.string.pos_scan_gs1);
                if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText(R.string.pos_scan_rfid);
                if (btnAddManual != null) btnAddManual.setText(R.string.pos_add);
            } else {
                if (tilManualEpc != null) tilManualEpc.setHint("Enter EPC or Barcode...");
                if (btnCheckout != null) btnCheckout.setText("Checkout");
                if (btnClearCart != null) btnClearCart.setText("Clear");
                if (btnOpenScanner != null) btnOpenScanner.setText("📷 Scan GS1");
                if (btnPosRFIDMode != null && !isRFIDModeActive) btnPosRFIDMode.setText("📡 Scan RFID");
                if (btnAddManual != null) btnAddManual.setText("Add");
            }
        } catch (Exception e) {
            Log.e(TAG, "applyLanguage error: " + e.getMessage());
        }
    }

    private void setupRecyclerView() {
        try {
            if (rvCartItems != null) {
                rvCartItems.setLayoutManager(new LinearLayoutManager(getContext()));
                cartAdapter = new CartAdapter(cartList, this::removeItemFromCart, isArabic, priceManager);
                rvCartItems.setAdapter(cartAdapter);
            }
        } catch (Exception e) {
            Log.e(TAG, "setupRecyclerView error: " + e.getMessage());
        }
    }

    private void setupListeners() {
        try {
            if (etManualEpc != null) {
                etManualEpc.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        submitManualEpc();
                        return true;
                    }
                    return false;
                });
            }
            
            if (btnAddManual != null) {
                btnAddManual.setOnClickListener(v -> submitManualEpc());
            }
            
            if (btnClearCart != null) {
                btnClearCart.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    clearCart();
                });
            }
            
            if (btnCheckout != null) {
                btnCheckout.setOnClickListener(v -> {
                    UIHelper.hideKeyboard(getActivity());
                    processCheckoutAndStockUpdate();
                });
            }
            
            if (btnPosRefund != null) {
                btnPosRefund.setOnClickListener(v -> showRefundDialog());
            }
            
            if (btnOpenScanner != null) {
                btnOpenScanner.setOnClickListener(v -> {
                    try {
                        UIHelper.hideKeyboard(getActivity());
                        openBarcodeScanner();
                    } catch (Exception e) {
                        Log.e(TAG, "Open scanner error: " + e.getMessage());
                        showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
                    }
                });
            }
            
            if (btnPosRFIDMode != null) {
                btnPosRFIDMode.setOnClickListener(v -> {
                    try {
                        UIHelper.hideKeyboard(getActivity());
                        toggleRFIDMode();
                    } catch (Exception e) {
                        Log.e(TAG, "RFID mode error: " + e.getMessage());
                        showToast(isArabic ? "⚠️ خطأ في RFID" : "⚠️ RFID error");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage());
        }
    }

    private void submitManualEpc() {
        try {
            if (!(permissionManager.canView("pos") || permissionManager.isPosOperator() || permissionManager.isManager())) {
                showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                return;
            }
            
            UIHelper.hideKeyboard(getActivity());
            String epc = etManualEpc != null ? etManualEpc.getText().toString().trim() : "";
            if (!epc.isEmpty()) {
                if (scannedEpcs.contains(epc)) {
                    showToast(isArabic ? "⚠️ هذه القطعة مضافة مسبقاً!" : "⚠️ Item already in cart!");
                    playAlertSound(2);
                    return;
                }
                processRingEpc(epc, "manual");
                if (etManualEpc != null) etManualEpc.setText("");
            } else {
                showToast(isArabic ? "أدخل EPC أو باركود" : "Enter EPC or barcode");
            }
        } catch (Exception e) {
            Log.e(TAG, "submitManualEpc error: " + e.getMessage());
        }
    }

    // ==================== دالة الميزان المحدثة ====================
    private void initSmartScale() {
        try {
            scaleConnector = new SmartScaleConnector();
            scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
                @Override
                public void onWeightReceived(double w) {
                    liveScaleWeight = w;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(String.format(Locale.US, "⚖️ %.2fg", w));
                            tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                        }
                        // تحديث أوزان السلة تلقائياً
                        updateCartItemsScaleWeight(w);
                    });
                }
                
                @Override
                public void onScaleConnected(String scaleName) {
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText("⚖️ " + scaleName);
                            tvScaleStatus.setTextColor(Color.parseColor("#2ECC71"));
                        }
                        showToast(isArabic ? "✅ تم الاتصال بالميزان: " + scaleName : "✅ Scale connected: " + scaleName);
                        saveScaleInfo(scaleName);
                    });
                }
                
                @Override
                public void onScaleDisconnected() {
                    liveScaleWeight = 0.0;
                    safeRun(() -> {
                        if (tvScaleStatus != null) {
                            tvScaleStatus.setText(isArabic ? "⚖️ غير متصل" : "⚖️ Offline");
                            tvScaleStatus.setTextColor(Color.parseColor("#E74C3C"));
                        }
                    });
                }
                
                @Override
                public void onScaleError(String err) {
                    Log.e(TAG, "Scale Error: " + err);
                    safeRun(() -> showToast(isArabic ? "⚠️ خطأ الميزان: " + err : "⚠️ Scale Error: " + err));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "initSmartScale error: " + e.getMessage());
        }
    }

    // ==================== دوال الميزان الجديدة ====================
    
    private void showScaleConnectionDialog() {
        try {
            if (!isAdded() || getContext() == null) return;
            
            String[] options;
            if (scaleConnector != null && scaleConnector.isConnected()) {
                options = new String[]{
                    isArabic ? "🔌 قطع الاتصال" : "🔌 Disconnect"
                };
            } else {
                options = new String[]{
                    isArabic ? "📡 الاتصال عبر Bluetooth" : "📡 Connect via Bluetooth",
                    isArabic ? "🔍 البحث عن ميزان جديد" : "🔍 Search for new scale"
                };
            }
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚖️ إعدادات الميزان" : "⚖️ Scale Settings")
                .setItems(options, (dialog, which) -> {
                    if (scaleConnector != null && scaleConnector.isConnected()) {
                        scaleConnector.disconnect();
                        deleteSavedScaleMac();
                    } else {
                        if (which == 0) {
                            String savedMac = getSavedScaleMac();
                            if (savedMac != null && !savedMac.isEmpty()) {
                                scaleConnector.connectToDevice(savedMac);
                            } else {
                                startBluetoothDiscovery();
                            }
                        } else {
                            startBluetoothDiscovery();
                        }
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showScaleConnectionDialog error: " + e.getMessage());
        }
    }

    private void startBluetoothDiscovery() {
        try {
            if (!isAdded() || getContext() == null) return;
            
            // طلب إذن Bluetooth إذا لزم الأمر
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_PERMISSION);
                    return;
                }
            }
            
            // طلب تفعيل Bluetooth إذا كان مطفأً
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                showToast(isArabic ? "❌ Bluetooth غير مدعوم" : "❌ Bluetooth not supported");
                return;
            }
            
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return;
            }
            
            // بدء البحث عن الأجهزة
            showToast(isArabic ? "📡 جاري البحث عن الموازين..." : "📡 Scanning for scales...");
            discoveredDevices.clear();
            
            // إنشاء BroadcastReceiver
            bluetoothReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    
                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (device != null) {
                            if (!discoveredDevices.contains(device)) {
                                discoveredDevices.add(device);
                                Log.d(TAG, "Discovered device: " + device.getName() + " - " + device.getAddress());
                            }
                        }
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                        showDiscoveredDevices();
                    }
                }
            };
            
            // تسجيل المستمع
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            requireContext().registerReceiver(bluetoothReceiver, filter);
            
            // بدء البحث
            bluetoothAdapter.startDiscovery();
            
        } catch (Exception e) {
            Log.e(TAG, "startBluetoothDiscovery error: " + e.getMessage());
            showToast(isArabic ? "❌ خطأ في البحث" : "❌ Error scanning");
        }
    }

    private void showDiscoveredDevices() {
        try {
            if (!isAdded() || getContext() == null) return;
            
            // إلغاء تسجيل المستمع
            if (bluetoothReceiver != null) {
                try {
                    requireContext().unregisterReceiver(bluetoothReceiver);
                } catch (Exception e) {
                    Log.e(TAG, "Unregister receiver error: " + e.getMessage());
                }
                bluetoothReceiver = null;
            }
            
            if (discoveredDevices == null || discoveredDevices.isEmpty()) {
                showToast(isArabic ? "⚠️ لم يتم العثور على أجهزة" : "⚠️ No devices found");
                return;
            }
            
            // إنشاء قائمة بالأجهزة
            String[] deviceNames = new String[discoveredDevices.size()];
            for (int i = 0; i < discoveredDevices.size(); i++) {
                BluetoothDevice device = discoveredDevices.get(i);
                deviceNames[i] = device.getName() != null ? device.getName() : "Unknown " + device.getAddress();
            }
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📡 اختر الميزان" : "📡 Select Scale")
                .setItems(deviceNames, (dialog, which) -> {
                    BluetoothDevice selectedDevice = discoveredDevices.get(which);
                    connectToScale(selectedDevice);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
                
        } catch (Exception e) {
            Log.e(TAG, "showDiscoveredDevices error: " + e.getMessage());
        }
    }

    private void connectToScale(BluetoothDevice device) {
        try {
            showToast(isArabic ? "🔄 جاري الاتصال بـ " + device.getName() : "🔄 Connecting to " + device.getName());
            
            // حفظ MAC address للميزان للاستخدام المستقبلي
            saveScaleMac(device.getAddress());
            
            // الاتصال بالميزان
            scaleConnector.connectToDevice(device.getAddress());
            
        } catch (Exception e) {
            Log.e(TAG, "connectToScale error: " + e.getMessage());
            showToast(isArabic ? "❌ فشل الاتصال" : "❌ Connection failed");
        }
    }

    private void autoConnectScale() {
        try {
            String savedMac = getSavedScaleMac();
            if (savedMac != null && !savedMac.isEmpty()) {
                scaleConnector.connectToDevice(savedMac);
            }
        } catch (Exception e) {
            Log.e(TAG, "autoConnectScale error: " + e.getMessage());
        }
    }

    // دوال حفظ واسترجاع MAC Address
    private void saveScaleMac(String macAddress) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            
            SharedPreferences prefs = ctx.getSharedPreferences("scale_settings", Context.MODE_PRIVATE);
            prefs.edit().putString(PREFS_SCALE_MAC, macAddress).apply();
            
        } catch (Exception e) {
            Log.e(TAG, "saveScaleMac error: " + e.getMessage());
        }
    }

    private String getSavedScaleMac() {
        try {
            Context ctx = getContext();
            if (ctx == null) return null;
            
            SharedPreferences prefs = ctx.getSharedPreferences("scale_settings", Context.MODE_PRIVATE);
            return prefs.getString(PREFS_SCALE_MAC, null);
            
        } catch (Exception e) {
            Log.e(TAG, "getSavedScaleMac error: " + e.getMessage());
            return null;
        }
    }

    private void deleteSavedScaleMac() {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            
            SharedPreferences prefs = ctx.getSharedPreferences("scale_settings", Context.MODE_PRIVATE);
            prefs.edit().remove(PREFS_SCALE_MAC).apply();
            
        } catch (Exception e) {
            Log.e(TAG, "deleteSavedScaleMac error: " + e.getMessage());
        }
    }

    private void saveScaleInfo(String deviceName) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;
            
            SharedPreferences prefs = ctx.getSharedPreferences("scale_settings", Context.MODE_PRIVATE);
            prefs.edit().putString(PREFS_SCALE_NAME, deviceName).apply();
            
        } catch (Exception e) {
            Log.e(TAG, "saveScaleInfo error: " + e.getMessage());
        }
    }

    // دالة تحديث أوزان السلة تلقائياً
    private void updateCartItemsScaleWeight(double currentScaleWeight) {
        if (cartList.isEmpty() || currentScaleWeight <= 0) return;
        
        CartItem topItem = cartList.get(0);
        if (topItem.scaleWeight != currentScaleWeight) {
            topItem.scaleWeight = currentScaleWeight;
            
            // إعادة حساب السعر بناءً على الوزن الجديد للمجوهرات
            if (priceManager != null && topItem.weight > 0) {
                topItem.price = priceManager.calculateGoldPrice(getKaratNumber(topItem.karat), currentScaleWeight);
            }
            
            if (cartAdapter != null) {
                cartAdapter.notifyItemChanged(0);
            }
            updateCartSummary();
        }
    }

    private void openBarcodeScanner() {
        try {
            if (!isAdded() || getContext() == null) return;
            if (getParentFragmentManager() == null) return;
            if (getParentFragmentManager().isStateSaved()) return;
            
            MiraBarcodeScannerFragment sf = new MiraBarcodeScannerFragment();
            sf.setOnBarcodeScannedListener((data, format) -> {
                try {
                    onBarcodeScanned(data);
                    if (isAdded() && getParentFragmentManager() != null && 
                        getParentFragmentManager().getBackStackEntryCount() > 0) {
                        getParentFragmentManager().popBackStack();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Barcode callback error: " + e.getMessage());
                }
            });
            
            int containerId = android.R.id.content;
            try {
                View view = getView();
                if (view != null && view.getParent() instanceof View) {
                    containerId = ((View) view.getParent()).getId();
                }
            } catch (Exception e) {
                containerId = android.R.id.content;
            }
            
            getParentFragmentManager().beginTransaction()
                    .replace(containerId, sf)
                    .addToBackStack("barcode_scanner")
                    .commitAllowingStateLoss();
        } catch (Exception e) {
            Log.e(TAG, "openBarcodeScanner error: " + e.getMessage());
            showToast(isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner");
        }
    }

    // ... باقي الدوال كما هي بدون تغيير ...
    
    // ==================== دوال الـ Cart Adapter ====================
    private static class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {
        interface OnRemoveListener {
            void onRemove(int position);
        }

        private final List<CartItem> items;
        private final OnRemoveListener removeListener;
        private final boolean isArabic;
        private final MiraPriceManager priceManager;

        CartAdapter(List<CartItem> items, OnRemoveListener listener, boolean isArabic, MiraPriceManager priceManager) {
            this.items = items;
            this.removeListener = listener;
            this.isArabic = isArabic;
            this.priceManager = priceManager;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pos_cart_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            try {
                CartItem item = items.get(pos);
                if (h.tvTitle != null) h.tvTitle.setText(item.name);
                if (h.tvEpc != null) h.tvEpc.setText("EPC: " + item.epc);
                if (h.tvKaratWeight != null) {
                    h.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat,
                            item.scaleWeight > 0 ? item.scaleWeight : item.weight));
                }
                if (h.tvPrice != null) h.tvPrice.setText(priceManager.formatPriceDefault(item.price));
                if (h.tvStatus != null) {
                    h.tvStatus.setText(item.isAvailable ? (isArabic ? "✅ متاحة" : "✅ Available") : (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));
                }
                if (h.btnRemove != null) {
                    h.btnRemove.setOnClickListener(v -> {
                        int p = h.getAdapterPosition();
                        if (p != RecyclerView.NO_POSITION) removeListener.onRemove(p);
                    });
                }
            } catch (Exception e) {
                Log.e("CartAdapter", "Bind error: " + e.getMessage());
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus;
            View btnRemove;

            ViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvRowItemTitle);
                tvEpc = v.findViewById(R.id.tvRowItemEpc);
                tvKaratWeight = v.findViewById(R.id.tvRowItemKaratWeight);
                tvPrice = v.findViewById(R.id.tvRowItemPrice);
                tvStatus = v.findViewById(R.id.tvRowItemStatus);
                btnRemove = v.findViewById(R.id.btnRowRemove);
            }
        }
    }
    
    // ==================== دوال الـ Refund ====================
    
    private final ActivityResultLauncher<ScanOptions> refundBarcodeLauncher = registerForActivityResult(
        new ScanContract(),
        result -> {
            if (result.getContents() != null && isAdded()) {
                if (etRefundCode != null) {
                    etRefundCode.setText(result.getContents().trim());
                    searchItemForRefund(result.getContents().trim());
                }
            }
        }
    );

    private void showRefundDialog() {
        if (!isAdded() || getContext() == null) return;
        
        try {
            refundDialog = new Dialog(requireContext());
            refundDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            refundDialog.setContentView(R.layout.dialog_refund);
            
            if (refundDialog.getWindow() != null) {
                refundDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                refundDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            }
            
            etRefundCode = refundDialog.findViewById(R.id.etRefundScanCode);
            EditText etReason = refundDialog.findViewById(R.id.etRefundReason);
            tvRefundItemTitle = refundDialog.findViewById(R.id.tvRefundItemTitle);
            tvRefundItemStatus = refundDialog.findViewById(R.id.tvRefundItemStatus);
            cardRefundItemInfo = refundDialog.findViewById(R.id.cardRefundItemInfo);
            MaterialButton btnScanBarcode = refundDialog.findViewById(R.id.btnRefundScanBarcode);
            MaterialButton btnCancel = refundDialog.findViewById(R.id.btnRefundCancel);
            MaterialButton btnConfirm = refundDialog.findViewById(R.id.btnRefundConfirm);
            
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    try {
                        if (refundDialog != null && refundDialog.isShowing()) {
                            refundDialog.dismiss();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error dismissing refund dialog: " + e.getMessage());
                    }
                });
            }
            
            refundDialog.setOnDismissListener(dialog -> {
                etRefundCode = null;
                tvRefundItemTitle = null;
                tvRefundItemStatus = null;
                cardRefundItemInfo = null;
                isRefundable = false;
            });
            
            refundDialog.setCancelable(true);
            refundDialog.setCanceledOnTouchOutside(true);
            
            if (btnScanBarcode != null) {
                btnScanBarcode.setOnClickListener(v -> {
                    try {
                        ScanOptions options = new ScanOptions();
                        options.setPrompt(isArabic ? "📷 وجه الكاميرا نحو باركود القطعة" : "📷 Aim camera at item barcode");
                        options.setBeepEnabled(false);
                        options.setOrientationLocked(true);
                        refundBarcodeLauncher.launch(options);
                    } catch (Exception e) {
                        Log.e(TAG, "Barcode scanner error: " + e.getMessage());
                        Toast.makeText(getContext(), 
                            isArabic ? "⚠️ تعذر فتح الماسح" : "⚠️ Unable to open scanner", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
            
            if (etRefundCode != null) {
                etRefundCode.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (s.length() >= 4) {
                            searchItemForRefund(s.toString().trim());
                        } else {
                            hideRefundItemInfo();
                        }
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });
            }
            
            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    String code = etRefundCode != null ? etRefundCode.getText().toString().trim() : "";
                    String reason = etReason != null ? etReason.getText().toString().trim() : "";
                    
                    if (code.isEmpty()) {
                        Toast.makeText(getContext(), 
                            isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (!isRefundable) {
                        Toast.makeText(getContext(), 
                            isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة" : "⚠️ This item cannot be refunded", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    processRefund(code, reason.isEmpty() ? (isArabic ? "مرتجع مبيعات" : "Sales Refund") : reason);
                });
            }
            
            refundDialog.show();
            
        } catch (Exception e) {
            Log.e(TAG, "showRefundDialog error: " + e.getMessage(), e);
            Toast.makeText(getContext(), 
                isArabic ? "⚠️ خطأ في فتح نافذة المرتجع" : "⚠️ Error opening refund dialog", 
                Toast.LENGTH_SHORT).show();
        }
    }


/**
 * ✅ عند عودة الاتصال - عرض إشعار للمزامنة
 */
private void checkAndPromptPendingSales() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() > 0) {
            // ✅ عرض إشعار للمستخدم
            showSyncPromptDialog(arr.length());
        }
    } catch (Exception e) {
        Log.e(TAG, "checkPendingSales error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ التحقق من أن القطعة ليست مباعة محلياً
 */
private boolean isItemSoldLocally(String epc) {
    try {
        Context ctx = getContext();
        if (ctx == null) return false;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale == null) continue;
            
            // ✅ فحص القطع في كل عملية بيع معلقة
            JSONArray items = sale.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item != null && epc.equals(item.optString("epc", ""))) {
                        return true; // ✅ القطعة مباعة محلياً
                    }
                }
            }
            
            // ✅ فحص payload أيضاً
            JSONObject payload = sale.optJSONObject("payload");
            if (payload != null) {
                JSONArray payloadItems = payload.optJSONArray("items");
                if (payloadItems != null) {
                    for (int j = 0; j < payloadItems.length(); j++) {
                        JSONObject item = payloadItems.optJSONObject(j);
                        if (item != null && epc.equals(item.optString("epc", ""))) {
                            return true;
                        }
                    }
                }
            }
        }
        
        return false;
    } catch (Exception e) {
        Log.e(TAG, "isItemSoldLocally error: " + e.getMessage());
        return false;
    }
}
/**
 * ✅ عرض نافذة المزامنة
 */
 
private void showSyncPromptDialog(int pendingCount) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        // ✅ بدلاً من رسالة بسيطة، اعرض التفاصيل
        showPendingSalesDetailsBeforeSync();
    } catch (Exception e) {
        Log.e(TAG, "showSyncPromptDialog error: " + e.getMessage());
    }
}
/**
 * ✅ مزامنة المبيعات المعلقة - يدوية مع تأكيد
 */
/**
 * ✅✅✅ مزامنة المبيعات المعلقة - الفرع الحالي فقط مع تفاصيل كاملة
 */
/**
 * ✅✅✅ مزامنة المبيعات المعلقة - الفرع الحالي فقط مع تفاصيل كاملة وتحديث الداشبورد
 */
public void syncPendingSales() {
    if (isSyncing) return;
    isSyncing = true;

    Context ctx = getContext();
    if (ctx == null) {
        isSyncing = false;
        return;
    }

    executor.execute(() -> {
        JSONArray arr = getPendingSalesArray();
        if (arr.length() == 0) {
            safeRun(() -> {
                isSyncing = false;
                updateSyncUI();
                Toast.makeText(ctx, isArabic ? "لا توجد مبيعات معلقة للمزامنة" : "No pending sales to sync", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // ✅ استخدام sessionManager بدلاً من UserSession
        int currentBranchId = sessionManager != null ? sessionManager.getBranchId() : getActiveBranchId();
        
        int synced = 0;
        int failed = 0;
        JSONArray remaining = new JSONArray();
        StringBuilder syncDetails = new StringBuilder();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale == null) continue;

            try {
                String saleId = sale.optString("sale_id", "Unknown");
                int saleBranchId = sale.optInt("branch_id", -1);
                int saleUserId = sale.optInt("user_id", -1);
                String username = sale.optString("username", "Unknown");
                double amount = sale.optDouble("final_amount", 0);
                int itemCount = sale.optInt("item_count", 0);

                if (saleBranchId != currentBranchId) {
                    continue;
                }

                sale.put("sync_status", "syncing");
                sale.put("sync_attempts", sale.optInt("sync_attempts", 0) + 1);
                sale.put("last_sync_attempt", System.currentTimeMillis());

                saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                              "syncing", isArabic ? "جاري المزامنة..." : "Syncing...");

                JSONObject payload = sale.optJSONObject("payload");
                if (payload == null) {
                    failed++;
                    sale.put("sync_status", "failed");
                    sale.put("last_error", "Payload missing");
                    remaining.put(sale);
                    saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                  "failed", isArabic ? "فشل - Payload مفقود" : "Failed - Payload missing");
                    continue;
                }

                payload.put("branch_id", currentBranchId);
                payload.put("user_id", saleUserId);

                MiraApiClient client = MiraApiClient.getInstance(ctx);
                MiraApiClient.ApiResponse response = client.checkout(payload);

                if (response != null && response.isSuccess) {
                    synced++;
                    sale.put("sync_status", "synced");
                    sale.put("last_error", "");

                    syncDetails.append("✅ ").append(saleId)
                        .append(" | 💰 ").append(priceManager.formatPriceDefault(amount))
                        .append(" | 👤 ").append(username)
                        .append("\n");

                    saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                  "synced", isArabic ? "تمت المزامنة بنجاح" : "Synced successfully");
                } else {
                    failed++;
                    String errorMsg = response != null ? (response.error != null ? response.error : "Unknown error") : "Network error";
                    sale.put("sync_status", "failed");
                    sale.put("last_error", errorMsg);
                    remaining.put(sale);

                    syncDetails.append("❌ ").append(saleId)
                        .append(" | ").append(errorMsg)
                        .append("\n");

                    saveToSyncLog(saleId, currentBranchId, saleUserId, username, amount, itemCount, 
                                  "failed", errorMsg);
                }
            } catch (Exception e) {
                failed++;
                try {
                    sale.put("sync_status", "failed");
                    sale.put("last_error", e.getMessage());
                } catch (org.json.JSONException jsonException) {
                    Log.e(TAG, "JSON error while handling sync exception: " + jsonException.getMessage());
                }
                remaining.put(sale);

                saveToSyncLog(sale.optString("sale_id", "Unknown"), currentBranchId, 
                              sale.optInt("user_id", -1), sale.optString("username", "Unknown"),
                              sale.optDouble("final_amount", 0), sale.optInt("item_count", 0),
                              "failed", e.getMessage());
            }
        }

        savePendingSalesArray(remaining);

        final int finalSynced = synced;
        final int finalFailed = failed;
        final String details = syncDetails.toString();

        safeRun(() -> {
            isSyncing = false;
            updateSyncUI();

            String summaryMessage = (isArabic ? "تمت المزامنة: " : "Synced: ") + finalSynced + 
                                   (isArabic ? " | فشل: " : "Failed: ") + finalFailed;
            
            Toast.makeText(ctx, summaryMessage, Toast.LENGTH_LONG).show();

            if (finalSynced > 0 || finalFailed > 0) {
                showSyncSummaryDialog(finalSynced, finalFailed, details);
            }
            
            // ✅✅✅ تحديث الداشبورد بعد المزامنة إذا تمت مزامنة أي عملية
            if (finalSynced > 0) {
                notifyDashboardAndInventoryUpdate("sync");
            }
        });
    });
}

/**
 * ✅✅✅ عرض سجل المزامنة المفصل للفرع الحالي
 */
private void showSyncHistoryLog() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_sync_log_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("sync_log", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        if (arr.length() == 0) {
            showToast(isArabic ? "📋 لا يوجد سجل مزامنة" : "📋 No sync history");
            return;
        }
        
        StringBuilder logText = new StringBuilder();
        logText.append(isArabic ? "📋 سجل المزامنة - الفرع الحالي:\n\n" : "📋 Sync History - Current Branch:\n\n");
        
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) continue;
            
            String saleId = entry.optString("sale_id", "");
            String username = entry.optString("username", "");
            String status = entry.optString("status", "");
            String message = entry.optString("message", "");
            double amount = entry.optDouble("amount", 0);
            long timestamp = entry.optLong("timestamp", 0);
            
            String statusEmoji = "❓";
            switch (status) {
                case "synced": statusEmoji = "✅"; break;
                case "syncing": statusEmoji = "⏳"; break;
                case "failed": statusEmoji = "❌"; break;
                case "pending": statusEmoji = "📦"; break;
            }
            
            logText.append(statusEmoji).append(" ").append(saleId)
                .append(" | 💰 ").append(priceManager.formatPriceDefault(amount))
                .append(" | 👤 ").append(username)
                .append("\n  ").append(message)
                .append("\n\n");
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📋 سجل المزامنة" : "📋 Sync History")
            .setMessage(logText.toString())
            .setPositiveButton(isArabic ? "حسناً" : "OK", null)
            .show();
    } catch (Exception e) {
        Log.e(TAG, "showSyncHistoryLog error: " + e.getMessage());
    }
}
/**
 * ✅ البحث عن القطعة للتحقق من إمكانية الإرجاع
 */
private void searchItemForRefund(String code) {
    if (code == null || code.trim().isEmpty()) return;
    
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("epc", code);
            payload.put("serial_no", code);
            payload.put("branch_id", getActiveBranchId());
            payload.put("context", "refund_check");

            // ✅ استخدم searchItem بدلاً من checkRefundEligibility
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.searchItem(payload);

            safeRun(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    // ✅ استخراج بيانات القطعة
                    JSONObject itemData = response.data.optJSONObject("item");
                    if (itemData == null) {
                        itemData = response.data;
                    }
                    
                    // ✅ فحص إذا كانت القطعة قابلة للإرجاع
                    boolean isRefundable = itemData.optBoolean("is_refundable", false);
                    
                    if (isRefundable) {
                        showRefundItemInfo(itemData);
                    } else {
                        showRefundItemError(isArabic ? "⚠️ القطعة غير صالحة للإرجاع" : "⚠️ Item not eligible for refund");
                    }
                } else {
                    String errorMsg = response != null ? response.error : "Unknown error";
                    showRefundItemError(errorMsg);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "searchItemForRefund error: " + e.getMessage());
            safeRun(() -> showRefundItemError(isArabic ? "⚠️ خطأ في البحث عن القطعة" : "⚠️ Error searching item"));
        }
    });
}
private void showRefundItemInfo(JSONObject itemData) {
    try {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.VISIBLE);
        
        String title = itemData.optString("title", itemData.optString("item_title", isArabic ? "قطعة ذهبية" : "Gold Item"));
        double price = itemData.optDouble("retail_price_usd", itemData.optDouble("retail_price", 0.0));

        if (tvRefundItemTitle != null) {
            tvRefundItemTitle.setText(title + " (" + priceManager.formatPriceDefault(price) + ")");
        }
        
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic ? "✅ القطعة مباعة وقابلة للإرجاع" : "✅ Item sold and refundable");
            tvRefundItemStatus.setTextColor(Color.parseColor("#2ECC71"));
        }
        
        isRefundable = true;
    } catch (Exception e) {
        Log.e(TAG, "showRefundItemInfo error: " + e.getMessage());
    }
}

private void hideRefundItemInfo() {
    safeRun(() -> {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.GONE);
        isRefundable = false;
    });
}

private void showRefundItemError(String message) {
    if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(View.VISIBLE);
    if (tvRefundItemTitle != null) tvRefundItemTitle.setText(message);
    if (tvRefundItemStatus != null) {
        tvRefundItemStatus.setText(isArabic ? "غير قابلة للإرجاع ❌" : "Not Refundable ❌");
        tvRefundItemStatus.setTextColor(Color.parseColor("#E74C3C"));
    }
    isRefundable = false;
}

/**
 * ✅✅✅ معالجة الإرجاع مع تحديث الداشبورد
 */
private void processRefund(String code, String reason) {
    final AlertDialog loadingDialog = showProfessionalLoadingDialog();
    
    executor.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            // ❌ احذف هذا السطر
            // payload.put("action", "PROCESS_REFUND");
            
            payload.put("epc", code);
            payload.put("serial_no", code); // ✅ أضف هذا
            payload.put("reason", reason);
            payload.put("branch_id", getActiveBranchId());
            payload.put("user_id", sessionManager != null ? sessionManager.getUserId() : 0);

            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.processRefund(payload);

            safeRun(() -> {
                dismissDialog(loadingDialog);
                if (refundDialog != null && refundDialog.isShowing()) {
                    refundDialog.dismiss();
                }
                
                if (response != null && response.isSuccess) {
                    showSuccessMessage(
                        isArabic ? "✅ تم الإرجاع" : "✅ Refund Successful",
                        isArabic ? "تم إرجاع القطعة بنجاح إلى المخزون" : "Item successfully returned to inventory",
                        true
                    );
                    
                    notifyDashboardAndInventoryUpdate("refund");
                    
                } else {
                    String err = response != null && response.error != null ? response.error : "Unknown error";
                    showSuccessMessage(
                        isArabic ? "❌ فشل الإرجاع" : "❌ Refund Failed",
                        err,
                        false
                    );
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
            safeRun(() -> {
                dismissDialog(loadingDialog);
                showSuccessMessage(isArabic ? "❌ خطأ" : "❌ Error", e.getMessage(), false);
            });
        }
    });
}
/**
 * ✅✅✅ إرسال إشارات تحديث الداشبورد والمخزون
 */
private void notifyDashboardAndInventoryUpdate(String source) {
    try {
        if (!isAdded() || getContext() == null) return;
        
        Context ctx = getContext();
        int branchId = getActiveBranchId();
        long timestamp = System.currentTimeMillis();
        
        Log.e(TAG, "🔔🔔🔔 NOTIFY UPDATE - Source: " + source + ", Branch: " + branchId);
        
        // ✅ 1. إرسال REFRESH_SALES
        Intent salesIntent = new Intent("com.example.uhf.REFRESH_SALES");
        salesIntent.setPackage(ctx.getPackageName());
        salesIntent.putExtra("source", source);
        salesIntent.putExtra("branch_id", branchId);
        salesIntent.putExtra("timestamp", timestamp);
        ctx.sendBroadcast(salesIntent);
        Log.e(TAG, "✅ Broadcast REFRESH_SALES sent");
        
        // ✅✅✅ 2. إرسال REFRESH_INVENTORY
        Intent inventoryIntent = new Intent("com.example.uhf.REFRESH_INVENTORY");
        inventoryIntent.setPackage(ctx.getPackageName());
        inventoryIntent.putExtra("source", source);
        inventoryIntent.putExtra("branch_id", branchId);
        inventoryIntent.putExtra("timestamp", timestamp);
        ctx.sendBroadcast(inventoryIntent);
        Log.e(TAG, "✅ Broadcast REFRESH_INVENTORY sent");
        
        // ✅✅✅ 3. إرسال REFRESH_DASHBOARD
        Intent dashboardIntent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
        dashboardIntent.setPackage(ctx.getPackageName());
        dashboardIntent.putExtra("source", source);
        dashboardIntent.putExtra("branch_id", branchId);
        dashboardIntent.putExtra("timestamp", timestamp);
        ctx.sendBroadcast(dashboardIntent);
        Log.e(TAG, "✅ Broadcast REFRESH_DASHBOARD sent");
        
        // ✅ 4. تحديث SharedPreferences
        SharedPreferences prefs = ctx.getSharedPreferences("app_refresh_flags", Context.MODE_PRIVATE);
        prefs.edit()
            .putLong("last_data_change", timestamp)
            .putString("last_change_type", source)
            .putInt("last_change_branch_id", branchId)
            .putBoolean("needs_refresh", true)
            .apply();
        
        Log.e(TAG, "✅ All dashboard update signals sent successfully");
        
    } catch (Exception e) {
        Log.e(TAG, "notifyDashboardAndInventoryUpdate error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ الحصول على مصفوفة المبيعات المعلقة
 */
private JSONArray getPendingSalesArray() {
    try {
        Context ctx = getContext();
        if (ctx == null) return new JSONArray();
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        return new JSONArray(existingJson);
    } catch (Exception e) {
        Log.e(TAG, "getPendingSalesArray error: " + e.getMessage());
        return new JSONArray();
    }
}

/**
 * ✅✅✅ حفظ مصفوفة المبيعات المعلقة
 */
private void savePendingSalesArray(JSONArray salesArray) {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.edit().putString("pending_sales", salesArray.toString()).apply();
        
        // ✅ تحديث العداد
        updatePendingSalesBadge(salesArray.length());
        
    } catch (Exception e) {
        Log.e(TAG, "savePendingSalesArray error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ تحديث واجهة المزامنة
 */
private void updateSyncUI() {
    try {
        safeRun(() -> {
            // ✅ تحديث العداد
            int pendingCount = getPendingSalesArray().length();
            updatePendingSalesBadge(pendingCount);
            
            // ✅ تحديث حالة السلة
            if (tvCartStatus != null) {
                if (isSyncing) {
                    tvCartStatus.setText(isArabic ? "⏳ جاري المزامنة..." : "⏳ Syncing...");
                    tvCartStatus.setTextColor(Color.parseColor("#F39C12"));
                } else if (pendingCount > 0) {
                    tvCartStatus.setText(isArabic ? "⚠️ " + pendingCount + " عملية معلقة" : "⚠️ " + pendingCount + " pending");
                    tvCartStatus.setTextColor(Color.parseColor("#F39C12"));
                } else if (cartList.isEmpty()) {
                    tvCartStatus.setText(isArabic ? getString(R.string.pos_ready_scan) : "Ready to scan...");
                    tvCartStatus.setTextColor(Color.parseColor("#2ECC71"));
                }
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "updateSyncUI error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ عرض ملخص المزامنة
 */
private void showSyncSummaryDialog(int syncedCount, int failedCount, String details) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        String title = isArabic ? "📊 ملخص المزامنة" : "📊 Sync Summary";
        String message = (isArabic ? "✅ تمت المزامنة: " : "✅ Synced: ") + syncedCount + "\n" +
                        (isArabic ? "❌ فشل: " : "❌ Failed: ") + failedCount;
        
        if (details != null && !details.isEmpty()) {
            message += "\n\n" + (isArabic ? "التفاصيل:\n" : "Details:\n") + details;
        }
        
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(isArabic ? "حسناً" : "OK", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "showSyncSummaryDialog error: " + e.getMessage());
    }
}
/**
 * ✅✅✅ عرض قائمة السجلات المعلقة مع إمكانية التعديل أو الحذف
 */
private void showPendingSalesWithActions() {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        JSONArray arr = getPendingSalesArray();
        
        if (arr.length() == 0) {
            showToast(isArabic ? "📋 لا توجد عمليات معلقة" : "📋 No pending sales");
            return;
        }
        
        // ✅ إنشاء قائمة بأسماء العمليات
        String[] saleTitles = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale != null) {
                String saleId = sale.optString("sale_id", "Unknown");
                String customerName = sale.optString("customer_name", "");
                double amount = sale.optDouble("final_amount", 0);
                String syncStatus = sale.optString("sync_status", "pending");
                
                String statusEmoji = "📦";
                switch (syncStatus) {
                    case "synced": statusEmoji = "✅"; break;
                    case "syncing": statusEmoji = "⏳"; break;
                    case "failed": statusEmoji = "❌"; break;
                }
                
                saleTitles[i] = statusEmoji + " " + 
                    (isArabic ? "عميل: " : "Customer: ") + customerName + 
                    " | " + priceManager.formatPriceDefault(amount);
            }
        }
        
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(isArabic ? "📋 العمليات المعلقة (" + arr.length() + ")" : "📋 Pending Sales (" + arr.length() + ")")
            .setItems(saleTitles, (dialog, which) -> {
                try {
                    JSONObject selectedSale = arr.optJSONObject(which);
                    if (selectedSale != null) {
                        showPendingSaleDetails(selectedSale, which);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "showPendingSalesWithActions.onClick error: " + e.getMessage());
                }
            })
            .setPositiveButton(isArabic ? "✅ مزامنة الكل" : "✅ Sync All", (dialog, which) -> {
                dialog.dismiss();
                syncPendingSales();
            })
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "showPendingSalesWithActions error: " + e.getMessage());
    }
}
/**
 * ✅✅✅ عرض تفاصيل سجل معلق محدد مع إمكانية التعديل أو الحذف
 */
private void showPendingSaleDetails(JSONObject sale, int position) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        String saleId = sale.optString("sale_id", "Unknown");
        String customerName = sale.optString("customer_name", "");
        String paymentMethod = sale.optString("payment_method", "");
        double amount = sale.optDouble("final_amount", 0);
        int itemCount = sale.optInt("item_count", 0);
        long createdAt = sale.optLong("created_at", 0);
        
        // ✅ تنسيق التاريخ
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String dateStr = sdf.format(new Date(createdAt));
        
        // ✅ عرض تفاصيل القطع
        StringBuilder itemsDetails = new StringBuilder();
        JSONArray items = sale.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    itemsDetails.append("  • ")
                        .append(item.optString("title", "Unknown"))
                        .append(" - ")
                        .append(item.optString("epc", ""))
                        .append("\n");
                }
            }
        }
        
        String message = (isArabic ? 
            "🔑 رقم العملية: " : "🔑 Sale ID: ") + saleId + "\n" +
            (isArabic ? "👤 العميل: " : "👤 Customer: ") + customerName + "\n" +
            (isArabic ? "💳 الدفع: " : "💳 Payment: ") + paymentMethod + "\n" +
            (isArabic ? "💰 المبلغ: " : "💰 Amount: ") + priceManager.formatPriceDefault(amount) + "\n" +
            (isArabic ? "📦 عدد القطع: " : "📦 Items: ") + itemCount + "\n" +
            (isArabic ? "🕐 التاريخ: " : "🕐 Date: ") + dateStr + "\n\n" +
            (isArabic ? "القطع:\n" : "Items:\n") + itemsDetails.toString();
        
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(isArabic ? "📋 تفاصيل العملية المعلقة" : "📋 Pending Sale Details")
            .setMessage(message)
            .setPositiveButton(isArabic ? "✏️ تعديل" : "✏️ Edit", (dialog, which) -> {
                dialog.dismiss();
                showEditPendingSaleDialog(sale, position);
            })
            .setNegativeButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (dialog, which) -> {
                dialog.dismiss();
                confirmDeletePendingSale(saleId, position);
            })
            .setNeutralButton(isArabic ? "إغلاق" : "Close", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "showPendingSaleDetails error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ عرض نافذة تعديل العملية المعلقة
 */
private void showEditPendingSaleDialog(JSONObject sale, int position) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        final String saleId = sale.optString("sale_id", "");
        
        // ✅ إنشاء نافذة التعديل
        MaterialCardView cardView = new MaterialCardView(ctx);
        cardView.setRadius(24);
        cardView.setCardElevation(10);
        
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 30);
        
        // ✅ حقل اسم العميل
        TextView tvCustomerLabel = new TextView(ctx);
        tvCustomerLabel.setText(isArabic ? "👤 اسم العميل:" : "👤 Customer Name:");
        tvCustomerLabel.setTextSize(14);
        tvCustomerLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvCustomerLabel);
        
        EditText etCustomerName = new EditText(ctx);
        etCustomerName.setText(sale.optString("customer_name", ""));
        etCustomerName.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameParams.bottomMargin = 16;
        etCustomerName.setLayoutParams(nameParams);
        layout.addView(etCustomerName);
        
        // ✅ حقل المبلغ
        TextView tvAmountLabel = new TextView(ctx);
        tvAmountLabel.setText(isArabic ? "💰 المبلغ:" : "💰 Amount:");
        tvAmountLabel.setTextSize(14);
        tvAmountLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvAmountLabel);
        
        EditText etAmount = new EditText(ctx);
        etAmount.setText(String.valueOf(sale.optDouble("final_amount", 0)));
        etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setSingleLine(true);
        LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        amountParams.bottomMargin = 16;
        etAmount.setLayoutParams(amountParams);
        layout.addView(etAmount);
        
        // ✅ حقل طريقة الدفع
        TextView tvPaymentLabel = new TextView(ctx);
        tvPaymentLabel.setText(isArabic ? "💳 طريقة الدفع:" : "💳 Payment Method:");
        tvPaymentLabel.setTextSize(14);
        tvPaymentLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvPaymentLabel);
        
        EditText etPaymentMethod = new EditText(ctx);
        etPaymentMethod.setText(sale.optString("payment_method", "cash"));
        etPaymentMethod.setSingleLine(true);
        layout.addView(etPaymentMethod);
        
        cardView.addView(layout);
        
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(isArabic ? "✏️ تعديل العملية المعلقة" : "✏️ Edit Pending Sale")
            .setView(cardView)
            .setPositiveButton(isArabic ? "💾 حفظ" : "💾 Save", (dialog, which) -> {
                String newCustomerName = etCustomerName.getText().toString().trim();
                String newAmountStr = etAmount.getText().toString().trim();
                String newPaymentMethod = etPaymentMethod.getText().toString().trim();
                
                if (newCustomerName.isEmpty()) {
                    showToast(isArabic ? "⚠️ اسم العميل مطلوب" : "⚠️ Customer name required");
                    return;
                }
                
                double newAmount = 0;
                try {
                    newAmount = Double.parseDouble(newAmountStr);
                } catch (NumberFormatException e) {
                    showToast(isArabic ? "⚠️ مبلغ غير صحيح" : "⚠️ Invalid amount");
                    return;
                }
                
                if (newAmount <= 0) {
                    showToast(isArabic ? "⚠️ المبلغ يجب أن يكون أكبر من صفر" : "⚠️ Amount must be greater than zero");
                    return;
                }
                
                // ✅ حفظ التعديلات
                updatePendingSale(saleId, newCustomerName, newAmount, newPaymentMethod, position);
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "showEditPendingSaleDialog error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ تأكيد حذف العملية المعلقة
 */
private void confirmDeletePendingSale(String saleId, int position) {
    try {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;
        
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(isArabic ? "🗑️ تأكيد الحذف" : "🗑️ Confirm Delete")
            .setMessage(isArabic ? 
                "هل أنت متأكد من حذف هذه العملية المعلقة؟\n\n" +
                "🔑 رقم العملية: " + saleId + "\n\n" +
                "لا يمكن التراجع عن هذا الإجراء!"
                :
                "Are you sure you want to delete this pending sale?\n\n" +
                "🔑 Sale ID: " + saleId + "\n\n" +
                "This action cannot be undone!")
            .setPositiveButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (dialog, which) -> {
                dialog.dismiss();
                deletePendingSale(saleId);
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "confirmDeletePendingSale error: " + e.getMessage());
    }
}

/**
 * ✅✅✅ تحديث العملية المعلقة بعد التعديل
 */
private void updatePendingSale(String saleId, String newCustomerName, double newAmount, 
                               String newPaymentMethod, int position) {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        
        // ✅ البحث عن العملية وتحديثها
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale != null && sale.optString("sale_id").equals(saleId)) {
                // ✅ تحديث البيانات الأساسية
                sale.put("customer_name", newCustomerName);
                sale.put("final_amount", newAmount);
                sale.put("payment_method", newPaymentMethod);
                
                // ✅ تحديث payload إذا كان موجوداً
                JSONObject payload = sale.optJSONObject("payload");
                if (payload != null) {
                    payload.put("customer_name", newCustomerName);
                    
                    // ✅ تحديث كائن العميل
                    JSONObject customer = payload.optJSONObject("customer");
                    if (customer == null) {
                        customer = new JSONObject();
                        payload.put("customer", customer);
                    }
                    customer.put("name", newCustomerName);
                    customer.put("payment_method", newPaymentMethod);
                    
                    // ✅ تحديث كائن الدفع
                    JSONObject payment = payload.optJSONObject("payment");
                    if (payment == null) {
                        payment = new JSONObject();
                        payload.put("payment", payment);
                    }
                    payment.put("final_amount_usd", newAmount);
                    payment.put("payment_method", newPaymentMethod);
                    
                    payload.put("payment", payment);
                    sale.put("payload", payload);
                }
                
                // ✅ تحديث السجل
                saveToSyncLog(saleId, currentBranchId, 
                    sale.optInt("user_id", 0), 
                    sale.optString("username", ""),
                    newAmount, 
                    sale.optInt("item_count", 0),
                    "pending", 
                    isArabic ? "تم التعديل" : "Edited");
                
                break;
            }
        }
        
        // ✅ حفظ التعديلات
        prefs.edit().putString("pending_sales", arr.toString()).apply();
        
        // ✅ تحديث الواجهة
        updatePendingSalesBadge(arr.length());
        
        showToast(isArabic ? "✅ تم حفظ التعديلات" : "✅ Changes saved");
        
    } catch (Exception e) {
        Log.e(TAG, "updatePendingSale error: " + e.getMessage());
        showToast(isArabic ? "❌ فشل حفظ التعديلات" : "❌ Failed to save changes");
    }
}

/**
 * ✅✅✅ حذف العملية المعلقة
 */
private void deletePendingSale(String saleId) {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        
        int currentBranchId = getActiveBranchId();
        String prefsName = "pos_offline_sales_branch_" + currentBranchId;
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String existingJson = prefs.getString("pending_sales", "[]");
        JSONArray arr = new JSONArray(existingJson);
        JSONArray newArr = new JSONArray();
        
        // ✅ إزالة العملية المحددة
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            if (sale != null && !sale.optString("sale_id").equals(saleId)) {
                newArr.put(sale);
            }
        }
        
        // ✅ حفظ القائمة الجديدة
        prefs.edit().putString("pending_sales", newArr.toString()).apply();
        
        // ✅ تحديث السجل
        saveToSyncLog(saleId, currentBranchId, 0, "", 0, 0, 
            "deleted", isArabic ? "تم الحذف" : "Deleted");
        
        // ✅ تحديث الواجهة
        updatePendingSalesBadge(newArr.length());
        
        showToast(isArabic ? "🗑️ تم حذف العملية" : "🗑️ Sale deleted");
        
    } catch (Exception e) {
        Log.e(TAG, "deletePendingSale error: " + e.getMessage());
        showToast(isArabic ? "❌ فشل الحذف" : "❌ Failed to delete");
    }
}

// ✅ دالة تحديث كاش القطع المباعة محلياً
private void removeSoldItemsFromCache() {
    try {
        Context ctx = getContext();
        if (ctx == null) return;
        MiraLocalItemCache cache = MiraLocalItemCache.getInstance(ctx);
        for (CartItem item : cartList) {
            cache.markItemAsSold(item.epc);
        }
    } catch (Exception e) {
        Log.e(TAG, "removeSoldItemsFromCache error: " + e.getMessage());
    }
}

} 
