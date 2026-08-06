package com.example.uhf.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MiraJournalEntry {
    private String referenceNo;
    private String entryDate;
    private String description;
    private int branchId;
    private List<JournalLine> lines = new ArrayList<>();

    public static class JournalLine {
        public String accountCode;
        public String accountName;
        public double debit;
        public double credit;

        public JournalLine(String accountCode, String accountName, double debit, double credit) {
            this.accountCode = accountCode;
            this.accountName = accountName;
            this.debit = debit;
            this.credit = credit;
        }
    }

    public MiraJournalEntry(String referenceNo, String entryDate, String description, int branchId) {
        this.referenceNo = referenceNo;
        this.entryDate = entryDate;
        this.description = description;
        this.branchId = branchId;
    }

    public void addLine(String accountCode, String accountName, double debit, double credit) {
        lines.add(new JournalLine(accountCode, accountName, debit, credit));
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("reference_no", referenceNo);
        obj.put("entry_date", entryDate);
        obj.put("description", description);
        obj.put("branch_id", branchId);

        JSONArray linesArray = new JSONArray();
        for (JournalLine line : lines) {
            JSONObject l = new JSONObject();
            l.put("account_code", line.accountCode);
            l.put("account_name", line.accountName);
            l.put("debit", line.debit);
            l.put("credit", line.credit);
            linesArray.put(l);
        }
        obj.put("lines", linesArray);
        return obj;
    }
}
