package com.example.uhf.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.fragment.KeyDwonFragment;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.UIHelper;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.text.SimpleDateFormat;
import java.util.Date;

public class BaseTabFragmentActivity extends AppCompatActivity {

    public RFIDWithUHFUART mReader;
    public KeyDwonFragment currentFragment = null;
    public int TidLen = 6;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private boolean isInitialized = false;

    private static final int[] SCAN_KEY_CODES = {
        139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        }
    }

    public void initUHF() {
        try {
            mReader = RFIDWithUHFUART.getInstance();
        } catch (Exception ex) {
            toastMessage("Failed to get UHF instance: " + ex.getMessage());
            Log.e("UHF", "getInstance failed", ex);
            return;
        }
        if (mReader != null) {
            new InitTask().execute();
        }
    }

    public void configureReaderForDevice() {
        if (mReader == null || !isInitialized) {
            Log.w("UHF", "Cannot configure - reader not initialized");
            return;
        }

        try {
            String model = Build.MODEL;
            DeviceDetector.DeviceModel deviceModel = DeviceDetector.getDeviceModel();
            
            Log.i("UHF", "===================================");
            Log.i("UHF", "Device Model: " + model);
            Log.i("UHF", "Detected as: " + deviceModel.name());
            
            // استخدام DeviceDetector.configure
            DeviceDetector.DeviceProfile profile = DeviceDetector.configure(mReader);
            
            if (profile != null && profile.ready) {
                Log.i("UHF", "Power set to: " + profile.appliedPower + " dBm (OK)");
                Log.i("UHF", "Frequency Range: " + profile.frequencyRange);
            } else {
                Log.w("UHF", "Profile not ready: " + DeviceDetector.getLastError());
            }
            
            Log.i("UHF", "===================================");
            
        } catch (Exception e) {
            Log.e("UHF", "Configuration error", e);
        }
    }

    public boolean isReaderReady() {
        return mReader != null && isInitialized;
    }

    private boolean isScanKey(int keyCode) {
        for (int code : SCAN_KEY_CODES) {
            if (code == keyCode) return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        if (BuildConfig.Test) {
            menu.findItem(R.id.speed).setVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) return false;
        if (id == R.id.UHF_ver) { getUHFVersion(); return true; }
        if (id == R.id.export) { exportData(); return true; }
        if (id == R.id.speed) {
            startActivity(new Intent(this, TestActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void exportData() {
        Toast.makeText(this, "Export", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isScanKey(keyCode)) {
            if (event.getRepeatCount() == 0 && currentFragment != null) {
                currentFragment.myOnKeyDwon();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void toastMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public boolean vailHexInput(String str) {
        if (str == null || str.length() == 0) return false;
        if (str.length() % 2 == 0) return StringUtility.isHexNumberRex(str);
        return false;
    }

    public void getUHFVersion() {
        if (mReader != null) {
            String rfidVer = mReader.getVersion();
            String hardwareVersion = mReader.getHardwareVersion();
            String deviceInfo = DeviceDetector.getDeviceInfo();
            String version = "=== Device ===\n" + deviceInfo + "\n=== UHF ===\n" +
                           "Software: " + rfidVer + "\nHardware: " + hardwareVersion;
            UIHelper.alert(this, R.string.action_uhf_ver, version, R.drawable.webtext);
        }
    }

    public String getVerName() {
        try {
            return this.getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Log.e("TEST", grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ? "SD Card permission granted" : "SD Card permission denied");
        }
    }

    public class InitTask extends AsyncTask<String, Integer, Boolean> {
        ProgressDialog mypDialog;

        @Override
        protected Boolean doInBackground(String... params) {
            return mReader.init(BaseTabFragmentActivity.this);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            mypDialog.cancel();
            
            if (!result) {
                isInitialized = false;
                DeviceDetector.reset();
                Toast.makeText(BaseTabFragmentActivity.this,
                    "UHF Init Failed\nDevice: " + Build.MODEL, Toast.LENGTH_LONG).show();
            } else {
                isInitialized = true;
                configureReaderForDevice();
                Log.i("UHF", "UHF initialized successfully");
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mypDialog = new ProgressDialog(BaseTabFragmentActivity.this);
            mypDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            mypDialog.setMessage("Initializing UHF...");
            mypDialog.setCanceledOnTouchOutside(false);
            mypDialog.show();
        }
    }
