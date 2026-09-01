package com.example.uhf.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.uhf.R;
// ❌ حذف استيراد xUtils
// import com.lidroid.xutils.util.LogUtils;
import com.rscja.deviceapi.entity.RadarLocationEntity;

import java.util.List;

/**
 * 雷达扫描组件
 * 宽高始终相等且取宽高中的最小值
 */
public class RadarView extends ConstraintLayout {

    private static final String TAG = "RadarView"; // ✅ إضافة TAG للتصحيح

    private RadarBackgroundView radarBackgroundView;
    private RadarPanelView radarPanelView;
    
    // ✅ داخل الكلاس
    private int radarBorderColor = 0xFF38BDF8;

    public final static int CLOCK_WISE = 1;
    public final static int ANTI_CLOCK_WISE = -1;

    public RadarView(Context context) {
        super(context);
    }

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.rardar_view, this, true);

        radarBackgroundView = findViewById(R.id._radarBackgroundView);
        radarPanelView = findViewById(R.id._labelPanelView);
    }

    // ✅ داخل الكلاس
    public void setBorderColor(int color) {
        this.radarBorderColor = color;
        invalidate();
        Log.d(TAG, "setBorderColor: " + color); // ✅ استخدام Log بدلاً من LogUtils
    }

    // ✅ داخل الكلاس
    public int getBorderColor() {
        return radarBorderColor;
    }

    public void setDirection(@RadarBackgroundView.RADAR_DIRECTION int direction) {
        if (direction != CLOCK_WISE && direction != ANTI_CLOCK_WISE) {
            throw new IllegalArgumentException("Use @RADAR_DIRECTION constants only!");
        }
        this.radarBackgroundView.setDirection(direction);
        Log.d(TAG, "setDirection: " + direction); // ✅ استخدام Log
    }

    public void setStartAngle(RadarBackgroundView.StartAngle startAngle) {
        this.radarBackgroundView.setStartAngle(startAngle);
        this.radarBackgroundView.invalidate();
        Log.d(TAG, "setStartAngle: " + startAngle); // ✅ استخدام Log
    }

    public void startRadar() {
        this.radarBackgroundView.start();
        Log.d(TAG, "startRadar"); // ✅ استخدام Log
    }

    public void stopRadar() {
        this.radarBackgroundView.stop();
        Log.d(TAG, "stopRadar"); // ✅ استخدام Log
    }

    public void bindingData(List<RadarLocationEntity> TagList, String targetTag) {
        this.radarPanelView.bindingData(TagList, targetTag);
        Log.d(TAG, "bindingData: " + (TagList != null ? TagList.size() : 0) + " items"); // ✅ استخدام Log
    }

    public void clearPanel() {
        this.radarPanelView.clearPanel();
        Log.d(TAG, "clearPanel"); // ✅ استخدام Log
    }

    public void setRotation(int angle) {
        this.radarBackgroundView.setRotation(angle);
        this.radarPanelView.setRotation(angle);
        Log.d(TAG, "setRotation: " + angle); // ✅ استخدام Log
    }
}
