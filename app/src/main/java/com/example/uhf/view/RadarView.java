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
import com.lidroid.xutils.util.LogUtils;
import com.rscja.deviceapi.entity.RadarLocationEntity;

import java.util.List;

/**
 * 雷达扫描组件
 * 宽高始终相等且取宽高中的最小值
 */
public class RadarView extends ConstraintLayout {

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
    }

    public void setStartAngle(RadarBackgroundView.StartAngle startAngle) {
        this.radarBackgroundView.setStartAngle(startAngle);
        this.radarBackgroundView.invalidate();
    }

    public void startRadar() {
        this.radarBackgroundView.start();
    }

    public void stopRadar() {
        this.radarBackgroundView.stop();
    }

    public void bindingData(List<RadarLocationEntity> TagList, String targetTag) {
        this.radarPanelView.bindingData(TagList, targetTag);
    }

    public void clearPanel() {
        this.radarPanelView.clearPanel();
    }

    public void setRotation(int angle) {
        this.radarBackgroundView.setRotation(angle);
        this.radarPanelView.setRotation(angle);
    }
}
