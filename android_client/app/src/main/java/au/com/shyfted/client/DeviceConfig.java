package au.com.shyfted.client;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

final class DeviceConfig {
    static final String DEFAULT_DEVICE_NAME = "Petey";
    static final String DEFAULT_DEVICE_ID = "petey_001";
    static final String DEFAULT_CMS_URL = "https://cms.shyfted.com.au";

    private static final String PREFS_NAME = "shyfted_device_config";
    private static final String CONFIG_FILE_NAME = "shyfted-client.properties";
    private static final String KEY_DEVICE_NAME = "device.name";
    private static final String KEY_DEVICE_ID = "device.id";
    private static final String KEY_CMS_URL = "cms.url";

    final String deviceName;
    final String deviceId;
    final String cmsUrl;
    final String source;

    private DeviceConfig(String deviceName, String deviceId, String cmsUrl, String source) {
        this.deviceName = deviceName;
        this.deviceId = deviceId;
        this.cmsUrl = trimTrailingSlash(cmsUrl);
        this.source = source;
    }

    static DeviceConfig load(Context context, Intent intent) {
        DeviceConfig config = defaults();
        config = config.withPreferences(context);
        config = config.withPropertiesFile(context);
        config = config.withIntentOverrides(intent);
        return config;
    }

    File externalConfigFile(Context context) {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            directory = context.getFilesDir();
        }
        return new File(directory, CONFIG_FILE_NAME);
    }

    private static DeviceConfig defaults() {
        return new DeviceConfig(DEFAULT_DEVICE_NAME, DEFAULT_DEVICE_ID, DEFAULT_CMS_URL, "defaults");
    }

    private DeviceConfig withPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return merge(
                prefs.getString(KEY_DEVICE_NAME, null),
                prefs.getString(KEY_DEVICE_ID, null),
                prefs.getString(KEY_CMS_URL, null),
                "preferences"
        );
    }

    private DeviceConfig withPropertiesFile(Context context) {
        File file = externalConfigFile(context);
        if (!file.isFile()) {
            return this;
        }

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        } catch (IOException ignored) {
            return this;
        }

        return merge(
                properties.getProperty(KEY_DEVICE_NAME),
                properties.getProperty(KEY_DEVICE_ID),
                properties.getProperty(KEY_CMS_URL),
                file.getAbsolutePath()
        );
    }

    private DeviceConfig withIntentOverrides(Intent intent) {
        if (intent == null) {
            return this;
        }

        return merge(
                intent.getStringExtra(KEY_DEVICE_NAME),
                intent.getStringExtra(KEY_DEVICE_ID),
                intent.getStringExtra(KEY_CMS_URL),
                "intent"
        );
    }

    private DeviceConfig merge(String newDeviceName, String newDeviceId, String newCmsUrl, String newSource) {
        String mergedDeviceName = valueOrDefault(newDeviceName, deviceName);
        String mergedDeviceId = valueOrDefault(newDeviceId, deviceId);
        String mergedCmsUrl = valueOrDefault(newCmsUrl, cmsUrl);
        String mergedSource = source;

        if (!mergedDeviceName.equals(deviceName)
                || !mergedDeviceId.equals(deviceId)
                || !trimTrailingSlash(mergedCmsUrl).equals(cmsUrl)) {
            mergedSource = newSource;
        }

        return new DeviceConfig(mergedDeviceName, mergedDeviceId, mergedCmsUrl, mergedSource);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        value = value.trim();
        return value.length() == 0 ? defaultValue : value;
    }

    private static String trimTrailingSlash(String value) {
        value = valueOrDefault(value, DEFAULT_CMS_URL);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
