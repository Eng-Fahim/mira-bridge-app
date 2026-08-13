package com.example.uhf.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.uhf.model.MiraAccountingItem;
import com.example.uhf.model.MiraAccountingItem.ItemType;

import java.util.ArrayList;
import java.util.List;

public class MiraAccountingViewModel extends ViewModel {

    private final MutableLiveData<List<MiraAccountingItem>> accountingItemsLiveData = new MutableLiveData<>(new ArrayList<>());

    public LiveData<List<MiraAccountingItem>> getAccountingItemsLiveData() {
        return accountingItemsLiveData;
    }

    public void fetchAccountingData(ItemType currentTab) {
        List<MiraAccountingItem> items = new ArrayList<>();

        // بيانات تجريبية سريعة لمنع أي خطأ أثنـاء البناء
        MiraAccountingItem item1 = new MiraAccountingItem(
                currentTab != null ? currentTab : ItemType.JOURNAL_ENTRY,
                "101",
                "صندوق الذهب والنقدية",
                50.0,
                0.0,
                1500000.0,
                0.0,
                "YER",
                "2026-08-14"
        );
        item1.setRfidTag("E2801170000002011A8F9001");
        item1.setMakingFee(85000.0);
        items.add(item1);

        // إجمالي المبالغ والذهب لحساب النسبة
        double totalCash = 0;
        double totalGold24 = 0;
        for (MiraAccountingItem item : items) {
            totalCash += item.getCashBalance();
            totalGold24 += item.getFineGoldBalance();
        }

        double fine24_2 = MiraAccountingItem.convertToFineGold24(40.0, 18);

        accountingItemsLiveData.setValue(items);
    }
}
