package com.example.uhf.model;

public class MiraFiscalYearItem {
    private int id;
    private String yearName;
    private String startDate;
    private String endDate;
    private boolean isClosed;
    private double closingGoldWeight24k;
    private double closingCashBalance;

    public MiraFiscalYearItem() {}

    public MiraFiscalYearItem(int id, String yearName, String startDate, String endDate, boolean isClosed, double closingGoldWeight24k, double closingCashBalance) {
        this.id = id;
        this.yearName = yearName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.isClosed = isClosed;
        this.closingGoldWeight24k = closingGoldWeight24k;
        this.closingCashBalance = closingCashBalance;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getYearName() { return yearName; }
    public void setYearName(String yearName) { this.yearName = yearName; }

    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }

    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }

    public boolean isClosed() { return isClosed; }
    public void setClosed(boolean closed) { isClosed = closed; }

    public double getClosingGoldWeight24k() { return closingGoldWeight24k; }
    public void setClosingGoldWeight24k(double closingGoldWeight24k) { this.closingGoldWeight24k = closingGoldWeight24k; }

    public double getClosingCashBalance() { return closingCashBalance; }
    public void setClosingCashBalance(double closingCashBalance) { this.closingCashBalance = closingCashBalance; }
}
