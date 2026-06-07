package au.com.shyfted.client;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
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

import java.io.File;

public final class MainActivity extends Activity {
    private static final int COLOR_BLACK = Color.rgb(5, 7, 11);
    private static final int COLOR_BLUE = Color.rgb(76, 140, 228);
    private static final int COLOR_YELLOW = Color.rgb(248, 222, 34);
    private static final int COLOR_TEXT = Color.rgb(246, 247, 251);
    private static final int COLOR_MUTED = Color.rgb(215, 222, 234);

    private WebView webView;
    private ImageView lcdImageView;
    private View splashView;
    private View errorView;
    private CmsEndpoints endpoints;
    private DeviceConfig deviceConfig;
    private ShyftedDeviceClient deviceClient;
    private boolean mainFrameLoadFailed;

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
                this::showLcdContent
        );
        Log.i(ShyftedDeviceClient.TAG, "Loaded device config source=" + deviceConfig.source
                + " deviceName=" + deviceConfig.deviceName
                + " deviceId=" + deviceConfig.deviceId
                + " cmsUrl=" + deviceConfig.cmsUrl
                + " display=" + displayMetrics.widthPixels + "x" + displayMetrics.heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BLACK);

        lcdImageView = createLcdImageView();
        webView = createWebView();
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

        setContentView(root);
        enterFullScreen();
        showLastGoodLcdContent();
        deviceClient.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullScreen();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
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
