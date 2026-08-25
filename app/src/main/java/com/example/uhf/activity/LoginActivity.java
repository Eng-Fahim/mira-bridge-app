package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric, btnSaveServer, btnToggleLanguage;
    private MaterialTextView tvServerSettings, tvAppVersion, tvBrandName, tvBrandSubtitle, tvBrandDescription;
    private MaterialTextView tvSignInTitle, tvSignInSubtitle;
    private ImageView ivAppLogo;
    private MaterialCardView serverConfigCard;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard, logoContainer;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private MiraOrgProfileManager orgProfileManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService bgExecutor;

    private boolean isArabic = true;
    private String lastPassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);

        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        bgExecutor = Executors.newSingleThreadExecutor();

        String currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        isArabic = currentLang.isEmpty() || currentLang.startsWith("ar");

        setContentView(R.layout.activity_login);
        
        orgProfileManager = MiraOrgProfileManager.getInstance(this);
        
        initViews();
        disableAutofill();
        setupBiometric();
        setupServerConfigToggle();
        animateEntrance();
        setupTextWatchers();
        updateUiLanguage(isArabic);
        setupProfileListener();
        loadOrgProfileFromCloud();
    }

    private void executeSafeBg(Runnable runnable) {
        if (bgExecutor != null && !bgExecutor.isShutdown() && !bgExecutor.isTerminated()) {
            try {
                bgExecutor.execute(runnable);
            } catch (Exception e) {
                Log.e(TAG, "Task execution rejected", e);
            }
        }
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.etServerUrl);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        tilServerUrl = findViewById(R.id.tilServerUrl);
        tilUsername = findViewById(R.id.tilUsername);
        tilPassword = findViewById(R.id.tilPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnBiometric = findViewById(R.id.btnBiometric);
        btnSaveServer = findViewById(R.id.btnSaveServer);
        btnToggleLanguage = findViewById(R.id.btnToggleLanguage);
        tvServerSettings = findViewById(R.id.tvServerSettings);
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvBrandName = findViewById(R.id.tvBrandName);
        tvBrandSubtitle = findViewById(R.id.tvBrandSubtitle);
        tvBrandDescription = findViewById(R.id.tvBrandDescription);
        tvSignInTitle = findViewById(R.id.tvSignInTitle);
        tvSignInSubtitle = findViewById(R.id.tvSignInSubtitle);
        ivAppLogo = findViewById(R.id.ivAppLogo);
        serverConfigCard = findViewById(R.id.serverConfigCard);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);
        logoContainer = findViewById(R.id.logoContainer);

        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }

        try {
            tvAppVersion.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " Enterprise");
        } catch (Exception e) {
            tvAppVersion.setText("v1.0.0 Enterprise");
        }

        apiClient = MiraApiClient.getInstance(this);

        btnLogin.setOnClickListener(v -> attemptApiLogin());

        btnSaveServer.setOnClickListener(v -> {
            String url = getText(etServerUrl);
            sessionManager.setServerUrl(url);
            Toast.makeText(this, isArabic ? "✓ تم حفظ رابط الخادم بنجاح" : "✓ Server URL saved", Toast.LENGTH_SHORT).show();
        });

        if (btnToggleLanguage != null) {
            btnToggleLanguage.setOnClickListener(v -> {
                isArabic = !isArabic;
                String langTag = isArabic ? "ar" : "en";
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(langTag));
                
                finish();
                startActivity(getIntent());
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
        }

        applyOrgProfile();
    }

    private void disableAutofill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (etUsername != null) etUsername.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            if (etPassword != null) etPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            if (etServerUrl != null) etServerUrl.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
    }

    private void setupProfileListener() {
        if (orgProfileManager == null) return;
        
        profileListener = () -> {
            if (isActivityAlive()) {
                mainHandler.post(this::applyOrgProfile);
            }
        };
        
        orgProfileManager.addListener(profileListener);
    }

    private void loadOrgProfileFromCloud() {
        executeSafeBg(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getOrgProfile();
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject org = response.data.optJSONObject("organization");
                    
                    if (org != null) {
                        orgProfileManager.saveFromServer(org);
                    } else {
                        JSONObject rootOrg = new JSONObject();
                        rootOrg.put("name_ar", response.data.optString("org_name", ""));
                        rootOrg.put("name_en", response.data.optString("org_name_en", ""));
                        rootOrg.put("phone", response.data.optString("org_phone", ""));
                        rootOrg.put("email", response.data.optString("org_email", ""));
                        rootOrg.put("tax_number", response.data.optString("tax_number", ""));
                        rootOrg.put("logo_url", response.data.optString("org_logo", ""));
                        orgProfileManager.saveFromServer(rootOrg);
                    }
                    
                    if (isActivityAlive()) {
                        mainHandler.post(this::applyOrgProfile);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Cloud org load error: " + e.getMessage());
            }
        });
    }

    private void updateUiLanguage(boolean arabic) {
        getWindow().getDecorView().setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        if (arabic) {
            if (tvSignInTitle != null) tvSignInTitle.setText("تسجيل الدخول");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("أدخل بيانات الاعتماد الخاصة بك للمتابعة");
            if (tilUsername != null) tilUsername.setHint("اسم المستخدم");
            if (tilPassword != null) tilPassword.setHint("كلمة المرور");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("دخول النظام");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 إعدادات الخادم والاتصال (Server Settings)");
            if (btnToggleLanguage != null) btnToggleLanguage.setText("EN / AR");
        } else {
            if (tvSignInTitle != null) tvSignInTitle.setText("Sign In");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("Enter your credentials to continue");
            if (tilUsername != null) tilUsername.setHint("Username");
            if (tilPassword != null) tilPassword.setHint("Password");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("Sign In");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 Server Configuration");
            if (btnToggleLanguage != null) btnToggleLanguage.setText("AR / EN");
        }
        applyOrgProfile();
    }

    private void applyOrgProfile() {
        if (!isActivityAlive()) return;

        if (orgProfileManager == null) {
            orgProfileManager = MiraOrgProfileManager.getInstance(this);
        }
        
        if (tvBrandName != null) {
            if (isArabic) {
                String nameAr = orgProfileManager.getOrgNameAr();
                tvBrandName.setText(nameAr != null && !nameAr.isEmpty() ? nameAr : "MIRA Jewelry");
            } else {
                String nameEn = orgProfileManager.getOrgNameEn();
                tvBrandName.setText(nameEn != null && !nameEn.isEmpty() ? nameEn : "MIRA Jewelry");
            }
        }
        
        if (tvBrandSubtitle != null) {
            tvBrandSubtitle.setText("MIRA ID™ Smart Jewelry Tag");
        }
        
        if (tvBrandDescription != null) {
            if (isArabic) {
                tvBrandDescription.setText("منظومة التتبع والمصادقة المعتمدة للمجوهرات");
            } else {
                tvBrandDescription.setText("Enterprise Jewelry Traceability & Authentication");
            }
        }
        
        if (ivAppLogo != null) {
            String logoBase64 = orgProfileManager.getOrgLogo();
            if (logoBase64 != null && !logoBase64.isEmpty()) {
                try {
                    byte[] bytes = android.util.Base64.decode(logoBase64, android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    ivAppLogo.setImageBitmap(bitmap);
                } catch (Exception e) {
                    ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
                }
            } else {
                ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
            }
        }
    }

    private void setupServerConfigToggle() {
        if (tvServerSettings != null) {
            tvServerSettings.setOnClickListener(v -> {
                if (serverConfigCard.getVisibility() == View.GONE) {
                    serverConfigCard.setVisibility(View.VISIBLE);
                    serverConfigCard.setAlpha(0f);
                    serverConfigCard.animate().alpha(1f).setDuration(300).start();
                    tvServerSettings.setText(isArabic ? "🔧 إخفاء إعدادات الخادم" : "🔧 Hide Server Settings");
                } else {
                    serverConfigCard.animate().alpha(0f).setDuration(200).withEndAction(() -> serverConfigCard.setVisibility(View.GONE)).start();
                    tvServerSettings.setText(isArabic ? "🔧 إعدادات الخادم والاتصال (Server Settings)" : "🔧 Server Configuration");
                }
            });
        }
        if (loginCard != null) {
            loginCard.setOnLongClickListener(v -> {
                if (tvServerSettings != null) tvServerSettings.performClick();
                return true;
            });
        }
    }

    private void animateEntrance() {
        if (loginCard == null) return;
        loginCard.setAlpha(0f);
        loginCard.setTranslationY(60f);
        loginCard.animate().alpha(1f).translationY(0f).setDuration(600)
            .setInterpolator(new AccelerateDecelerateInterpolator()).setStartDelay(150).start();
        if (logoContainer != null) {
            logoContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_up));
        }
    }

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateLoginButtonState(); }
        };
        if (etUsername != null) etUsername.addTextChangedListener(watcher);
        if (etPassword != null) etPassword.addTextChangedListener(watcher);
    }

    private void updateLoginButtonState() {
        String username = getText(etUsername);
        String password = getText(etPassword);
        boolean valid = !username.isEmpty() && password.length() >= 4;
        if (btnLogin != null) {
            btnLogin.setEnabled(valid);
            btnLogin.setAlpha(valid ? 1f : 0.5f);
        }
    }

    private String getText(TextInputEditText editText) {
        return (editText != null && editText.getText() != null) ? editText.getText().toString().trim() : "";
    }

    private void playTone(boolean isSuccess) {
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            toneGen.startTone(isSuccess ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_SUP_ERROR, 150);
        } catch (Exception e) {
            Log.e(TAG, "Tone error: " + e.getMessage());
        }
    }

    private void attemptApiLogin() {
        String username = getText(etUsername);
        String password = getText(etPassword);
        
        lastPassword = password;

        if (username.isEmpty()) {
            tilUsername.setError(isArabic ? "اسم المستخدم مطلوب" : "Username required");
            shakeView(tilUsername);
            playTone(false);
            return;
        }
        if (password.length() < 4) {
            tilPassword.setError(isArabic ? "كلمة المرور قصيرة جداً" : "Password too short");
            shakeView(tilPassword);
            playTone(false);
            return;
        }

        tilUsername.setError(null);
        tilPassword.setError(null);
        showLoading(true);

        executeSafeBg(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse response = apiClient.login(username, password, deviceId);
            
            mainHandler.post(() -> {
                if (!isActivityAlive()) return;
                showLoading(false);
                if (response != null && response.isSuccess) {
                    handleLoginSuccess(response, username, password);
                } else {
                    String lastBiometricUser = sessionManager.getLastBiometricUser();
                    if (!lastBiometricUser.isEmpty() && lastBiometricUser.equalsIgnoreCase(username)) {
                        executeOfflineLogin(username);
                    } else {
                        playTone(false);
                        String errorMsg = response != null
                            ? response.optString("message", isArabic ? "بيانات الاعتماد غير صالحة" : "Invalid credentials")
                            : (isArabic ? "فشل الاتصال بالخادم وتطابق أوفلاين غير متوفر" : "Connection failed and offline verification unavailable");
                        Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                        shakeView(loginCard);
                    }
                }
            });
        });
    }

    private void executeOfflineLogin(String username) {
        playTone(true);
        String lastRole = sessionManager.getLastBiometricRole();
        String lastDisplayName = sessionManager.getLastBiometricDisplayName();
        int lastUserId = sessionManager.getLastBiometricUserId();
        String lastToken = sessionManager.getLastBiometricToken();

        sessionManager.createLoginSession(username, lastRole, 
            lastToken.isEmpty() ? "OFFLINE_SESSION_TOKEN" : lastToken, 
            sessionManager.getServerUrl());
        sessionManager.setDisplayName(lastDisplayName);
        sessionManager.setUserId(lastUserId);

        int savedBranchId = sessionManager.getLastBiometricBranchId();
        String savedBranchName = sessionManager.getLastBiometricBranchName();
        if (savedBranchId > 0) {
            MiraBranchesManager bm = MiraBranchesManager.getInstance(LoginActivity.this);
            bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
            bm.setUserBranchLocked(true);
        }

        Toast.makeText(this, (isArabic ? "تم تسجيل الدخول (وضع بدون اتصال) 👋 " : "Signed in (Offline Mode) 👋 ") + lastDisplayName, Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::navigateToMain, 500);
    }

