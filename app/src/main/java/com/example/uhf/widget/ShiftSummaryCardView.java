package com.example.uhf.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;

import java.util.Locale;

/**
 * مكوّن مستقل لعرض ملخص وردية موظف المبيعات.
 * قابل للاستخدام في أي شاشة.
 */
public class ShiftSummaryCardView extends FrameLayout {

    private TextView tvTotalCash, tvInvoiceCount, tvReturns, tvGoldWeight;
    private ImageButton btnToggleVisibility;

    private boolean isHidden = true;
    private double totalCash = 0;
    private int invoiceCount = 0;
    private int returns = 0;
    private double goldWeight = 0;

    public ShiftSummaryCardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ShiftSummaryCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_shift_summary_card, this, true);

        tvTotalCash    = findViewById(R.id.tvTotalCash);
        tvInvoiceCount = findViewById(R.id.tvInvoiceCount);
        tvReturns      = findViewById(R.id.tvReturns);
        tvGoldWeight   = findViewById(R.id.tvGoldWeight);
        btnToggleVisibility = findViewById(R.id.btnToggleVisibility);

        btnToggleVisibility.setOnClickListener(v -> toggleVisibility());
    }

    /**
     * تحديث بيانات البطاقة.
     * @param totalCash إجمالي المبيعات النقدية
     * @param invoiceCount عدد الفواتير
     * @param returns عدد المرتجعات
     * @param goldWeight إجمالي وزن الذهب المُباع
     */
    public void bindData(double totalCash, int invoiceCount, int returns, double goldWeight) {
        this.totalCash = totalCash;
        this.invoiceCount = invoiceCount;
        this.returns = returns;
        this.goldWeight = goldWeight;
        render();
    }

    private void toggleVisibility() {
        isHidden = !isHidden;
        btnToggleVisibility.setImageResource(isHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        render();
    }

    private void render() {
        if (isHidden) {
            tvTotalCash.setText("••••••");
            tvInvoiceCount.setText("••");
            tvReturns.setText("••");
            tvGoldWeight.setText("•• g");
        } else {
            tvTotalCash.setText(String.format(Locale.US, "$%,.2f", totalCash));
            tvInvoiceCount.setText(String.valueOf(invoiceCount));
            tvReturns.setText(String.valueOf(returns));
            tvGoldWeight.setText(String.format(Locale.US, "%.2f g", goldWeight));
        }
    }

    /** إخفاء القيم الحساسة تلقائياً (عند الخروج من الشاشة) */
    public void hideSensitiveValues() {
        if (!isHidden) {
            isHidden = true;
            btnToggleVisibility.setImageResource(R.drawable.ic_eye_off);
            render();
        }
    }
}
