package com.example.uhf.api;

import org.json.JSONArray;
import org.json.JSONObject;

public class ApiResponse {
    public final int code;
    public final JSONObject data;
    public final String error;
    public String message;
    public int statusCode;
    public boolean isNetworkError;
    public String errorCode;
    public final boolean isSuccess;
    private String rawBody;

    public ApiResponse(int code, JSONObject data, String error) {
        this.code = code;
        this.data = data;
        this.error = error;
        this.isSuccess = code >= 200 && code < 300;
        this.rawBody = data != null ? data.toString() : "";
        
        if (data != null) {
            this.message = data.optString("message", "");
        } else {
            this.message = "";
        }
    }

    public String getBody() {
        if (rawBody != null && !rawBody.isEmpty()) {
            return rawBody;
        }
        return data != null ? data.toString() : "";
    }

    public void setBody(String body) {
        this.rawBody = body;
    }

    public String optString(String key, String defaultValue) {
        if (data != null) return data.optString(key, defaultValue);
        return defaultValue;
    }

    public int optInt(String key, int defaultValue) {
        if (data != null) return data.optInt(key, defaultValue);
        return defaultValue;
    }

    public JSONObject optJSONObject(String key) {
        if (data != null) return data.optJSONObject(key);
        return null;
    }

    public JSONArray optJSONArray(String key) {
        if (data != null) return data.optJSONArray(key);
        return null;
    }
}
