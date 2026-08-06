package com.example.uhf.model;

public class MiraAccountingItem {

    // أنواع العناصر للتمييز بين التبويبات
    public enum ItemType {
        JOURNAL_ENTRY,     // قيد يومية
        CHART_OF_ACCOUNT,  // حساب في دليل الحسابات
        TRIAL_BALANCE      // سطر ميزان مراجعة
    }

    private ItemType itemType;
    private String col1Text; // رقم القيد / رمز الحساب
    private String col2Text; // البيان / اسم الحساب
    private String col3Text; // مدين / النوع / إجمالي مدين
    private String col4Text; // دائن / الرصيد / إجمالي دائن
    private String dateOrDetails; // اختياري: التاريخ أو تفاصيل إضافية

    // Constructor للقيود والحسابات
    public MiraAccountingItem(ItemType itemType, String col1Text, String col2Text, String col3Text, String col4Text) {
        this.itemType = itemType;
        this.col1Text = col1Text;
        this.col2Text = col2Text;
        this.col3Text = col3Text;
        this.col4Text = col4Text;
    }

    public MiraAccountingItem(ItemType itemType, String col1Text, String col2Text, String col3Text, String col4Text, String dateOrDetails) {
        this(itemType, col1Text, col2Text, col3Text, col4Text);
        this.dateOrDetails = dateOrDetails;
    }

    // Getters and Setters
    public ItemType getItemType() { return itemType; }
    public String getCol1Text() { return col1Text; }
    public String getCol2Text() { return col2Text; }
    public String getCol3Text() { return col3Text; }
    public String getCol4Text() { return col4Text; }
    public String getDateOrDetails() { return dateOrDetails; }
}
