package com.example.uhf.MiraRfid;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VanchNetworkClient v1.1
 *
 * v1.1:
 *   ✅ gateId حقل اختياري للربط ببوابة محددة
 *   ✅ constructor جديد: (endpoint, gateId, listener)
 *   ✅ getGateId() accessor
 *
 * v1.0 (محفوظ):
 *   ✅ Auto-reconnect + exponential backoff
 *   ✅ Keep-alive
 *   ✅ START/STOP
 *   ✅ Zero context dependencies
 */
public class VanchNetworkClient {

    private static final String TAG = "VanchNetworkClient";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 30000;
    private static final int MIN_BACKOFF_MS     = 2000;
    private static final int MAX_BACKOFF_MS     = 30000;
    private static final int MIN_EPC_LENGTH     = 16;

    public interface VanchEventListener {
        void onConnected();
        void onDisconnected(String reason);
        void onTagRead(String epc, int rssi);
        void onError(String error);
    }

    private final String host;
    private final int port;
    private final VanchEventListener listener;

    // ✅ [v1.1] gateId اختياري
    private final String gateId;

    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile OutputStream writer;

    private Thread connectionThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean shouldReconnect = true;

    private String startCommand = "START\n";
    private String stopCommand = "STOP\n";

    // ═══════════════════════════════════════════════════════════
    //  Constructors
    // ═══════════════════════════════════════════════════════════

    /** ✅ [v1.1] مع gateId */
    public VanchNetworkClient(String ipAddressWithPort, String gateId,
                              VanchEventListener listener) {
        String[] parts = parseAddress(ipAddressWithPort);
        this.host = parts[0];
        this.port = Integer.parseInt(parts[1]);
        this.gateId = gateId != null ? gateId : "";
        this.listener = listener;
    }

    public VanchNetworkClient(String ipAddressWithPort, VanchEventListener listener) {
        String[] parts = parseAddress(ipAddressWithPort);
        this.host = parts[0];
        this.port = Integer.parseInt(parts[1]);
        this.gateId = "";
        this.listener = listener;
    }

    public VanchNetworkClient(String host, int port, VanchEventListener listener) {
        this.host = host != null ? host.trim() : "";
        this.port = port > 0 ? port : 4001;
        this.gateId = "";
        this.listener = listener;
    }

