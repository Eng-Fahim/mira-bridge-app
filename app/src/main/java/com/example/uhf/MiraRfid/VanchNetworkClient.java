package com.example.uhf.MiraRfid;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * عميل شبكة لبوابات VANCH Fixed
 * 
 * يدعم بروتوكول TCP Stream (الأكثر شيوعاً في VANCH)
 * كل سطر = حدث واحد بصيغة: "TAG,EPC,RSSI,ANTENNA,TIMESTAMP"
 */
public class VanchNetworkClient {

    private static final String TAG = "VanchNetworkClient";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    public interface VanchEventListener {
        void onConnected();
        void onDisconnected(String reason);
        void onTagRead(String epc, int rssi, int antenna);
        void onError(String error);
    }

    private final String host;
    private final int port;
    private final VanchEventListener listener;

    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;
    private Thread readerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile boolean shouldReconnect = true;

    public VanchNetworkClient(String ipAddressWithPort, VanchEventListener listener) {
        String[] parts = ipAddressWithPort.split(":");
        this.host = parts[0];
        this.port = parts.length > 1 ? Integer.parseInt(parts[1]) : 4001;
        this.listener = listener;
    }

    public void connect() {
        if (isRunning.get()) return;
        isRunning.set(true);
        shouldReconnect = true;
        
        new Thread(this::connectionLoop, "Vanch-Connect").start();
    }

    private void connectionLoop() {
        int backoff = 2000;
        
        while (shouldReconnect) {
            try {
                openSocket();
                backoff = 2000;   // reset
                readLoop();
            } catch (Exception e) {
                Log.w(TAG, "Connection lost: " + e.getMessage());
                closeQuietly();
                
                if (!shouldReconnect) break;
                
                listener.onDisconnected(e.getMessage());
                
                try {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);   // max 30s
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void openSocket() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setKeepAlive(true);

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = socket.getOutputStream();

        // VANCH protocol: send START command
        writer.write("START\n".getBytes());
        writer.flush();

        listener.onConnected();
        Log.i(TAG, "✅ Connected to VANCH at " + host + ":" + port);
    }

    private void readLoop() throws IOException {
        String line;
        while ((line = reader.readLine()) != null && shouldReconnect) {
            parseTagLine(line);
        }
    }

    private void parseTagLine(String line) {
        // Protocol: "TAG,<EPC>,<RSSI>,<ANTENNA>,<TIMESTAMP>"
        //          أو "EPC,<HEX>" (مبسط)
        try {
            line = line.trim();
            if (line.isEmpty()) return;

            String[] parts = line.split(",");
            
            String epc;
            int rssi = -50;
            int antenna = 1;

            if (parts[0].equalsIgnoreCase("TAG") && parts.length >= 2) {
                epc = parts[1].trim();
                if (parts.length >= 3) rssi = Integer.parseInt(parts[2].trim());
                if (parts.length >= 4) antenna = Integer.parseInt(parts[3].trim());
            } else if (parts[0].equalsIgnoreCase("EPC") && parts.length >= 2) {
                epc = parts[1].trim();
            } else {
                // Assume first token is EPC
                epc = parts[0].trim();
            }

            if (epc.length() >= 16) {
                listener.onTagRead(epc.toUpperCase(), rssi, antenna);
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + line, e);
        }
    }

    public void disconnect() {
        shouldReconnect = false;
        isRunning.set(false);
        try {
            if (writer != null) {
                writer.write("STOP\n".getBytes());
                writer.flush();
            }
        } catch (Exception ignored) {}
        closeQuietly();
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
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}
