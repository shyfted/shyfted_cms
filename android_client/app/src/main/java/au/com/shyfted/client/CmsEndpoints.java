package au.com.shyfted.client;

final class CmsEndpoints {
    static final String DEFAULT_CMS_URL = "https://cms.shyfted.com.au";

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

    private static String trimTrailingSlash(String value) {
        if (value == null || value.length() == 0) {
            return DEFAULT_CMS_URL;
        }

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }
}
