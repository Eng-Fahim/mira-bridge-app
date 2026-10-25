package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class AuditLogDto {
    @SerializedName("id") public long id;
    @SerializedName("user_id") public int userId;
    @SerializedName("action") public String action;
    @SerializedName("object_type") public String objectType;
    @SerializedName("object_id") public String objectId;
    @SerializedName("details") public String details; // JSON String
    @SerializedName("ip_address") public String ipAddress;
    @SerializedName("status") public String status; // success, failed, error
    @SerializedName("created_at") public String createdAt;
}
