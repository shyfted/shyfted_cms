package au.com.shyfted.client;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public final class BatteryOverlayService extends Service {
    private WindowManager windowManager;
    private TextView batteryView;
    private boolean receiverRegistered;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!canDrawOverlays()) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay unavailable: SYSTEM_ALERT_WINDOW permission missing");
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        batteryView = createBatteryView();
        try {
            windowManager.addView(batteryView, createLayoutParams());
        } catch (RuntimeException e) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay unavailable: unable to add overlay view", e);
            stopSelf();
            return;
        }
        Intent batteryStatus = registerReceiver(
                batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        );
        receiverRegistered = true;
        updateBattery(batteryStatus);
        Log.i(ShyftedDeviceClient.TAG, "Battery overlay service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!canDrawOverlays()) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay start skipped: SYSTEM_ALERT_WINDOW permission missing");
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already gone.
            }
            receiverRegistered = false;
        }
        if (windowManager != null && batteryView != null) {
            try {
                windowManager.removeView(batteryView);
            } catch (IllegalArgumentException ignored) {
                // View was already gone.
            }
        }
        batteryView = null;
        windowManager = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private boolean canDrawOverlays() {
        return canDrawOverlays(this);
    }

    private TextView createBatteryView() {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setBackgroundColor(Color.argb(165, 0, 0, 0));
        int horizontal = dp(8);
        int vertical = dp(4);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setMinWidth(dp(54));
        return view;
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.x = dp(10);
        params.y = dp(8);
        return params;
    }

    private void updateBattery(Intent batteryStatus) {
        if (batteryStatus == null || batteryView == null) {
            return;
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0 || status < 0) {
            Log.w(ShyftedDeviceClient.TAG, "Battery overlay update skipped: battery fields unavailable");
            batteryView.setVisibility(View.GONE);
            return;
        }

        int percent = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        batteryView.setText(charging ? percent + "% \u26A1" : percent + "%");
        batteryView.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
