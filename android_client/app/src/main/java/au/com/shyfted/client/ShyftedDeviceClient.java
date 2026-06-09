package au.com.shyfted.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
    private static final long EINK_RETRY_DELAY_MS = 8_000;
    private static final String PREFS_NAME = "shyfted_lcd_cache";
    private static final String KEY_LAST_CONTENT_ID = "last_content_id";
    private static final String KEY_LAST_FILE_NAME = "last_file_name";

    private final Context context;
    private final CmsEndpoints endpoints;
    private final JSONObject deviceSpec;
    private final LcdContentListener lcdContentListener;
    private final EinkContentListener einkContentListener;
    private final File lcdCacheDirectory;
    private final File einkCacheDirectory;
    private ScheduledExecutorService executor;
    private String activeLcdContentId;
    private String activeEinkContentId;
    private String retryEinkContentId;
    private long nextEinkRetryAtMs;
    private boolean einkSendInProgress;

    ShyftedDeviceClient(
            Context context,
            CmsEndpoints endpoints,
            Map<String, Object> deviceSpec,
            LcdContentListener lcdContentListener,
            EinkContentListener einkContentListener
    ) {
        this.context = context.getApplicationContext();
        this.endpoints = endpoints;
        this.deviceSpec = new JSONObject(deviceSpec);
        this.lcdContentListener = lcdContentListener;
        this.einkContentListener = einkContentListener;
        this.lcdCacheDirectory = new File(this.context.getFilesDir(), "lcd_cache");
        File externalEinkDirectory = this.context.getExternalFilesDir("eink_cache");
        this.einkCacheDirectory = externalEinkDirectory == null
                ? new File(this.context.getCacheDir(), "eink_cache")
                : externalEinkDirectory;
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
                JSONObject config = new JSONObject(result.body);
                JSONObject lcd = config.optJSONObject("lcd");
                JSONObject eink = config.optJSONObject("eink");
                logConfigSummary(config);
                handleLcdAssignment(lcd);
                if (hasUrl(eink)) {
                    Log.i(TAG, "E-ink handler invoked file=" + optStringOrNull(eink, "file")
                            + " content_id=" + optStringOrNull(eink, "content_id")
                            + " url=" + optStringOrNull(eink, "url"));
                    handleEinkAssignment(eink);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Config poll error", e);
        }
    }

    File lastGoodLcdImage() {
        String contentId = prefs().getString(KEY_LAST_CONTENT_ID, null);
        if (contentId == null || contentId.length() == 0) {
            Log.i(TAG, "LCD cache miss content_id=null");
            return null;
        }

        String fileName = prefs().getString(KEY_LAST_FILE_NAME, "lcd");
        File file = cacheFile(contentId, fileName);
        if (!file.isFile()) {
            Log.i(TAG, "LCD cache miss content_id=" + contentId + " file=" + file.getAbsolutePath());
            return null;
        }

        Log.i(TAG, "LCD cache hit content_id=" + contentId + " file=" + file.getAbsolutePath());
        activeLcdContentId = contentId;
        return file;
    }

    String lastGoodLcdContentId() {
        return prefs().getString(KEY_LAST_CONTENT_ID, null);
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

    private void handleLcdAssignment(JSONObject lcd) throws Exception {
        if (lcd == null || lcd.isNull("content_id") || lcd.isNull("url")) {
            return;
        }

        String contentId = lcd.optString("content_id", "").trim();
        String relativeUrl = lcd.optString("url", "").trim();
        String fileName = lcd.optString("file", "lcd");
        if (contentId.length() == 0 || relativeUrl.length() == 0) {
            return;
        }

        String resolvedUrl = endpoints.resolveContentUrl(relativeUrl);
        Log.i(TAG, "Resolved LCD URL content_id=" + contentId + " url=" + resolvedUrl);

        File cacheFile = cacheFile(contentId, fileName);
        if (cacheFile.isFile()) {
            Log.i(TAG, "LCD cache hit content_id=" + contentId + " file=" + cacheFile.getAbsolutePath());
            rememberLastGood(contentId, fileName);
            displayLcd(contentId, cacheFile);
            return;
        }

        Log.i(TAG, "LCD cache miss content_id=" + contentId + " file=" + cacheFile.getAbsolutePath());
        Log.i(TAG, "LCD download started content_id=" + contentId + " url=" + resolvedUrl);
        try {
            downloadToFile(resolvedUrl, cacheFile, "LCD image");
            rememberLastGood(contentId, fileName);
            Log.i(TAG, "LCD download success content_id=" + contentId + " file=" + cacheFile.getAbsolutePath());
            displayLcd(contentId, cacheFile);
        } catch (Exception e) {
            Log.e(TAG, "LCD download failure content_id=" + contentId + " url=" + resolvedUrl, e);
            if (cacheFile.isFile() && !cacheFile.delete()) {
                Log.w(TAG, "Unable to remove partial LCD cache file=" + cacheFile.getAbsolutePath());
            }
        }
    }

    private void handleEinkAssignment(JSONObject eink) throws Exception {
        Log.i(TAG, "E-ink handler entered assignment=" + (eink == null ? "null" : eink.toString()));
        if (eink == null || eink.isNull("url")) {
            Log.i(TAG, "E-ink handler return: missing url assignment=" + (eink == null ? "null" : eink.toString()));
            return;
        }

        String relativeUrl = eink.optString("url", "").trim();
        String fileName = eink.optString("file", "eink");
        if (relativeUrl.length() == 0) {
            Log.i(TAG, "E-ink assignment skipped: empty url assignment=" + eink.toString());
            return;
        }

        String contentId = eink.optString("content_id", "").trim();
        if (contentId.length() == 0) {
            Log.w(TAG, "E-ink handler content_id missing/invalid; deriving from url=" + relativeUrl);
            contentId = "url_" + Integer.toHexString(relativeUrl.hashCode());
            Log.i(TAG, "E-ink assignment using derived content_id=" + contentId + " url=" + relativeUrl);
        }
        Log.i(TAG, "E-ink active-state check content_id=" + contentId + " current=" + activeEinkContentId);
        if (contentId.equals(activeEinkContentId)) {
            Log.i(TAG, "E-ink handler return: content already active after successful send content_id=" + contentId);
            return;
        }
        if (einkSendInProgress) {
            Log.i(TAG, "E-ink handler return: send already in progress content_id=" + contentId);
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (contentId.equals(retryEinkContentId) && nowMs < nextEinkRetryAtMs) {
            Log.i(TAG, "E-ink retry waiting content_id=" + contentId
                    + " retry_in_ms=" + (nextEinkRetryAtMs - nowMs));
            return;
        }
        if (!contentId.equals(retryEinkContentId)) {
            retryEinkContentId = null;
            nextEinkRetryAtMs = 0;
        }

        String resolvedUrl;
        try {
            Log.i(TAG, "E-ink URL resolution started content_id=" + contentId + " url=" + relativeUrl);
            resolvedUrl = endpoints.resolveContentUrl(relativeUrl);
            Log.i(TAG, "Resolved e-ink URL content_id=" + contentId + " url=" + resolvedUrl);
        } catch (Exception e) {
            Log.e(TAG, "E-ink URL resolution failure content_id=" + contentId + " url=" + relativeUrl, e);
            throw e;
        }

        File cacheFile = einkCacheFile(contentId, fileName);
        Log.i(TAG, "E-ink cache path content_id=" + contentId
                + " image_path=" + cacheFile.getAbsolutePath()
                + " exists=" + cacheFile.isFile());
        if (cacheFile.isFile()) {
            Log.i(TAG, "E-ink cache hit content_id=" + contentId + " image_path=" + cacheFile.getAbsolutePath());
            sendEink(contentId, cacheFile);
            return;
        }

        Log.i(TAG, "E-ink cache miss content_id=" + contentId + " image_path=" + cacheFile.getAbsolutePath());
        Log.i(TAG, "E-ink download started content_id=" + contentId + " url=" + resolvedUrl);
        try {
            downloadToFile(resolvedUrl, cacheFile, "E-ink image");
            makeServiceReadable(cacheFile);
            Log.i(TAG, "E-ink download success content_id=" + contentId
                    + " image_path=" + cacheFile.getAbsolutePath());
            sendEink(contentId, cacheFile);
        } catch (Exception e) {
            Log.e(TAG, "E-ink download failure content_id=" + contentId + " url=" + resolvedUrl, e);
            if (cacheFile.isFile() && !cacheFile.delete()) {
                Log.w(TAG, "Unable to remove partial e-ink cache file=" + cacheFile.getAbsolutePath());
            }
        }
    }

    private void displayLcd(String contentId, File file) {
        if (contentId.equals(activeLcdContentId)) {
            return;
        }

        activeLcdContentId = contentId;
        lcdContentListener.onLcdContentReady(contentId, file);
    }

    private void sendEink(String contentId, File file) {
        Log.i(TAG, "E-ink image ready content_id=" + contentId + " image_path=" + file.getAbsolutePath());
        Log.i(TAG, "E-ink sendImage dispatch content_id=" + contentId + " image_path=" + file.getAbsolutePath());
        einkSendInProgress = true;
        int returnCode;
        try {
            returnCode = einkContentListener.onEinkContentReady(contentId, file);
        } finally {
            einkSendInProgress = false;
        }

        if (returnCode == 0) {
            activeEinkContentId = contentId;
            retryEinkContentId = null;
            nextEinkRetryAtMs = 0;
            Log.i(TAG, "E-ink sendImage success/accepted content_id=" + contentId
                    + " return_code=" + returnCode);
            Log.i(TAG, "E-ink active-state updated only after sendImage success/accepted content_id=" + contentId);
            return;
        }

        Log.w(TAG, "E-ink active-state not updated because sendImage was not accepted content_id=" + contentId
                + " current=" + activeEinkContentId
                + " return_code=" + returnCode);
        if (returnCode == -1) {
            scheduleEinkRetry(contentId, returnCode, "busy");
        } else {
            scheduleEinkRetry(contentId, returnCode, "not_accepted");
        }
    }

    private void scheduleEinkRetry(String contentId, int returnCode, String reason) {
        retryEinkContentId = contentId;
        nextEinkRetryAtMs = System.currentTimeMillis() + EINK_RETRY_DELAY_MS;
        Log.w(TAG, "E-ink sendImage " + reason + "; retry scheduled content_id=" + contentId
                + " return_code=" + returnCode
                + " retry_delay_ms=" + EINK_RETRY_DELAY_MS);
    }

    private File cacheFile(String contentId, String fileName) {
        if (!lcdCacheDirectory.isDirectory() && !lcdCacheDirectory.mkdirs()) {
            Log.w(TAG, "Unable to create LCD cache directory=" + lcdCacheDirectory.getAbsolutePath());
        }
        String extension = extensionFrom(fileName);
        return new File(lcdCacheDirectory, safeFilePart(contentId) + extension);
    }

    private File einkCacheFile(String contentId, String fileName) {
        if (!einkCacheDirectory.isDirectory() && !einkCacheDirectory.mkdirs()) {
            Log.w(TAG, "Unable to create e-ink cache directory=" + einkCacheDirectory.getAbsolutePath());
        }
        Log.i(TAG, "E-ink cache directory path=" + einkCacheDirectory.getAbsolutePath()
                + " exists=" + einkCacheDirectory.isDirectory());
        String extension = extensionFrom(fileName);
        return new File(einkCacheDirectory, safeFilePart(contentId) + extension);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void rememberLastGood(String contentId, String fileName) {
        prefs().edit()
                .putString(KEY_LAST_CONTENT_ID, contentId)
                .putString(KEY_LAST_FILE_NAME, fileName)
                .apply();
    }

    private static String optStringOrNull(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "null";
        }
        return object.optString(key, "null");
    }

    private static boolean hasUrl(JSONObject object) {
        return object != null && !object.isNull("url") && object.optString("url", "").trim().length() > 0;
    }

    private static void downloadToFile(String url, File file, String label) throws IOException {
        boolean isEink = label.startsWith("E-ink");
        if (isEink) {
            Log.i(TAG, label + " download opening url=" + url + " image_path=" + file.getAbsolutePath());
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "image/*");

        int code = connection.getResponseCode();
        if (isEink) {
            Log.i(TAG, label + " download HTTP response code=" + code
                    + " content_type=" + connection.getContentType()
                    + " content_length=" + connection.getContentLength());
        }
        if (code < 200 || code >= 300) {
            String body = readBody(connection.getErrorStream());
            if (isEink) {
                Log.e(TAG, label + " download HTTP failure code=" + code + " body=" + body);
            }
            connection.disconnect();
            throw new IOException(label + " request failed code=" + code + " body=" + body);
        }

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            long totalBytes = 0;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                totalBytes += count;
            }
            if (isEink) {
                Log.i(TAG, label + " file write success image_path=" + file.getAbsolutePath()
                        + " bytes=" + totalBytes
                        + " exists=" + file.isFile()
                        + " size=" + file.length());
            }
        } catch (IOException e) {
            if (isEink) {
                Log.e(TAG, label + " file write failure image_path=" + file.getAbsolutePath(), e);
            }
            throw e;
        } finally {
            connection.disconnect();
            if (isEink) {
                Log.i(TAG, label + " download connection closed url=" + url);
            }
        }
    }

    private static void makeServiceReadable(File file) {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.setExecutable(true, false);
            parent.setReadable(true, false);
        }
        file.setReadable(true, false);
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

    private static String extensionFrom(String fileName) {
        int query = fileName.indexOf('?');
        if (query >= 0) {
            fileName = fileName.substring(0, query);
        }
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".img";
        }
        return fileName.substring(dot);
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    interface LcdContentListener {
        void onLcdContentReady(String contentId, File file);
    }

    interface EinkContentListener {
        int onEinkContentReady(String contentId, File file);
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
