package com.example.uhf.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.example.uhf.models.ReportModels;
import com.example.uhf.repository.ReportRepository;

import java.util.List;

public class ReportsViewModel extends AndroidViewModel {

    private final ReportRepository repository;
    private final MutableLiveData<ReportModels.ReportFilters> activeFilters = new MutableLiveData<>(new ReportModels.ReportFilters());
    private final MutableLiveData<String> selectedReportType = new MutableLiveData<>("GENERAL");

    public ReportsViewModel(@NonNull Application application) {
        super(application);
        this.repository = ReportRepository.getInstance(application);
    }

    public void setReportType(String type) {
        selectedReportType.setValue(type);
    }

    public void updateFilters(ReportModels.ReportFilters filters) {
        activeFilters.setValue(filters);
    }

    public LiveData<ReportModels.ReportFilters> getActiveFilters() {
        return activeFilters;
    }

    // جلب التقرير العادي بناءً على النوع والفلتر الحالي
    public LiveData<ReportModels.ReportResult> getReportData() {
        return Transformations.switchMap(activeFilters, filters -> 
            repository.fetchReport(selectedReportType.getValue(), filters)
        );
    }

    // جلب قائمة المستخدمين لملء Spinners في الفلتر
    public LiveData<List<ReportModels.UserInfo>> getUsers() {
        return repository.fetchUsers();
    }

    // جلب البوابات للفرع المادي
    public LiveData<List<ReportModels.GateInfo>> getGates(int branchId) {
        return repository.fetchGates(branchId);
    }
}
