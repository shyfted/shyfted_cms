package au.com.shyfted.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity {
    private static final String EXTRA_EPD_PROBE_CALL = "epd_probe_call";
    private static final String EXTRA_EPD_PROBE_PATH = "epd_probe_path";
    private static final String EXTRA_EPD_PROBE_ONLY = "epd_probe_only";
    private static final String DEFAULT_EPD_PROBE_PATH = "/sdcard/Download/shyfted_epd_test.png";

    private static final int COLOR_BLACK = Color.rgb(5, 7, 11);
    private static final int COLOR_BLUE = Color.rgb(76, 140, 228);
    private static final int COLOR_YELLOW = Color.rgb(248, 222, 34);
    private static final int COLOR_TEXT = Color.rgb(246, 247, 251);
    private static final int COLOR_MUTED = Color.rgb(215, 222, 234);

    private WebView webView;
    private ImageView lcdImageView;
    private TextView batteryTextView;
    private View splashView;
    private View errorView;
    private CmsEndpoints endpoints;
    private DeviceConfig deviceConfig;
    private ShyftedDeviceClient deviceClient;
    private PeteyEinkServiceProbe peteyEinkServiceProbe;
    private boolean mainFrameLoadFailed;
    private boolean batteryPulseBright = true;
    private Integer lastBatteryPercent;
    private Boolean lastBatteryCharging;
    private Integer lastBatteryPlugged;
    private boolean batteryUnavailableLogged;
    private final Handler batteryPulseHandler = new Handler(Looper.getMainLooper());
    private final Runnable batteryPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (batteryTextView == null || batteryTextView.getVisibility() != View.VISIBLE) {
                return;
            }

            batteryPulseBright = !batteryPulseBright;
            batteryTextView.animate()
                    .alpha(batteryPulseBright ? 1.0f : 0.55f)
                    .setDuration(450)
                    .start();
            batteryPulseHandler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryPercent(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        deviceConfig = DeviceConfig.load(this, getIntent());
        endpoints = new CmsEndpoints(deviceConfig.cmsUrl, deviceConfig.deviceId);
        DisplayMetrics displayMetrics = currentDisplayMetrics();
        deviceClient = new ShyftedDeviceClient(
                this,
                endpoints,
                DeviceSpec.peteyLcdDevice(deviceConfig, displayMetrics.widthPixels, displayMetrics.heightPixels),
                this::showLcdContent,
                this::sendEinkContent
        );
        peteyEinkServiceProbe = new PeteyEinkServiceProbe(this);
        Log.i(ShyftedDeviceClient.TAG, "Loaded device config source=" + deviceConfig.source
                + " deviceName=" + deviceConfig.deviceName
                + " deviceId=" + deviceConfig.deviceId
                + " cmsUrl=" + deviceConfig.cmsUrl
                + " display=" + displayMetrics.widthPixels + "x" + displayMetrics.heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BLACK);

        lcdImageView = createLcdImageView();
        webView = createWebView();
        batteryTextView = createBatteryTextView();
        splashView = createStatusView(
                getString(R.string.loading_title),
                getString(R.string.loading_message),
                deviceConfig.deviceName,
                false
        );
        errorView = createErrorView();
        errorView.setVisibility(View.GONE);

        root.addView(lcdImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(splashView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(errorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        FrameLayout.LayoutParams batteryParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT
        );
        batteryParams.setMargins(0, dp(8), dp(10), 0);
        root.addView(batteryTextView, batteryParams);

        setContentView(root);
        enterFullScreen();
        attemptEnableAndroidBatteryPercentage();
        startBatteryOverlayServiceIfAllowed();
        refreshBatteryStateOnce();
        showLastGoodLcdContent();
        peteyEinkServiceProbe.start();
        handleEpdProbeIntent(getIntent());
        if (!isEpdProbeOnly(getIntent())) {
            deviceClient.start();
        } else {
            Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE probe-only launch: CMS client start skipped");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullScreen();
        registerBatteryReceiver();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        unregisterBatteryReceiver();
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (deviceClient != null) {
            deviceClient.stop();
            deviceClient = null;
        }
        if (peteyEinkServiceProbe != null) {
            peteyEinkServiceProbe.stop();
            peteyEinkServiceProbe = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        super.onBackPressed();
    }

    private ImageView createLcdImageView() {
        ImageView view = new ImageView(this);
        view.setBackgroundColor(COLOR_BLACK);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setAdjustViewBounds(false);
        view.setVisibility(View.GONE);
        return view;
    }

    private TextView createBatteryTextView() {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setBackgroundColor(Color.argb(170, 0, 0, 0));
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        view.setMinWidth(dp(76));
        view.setVisibility(View.GONE);
        view.setOnLongClickListener(v -> {
            showAdminControls();
            return true;
        });
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(COLOR_BLACK);
        view.setVisibility(View.GONE);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!mainFrameLoadFailed) {
                    showWebView();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showErrorView();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    showErrorView();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showErrorView();
            }
        });

        return view;
    }

    private View createErrorView() {
        return createStatusView(
                getString(R.string.offline_title),
                getString(R.string.offline_message),
                deviceConfig.deviceName + " - " + deviceConfig.deviceId,
                true
        );
    }

    private View createStatusView(String titleText, String messageText, String detailText, boolean includeRetry) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(COLOR_BLACK);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.shyfted_avatar);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("Shyfted");

        TextView tagline = new TextView(this);
        tagline.setText(getString(R.string.tagline));
        tagline.setTextColor(COLOR_YELLOW);
        tagline.setTextSize(14);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, 26, 0, 12);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextColor(COLOR_MUTED);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 14, 0, 14);

        TextView detail = new TextView(this);
        detail.setText(detailText + "\n" + deviceConfig.cmsUrl);
        detail.setTextColor(Color.rgb(150, 160, 178));
        detail.setTextSize(12);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, 0, 0, includeRetry ? 28 : 0);

        layout.addView(logo, new LinearLayout.LayoutParams(
                dp(132),
                dp(132)
        ));
        layout.addView(tagline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        layout.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        if (includeRetry) {
            Button retry = new Button(this);
            retry.setText(getString(R.string.retry_label));
            retry.setAllCaps(false);
            retry.setTextColor(COLOR_TEXT);
            retry.setBackgroundColor(COLOR_BLUE);
            retry.setOnClickListener(v -> retryLoad());

            layout.addView(retry, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        return layout;
    }

    private void retryLoad() {
        mainFrameLoadFailed = false;
        showWebView();
        webView.loadUrl(endpoints.launchUrl());
    }

    private void showWebView() {
        lcdImageView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        splashView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        enterFullScreen();
    }

    private void showErrorView() {
        mainFrameLoadFailed = true;
        lcdImageView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        splashView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        enterFullScreen();
    }

    private void showLastGoodLcdContent() {
        File lastGood = deviceClient.lastGoodLcdImage();
        String contentId = deviceClient.lastGoodLcdContentId();
        if (lastGood != null && contentId != null) {
            updateLcdDisplay(contentId, lastGood);
        }
    }

    private void showLcdContent(String contentId, File file) {
        runOnUiThread(() -> updateLcdDisplay(contentId, file));
    }

    private int sendEinkContent(String contentId, File file) {
        Log.i(ShyftedDeviceClient.TAG, "Sending e-ink content_id=" + contentId
                + " image_path=" + file.getAbsolutePath());
        int returnCode = peteyEinkServiceProbe.sendImage(file.getAbsolutePath());
        Log.i(ShyftedDeviceClient.TAG, "E-ink sendImage return_code=" + returnCode
                + " content_id=" + contentId
                + " image_path=" + file.getAbsolutePath());
        return returnCode;
    }

    private void handleEpdProbeIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_EPD_PROBE_CALL)) {
            return;
        }

        String call = intent.getStringExtra(EXTRA_EPD_PROBE_CALL);
        String path = intent.getStringExtra(EXTRA_EPD_PROBE_PATH);
        if (path == null || path.trim().isEmpty()) {
            path = DEFAULT_EPD_PROBE_PATH;
        }
        Log.i(ShyftedDeviceClient.TAG, "EPD_VENDOR_PROBE requested call=" + call
                + " path=" + path
                + " probe_only=" + isEpdProbeOnly(intent));
        peteyEinkServiceProbe.runVendorProbeCall(call, path);
    }

    private static boolean isEpdProbeOnly(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_EPD_PROBE_ONLY, false);
    }

    private void updateLcdDisplay(String contentId, File file) {
        lcdImageView.setImageURI(null);
        lcdImageView.setImageURI(android.net.Uri.fromFile(file));
        lcdImageView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        splashView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        Log.i(ShyftedDeviceClient.TAG, "Display updated with content_id=" + contentId);
        enterFullScreen();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void registerBatteryReceiver() {
        Intent batteryStatus = registerReceiver(
                batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        updateBatteryPercent(batteryStatus);
    }

    private void refreshBatteryStateOnce() {
        Intent batteryStatus = registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        updateBatteryPercent(batteryStatus);
    }

    private void unregisterBatteryReceiver() {
        stopBatteryPulse();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    private void updateBatteryPercent(Intent batteryStatus) {
        if (batteryStatus == null || batteryTextView == null) {
            logBatteryUnavailableIfChanged("battery intent unavailable");
            hideBatteryOverlay();
            return;
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        if (level < 0 || scale <= 0 || status < 0 || plugged < 0) {
            logBatteryUnavailableIfChanged("battery fields unavailable"
                    + " level=" + level
                    + " scale=" + scale
                    + " status=" + status
                    + " plugged=" + plugged);
            hideBatteryOverlay();
            return;
        }

        int percent = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        logBatteryIfChanged(percent, charging, plugged);
        if (deviceClient != null) {
            deviceClient.updateBatteryState(percent, charging, plugged);
        }
        batteryTextView.setText(charging ? percent + "% CHG" : percent + "%");
        batteryTextView.setVisibility(View.VISIBLE);

        if (charging) {
            startBatteryPulse();
        } else {
            stopBatteryPulse();
        }
    }

    private void hideBatteryOverlay() {
        if (batteryTextView == null) {
            return;
        }

        stopBatteryPulse();
        if (deviceClient != null) {
            deviceClient.clearBatteryState();
        }
        batteryTextView.setVisibility(View.GONE);
    }

    private void logBatteryIfChanged(int percent, boolean charging, int plugged) {
        boolean changed = lastBatteryPercent == null
                || lastBatteryPercent != percent
                || lastBatteryCharging == null
                || lastBatteryCharging != charging
                || lastBatteryPlugged == null
                || lastBatteryPlugged != plugged;
        if (!changed) {
            return;
        }

        lastBatteryPercent = percent;
        lastBatteryCharging = charging;
        lastBatteryPlugged = plugged;
        batteryUnavailableLogged = false;
        Log.i(ShyftedDeviceClient.TAG, "Battery state changed percentage=" + percent
                + " charging=" + charging
                + " plugged=" + plugged);
    }

    private void logBatteryUnavailableIfChanged(String reason) {
        if (!batteryUnavailableLogged) {
            Log.w(ShyftedDeviceClient.TAG, "Battery state unavailable; hiding overlay reason=" + reason);
        }
        batteryUnavailableLogged = true;
        lastBatteryPercent = null;
        lastBatteryCharging = null;
        lastBatteryPlugged = null;
    }

    private void attemptEnableAndroidBatteryPercentage() {
        try {
            boolean applied = Settings.System.putInt(
                    getContentResolver(),
                    "status_bar_show_battery_percent",
                    1
            );
            Log.i(ShyftedDeviceClient.TAG, "Android system battery percentage setting attempted success=" + applied);
        } catch (RuntimeException e) {
            Log.i(ShyftedDeviceClient.TAG, "Android system battery percentage setting unavailable", e);
        }

        try {
            boolean applied = Settings.Secure.putInt(
                    getContentResolver(),
                    "status_bar_show_battery_percent",
                    1
            );
            Log.i(ShyftedDeviceClient.TAG, "Android secure battery percentage setting attempted success=" + applied);
        } catch (RuntimeException e) {
            Log.i(ShyftedDeviceClient.TAG, "Android secure battery percentage setting unavailable", e);
        }
    }

    private void startBatteryOverlayServiceIfAllowed() {
        if (!BatteryOverlayService.canDrawOverlays(this)) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay not started: SYSTEM_ALERT_WINDOW permission missing");
            return;
        }

        Intent intent = new Intent(this, BatteryOverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay service start failed", e);
        }
    }

    private void showAdminControls() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        layout.setPadding(padding, padding, padding, padding);

        TextView status = new TextView(this);
        status.setTextColor(Color.rgb(35, 39, 47));
        status.setTextSize(14);
        status.setText(BatteryOverlayService.canDrawOverlays(this)
                ? "Overlay permission granted."
                : "Overlay permission missing.");
        status.setPadding(0, 0, 0, dp(12));
        layout.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button androidSettings = createAdminButton("Open Android Settings");
        androidSettings.setOnClickListener(v -> openAndroidSettings());
        layout.addView(androidSettings);

        Button exitToAndroid = createAdminButton("Exit to Android");
        exitToAndroid.setOnClickListener(v -> exitToAndroid());
        layout.addView(exitToAndroid);

        Button overlayPermission = createAdminButton("Overlay permission");
        overlayPermission.setOnClickListener(v -> openOverlaySettings());
        layout.addView(overlayPermission);

        Button checkSu = createAdminButton("Check root");
        checkSu.setOnClickListener(v -> runAdminAction("Checking root", () -> {
            boolean available = DevicePowerController.isSuAvailable();
            return available ? "su is available." : "su is unavailable.";
        }));
        layout.addView(checkSu);

        Button restart = createAdminButton("Restart device");
        restart.setOnClickListener(v -> runAdminAction("Restarting", () ->
                DevicePowerController.restart(this).message
        ));
        layout.addView(restart);

        Button powerOff = createAdminButton("Power off device");
        powerOff.setOnClickListener(v -> runAdminAction("Powering off", () ->
                DevicePowerController.powerOff(this).message
        ));
        layout.addView(powerOff);

        new AlertDialog.Builder(this)
                .setTitle("Petey controls")
                .setView(layout)
                .setNegativeButton("Close", null)
                .show();
    }

    private Button createAdminButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private void openAndroidSettings() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "Android settings unavailable", e);
            Toast.makeText(this, "Android settings unavailable on this build.", Toast.LENGTH_LONG).show();
        }
    }

    private void exitToAndroid() {
        stopService(new Intent(this, BatteryOverlayService.class));
        unregisterBatteryReceiver();
        if (deviceClient != null) {
            deviceClient.stop();
        }
        if (webView != null) {
            webView.onPause();
        }

        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "Android settings unavailable during technician exit", e);
        }

        String message = "Exited to Android. Shyfted remains the HOME app and may resume when Home is pressed.";
        Log.i(ShyftedDeviceClient.TAG, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Overlay permission is automatic on this Android version.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "Overlay settings unavailable", e);
            Toast.makeText(this, "Overlay settings unavailable on this build.", Toast.LENGTH_LONG).show();
        }
    }

    private void runAdminAction(String pendingMessage, AdminAction action) {
        Toast.makeText(this, pendingMessage, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                message = action.run();
            } catch (RuntimeException e) {
                Log.w(ShyftedDeviceClient.TAG, "Admin action failed", e);
                message = "Power control unavailable on this build without root/system permission.";
            }
            String finalMessage = message;
            Log.i(ShyftedDeviceClient.TAG, finalMessage);
            runOnUiThread(() -> Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show());
        }, "shyfted-admin-action").start();
    }

    private interface AdminAction {
        String run();
    }

    private void startBatteryPulse() {
        batteryPulseHandler.removeCallbacks(batteryPulseRunnable);
        batteryPulseBright = true;
        batteryTextView.setAlpha(1.0f);
        batteryPulseHandler.postDelayed(batteryPulseRunnable, 1000);
    }

    private void stopBatteryPulse() {
        batteryPulseHandler.removeCallbacks(batteryPulseRunnable);
        if (batteryTextView != null) {
            batteryTextView.animate().cancel();
            batteryTextView.setAlpha(1.0f);
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics currentDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    private void enterFullScreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
