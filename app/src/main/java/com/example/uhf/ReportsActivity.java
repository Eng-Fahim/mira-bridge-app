package com.example.uhf;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.adapter.ReportGridAdapter;
import com.example.uhf.model.ReportModels;

import java.util.ArrayList;
import java.util.List;

public class ReportsActivity extends AppCompatActivity {

    private RecyclerView recyclerViewGrid;
    private ReportGridAdapter gridAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reports); // تأكد من اسم ملف الـ Layout الرئيسي للنشاط

        initViews();
        setupReportsGrid();
    }

    private void initViews() {
        recyclerViewGrid = findViewById(R.id.recyclerViewReportsGrid);
        
        // زر العودة إن وجد في الـ Toolbar
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }
    }

    private void setupReportsGrid() {
        // عرض شبكة من عمودين
        recyclerViewGrid.setLayoutManager(new GridLayoutManager(this, 2));

        // تجهيز بيانات القائمة
        List<ReportModels.ReportItem> reportItems = getReportMenuItems();

        // إعداد المحول ومعالجة الضغط على الكروت
        gridAdapter = new ReportGridAdapter(reportItems, item -> {
            onReportCardClicked(item);
        });

        recyclerViewGrid.setAdapter(gridAdapter);
    }

    private List<ReportModels.ReportItem> getReportMenuItems() {
        List<ReportModels.ReportItem> items = new ArrayList<>();

        // إضافة أقسام التقارير مع تحديد الـ Type الخاص بكل منها
        items.add(new ReportModels.ReportItem(null, "لوحة القيادة", "نظرة عامة شاملة", "DASHBOARD"));
        items.add(new ReportModels.ReportItem(null, "المبيعات", "الفواتير والأداء", "SALES"));
        items.add(new ReportModels.ReportItem(null, "المخزون", "كل القطع والذهب", "INVENTORY"));
        items.add(new ReportModels.ReportItem(null, "التحويلات", "حركة الذهب بين الفروع", "MOVEMENT"));
        items.add(new ReportModels.ReportItem(null, "البوابات", "سجلات أمان RFID", "GATES"));

        return items;
    }

    private void onReportCardClicked(ReportModels.ReportItem item) {
        // هنا يمكنك التبديل بين عرض التقارير أو فتح شاشة التقرير المحددة
        Toast.makeText(this, "تم اختيار تقرير: " + item.title, Toast.LENGTH_SHORT).show();
        
        // مستقبلاً: فتح الـ Activity الخاص بالتقرير المباشر
        /*
        Intent intent = new Intent(this, ReportDetailActivity.class);
        intent.putExtra("REPORT_TYPE", item.type);
        startActivity(intent);
        */
    }
}
