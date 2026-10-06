package com.example.uhf.MiraRfid.readers;

import android.util.Log;

import java.util.Arrays;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  VanchProtocol — بروتوكول VANCH VF-747P
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  تنسيق الإطار (Frame Format):
 *  ┌─────┬──────┬─────┬──────┬──────┬─────────┬───────┬──────┬──────┐
 *  │ 0xA0│ Type │ CMD │ LenH │ LenL │ Data... │ Check │ 0x0D │ 0x0A │
 *  └─────┴──────┴─────┴──────┴──────┴─────────┴───────┴──────┴──────┘
 *    │      │      │      └──────┴─ طول البيانات (Big-Endian)
 *    │      │      └─ نوع الأمر (Command)
 *    │      └─ اتجاه الرسالة:
 *    │           0x00 = Host → Reader
 *    │           0x01 = Reader → Host
 *    └─ بايت البداية الثابت
 *
 *  Checksum: XOR لكل البايتات من الـ Header حتى نهاية البيانات
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class VanchProtocol {

    private static final String TAG = "VanchProtocol";

    // ==================== إطارات البروتوكول ====================

    public static final byte FRAME_HEADER    = (byte) 0xA0;
    public static final byte FRAME_END_1     = (byte) 0x0D;
    public static final byte FRAME_END_2     = (byte) 0x0A;

    public static final byte DIR_HOST_TO_DEV = (byte) 0x00;
    public static final byte DIR_DEV_TO_HOST = (byte) 0x01;

    // ==================== أوامر VANCH القياسية ====================

    /** بدء القراءة المستمرة (Continuous Inventory) */
    public static final byte CMD_INVENTORY       = (byte) 0x80;

    /** إيقاف القراءة */
    public static final byte CMD_STOP            = (byte) 0x81;

    /** قراءة بيانات من وسم */
    public static final byte CMD_READ_DATA       = (byte) 0x82;

    /** كتابة بيانات على وسم */
    public static final byte CMD_WRITE_DATA      = (byte) 0x83;

    /** قفل وسم */
    public static final byte CMD_LOCK            = (byte) 0x84;

    /** إتلاف وسم */
    public static final byte CMD_KILL            = (byte) 0x85;

    /** ضبط قوة الإرسال */
    public static final byte CMD_SET_POWER       = (byte) 0x86;

    /** قراءة قوة الإرسال الحالية */
    public static final byte CMD_GET_POWER       = (byte) 0x87;

    /** قراءة معلومات الجهاز (موديل، إصدار، إلخ) */
    public static final byte CMD_GET_INFO        = (byte) 0x88;

    /** ضبط المنطقة الترددية (FCC/ETSI) */
    public static final byte CMD_SET_REGION      = (byte) 0x89;

    /** ضبط Session / Target / Q */
    public static final byte CMD_SET_QUERY_PARAM = (byte) 0x8A;

    /** ضبط الفلتر */
    public static final byte CMD_SET_FILTER      = (byte) 0x8B;

    /** إلغاء الفلتر */
    public static final byte CMD_CLEAR_FILTER    = (byte) 0x8C;

    /** Heartbeat / Get Status */
    public static final byte CMD_HEARTBEAT       = (byte) 0x90;

    // ==================== حزم الاستجابة ====================

    public static final byte RESP_SUCCESS        = (byte) 0x00;
    public static final byte RESP_ERROR          = (byte) 0x01;
    public static final byte RESP_INVENTORY_DATA = (byte) 0x80;  // بيانات وسم جديد

    // ==================== بايتات التحكم في المنطقة ====================

    public static final byte REGION_FCC          = (byte) 0x01;  // 902-928 MHz
    public static final byte REGION_ETSI         = (byte) 0x02;  // 865-868 MHz
    public static final byte REGION_CHINA        = (byte) 0x03;  // 920-925 MHz

    // ==================== بناء الإطارات ====================

    /**
     * ✅ بناء إطار أمر جاهز للإرسال
     *
     * @param command الأمر (مثل CMD_INVENTORY)
     * @param data    البيانات (قد تكون null)
     * @return إطار كامل جاهز للإرسال
     */
    public static byte[] buildCommand(byte command, byte[] data) {
        int dataLen = (data != null) ? data.length : 0;

        // 1 (header) + 1 (type) + 1 (cmd) + 2 (len) + N (data) + 1 (checksum) + 2 (end)
        int totalLen = 1 + 1 + 1 + 2 + dataLen + 1 + 2;
        byte[] frame = new byte[totalLen];

        int idx = 0;
        frame[idx++] = FRAME_HEADER;
        frame[idx++] = DIR_HOST_TO_DEV;
        frame[idx++] = command;
        frame[idx++] = (byte) ((dataLen >> 8) & 0xFF);   // Length Hi
        frame[idx++] = (byte) (dataLen & 0xFF);           // Length Lo

        if (dataLen > 0) {
            System.arraycopy(data, 0, frame, idx, dataLen);
            idx += dataLen;
        }

        // Checksum: XOR من البداية حتى نهاية البيانات
        int checksum = 0;
        for (int i = 0; i < idx; i++) {
            checksum ^= (frame[i] & 0xFF);
        }
        frame[idx++] = (byte) checksum;
        frame[idx++] = FRAME_END_1;
        frame[idx  ] = FRAME_END_2;

        Log.d(TAG, "📤 Built frame: cmd=0x" + String.format("%02X", command)
                + " len=" + dataLen + " total=" + totalLen);

        return frame;
    }

    /**
     * ✅ بناء إطار قراءة/كتابة بيانات وسم
     * صيغة البيانات: [EPC Length (2)] [EPC] [Bank] [Ptr Hi] [Ptr Lo] [Len Hi] [Len Lo] [Pwd 4 bytes] [Data...]
     */
    public static byte[] buildMemoryCommand(byte command, String epc,
                                             int bank, int ptr, int len,
                                             String password, byte[] writeData) {
        try {
            byte[] epcBytes = hexToBytes(epc);
            int epcLen = epcBytes.length;

            int dataLen = 2 + epcLen + 1 + 2 + 2 + 4 + (writeData != null ? writeData.length : 0);
            byte[] payload = new byte[dataLen];

            int idx = 0;
            payload[idx++] = (byte) ((epcLen >> 8) & 0xFF);
            payload[idx++] = (byte) (epcLen & 0xFF);
            System.arraycopy(epcBytes, 0, payload, idx, epcLen);
            idx += epcLen;
            payload[idx++] = (byte) bank;
            payload[idx++] = (byte) ((ptr >> 8) & 0xFF);
            payload[idx++] = (byte) (ptr & 0xFF);
            payload[idx++] = (byte) ((len >> 8) & 0xFF);
            payload[idx++] = (byte) (len & 0xFF);

            byte[] pwdBytes = hexToBytes(password != null ? password : "00000000");
            System.arraycopy(pwdBytes, 0, payload, idx, 4);
            idx += 4;

            if (writeData != null && writeData.length > 0) {
                System.arraycopy(writeData, 0, payload, idx, writeData.length);
            }

            return buildCommand(command, payload);
        } catch (Exception e) {
            Log.e(TAG, "buildMemoryCommand error", e);
            return null;
        }
    }

    // ==================== فك ترميز الاستجابات ====================

    /**
     * ✅ التحقق من صحة إطار مستلم
     */
    public static boolean isValidFrame(byte[] buffer, int length) {
        if (buffer == null || length < 8) return false;
        if (buffer[0] != FRAME_HEADER) return false;
        if (buffer[length - 2] != FRAME_END_1) return false;
        if (buffer[length - 1] != FRAME_END_2) return false;

        // التحقق من Checksum
        int expectedChecksum = 0;
        for (int i = 0; i < length - 3; i++) {
            expectedChecksum ^= (buffer[i] & 0xFF);
        }
        int actualChecksum = buffer[length - 3] & 0xFF;
        return expectedChecksum == actualChecksum;
    }

    /**
     * ✅ الحصول على نوع الأمر من إطار
     */
    public static byte extractCommand(byte[] buffer) {
        if (buffer == null || buffer.length < 3) return 0x00;
        return buffer[2];
    }

    /**
     * ✅ الحصول على طول البيانات من إطار
     */
    public static int extractDataLength(byte[] buffer) {
        if (buffer == null || buffer.length < 5) return 0;
        int hi = buffer[3] & 0xFF;
        int lo = buffer[4] & 0xFF;
        return (hi << 8) | lo;
    }

    /**
     * ✅ استخراج بيانات الوسم من إطار Inventory
     *
     * تنسيق بيانات Inventory:
     * [EPC Length (2)] [EPC] [RSSI (1)] [PC (2)] [CRC (2)]
     *
     * @return null إذا كان الإطار غير صالح
     */
    public static VanchTagData parseInventoryTag(byte[] buffer, int length) {
        try {
            if (!isValidFrame(buffer, length)) {
                return null;
            }

            int dataLen = extractDataLength(buffer);
            if (dataLen < 4) return null;

            int dataStart = 5;  // بعد Header + Type + CMD + Len Hi + Len Lo

            // قراءة طول EPC
            int epcLen = ((buffer[dataStart] & 0xFF) << 8) | (buffer[dataStart + 1] & 0xFF);
            if (epcLen <= 0 || epcLen > 62) return null;

            dataStart += 2;

            // استخراج EPC
            if (dataStart + epcLen > length - 3) return null;
            byte[] epcBytes = new byte[epcLen];
            System.arraycopy(buffer, dataStart, epcBytes, 0, epcLen);
            String epc = bytesToHex(epcBytes);
            dataStart += epcLen;

            // استخراج RSSI (byte واحد — يُفسَّر كـ int مع إشارة سالبة)
            int rssi = -50;  // افتراضي
            if (dataStart < length - 3) {
                rssi = buffer[dataStart] & 0xFF;
                // تحويل إلى dBm سالب: 0-255 → -80 إلى -30
                rssi = -80 + (rssi / 8);
            }

            return new VanchTagData(epc, rssi);

        } catch (Exception e) {
            Log.e(TAG, "parseInventoryTag error", e);
            return null;
        }
    }

    // ==================== أدوات مساعدة ====================

    /**
     * ✅ تحويل String Hex إلى byte[]
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        hex = hex.trim().toUpperCase();
        if (hex.length() % 2 != 0) hex = "0" + hex;

        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            result[i / 2] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    /**
     * ✅ تحويل byte[] إلى String Hex
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * ✅ الحصول على وصف نصي للأمر (للتشخيص)
     */
    public static String describeCommand(byte cmd) {
        switch (cmd) {
            case CMD_INVENTORY:       return "INVENTORY";
            case CMD_STOP:            return "STOP";
            case CMD_READ_DATA:       return "READ_DATA";
            case CMD_WRITE_DATA:      return "WRITE_DATA";
            case CMD_LOCK:            return "LOCK";
            case CMD_KILL:            return "KILL";
            case CMD_SET_POWER:       return "SET_POWER";
            case CMD_GET_POWER:       return "GET_POWER";
            case CMD_GET_INFO:        return "GET_INFO";
            case CMD_SET_REGION:      return "SET_REGION";
            case CMD_SET_QUERY_PARAM: return "SET_QUERY_PARAM";
            case CMD_SET_FILTER:      return "SET_FILTER";
            case CMD_CLEAR_FILTER:    return "CLEAR_FILTER";
            case CMD_HEARTBEAT:       return "HEARTBEAT";
            default:                  return "UNKNOWN(0x" + String.format("%02X", cmd) + ")";
        }
    }

    // ==================== حاوية بيانات الوسم ====================

    /**
     * ✅ حاوية بيانات وسم واحد
     */
    public static class VanchTagData {
        public final String epc;
        public final int rssi;

        public VanchTagData(String epc, int rssi) {
            this.epc = epc;
            this.rssi = rssi;
        }

        @Override
        public String toString() {
            return "VanchTag{EPC=" + epc + ", RSSI=" + rssi + "}";
        }
    }
}
