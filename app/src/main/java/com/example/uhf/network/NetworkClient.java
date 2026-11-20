package com.example.uhf.network;

import com.example.uhf.api.ApiResponse;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.utils.MiraExecutorService;

import org.json.JSONObject;

public class NetworkClient {

    public interface JsonCallback {
        void onSuccess(JSONObject response);
        void onError(Throwable t);
    }

    private final MiraApiClient apiClient;

    public NetworkClient(MiraApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void get(String path, JsonCallback callback) {
        MiraExecutorService.getInstance().getBackgroundExecutor().execute(() -> {
            try {
                // تنظيف البادئة لتجنب تكرار /api/v1
                String cleanPath = path.replaceFirst("^/api/v1", "");
                if (!cleanPath.startsWith("/")) {
                    cleanPath = "/" + cleanPath;
                }

                ApiResponse res = apiClient.getWithAuth(cleanPath);
                if (res != null && res.isSuccess()) {
                    JSONObject json = res.getRawJson() != null
                            ? res.getRawJson()
                            : (res.getData() != null ? new JSONObject().put("data", res.getData()) : new JSONObject());
                    if (callback != null) callback.onSuccess(json);
                } else {
                    String err = (res != null && res.getMessage() != null) ? res.getMessage() : "Request failed";
                    if (callback != null) callback.onError(new Exception(err));
                }
            } catch (Throwable t) {
                if (callback != null) callback.onError(t);
            }
        });
    }
}
