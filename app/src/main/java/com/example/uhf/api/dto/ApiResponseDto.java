package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class ApiResponseDto<T> {
    
    @SerializedName("success")
    public boolean success;
    
    @SerializedName("data")
    public T data;
    
    @SerializedName("error")
    public String error;
    
    @SerializedName("message")
    public String message;
    
    @SerializedName("total")
    public Integer total;
}
