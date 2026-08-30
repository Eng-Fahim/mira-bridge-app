package com.example.uhf.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.ReportModels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ مستودع التقارير - يستخدم الدوال الصحيحة من MiraApiClient
 */
public class ReportRepository {
    private static final String TAG = "ReportRepository";
    private static ReportRepository instance;
    
    private final Context context;
    private final MiraApiClient apiClient;
    private final ExecutorService executorService;
    
    private ReportRepository(Context context) {
        this.context = context.getApplicationContext();
        this.apiClient = MiraApiClient.getInstance(context);
        this.executorService = Executors.newFixedThreadPool(3);
    }
    
    public static synchronized ReportRepository getInstance(Context context) {
        if (instance == null) {
            instance = new ReportRepository(context);
        }
        return instance;
    }
    
    /**
     * ✅ جلب تقرير باستخدام الدوال الصحيحة من MiraApiClient
     */
    public LiveData<ReportModels.ReportResult> fetchReport(String type, 
                                                           ReportModels.ReportFilters filters) {
        MutableLiveData<ReportModels.ReportResult> resultLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = null;
                
                // ✅ استخدام الدوال الصحيحة لكل نوع تقرير
                switch (type) {
                    case "dashboard":
                        response = apiClient.getDashboardFull(filters.branchId);
                        break;
                        
                    case "sales":
                        response = apiClient.getSalesHistoryByDateRange(
                            filters.fromDate, filters.toDate, 1, 100);
                        break;
                        
                    case "inventory":
                        response = apiClient.getInventorySessionsList();
                        break;
                        
                    case "movement":
                        response = apiClient.getTransfers(filters.branchId, "");
                        break;
                        
                    case "branches":
                        response = apiClient.getBranches();
                        break;
                        
                    case "gates":
                        response = apiClient.getGatesByBranch(filters.branchId);
                        break;
                        
                    case "audit":
                        response = apiClient.getInventoryReports(filters.branchId, 1, 100);
                        break;
                        
                    case "users":
                        response = apiClient.getUsers();
                        break;
                        
                    case "financial":
                        response = apiClient.getJournalEntries(String.valueOf(filters.branchId));
                        break;
                        
                    case "reorder":
                        response = apiClient.getReportData("reorder", 
                            filters.fromDate, filters.toDate, filters.branchId);
                        break;
                        
                    default:
                        // ✅ للتقارير غير المعروفة - استخدم getReportData
                        response = apiClient.getReportData(type, 
                            filters.fromDate, filters.toDate, filters.branchId);
                        break;
                }
                
                if (response == null || !response.isSuccess || response.data == null) {
                    String errorMsg = response != null ? 
                        (response.error != null ? response.error : "Network error") : "Network error";
                    resultLiveData.postValue(ReportModels.ReportResult.error(errorMsg));
                    return;
                }
                
                resultLiveData.postValue(ReportModels.ReportResult.success(response.data));
                
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                resultLiveData.postValue(ReportModels.ReportResult.error(e.getMessage()));
            }
        });
        
        return resultLiveData;
    }
    
    /**
     * ✅ جلب تقرير متقدم
     */
    public LiveData<ReportModels.ReportResult> fetchAdvancedReport(String type, 
                                                                    ReportModels.ReportFilters filters) {
        MutableLiveData<ReportModels.ReportResult> resultLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = null;
                
                switch (type) {
                    case "user_timeline":
                        response = apiClient.getUserActivityLogs(filters.userId, 100);
                        break;
                        
                    case "item_passport":
                        response = apiClient.getItemDetail(filters.serialNumber);
                        break;
                        
                    case "gate_security_index":
                        response = apiClient.getGatesStats();
                        break;
                        
                    case "inventory_aging":
                        response = apiClient.getReportData("inventory_aging", 
                            filters.fromDate, filters.toDate, filters.branchId);
                        break;
                        
                    case "audit_discrepancy_detail":
                        response = apiClient.getInventoryReports(filters.branchId, 1, 100);
                        break;
                        
                    default:
                        response = apiClient.getReportData(type, 
                            filters.fromDate, filters.toDate, filters.branchId);
                        break;
                }
                
                if (response == null || !response.isSuccess || response.data == null) {
                    String errorMsg = response != null ? 
                        (response.error != null ? response.error : "Failed to load") : "Failed to load";
                    resultLiveData.postValue(ReportModels.ReportResult.error(errorMsg));
                    return;
                }
                
                resultLiveData.postValue(ReportModels.ReportResult.success(response.data));
                
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                resultLiveData.postValue(ReportModels.ReportResult.error(e.getMessage()));
            }
        });
        
        return resultLiveData;
    }
    
    /**
     * ✅ جلب قائمة المستخدمين
     */
    public LiveData<List<ReportModels.UserInfo>> fetchUsers() {
        MutableLiveData<List<ReportModels.UserInfo>> usersLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                List<ReportModels.UserInfo> users = new ArrayList<>();
                
                MiraApiClient.ApiResponse response = apiClient.getUsers();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray usersArray = response.data.optJSONArray("users");
                    if (usersArray == null) {
                        usersArray = response.data.optJSONArray("data");
                    }
                    
                    if (usersArray != null) {
                        for (int i = 0; i < usersArray.length(); i++) {
                            JSONObject userObj = usersArray.optJSONObject(i);
                            if (userObj != null) {
                                int id = userObj.optInt("id");
                                String name = userObj.optString("display_name", 
                                    userObj.optString("name", 
                                        userObj.optString("username", "User " + id)));
                                String username = userObj.optString("username", "");
                                String role = userObj.optString("role_name", 
                                    userObj.optString("role", ""));
                                users.add(new ReportModels.UserInfo(id, name, username, role));
                            }
                        }
                    }
                }
                
                usersLiveData.postValue(users);
                
            } catch (Exception e) {
                Log.e(TAG, "Error fetching users: " + e.getMessage(), e);
                usersLiveData.postValue(new ArrayList<>());
            }
        });
        
        return usersLiveData;
    }
    
    /**
     * ✅ جلب قائمة البوابات
     */
    public LiveData<List<ReportModels.GateInfo>> fetchGates(int branchId) {
        MutableLiveData<List<ReportModels.GateInfo>> gatesLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                List<ReportModels.GateInfo> gates = new ArrayList<>();
                
                MiraApiClient.ApiResponse response = apiClient.getGatesByBranch(branchId);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = response.data.optJSONArray("gates");
                    if (gatesArray == null) {
                        gatesArray = response.data.optJSONArray("data");
                    }
                    
                    if (gatesArray != null) {
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject gateObj = gatesArray.optJSONObject(i);
                            if (gateObj != null) {
                                int id = gateObj.optInt("id", 0);
                                String gateId = gateObj.optString("gate_id", "");
                                String name = gateObj.optString("gate_name", 
                                    gateObj.optString("name", "Gate " + (i + 1)));
                                String location = gateObj.optString("gate_location", 
                                    gateObj.optString("location", ""));
                                gates.add(new ReportModels.GateInfo(id, gateId, name, location));
                            }
                        }
                    }
                }
                
                gatesLiveData.postValue(gates);
                
            } catch (Exception e) {
                Log.e(TAG, "Error fetching gates: " + e.getMessage(), e);
                gatesLiveData.postValue(new ArrayList<>());
            }
        });
        
        return gatesLiveData;
    }
    
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
