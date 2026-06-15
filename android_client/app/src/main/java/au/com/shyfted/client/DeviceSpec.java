package au.com.shyfted.client;

import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

final class DeviceSpec {
    static final String CLIENT_VERSION = "android-0.1.0";
    private static final int PETEY_LCD_WIDTH = 1920;
    private static final int PETEY_LCD_HEIGHT = 1080;
    private static final int PETEY_EINK_WIDTH = 1200;
    private static final int PETEY_EINK_HEIGHT = 1600;

    private DeviceSpec() {
    }

    static Map<String, Object> peteyLcdDevice(DeviceConfig config, int width, int height) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("device_id", config.deviceId);
        spec.put("name", config.deviceName);
        spec.put("platform", "Android");
        spec.put("model", "RK3566 / " + Build.MODEL);
        spec.put("hostname", Build.MODEL);
        spec.put("client_version", CLIENT_VERSION);

        Map<String, Object> lcd = new LinkedHashMap<>();
        lcd.put("type", "lcd");
        lcd.put("width", width > 0 ? width : PETEY_LCD_WIDTH);
        lcd.put("height", height > 0 ? height : PETEY_LCD_HEIGHT);
        lcd.put("color", true);
        lcd.put("orientation", 0);
        lcd.put("rotation", 0);

        Map<String, Object> eink = new LinkedHashMap<>();
        eink.put("type", "eink");
        eink.put("width", PETEY_EINK_WIDTH);
        eink.put("height", PETEY_EINK_HEIGHT);
        eink.put("color", false);
        eink.put("orientation", 0);
        eink.put("rotation", 0);
        eink.put("driver", "geniatech.el133sdk.epdService");

        Map<String, Object> screens = new LinkedHashMap<>();
        screens.put("lcd", lcd);
        screens.put("eink", eink);
        spec.put("screens", screens);

        return spec;
    }
}