    private static String[] parseAddress(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("VANCH address empty");
        }
        input = input.trim();
        if (input.contains(":")) {
            String[] parts = input.split(":", 2);
            return new String[]{parts[0].trim(), parts[1].trim()};
        }
        return new String[]{input, "4001"};
    }

    /** ✅ [v1.1] accessor */
    public String getGateId() {
        return gateId;
    }

    public void setStartCommand(String cmd) {
        this.startCommand = cmd;
    }

    public void setStopCommand(String cmd) {
        this.stopCommand = cmd;
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    public void connect() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Already connecting/connected — ignoring duplicate connect()");
            return;
        }
        shouldReconnect = true;

        connectionThread = new Thread(this::connectionLoop, "VanchNet-Conn");
        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    private void connectionLoop() {
        long backoff = MIN_BACKOFF_MS;

        while (shouldReconnect && isRunning.get()) {
            try {
                openSocket();
                backoff = MIN_BACKOFF_MS;

                notifyConnected();
                Log.i(TAG, "✅ Connected to " + getEndpoint());

                readLoop();

            } catch (SocketTimeoutException e) {
                Log.d(TAG, "Read timeout — sending keep-alive");
                sendKeepAlive();

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Log.w(TAG, "Connection lost: " + msg);
                notifyDisconnected(msg);
                closeQuietly();

                if (!shouldReconnect) break;

                try {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2L, MAX_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        closeQuietly();
        isRunning.set(false);
        Log.d(TAG, "Connection loop ended");
    }

    private void openSocket() throws Exception {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);

        this.socket = s;
        this.reader = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        this.writer = s.getOutputStream();

        if (startCommand != null && !startCommand.isEmpty()) {
            writer.write(startCommand.getBytes(StandardCharsets.UTF_8));
            writer.flush();
            Log.d(TAG, "▶️ START: " + startCommand.trim());
        }
    }

    private void readLoop() throws Exception {
        String line;
        while (shouldReconnect && (line = reader.readLine()) != null) {
            parseLine(line);
        }
    }

    private void sendKeepAlive() {
        try {
            if (writer != null) {
                writer.write("\n".getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  Protocol parsing
    // ═══════════════════════════════════════════════════════════

    private void parseLine(String rawLine) {
        if (rawLine == null) return;
        final String line = rawLine.trim();
        if (line.isEmpty()) return;

        try {
            // Format 1: TAG,<EPC>,<RSSI>,<ANT>
            if (line.regionMatches(true, 0, "TAG,", 0, 4)) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String epc = normalizeEpc(parts[1]);
                    int rssi = parts.length >= 3 ? parseIntSafe(parts[2], -50) : -50;
                    if (isValidEpc(epc)) notifyTag(epc, rssi);
                }
                return;
            }

            // Format 2: EPC,<EPC>[,<RSSI>]
            if (line.regionMatches(true, 0, "EPC,", 0, 4)) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String epc = normalizeEpc(parts[1]);
                    int rssi = parts.length >= 3 ? parseIntSafe(parts[2], -50) : -50;
                    if (isValidEpc(epc)) notifyTag(epc, rssi);
                }
                return;
            }

            // Format 3: JSON
            if (line.startsWith("{")) {
                try {
                    JSONObject obj = new JSONObject(line);
                    String epc = obj.optString("epc",
                                    obj.optString("EPC",
                                        obj.optString("tag", "")));
                    int rssi = obj.optInt("rssi",
                                    obj.optInt("RSSI", -50));
                    epc = normalizeEpc(epc);
                    if (isValidEpc(epc)) notifyTag(epc, rssi);
                } catch (Exception ignored) {}
                return;
            }

            // Format 4: EPC:<hex>
            Matcher m = Pattern.compile("(?i)epc\\s*[:=]\\s*([0-9A-Fa-f]{16,32})").matcher(line);
            if (m.find()) {
                String epc = normalizeEpc(m.group(1));
                if (isValidEpc(epc)) notifyTag(epc, -50);
                return;
            }

            // Format 5: Raw hex
            String cleanHex = line.replaceAll("[^0-9A-Fa-f]", "");
            if (cleanHex.length() >= MIN_EPC_LENGTH
                    && cleanHex.length() <= 40
                    && cleanHex.length() % 2 == 0) {
                notifyTag(cleanHex.toUpperCase(), -50);
                return;
            }

            Log.v(TAG, "⏭️ Skipped line: " + line);

        } catch (Exception e) {
            Log.w(TAG, "parseLine error: " + line, e);
        }
    }

    private String normalizeEpc(String epc) {
        if (epc == null) return "";
        return epc.trim().replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
    }

    private boolean isValidEpc(String epc) {
        return epc != null
                && epc.length() >= MIN_EPC_LENGTH
                && epc.matches("^[0-9A-F]+$");
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    // ═══════════════════════════════════════════════════════════
    //  Notifications
    // ═══════════════════════════════════════════════════════════

    private void notifyConnected() {
        if (listener != null) {
            try { listener.onConnected(); } catch (Exception ignored) {}
        }
    }

    private void notifyDisconnected(String reason) {
        if (listener != null) {
            try { listener.onDisconnected(reason); } catch (Exception ignored) {}
        }
    }

    private void notifyTag(String epc, int rssi) {
        if (listener != null) {
            try { listener.onTagRead(epc, rssi); } catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Shutdown
    // ═══════════════════════════════════════════════════════════

    public void disconnect() {
        Log.d(TAG, "Disconnecting from " + getEndpoint());
        shouldReconnect = false;
        isRunning.set(false);

        try {
            if (writer != null && stopCommand != null) {
                writer.write(stopCommand.getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }
        } catch (Exception ignored) {}

        closeQuietly();

        Thread t = connectionThread;
        if (t != null) {
            t.interrupt();
            connectionThread = null;
        }
    }

    private void closeQuietly() {
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        reader = null;
        writer = null;
        socket = null;
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    public String getEndpoint() {
        return host + ":" + port;
    }
}
