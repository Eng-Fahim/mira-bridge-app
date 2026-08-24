package com.example.uhf.model;

/**
 * نموذج البيانات الخاص بصفوف التقارير المالية لقطاع الذهب والمجوهرات.
 * يدعم المحاسبة المزدوجة (نقدية + أوزان بالعيارات).
 */
public class MiraReportRowItem {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ROW = 1;

    private int viewType;
    private String title;
    private double cashAmount;
    private double goldWeight24k;
    private String karatDetails;

    // مشيد مخصص للصفوف العادية
    public MiraReportRowItem(String title, double cashAmount, double goldWeight24k, String karatDetails) {
        this.viewType = TYPE_ROW;
        this.title = title;
        this.cashAmount = cashAmount;
        this.goldWeight24k = goldWeight24k;
        this.karatDetails = karatDetails != null ? karatDetails : "";
    }

    // مشيد مخصص للترويسات (Header Titles)
    public static MiraReportRowItem createHeader(String title) {
        MiraReportRowItem item = new MiraReportRowItem(title, 0, 0, "");
        item.viewType = TYPE_HEADER;
        return item;
    }

    public int getViewType() {
        return viewType;
    }

    public String getTitle() {
        return title;
    }

    public double getCashAmount() {
        return cashAmount;
    }

    public double getGoldWeight24k() {
        return goldWeight24k;
    }

    public String getKaratDetails() {
        return karatDetails;
    }
}
