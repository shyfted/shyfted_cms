package au.com.shyfted.client;

import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

final class DeviceSpec {
    static final String DEFAULT_DEVICE_ID = "android_001";
    static final String CLIENT_VERSION = "android-0.1.0";

    private DeviceSpec() {
    }

    static Map<String, Object> androidWebViewDevice() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "Shyfted Android Client");
        spec.put("hostname", Build.MODEL);
        spec.put("client_version", CLIENT_VERSION);

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("type", "webview");
        web.put("width", 0);
        web.put("height", 0);
        web.put("color", true);
        web.put("orientation", 0);
        web.put("rotation", 0);

        Map<String, Object> screens = new LinkedHashMap<>();
        screens.put("web", web);
        spec.put("screens", screens);

        return spec;
    }
}
