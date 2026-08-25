package com.example.uhf.model;

public class MiraAuditLogItem {
    private String action;
    private String username;
    private String objectType;
    private String objectId;
    private String details;
    private String createdAt;
    private String ipAddress;

    public MiraAuditLogItem() {}

    public MiraAuditLogItem(String action, String username, String objectType, String objectId, String details, String createdAt, String ipAddress) {
        this.action = action;
        this.username = username;
        this.objectType = objectType;
        this.objectId = objectId;
        this.details = details;
        this.createdAt = createdAt;
        this.ipAddress = ipAddress;
    }

    public String getAction() { return action; }
    public String getUsername() { return username; }
    public String getObjectType() { return objectType; }
    public String getObjectId() { return objectId; }
    public String getDetails() { return details; }
    public String getCreatedAt() { return createdAt; }
    public String getIpAddress() { return ipAddress; }
}
