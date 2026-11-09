package chat.lumiverse.prototype;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The public Android nightly release contract, independent of the connected server. */
public final class AppUpdatePolicy {
    public static final String REPOSITORY = "https://github.com/prolix-oc/Lumiverse-AndroidApp";
    public static final String RELEASES_API = "https://api.github.com/repos/prolix-oc/Lumiverse-AndroidApp/releases?per_page=30";
    public static final String APK_NAME = "lumiverse-tauri-arm64.apk";
    public static final long CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    public static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L;
    public static final long REMINDER_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern TAG = Pattern.compile("android-v([0-9]+\\.[0-9]+\\.[0-9]+-nightly\\.[0-9]{8}\\.[1-9][0-9]*)");
    private static final Pattern CODE = Pattern.compile("(?m)^- Version code: ([1-9][0-9]{0,9})\\r?$");

    public static final class Release {
        public final long versionCode;
        public final String version;
        public final String url;

        private Release(long versionCode, String version, String url) {
            this.versionCode = versionCode;
            this.version = version;
            this.url = url;
        }
    }

    public static Release candidate(String tag, String body, boolean draft, String publishedAt,
            String assetName, String assetState, long assetSize, String assetUrl) {
        if (draft || tag == null || body == null || publishedAt == null) return null;
        Matcher version = TAG.matcher(tag);
        Matcher code = CODE.matcher(body);
        if (!version.matches() || !code.find()) return null;
        try {
            Instant.parse(publishedAt);
            long versionCode = Long.parseLong(code.group(1));
            if (versionCode > 2_100_000_000L || code.find()) return null;
            if (!APK_NAME.equals(assetName) || !"uploaded".equals(assetState) || assetSize <= 0
                    || !(REPOSITORY + "/releases/download/" + tag + "/" + APK_NAME).equals(assetUrl)) return null;
            return new Release(versionCode, version.group(1), REPOSITORY + "/releases/tag/" + tag);
        } catch (RuntimeException invalid) { return null; }
    }

    public static boolean shouldCheck(long lastAttempt, long now, boolean failed) {
        return lastAttempt < 0 || now < lastAttempt
                || now - lastAttempt >= (failed ? RETRY_INTERVAL_MS : CHECK_INTERVAL_MS);
    }

    public static boolean shouldNotify(long availableCode, long installedCode, long notifiedCode, long notifiedAt, long now) {
        return availableCode > installedCode && (availableCode != notifiedCode
                || now < notifiedAt || now - notifiedAt >= REMINDER_INTERVAL_MS);
    }

    private AppUpdatePolicy() { }
}
