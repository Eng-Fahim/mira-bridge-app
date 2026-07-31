package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraDashboardFragment extends KeyDwonFragment {

    private UHFMainActivity mContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    // 1. ربط العناصر الفعالة من ملف XML
    private TextView tvTime, tvBatchTotal, tvBatchAllowed, tvBatchAllowedPct;
    private TextView tvBatchBlocked, tvBatchBlockedPct, tvBatchUnknown, tvBatchUnknownPct;
    private TextView tvCount, tvTotal;
    private EditText etGtinInput, etTime;
    private Button btnCheckGtin, btInventory, btClear;
    private ListView lvTags;
    private RadioGroup rgInventory;
    private RadioButton rbInventorySingle, rbInventoryLoop;
    private CheckBox cbFilter;
    private View layoutFilter;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) context;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
    }

    @Override
    public void onViewCreated(View v, Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        // 2. التعريف بالعناصر المعتمدة داخل fragment_mira_dashboard.xml
        tvTime = v.findViewById(R.id.tvTime);
        tvBatchTotal = v.findViewById(R.id.tvBatchTotal);
        tvBatchAllowed = v.findViewById(R.id.tvBatchAllowed);
        tvBatchAllowedPct = v.findViewById(R.id.tvBatchAllowedPct);
        tvBatchBlocked = v.findViewById(R.id.tvBatchBlocked);
        tvBatchBlockedPct = v.findViewById(R.id.tvBatchBlockedPct);
        tvBatchUnknown = v.findViewById(R.id.tvBatchUnknown);
        tvBatchUnknownPct = v.findViewById(R.id.tvBatchUnknownPct);
        
        tvCount = v.findViewById(R.id.tv_count);
        tvTotal = v.findViewById(R.id.tv_total);
        lvTags = v.findViewById(R.id.LvTags);

        etGtinInput = v.findViewById(R.id.etGtinInput);
        btnCheckGtin = v.findViewById(R.id.btnCheckGtin);
        btInventory = v.findViewById(R.id.BtInventory);
        btClear = v.findViewById(R.id.BtClear);
        
        cbFilter = v.findViewById(R.id.cbFilter);
        layoutFilter = v.findViewById(R.id.layout_filter);

        // إظهار وإخفاء قسم الفلترة
        if (cbFilter != null && layoutFilter != null) {
            cbFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
                layoutFilter.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            });
        }

        // أزرار الأحداث
        if (btnCheckGtin != null) {
            btnCheckGtin.setOnClickListener(v2 -> {
                // إضافة وظيفة فحص GTIN هنا
            });
        }

        updateAll();

        // تحديث الوقت والقراءات دورياً
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && getActivity() != null) {
                    updateAll();
                    handler.postDelayed(this, 1000); // تحديث الوقت كل ثانية
                }
            }
        };
        handler.postDelayed(updateRunnable, 1000);
    }

    private void updateAll() {
        if (!isAdded()) return;

        // تحديث شريط الوقت
        if (tvTime != null) {
            SimpleDateFormat tf = new SimpleDateFormat("hh:mm:ss a", new Locale("ar"));
            tvTime.setText(tf.format(new Date()));
        }
    }

    public void updateMetrics(int total, int allowed, int blocked, int unknown) {
        if (!isAdded()) return;

        if (tvBatchTotal != null) tvBatchTotal.setText(String.valueOf(total));
        if (tvBatchAllowed != null) tvBatchAllowed.setText(String.valueOf(allowed));
        if (tvBatchBlocked != null) tvBatchBlocked.setText(String.valueOf(blocked));
        if (tvBatchUnknown != null) tvBatchUnknown.setText(String.valueOf(unknown));

        if (total > 0) {
            int allowedPct = (allowed * 100) / total;
            int blockedPct = (blocked * 100) / total;
            int unknownPct = (unknown * 100) / total;

            if (tvBatchAllowedPct != null) tvBatchAllowedPct.setText(allowedPct + "%");
            if (tvBatchBlockedPct != null) tvBatchBlockedPct.setText(blockedPct + "%");
            if (tvBatchUnknownPct != null) tvBatchUnknownPct.setText(unknownPct + "%");
        }
    }

    @Override
    public void myOnKeyDwon() {}

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mContext = null;
    }
}
