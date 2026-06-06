package au.com.shyfted.client;

import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

final class DeviceSpec {
    static final String CLIENT_VERSION = "android-0.1.0";

    private DeviceSpec() {
    }

    static Map<String, Object> peteyLcdDevice(DeviceConfig config, int width, int height) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", config.deviceName);
        spec.put("hostname", Build.MODEL);
        spec.put("client_version", CLIENT_VERSION);

        Map<String, Object> lcd = new LinkedHashMap<>();
        lcd.put("type", "lcd");
        lcd.put("width", width);
        lcd.put("height", height);
        lcd.put("color", true);
        lcd.put("orientation", 0);
        lcd.put("rotation", 0);

        Map<String, Object> screens = new LinkedHashMap<>();
        screens.put("lcd", lcd);
        spec.put("screens", screens);

        return spec;
    }
}
