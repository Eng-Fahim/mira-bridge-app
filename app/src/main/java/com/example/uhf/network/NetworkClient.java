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
                String cleanPath = path.replaceFirst("^/api/v1", "");
                if (!cleanPath.startsWith("/")) {
                    cleanPath = "/" + cleanPath;
                }

                ApiResponse res = apiClient.getWithAuth(cleanPath);
                if (res != null && res.isSuccess()) {
                    JSONObject root = new JSONObject();
                    root.put("success", true);

                    if (res.data instanceof JSONObject) {
                        JSONObject dataJson = (JSONObject) res.data;
                        // إذا كانت الاستجابة هي الجذر نفسه (تحتوي على version أو data)
                        if (dataJson.has("version") || dataJson.has("data")) {
                            root = dataJson;
                        } else {
                            root.put("data", dataJson);
                        }
                    } else if (res.data != null) {
                        root.put("data", res.data);
                    }

                    if (callback != null) callback.onSuccess(root);
                } else {
                    String err = (res != null && res.message != null) ? res.message : "Request failed";
                    if (callback != null) callback.onError(new Exception(err));
                }
            } catch (Throwable t) {
                if (callback != null) callback.onError(t);
            }
        });
    }
}