private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username, String password) {
    playTone(true);

    String token = response.optString("token", "");
    String roleStr = response.optString("role", "ADMIN");
    String displayName = response.optString("display_name", username);
    int userId = response.optInt("user_id", 0);
    int branchId = response.optInt("branch_id", 1);
    String branchName = response.optString("branch_name", "");
    String branchCode = response.optString("branch_code", "");
    String serverUrl = getText(etServerUrl);
    
    String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    String apiKey = response.optString("api_key", "");
    String gateId = response.optString("gate_id", "");
    
    if (!apiKey.isEmpty()) {
        String effectiveGateId = !gateId.isEmpty() ? gateId : deviceId;
        sessionManager.saveApiKey(apiKey);
        sessionManager.saveGateId(effectiveGateId);
        MiraServerConfig.getInstance(this).saveSession(apiKey, effectiveGateId, token);
    } else {
        if (!sessionManager.hasApiKey()) {
            reconnectAndRestoreData(username, password, serverUrl);
            return;
        }
    }
    
    if (!serverUrl.isEmpty()) {
        MiraServerConfig.getInstance(this).setBaseUrl(serverUrl);
    }
    
    String permissionsJson = response.optString("permissions", "");
    if (permissionsJson.isEmpty()) {
        JSONObject permissionsObj = response.optJSONObject("permissions");
        if (permissionsObj != null) {
            permissionsJson = permissionsObj.toString();
        }
    }
    
    if (!permissionsJson.isEmpty()) {
        PermissionManager pm = PermissionManager.getInstance(this);
        pm.savePermissions(permissionsJson);
    }

    sessionManager.createLoginSession(username, roleStr, token, serverUrl);
    sessionManager.setDisplayName(displayName);
    sessionManager.setUserId(userId);

    sessionManager.saveBiometricUser(username, roleStr, displayName, userId, token);
    sessionManager.saveBiometricBranch(branchId, branchName);
    sessionManager.setBiometricEnabled(true);

    MiraBranchesManager bm = MiraBranchesManager.getInstance(this);
    bm.setActiveBranch(branchId, branchName, branchCode, "", "");
    bm.setUserBranchLocked(true);

    // ✅ انتظر اكتمال الاستعادة ثم انتقل
    restoreAllDataAfterLogin(() -> {
        if (isActivityAlive()) {
            Toast.makeText(this, (isArabic ? "أهلاً بك " : "Welcome ") + displayName + " ✓", 
                Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(this::navigateToMain, 300);
        }
    });
}

    private void reconnectAndRestoreData(String username, String password, String serverUrl) {
    showLoading(true);
    executeSafeBg(() -> {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse reconnectResponse = apiClient.reconnectDevice(username, password, deviceId);
            
            mainHandler.post(() -> {
                if (!isActivityAlive()) return;
                
                if (reconnectResponse != null && reconnectResponse.isSuccess) {
                    String newApiKey = reconnectResponse.optString("api_key", "");
                    if (!newApiKey.isEmpty()) {
                        sessionManager.saveApiKey(newApiKey);
                    }
                    String newServerUrl = reconnectResponse.optString("server_url", serverUrl);
                    if (!newServerUrl.isEmpty()) {
                        sessionManager.setServerUrl(newServerUrl);
                        MiraServerConfig.getInstance(this).setBaseUrl(newServerUrl);
                    }
                    
                    // ✅ انتظر اكتمال الاستعادة ثم انتقل
                    restoreAllDataAfterLogin(() -> {
                        if (isActivityAlive()) {
                            showLoading(false);
                            Toast.makeText(this, "✅ تم استعادة الاتصال", Toast.LENGTH_SHORT).show();
                            mainHandler.postDelayed(this::navigateToMain, 300);
                        }
                    });
                } else {
                    showLoading(false);
                    String error = reconnectResponse != null ? 
                        reconnectResponse.optString("message", "فشل إعادة الاتصال") : "فشل إعادة الاتصال";
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Reconnect error: " + e.getMessage());
            mainHandler.post(() -> {
                if (isActivityAlive()) {
                    showLoading(false);
                    Toast.makeText(this, "فشل إعادة الاتصال", Toast.LENGTH_LONG).show();
                }
            });
        }
    });
}

    
private void restoreAllDataAfterLogin(Runnable onComplete) {
    executeSafeBg(() -> {
        try {
            // ✅ 1. استعادة أسعار الذهب والعملات
            MiraApiClient.ApiResponse goldResponse = apiClient.getGoldCurrencySettings();
            if (goldResponse != null && goldResponse.isSuccess && goldResponse.data != null) {
                MiraPriceManager priceManager = MiraPriceManager.getInstance(this);
                JSONObject data = goldResponse.data.optJSONObject("data");
                if (data == null) data = goldResponse.data;
                JSONObject goldData = data.optJSONObject("gold");
                JSONObject currencyData = data.optJSONObject("currency");
                if (goldData != null) priceManager.saveGoldSettings(goldData);
                if (currencyData != null) priceManager.saveCurrencySettings(currencyData);
            }

            // ✅ 2. استعادة الفروع
            MiraApiClient.ApiResponse branchesResponse = apiClient.getBranches();
            if (branchesResponse != null && branchesResponse.isSuccess && branchesResponse.data != null) {
                MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(this);
                branchesManager.saveBranchesFromServer(branchesResponse.data);
            }

            // ✅ 3. استعادة الملف الشخصي
            MiraApiClient.ApiResponse orgResponse = apiClient.getOrgProfile();
            if (orgResponse != null && orgResponse.isSuccess && orgResponse.data != null) {
                JSONObject org = orgResponse.data.optJSONObject("organization");
                if (org == null) org = orgResponse.data.optJSONObject("data");
                if (org != null && orgProfileManager != null) {
                    orgProfileManager.saveFromServer(org);
                    if (isActivityAlive()) {
                        mainHandler.post(this::applyOrgProfile);
                    }
                }
            }

            Log.d(TAG, "✅ All data restored successfully");
        } catch (Exception e) {
            Log.e(TAG, "Restore data error: " + e.getMessage());
        }

        // ✅ عند الاكتمال (نجاح أو فشل) - انتقل للواجهة الرئيسية
        if (isActivityAlive() && onComplete != null) {
            mainHandler.post(onComplete);
        }
    });
}

    private void setupBiometric() {
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                String lastUser = sessionManager.getLastBiometricUser();
                String lastRole = sessionManager.getLastBiometricRole();
                String lastDisplayName = sessionManager.getLastBiometricDisplayName();
                int lastUserId = sessionManager.getLastBiometricUserId();
                String lastToken = sessionManager.getLastBiometricToken();

                if (!lastUser.isEmpty()) {
                    if (!sessionManager.isLoggedIn()) {
                        sessionManager.createLoginSession(lastUser, lastRole,
                            lastToken.isEmpty() ? "BIOMETRIC_TOKEN" : lastToken,
                            sessionManager.getServerUrl());
                        sessionManager.setDisplayName(lastDisplayName);
                        sessionManager.setUserId(lastUserId);

                        int savedBranchId = sessionManager.getLastBiometricBranchId();
                        String savedBranchName = sessionManager.getLastBiometricBranchName();
                        if (savedBranchId > 0) {
                            MiraBranchesManager bm = MiraBranchesManager.getInstance(LoginActivity.this);
                            bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
                            bm.setUserBranchLocked(true);
                        }
                    }
                    playTone(true);
                    String nameToShow = !lastDisplayName.isEmpty() ? lastDisplayName : lastUser;
                    Toast.makeText(LoginActivity.this,
                        (isArabic ? "أهلاً بعودتك " : "Welcome back ") + nameToShow + " 👋",
                        Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    playTone(false);
                    Toast.makeText(LoginActivity.this,
                        isArabic ? "قم بتسجيل الدخول بكلمة المرور أولاً" : "Sign in with password first",
                        Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    playTone(false);
                    Toast.makeText(LoginActivity.this, "Error: " + errString, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                playTone(false);
                Toast.makeText(LoginActivity.this,
                    isArabic ? "لم يتم التعرف على بصمة الإصبع" : "Fingerprint not recognized",
                    Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(isArabic ? "تسجيل الدخول الحيوي" : "Biometric Sign In")
            .setSubtitle(isArabic ? "استخدم بصمة الإصبع لتسجيل الدخول السريع" : "Use your fingerprint to sign in")
            .setNegativeButtonText(isArabic ? "استخدام كلمة المرور" : "Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        if (btnBiometric != null) {
            btnBiometric.setOnClickListener(v -> {
                if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                    biometricPrompt.authenticate(promptInfo);
                } else {
                    playTone(false);
                    Toast.makeText(this,
                        isArabic ? "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" : "Sign in with password first to enable biometric",
                        Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void showLoading(boolean loading) {
        if (loadingContainer != null) loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) {
            btnLogin.setEnabled(!loading);
            btnLogin.setText(loading ? "" : (isArabic ? "دخول النظام" : "Sign In"));
        }
        if (btnBiometric != null) btnBiometric.setEnabled(!loading);
        if (etUsername != null) etUsername.setEnabled(!loading);
        if (etPassword != null) etPassword.setEnabled(!loading);
    }

    private void shakeView(View view) {
        if (view == null) return;
        ObjectAnimator.ofFloat(view, "translationX", 0, 20, -20, 20, -20, 10, -10, 5, -5, 0).setDuration(500).start();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, UHFMainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("fresh_start", true);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyOrgProfile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (orgProfileManager != null && profileListener != null) {
            orgProfileManager.removeListener(profileListener);
        }
        
        mainHandler.removeCallbacksAndMessages(null);

        if (bgExecutor != null && !bgExecutor.isShutdown()) {
            bgExecutor.shutdownNow();
        }
    }
                                                          }
