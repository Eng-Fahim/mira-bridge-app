package com.example.uhf.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.fragment.KeyDwonFragment;
import com.example.uhf.tools.UIHelper;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.text.SimpleDateFormat;
import java.util.Date;

public class BaseTabFragmentActivity extends FragmentActivity {

    public RFIDWithUHFUART mReader;
    public KeyDwonFragment currentFragment = null;
    public int TidLen = 6;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // التحقق من صلاحيات التخزين لقراءة وتصدير الجداول
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
            toastMessage(ex.getMessage());
            return;
        }

        if (mReader != null) {
            new InitTask().execute();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        // 🔒 استخدام قائمة MIRA المحدثة التي تحتوي على خيار تسجيل الخروج
        try {
            inflater.inflate(R.menu.main_menu, menu);
        } catch (Exception e) {
            inflater.inflate(R.menu.main, menu);
        }
        
        if (BuildConfig.DEBUG && menu.findItem(R.id.speed) != null) {
            menu.findItem(R.id.speed).setVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == android.R.id.home) {
            return true;
        } else if (id == R.id.UHF_ver) {
            getUHFVersion();
            return true;
        } else if (id == R.id.export) {
            exportData();
            return true;
        } else if (id == R.id.action_logout) {
            // تسجيل الخروج في حال تم ضغطه من القائمة
            if (this instanceof UHFMainActivity) {
                ((UHFMainActivity) this).performLogout();
            }
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    public void exportData() {
        Date currentDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String currentTime = dateFormat.format(currentDate);
        String file = "sdcard/UHF_exportData/";
        String fileName = file + currentTime;
        Toast.makeText(BaseTabFragmentActivity.this, "بدء تصدير البيانات...", Toast.LENGTH_SHORT).show();
    }

    // ⚡ التقاط ضغطة الزر الفيزيائي للقارئ اليدوي (Handheld Trigger)
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == 139
                || keyCode == 280
                || keyCode == 291
                || keyCode == 293
                || keyCode == 294
                || keyCode == 311
                || keyCode == 312
                || keyCode == 313
                || keyCode == 315
                || keyCode == 591
                || keyCode == 593
                || keyCode == 594
                || keyCode == 596
        ) {
            if (event.getRepeatCount() == 0) {
                if (currentFragment != null) {
                    currentFragment.myOnKeyDwon();
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void toastMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // تهيئة عتاد الـ RFID في الخلفية
    public class InitTask extends AsyncTask<String, Integer, Boolean> {
        ProgressDialog mypDialog;

        @Override
        protected Boolean doInBackground(String... params) {
            return mReader.init(BaseTabFragmentActivity.this);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (mypDialog != null && mypDialog.isShowing()) {
                mypDialog.dismiss();
            }
            if (!result) {
                Toast.makeText(BaseTabFragmentActivity.this, "فشل تهيئة قارئ RFID اليدوي", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mypDialog = new ProgressDialog(BaseTabFragmentActivity.this);
            mypDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            mypDialog.setMessage("جاري تشغيل محرك MIRA RFID...");
            mypDialog.setCanceledOnTouchOutside(false);
            mypDialog.show();
        }
    }

    public boolean vailHexInput(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        if (str.length() % 2 == 0) {
            return StringUtility.isHexNumberRex(str);
        }
        return false;
    }

    public void getUHFVersion() {
        if (mReader != null) {
            String rfidVer = mReader.getVersion();
            String hardwareVersion = mReader.getHardwareVersion();
            String version = "إصدار النظام: " + rfidVer + " \nإصدار العتاد: " + hardwareVersion;

            UIHelper.alert(this, R.string.action_uhf_ver,
                    version, R.drawable.webtext);
        }
    }

    public String getVerName() {
        try {
            return this.getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {

        }
        return "";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.e("TEST", "تم الحصول على صلاحيات التخزين بنجاح");
            } else {
                Log.e("TEST", "تم رفض صلاحيات التخزين");
            }
        }
    }
}
