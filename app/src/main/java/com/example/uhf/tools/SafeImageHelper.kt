package com.example.uhf.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

object SafeImageHelper {
    private const val TAG = "SafeImageHelper"

    /**
     * فك ترميز آمن لأي صورة من الاستوديو أو الكاميرا:
     * - يفتح Stream جديد لكل مرحلة (يمنع عودة null)
     * - يصغّر الأبعاد الكبيرة لتجنب OutOfMemory
     * - يضمن صيغة ARGB_8888 البرمجية (يمنع انهيار HARDWARE Bitmap)
     * - يصحح زاوية التدوير EXIF تلقائياً
     */
    fun decodeSafeSoftwareBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024
    ): Bitmap? {
        return try {
            // 1. قراءة الأبعاد فقط من Stream أول
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                Log.e(TAG, "Invalid image bounds for uri: $uri")
                return null
            }

            // 2. حساب معامل التصغير المناسب
            var sampleSize = 1
            val largestSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
            while (largestSide / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            // 3. فك الترميز الفعلي بصيغة ARGB_8888 من Stream جديد
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // تحويل إجباري إلى ARGB_8888 إذا أعاد الجهاز صيغة أخرى
            if (decoded.config != Bitmap.Config.ARGB_8888) {
                val swCopy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                if (swCopy != null && swCopy != decoded) {
                    decoded.recycle()
                    decoded = swCopy
                }
            }

            // 4. تصحيح اتجاه الصورة (EXIF Rotation)
            val rotationDegrees = readExifRotation(context, uri)
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                )
                if (rotated != decoded) {
                    decoded.recycle()
                    decoded = rotated
                }
            }

            decoded
        } catch (e: Throwable) {
            Log.e(TAG, "decodeSafeSoftwareBitmap failed: ${e.message}", e)
            null
        }
    }

    private fun readExifRotation(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) {
            0f
        }
    }

    /**
     * مقارنة بصرية آمنة (Normalized Thumbnail Luma Comparison):
     * توحّد مقاس الصورتين إلى 32x32 بصيغة ARGB_8888 قبل المقارنة
     * فلا تفشل أبداً بسبب اختلاف الأبعاد أو صيغة البكسل.
     * @return نسبة التشابه من 0.0 إلى 1.0
     */
    fun computeSafeSimilarity(bmp1: Bitmap?, bmp2: Bitmap?): Float {
        if (bmp1 == null || bmp2 == null || bmp1.isRecycled || bmp2.isRecycled) {
            return 0f
        }
        return try {
            val size = 32
            val s1 = ensureSoftwareBitmap(Bitmap.createScaledBitmap(bmp1, size, size, true))
            val s2 = ensureSoftwareBitmap(Bitmap.createScaledBitmap(bmp2, size, size, true))

            val pixels1 = IntArray(size * size)
            val pixels2 = IntArray(size * size)
            s1.getPixels(pixels1, 0, size, 0, 0, size, size)
            s2.getPixels(pixels2, 0, size, 0, 0, size, size)

            var totalDiff = 0L
            for (i in pixels1.indices) {
                val c1 = pixels1[i]
                val c2 = pixels2[i]
                val rDiff = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
                val gDiff = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
                val bDiff = abs((c1 and 0xFF) - (c2 and 0xFF))
                totalDiff += (rDiff + gDiff + bDiff)
            }

            if (s1 != bmp1) s1.recycle()
            if (s2 != bmp2) s2.recycle()

            val maxDiff = size * size * 3L * 255L
            (1.0 - (totalDiff.toDouble() / maxDiff.toDouble())).toFloat().coerceIn(0f, 1f)
        } catch (e: Throwable) {
            Log.e(TAG, "computeSafeSimilarity error: ${e.message}", e)
            0f
        }
    }

    private fun ensureSoftwareBitmap(bmp: Bitmap): Bitmap {
        return if (bmp.config != Bitmap.Config.ARGB_8888) {
            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        } else {
            bmp
        }
    }
}
