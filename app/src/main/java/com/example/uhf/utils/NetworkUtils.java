package com.example.uhf.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class NetworkUtils {

    // 1. دالة للتحقق من وجود إنترنت قبل إرسال الطلب (اختياري ولكن مفيد)
    public static boolean isNetworkAvailable(Context context) {
        if (context == null) return false;
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
        return false;
    }

    // 2. دالة تحويل الخطأ البرمجي إلى رسالة احترافية (عربي/إنجليزي)
    public static String getFriendlyErrorMessage(Context context, Throwable throwable, boolean isArabic) {
        if (throwable == null) {
            return isArabic ? "حدث خطأ غير متوقع، يرجى المحاولة لاحقاً." : "An unexpected error occurred. Please try again.";
        }

        // التحقق من عدم وجود إنترنت
        if (!isNetworkAvailable(context)) {
            return isArabic 
                ? "لا يوجد اتصال بالإنترنت. يرجى التحقق من الشبكة والمحاولة مرة أخرى." 
                : "No internet connection. Please check your network and try again.";
        }

        // فحص نوع الخطأ
        if (throwable instanceof UnknownHostException) {
            return isArabic 
                ? "تعذر الوصول إلى الخادم. يرجى التأكد من اتصالك بالإنترنت." 
                : "Unable to reach the server. Please check your connection.";
        } else if (throwable instanceof SocketTimeoutException) {
            return isArabic 
                ? "انتهت مهلة الاتصال بالخادم. يرجى المحاولة مرة أخرى." 
                : "Connection timed out. Please try again.";
        } else if (throwable instanceof ConnectException) {
            return isArabic 
                ? "فشل الاتصال بالخادم. يرجى المحاولة لاحقاً." 
                : "Failed to connect to the server. Please try again later.";
        }

        // إذا كان الخطأ غير معروف، نعرض رسالة عامة (ولا نعرض رسالة النظام الخام)
        return isArabic 
            ? "حدث خطأ في الاتصال. يرجى المحاولة لاحقاً." 
            : "A network error occurred. Please try again later.";
    }
}
