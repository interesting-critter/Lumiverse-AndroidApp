package chat.lumiverse.prototype;

public final class AppUpdatePolicyCheck {
    private static final String TAG = "android-v0.3.5-nightly.20261028.20";
    private static final String NOTES = "Nightly bundled Android ARM64 APK.\n\n- Version code: 1000000020\n- Build and checks: run";
    private static final String APK = AppUpdatePolicy.REPOSITORY + "/releases/download/" + TAG + "/" + AppUpdatePolicy.APK_NAME;

    public static void main(String[] args) {
        AppUpdatePolicy.Release release = candidate(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 270776203, APK);
        require(release != null && release.versionCode == 1000000020L, "published APK recognized");
        require("0.3.5-nightly.20261028.20".equals(release.version), "full build version retained");
        require((AppUpdatePolicy.REPOSITORY + "/releases/tag/" + TAG).equals(release.url), "official release page");
        require(candidate(TAG, NOTES.replace("\n", "\r\n"), false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK) != null, "CRLF release notes");

        // Scheduled and manually triggered rebuilds use the same base version;
        // the install version, rather than semver/date/commit changes, decides.
        require(AppUpdatePolicy.shouldNotify(release.versionCode, 1000000019L, 0, 0, 1), "same-day rebuild detected");
        require(!AppUpdatePolicy.shouldNotify(release.versionCode, release.versionCode, 0, 0, 1), "installed nightly stays quiet");
        require(!AppUpdatePolicy.shouldNotify(release.versionCode, 1000000021L, 0, 0, 1), "no downgrade notification");
        require(!AppUpdatePolicy.shouldNotify(release.versionCode, 1000000019L, release.versionCode, 1000, 1001), "dismissal survives restart");
        require(!AppUpdatePolicy.shouldNotify(release.versionCode, 1000000019L, release.versionCode, 1000, 1000 + AppUpdatePolicy.REMINDER_INTERVAL_MS - 1), "no repeated hourly prompt");
        require(AppUpdatePolicy.shouldNotify(release.versionCode, 1000000019L, release.versionCode, 1000, 1000 + AppUpdatePolicy.REMINDER_INTERVAL_MS), "daily reminder");
        require(AppUpdatePolicy.shouldNotify(1000000021L, 1000000019L, release.versionCode, 1000, 1001), "new build bypasses reminder delay");
        require(AppUpdatePolicy.shouldNotify(release.versionCode, 1000000019L, release.versionCode, 2000, 1000), "clock rollback cannot suppress forever");

        reject(TAG, NOTES, true, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "draft");
        reject(TAG, NOTES, false, "", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "unpublished");
        reject(TAG, NOTES, false, "null", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "missing publish timestamp");
        reject(TAG, NOTES, false, "yesterday", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "invalid publish timestamp");
        reject("v1.3.0", NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "backend release");
        reject("android-v0.3.5", NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "other Android channel");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", "desktop.apk", "uploaded", 1, APK, "other artifact");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME + ".json", "uploaded", 1, APK, "metadata is not the APK");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "starter", 1, APK, "unfinished upload");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 0, APK, "empty upload");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK.replace("https:", "http:"), "HTTP download");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK.replace("github.com", "example.com"), "external download");
        reject(TAG, NOTES, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK.replace(".20/", ".19/"), "wrong build asset");
        for (String code : new String[] { "", "0", "-1", "2100000001", "99999999999999999999", "1.5" }) {
            reject(TAG, "- Version code: " + code, false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "invalid install version " + code);
        }
        reject(TAG, NOTES + "\n- Version code: 1000000021", false, "2026-10-28T13:42:34Z", AppUpdatePolicy.APK_NAME, "uploaded", 1, APK, "ambiguous install version");

        require(AppUpdatePolicy.shouldCheck(-1, 0, false), "check on launch");
        require(!AppUpdatePolicy.shouldCheck(0, 1000, false), "resume storm throttled");
        require(AppUpdatePolicy.shouldCheck(0, AppUpdatePolicy.CHECK_INTERVAL_MS, false), "hourly success check");
        require(!AppUpdatePolicy.shouldCheck(0, AppUpdatePolicy.RETRY_INTERVAL_MS - 1, true), "offline retries throttled");
        require(AppUpdatePolicy.shouldCheck(0, AppUpdatePolicy.RETRY_INTERVAL_MS, true), "offline retries recover sooner");
    }

    private static AppUpdatePolicy.Release candidate(String tag, String body, boolean draft, String publishedAt,
            String name, String state, long size, String url) {
        return AppUpdatePolicy.candidate(tag, body, draft, publishedAt, name, state, size, url);
    }
    private static void reject(String tag, String body, boolean draft, String publishedAt,
            String name, String state, long size, String url, String reason) {
        require(candidate(tag, body, draft, publishedAt, name, state, size, url) == null, "reject " + reason);
    }
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
