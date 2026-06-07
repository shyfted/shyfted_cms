package au.com.shyfted.client;

import java.net.URI;
import java.net.URISyntaxException;

final class CmsEndpoints {
    private final String cmsBaseUrl;
    private final String deviceId;

    CmsEndpoints(String cmsBaseUrl, String deviceId) {
        this.cmsBaseUrl = trimTrailingSlash(cmsBaseUrl);
        this.deviceId = deviceId;
    }

    String launchUrl() {
        return cmsBaseUrl;
    }

    String configUrl() {
        return cmsBaseUrl + "/device/" + deviceId + "/config";
    }

    String heartbeatUrl() {
        return cmsBaseUrl + "/device/" + deviceId + "/heartbeat";
    }

    String resolveContentUrl(String url) throws URISyntaxException {
        return new URI(cmsBaseUrl + "/").resolve(url).toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.length() == 0) {
            return DeviceConfig.DEFAULT_CMS_URL;
        }

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }
}
