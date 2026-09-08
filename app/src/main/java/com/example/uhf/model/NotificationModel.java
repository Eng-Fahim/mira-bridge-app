package com.example.uhf.models;

public class NotificationModel {
    private String id;
    private String title;
    private String content;
    private long timestamp;
    private String notificationType; // SYSTEM, INVENTORY, ADMIN, CHAT
    private boolean isRead;
    private String priority; // LOW, MEDIUM, HIGH

    public NotificationModel() {
    }

    public NotificationModel(String title, String content, String notificationType) {
        this.title = title;
        this.content = content;
        this.notificationType = notificationType;
        this.timestamp = System.currentTimeMillis();
        this.isRead = false;
        this.priority = "MEDIUM";
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }
}
