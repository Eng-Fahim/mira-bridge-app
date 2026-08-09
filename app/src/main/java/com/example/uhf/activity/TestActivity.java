package com.example.uhf.activity;

import androidx.appcompat.app.AppCompatActivity;
import com.example.uhf.tools.DeviceDetector;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.uhf.R;
import com.example.uhf.tools.CheckUtils;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.NumberTool;
import com.example.uhf.tools.StringUtils;
import com.example.uhf.tools.UIHelper;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.exception.ConfigurationException;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TestActivity extends Activity {
    private static final String TAG = "TestActivity";
    
    MyAdapter adapter;
    TextView tvTime;
    TextView tv_count;
    TextView tv_total;
    TextView tvSpeed;
    TextView tvDeviceInfo;
    long lastTime = 0;
    int lastTags = 0;
    public static ListView LvTags;
    private int total;
    private long time;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    RFIDWithUHFUART rfidWithUHFUART = null;
    
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                UHFTAGInfo info = (UHFTAGInfo) msg.obj;
                addDataToList(info);
                lastTags++;
            } else if (msg.what == 2) {
                if (rfidWithUHFUART != null && rfidWithUHFUART.isInventorying()) {
                    handler.sendEmptyMessageDelayed(2, 10);
                    setTotalTime();

                    if (SystemClock.elapsedRealtime() - lastTime >= 200) {
                        lastTime = SystemClock.elapsedRealtime();
                        tvSpeed.setText((lastTags * 5) + "");
                        lastTags = 0;
                    }
                } else {
                    handler.removeMessages(2);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        
        tvTime = findViewById(R.id.tvTime);
        tvTime.setText("0s");
        tv_count = findViewById(R.id.tv_count);
        tv_total = findViewById(R.id.tv_total);
        tvSpeed = findViewById(R.id.tvSpeed);
        LvTags = findViewById(R.id.LvTags);
        
        // عرض معلومات الجهاز إذا وجد العنصر
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        
        adapter = new MyAdapter(this);
        LvTags.setAdapter(adapter);
        tv_count.setText(tagList.size() + "");
        tv_total.setText(total + "");
        
        try {
            rfidWithUHFUART = RFIDWithUHFUART.getInstance();
        } catch (ConfigurationException e) {
            Log.e(TAG, "getInstance failed", e);
            Toast.makeText(this, "Failed to get UHF instance", Toast.LENGTH_SHORT).show();
        }
        
        initSound();
        
        // تهيئة القارئ مع Profile
        if (rfidWithUHFUART != null) {
            new InitTask().execute();
        }
        
        // عرض معلومات الجهاز
        displayDeviceInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (playSoundThread != null) {
            playSoundThread.stopPlay();
        }
        releaseSoundPool();
    }

private static final int[] SCAN_KEY_CODES = {
    139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
};

private boolean isScanKey(int keyCode) {
    for (int code : SCAN_KEY_CODES) {
        if (code == keyCode) return true;
    }
    return false;
}

@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (isScanKey(keyCode)) {
        if (event.getRepeatCount() == 0) {
            readTag();
        }
        return true;
    }
    return super.onKeyDown(keyCode, event);
}

    private void readTag() {
        if (rfidWithUHFUART == null) {
            Toast.makeText(this, "Reader not initialized", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!rfidWithUHFUART.isInventorying()) {
            clearData();
            rfidWithUHFUART.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo uhftagInfo) {
                    Message msg = handler.obtainMessage();
                    msg.obj = uhftagInfo;
                    msg.what = 1;
                    handler.sendMessage(msg);
                    playSoundThread.play();
                }
            });
            playSoundThread.cleanData();
            
            if (rfidWithUHFUART.startInventoryTag()) {
                time = SystemClock.elapsedRealtime();
                handler.sendEmptyMessageDelayed(2, 10);
                displayDeviceInfo();
            } else {
                UIHelper.ToastMessage(this, R.string.uhf_msg_inventory_open_fail);
            }
        } else {
            rfidWithUHFUART.stopInventory();
            setTotalTime();
        }
    }

    private void displayDeviceInfo() {
        if (tvDeviceInfo != null) {
            String info = DeviceDetector.getDeviceInfo();
            tvDeviceInfo.setText(info);
        }
    }

    private void setTotalTime() {
        float useTime = (SystemClock.elapsedRealtime() - time) / 1000.0F;
        tvTime.setText(NumberTool.getPointDouble(1, useTime) + "s");
    }

    private void addDataToList(UHFTAGInfo info) {
        String epc = info.getEPC();
        if (StringUtils.isNotEmpty(epc)) {
            boolean[] exists = new boolean[1];
            int insertIndex = CheckUtils.getInsertIndex(tagList, info, exists);
            if (exists[0]) {
                info.setCount(tagList.get(insertIndex).getCount() + 1);
                tagList.set(insertIndex, info);
            } else {
                tagList.add(insertIndex, info);
                tv_count.setText(String.valueOf(adapter.getCount()));
            }
            tv_total.setText(String.valueOf(++total));
            adapter.notifyDataSetChanged();
        }
    }

    private void clearData() {
        lastTime = 0;
        lastTags = 0;
        tv_count.setText("0");
        tv_total.setText("0");
        tvTime.setText("0s");
        tvSpeed.setText("0");
        total = 0;
        tagList.clear();
        adapter.notifyDataSetChanged();
    }

    // ==================== Init Task with DeviceDetector ====================

    public class InitTask extends AsyncTask<String, Integer, Boolean> {
        ProgressDialog mypDialog;

        @Override
        protected Boolean doInBackground(String... params) {
            return rfidWithUHFUART.init(TestActivity.this);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            mypDialog.cancel();
            
            if (!result) {
                Toast.makeText(TestActivity.this, 
                    "Init Failed\nDevice: " + DeviceDetector.getDeviceModelName(), 
                    Toast.LENGTH_LONG).show();
            } else {
                // تطبيق Profile الذكي
                DeviceDetector.DeviceProfile profile = DeviceDetector.configure(rfidWithUHFUART);
                
                if (profile != null && profile.ready) {
                    Toast.makeText(TestActivity.this,
                        "✅ Ready: " + profile.modelName + " | " + profile.appliedPower + "dBm",
                        Toast.LENGTH_SHORT).show();
                } else {
                    String error = DeviceDetector.getLastError();
                    Toast.makeText(TestActivity.this,
                        "⚠️ Config issue: " + (error != null ? error : "Unknown"),
                        Toast.LENGTH_LONG).show();
                }
                
                displayDeviceInfo();
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mypDialog = new ProgressDialog(TestActivity.this);
            mypDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            mypDialog.setMessage("Initializing...\n" + DeviceDetector.getDeviceModelName());
            mypDialog.setCanceledOnTouchOutside(false);
            mypDialog.show();
        }
    }

    // ==================== Adapter ====================

    public class MyAdapter extends BaseAdapter {
        public final class ViewHolder {
            public TextView tvEPCTID;
            public TextView tvTagCount;
            public TextView tvTagRssi;
        }

        private LayoutInflater mInflater;

        public MyAdapter(Context context) {
            this.mInflater = LayoutInflater.from(context);
        }

        public int getCount() {
            return tagList.size();
        }

        public Object getItem(int arg0) {
            return tagList.get(arg0);
        }

        public long getItemId(int arg0) {
            return arg0;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                holder = new ViewHolder();
                convertView = mInflater.inflate(R.layout.listtag_items, null);
                holder.tvEPCTID = convertView.findViewById(R.id.TvTagUii);
                holder.tvTagCount = convertView.findViewById(R.id.TvTagCount);
                holder.tvTagRssi = convertView.findViewById(R.id.TvTagRssi);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            UHFTAGInfo uhftagInfo = tagList.get(position);
            String epcAndTidUser = uhftagInfo.getEPC();
            holder.tvEPCTID.setText(epcAndTidUser);
            holder.tvTagCount.setText(uhftagInfo.getCount() + "");
            holder.tvTagRssi.setText(uhftagInfo.getRssi());
            return convertView;
        }
    }

    // ==================== Sound ====================

    private final Object objectLock = new Object();
    PlaySoundThread playSoundThread = null;

    private class PlaySoundThread extends Thread {
        private volatile boolean isStop = false;
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        long count = 0;
        long consumption = 0;

        @Override
        public void run() {
            while (!isStop) {
                if (queue.isEmpty()) {
                    synchronized (objectLock) {
                        try {
                            objectLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (rfidWithUHFUART != null && rfidWithUHFUART.isInventorying()) {
                    playSound(1);
                    queue.poll();
                    consumption++;
                }
                if (count - consumption > 50) {
                    for (int k = 0; k < 25; k++) {
                        queue.poll();
                    }
                    consumption += 25;
                }
            }
        }

        public void play() {
            queue.offer(1);
            synchronized (objectLock) {
                objectLock.notifyAll();
                count++;
            }
        }

        public void cleanData() {
            count = 0;
            consumption = 0;
            queue.clear();
        }

        public void stopPlay() {
            isStop = true;
            count = 0;
            consumption = 0;
            queue.clear();
            synchronized (objectLock) {
                objectLock.notifyAll();
            }
        }
    }

    HashMap<Integer, Integer> soundMap = new HashMap<>();
    private SoundPool soundPool;
    private float volumnRatio;
    private AudioManager am;

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.serror, 1));
        am = (AudioManager) this.getSystemService(AUDIO_SERVICE);

        playSoundThread = new PlaySoundThread();
        playSoundThread.start();
    }

    private void releaseSoundPool() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    public void playSound(int id) {
        if (am == null || soundPool == null) return;
        float audioMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float audioCurrentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumnRatio = audioCurrentVolume / audioMaxVolume;
        try {
            soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1);
        } catch (Exception e) {
            Log.e(TAG, "playSound error", e);
        }
    }
}
