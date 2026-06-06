package au.com.shyfted.client;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ShyftedDeviceClient {
    static final String TAG = "ShyftedClient";

    private static final int REQUEST_TIMEOUT_MS = 10_000;
    private static final int POLL_SECONDS = 5;
    private static final int HEARTBEAT_SECONDS = 60;

    private final CmsEndpoints endpoints;
    private final JSONObject deviceSpec;
    private ScheduledExecutorService executor;

    ShyftedDeviceClient(CmsEndpoints endpoints, Map<String, Object> deviceSpec) {
        this.endpoints = endpoints;
        this.deviceSpec = new JSONObject(deviceSpec);
    }

    void start() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor();
        Log.i(TAG, "Device client starting configUrl=" + endpoints.configUrl()
                + " heartbeatUrl=" + endpoints.heartbeatUrl());
        Log.i(TAG, "Heartbeat payload=" + deviceSpec.toString());

        executor.execute(this::sendHeartbeat);
        executor.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(this::pollConfig, 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    String heartbeatPayloadForLogs() {
        return deviceSpec.toString();
    }

    private void sendHeartbeat() {
        try {
            HttpResult result = request("POST", endpoints.heartbeatUrl(), deviceSpec.toString());
            Log.i(TAG, "Heartbeat response code=" + result.code + " body=" + result.body);
        } catch (Exception e) {
            Log.e(TAG, "Heartbeat error", e);
        }
    }

    private void pollConfig() {
        try {
            HttpResult result = request("GET", endpoints.configUrl(), null);
            Log.i(TAG, "Config response code=" + result.code + " body=" + result.body);

            if (result.code >= 200 && result.code < 300 && result.body.length() > 0) {
                logConfigSummary(new JSONObject(result.body));
            }
        } catch (Exception e) {
            Log.e(TAG, "Config poll error", e);
        }
    }

    private void logConfigSummary(JSONObject config) throws JSONException {
        JSONObject lcd = config.optJSONObject("lcd");
        JSONObject eink = config.optJSONObject("eink");
        JSONObject device = config.optJSONObject("device");
        Log.i(TAG, "Config summary timestamp=" + config.optString("timestamp", "null")
                + " lcd_file=" + optStringOrNull(lcd, "file")
                + " lcd_content_id=" + optStringOrNull(lcd, "content_id")
                + " eink_file=" + optStringOrNull(eink, "file")
                + " eink_content_id=" + optStringOrNull(eink, "content_id")
                + " device=" + (device == null ? "null" : device.toString()));
    }

    private static String optStringOrNull(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "null";
        }
        return object.optString(key, "null");
    }

    private static HttpResult request(String method, String url, String jsonBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");

        if (jsonBody != null) {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readBody(stream);
        connection.disconnect();
        return new HttpResult(code, body);
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
