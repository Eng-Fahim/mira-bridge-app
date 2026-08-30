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
     * ✅ جلب تقرير - يستخدم getReportData التي كانت تعمل في الكود القديم
     */
    public LiveData<ReportModels.ReportResult> fetchReport(String type, 
                                                           ReportModels.ReportFilters filters) {
        MutableLiveData<ReportModels.ReportResult> resultLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                // ✅ استخدام نفس الدالة التي كانت تعمل في الكود القديم
                MiraApiClient.ApiResponse response = apiClient.getReportData(
                    type, filters.fromDate, filters.toDate, filters.branchId);
                
                if (response == null || !response.isSuccess) {
                    String errorMsg = response != null ? 
                        (response.error != null ? response.error : "Network error") : "Network error";
                    resultLiveData.postValue(ReportModels.ReportResult.error(errorMsg));
                    return;
                }
                
                JSONObject data = response.data;
                
                // إذا البيانات فارغة، حاول من raw body
                if (data == null && response.getBody() != null) {
                    try {
                        String rawBody = response.getBody();
                        if (rawBody.trim().startsWith("[")) {
                            JSONArray rootArray = new JSONArray(rawBody);
                            data = new JSONObject();
                            data.put("items_list", rootArray);
                        } else {
                            data = new JSONObject(rawBody);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parse error: " + e.getMessage());
                    }
                }
                
                if (data == null) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("No data available"));
                    return;
                }
                
                resultLiveData.postValue(ReportModels.ReportResult.success(data));
                
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                resultLiveData.postValue(ReportModels.ReportResult.error(e.getMessage()));
            }
        });
        
        return resultLiveData;
    }
    
    /**
     * ✅ جلب تقرير متقدم - يستخدم /reports-analytics مثل الكود القديم
     */
    public LiveData<ReportModels.ReportResult> fetchAdvancedReport(String type, 
                                                                    ReportModels.ReportFilters filters) {
        MutableLiveData<ReportModels.ReportResult> resultLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                // ✅ بناء الرابط مثل الكود القديم
                StringBuilder endpoint = new StringBuilder("/reports-analytics?type=").append(type);
                
                if (filters.userId > 0) endpoint.append("&user_id=").append(filters.userId);
                if (filters.serialNumber != null && !filters.serialNumber.isEmpty()) 
                    endpoint.append("&serial_number=").append(filters.serialNumber);
                if (filters.gtin13 != null && !filters.gtin13.isEmpty()) 
                    endpoint.append("&gtin13=").append(filters.gtin13);
                if (filters.gateId > 0) endpoint.append("&gate_id=").append(filters.gateId);
                if (filters.fromDate != null && !filters.fromDate.isEmpty()) 
                    endpoint.append("&from=").append(filters.fromDate);
                if (filters.toDate != null && !filters.toDate.isEmpty()) 
                    endpoint.append("&to=").append(filters.toDate);
                if (filters.branchId > 0) 
                    endpoint.append("&branch_id=").append(filters.branchId);
                
                Log.d(TAG, "Advanced Report URL: " + endpoint);
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());
                
                if (response == null || !response.isSuccess) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("Failed to load"));
                    return;
                }
                
                JSONObject data = response.data;
                if (data == null) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("No data available"));
                    return;
                }
                
                resultLiveData.postValue(ReportModels.ReportResult.success(data));
                
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
                Log.e(TAG, "Error: " + e.getMessage(), e);
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
                
                // ✅ نفس الرابط الذي كان يعمل في الكود القديم
                String endpoint = "/gates?branch_id=" + branchId;
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);
                
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
                Log.e(TAG, "Error: " + e.getMessage(), e);
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
