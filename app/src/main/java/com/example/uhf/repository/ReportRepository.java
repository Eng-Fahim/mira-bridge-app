package com.example.uhf.repository;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.cache.ReportCacheManager;
import com.example.uhf.models.ReportModels;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;  // ✅ إضافة هذا
import java.util.List;       // ✅ إضافة هذا
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ مستودع التقارير - يتعامل مع جلب وتخزين البيانات
 */
public class ReportRepository {
    private static final String TAG = "ReportRepository";
    private static ReportRepository instance;
    
    private final Context context;
    private final MiraApiClient apiClient;
    private final ReportCacheManager cacheManager;
    private final ExecutorService executorService;
    
    private ReportRepository(Context context) {
        this.context = context.getApplicationContext();
        this.apiClient = MiraApiClient.getInstance(context);
        this.cacheManager = new ReportCacheManager(context);
        this.executorService = Executors.newFixedThreadPool(3);
    }
    
    public static synchronized ReportRepository getInstance(Context context) {
        if (instance == null) {
            instance = new ReportRepository(context);
        }
        return instance;
    }
    
    /**
     * ✅ جلب تقرير مع دعم التخزين المؤقت
     */
    public LiveData<ReportModels.ReportResult> fetchReport(String type, 
                                                           ReportModels.ReportFilters filters) {
        MutableLiveData<ReportModels.ReportResult> resultLiveData = new MutableLiveData<>();
        
        executorService.execute(() -> {
            try {
                // 1. إنشاء مفتاح الكاش
                String cacheKey = generateCacheKey(type, filters);
                
                // 2. محاولة التحميل من الكاش
                ReportModels.ReportResult cachedResult = cacheManager.getCachedReport(cacheKey);
                if (cachedResult != null) {
                    resultLiveData.postValue(cachedResult);
                    
                    // إذا كان الكاش حديث (أقل من 5 دقائق)، لا نكمل
                    if (System.currentTimeMillis() - cachedResult.timestamp < 5 * 60 * 1000) {
                        return;
                    }
                }
                
                // 3. بناء الرابط
                String endpoint = buildEndpoint(type, filters);
                Log.d(TAG, "Fetching report: " + endpoint);
                
                // 4. جلب البيانات من الخادم
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);
                
                if (response == null || !response.isSuccess) {
                    String errorMsg = response != null ? response.message : "Network error";
                    resultLiveData.postValue(ReportModels.ReportResult.error(errorMsg));
                    return;
                }
                
                // 5. تحليل البيانات
                JSONObject data = parseResponseData(response);
                if (data == null) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("No data available"));
                    return;
                }
                
                // 6. حفظ في الكاش
                ReportModels.ReportResult result = ReportModels.ReportResult.success(data);
                cacheManager.cacheReport(cacheKey, data);
                
                // 7. إرسال النتيجة
                resultLiveData.postValue(result);
                
            } catch (Exception e) {
                Log.e(TAG, "Error fetching report: " + e.getMessage(), e);
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
                String cacheKey = "advanced_" + generateCacheKey(type, filters);
                
                // محاولة الكاش
                ReportModels.ReportResult cached = cacheManager.getCachedReport(cacheKey);
                if (cached != null) {
                    resultLiveData.postValue(cached);
                    return;
                }
                
                // بناء الرابط
                StringBuilder endpoint = new StringBuilder("/reports-analytics?type=").append(type);
                String queryString = filters.toQueryString();
                if (!queryString.isEmpty()) {
                    endpoint.append("&").append(queryString);
                }
                
                Log.d(TAG, "Fetching advanced report: " + endpoint);
                
                // جلب البيانات
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());
                
                if (response == null || !response.isSuccess) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("Failed to load"));
                    return;
                }
                
                JSONObject data = parseResponseData(response);
                if (data == null) {
                    resultLiveData.postValue(ReportModels.ReportResult.error("No data available"));
                    return;
                }
                
                ReportModels.ReportResult result = ReportModels.ReportResult.success(data);
                cacheManager.cacheReport(cacheKey, data);
                resultLiveData.postValue(result);
                
            } catch (Exception e) {
                Log.e(TAG, "Error fetching advanced report: " + e.getMessage(), e);
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
                        JSONObject dataObj = response.data.optJSONObject("data");
                        if (dataObj != null) {
                            usersArray = dataObj.optJSONArray("users");
                        }
                    }
                    
                    if (usersArray != null) {
                        for (int i = 0; i < usersArray.length(); i++) {
                            JSONObject userObj = usersArray.optJSONObject(i);
                            if (userObj != null) {
                                int id = userObj.optInt("id");
                                String name = userObj.optString("display_name", 
                                    userObj.optString("username", "User " + id));
                                String username = userObj.optString("username", "");
                                String role = userObj.optString("role", "");
                                
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
                
                String endpoint = "/gates?branch_id=" + branchId;
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = response.data.optJSONArray("gates");
                    if (gatesArray == null) {
                        JSONObject dataObj = response.data.optJSONObject("data");
                        if (dataObj != null) {
                            gatesArray = dataObj.optJSONArray("gates");
                        }
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
    
    /**
     * ✅ بناء رابط API
     */
    private String buildEndpoint(String type, ReportModels.ReportFilters filters) {
        StringBuilder endpoint = new StringBuilder("/reports-app?type=").append(type);
        
        String queryString = filters.toQueryString();
        if (!queryString.isEmpty()) {
            endpoint.append("&").append(queryString);
        }
        
        return endpoint.toString();
    }
    
    /**
     * ✅ تحليل بيانات الاستجابة
     */
    private JSONObject parseResponseData(MiraApiClient.ApiResponse response) {
        JSONObject data = response.data;
        
        if (data == null && response.getBody() != null) {
            try {
                String rawBody = response.getBody();
                if (rawBody.trim().startsWith("[")) {
                    JSONArray rootArray = new JSONArray(rawBody);
                    data = new JSONObject();
                    data.put("items", rootArray);
                } else {
                    data = new JSONObject(rawBody);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing response: " + e.getMessage());
            }
        }
        
        return data;
    }
    
    /**
     * ✅ إنشاء مفتاح الكاش
     */
    private String generateCacheKey(String type, ReportModels.ReportFilters filters) {
        StringBuilder sb = new StringBuilder(type);
        sb.append("_").append(filters.branchId);
        sb.append("_").append(filters.fromDate != null ? filters.fromDate : "any");
        sb.append("_").append(filters.toDate != null ? filters.toDate : "any");
        sb.append("_").append(filters.searchQuery != null ? filters.searchQuery : "");
        sb.append("_").append(filters.filter1 != null ? filters.filter1 : "");
        sb.append("_").append(filters.filter2 != null ? filters.filter2 : "");
        sb.append("_").append(filters.userId > 0 ? filters.userId : 0);
        sb.append("_").append(filters.gateId > 0 ? filters.gateId : 0);
        
        return sb.toString().replaceAll("[^a-zA-Z0-9_]", "_");
    }
    
    /**
     * ✅ تنظيف الموارد
     */
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
