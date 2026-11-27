package com.gtasixty.spatial;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * JVM unit tests for simplified GPU enhance mode mapping and GitHub update parsing/versioning.
 */
public class UiAndUpdaterTest {

    @Test
    public void versionComparison_handlesSemverAndPrefix() {
        assertTrue(AppUpdateParser.compareVersionNames("2.2", "2.1") > 0);
        assertTrue(AppUpdateParser.compareVersionNames("2.10", "2.2") > 0);
        assertEquals(0, AppUpdateParser.compareVersionNames("v2.1", "2.1"));
        assertEquals(0, AppUpdateParser.compareVersionNames("2.1-spatial", "2.1"));
        assertTrue(AppUpdateParser.compareVersionNames("2.0", "2.1") < 0);
    }

    @Test
    public void updateResolver_mergesHistoryForNewerVersionJson() {
        final String versionJson = "{"
                + "\"versionCode\": 8,"
                + "\"versionName\": \"2.3\","
                + "\"apkUrl\": \"https://example.com/v2.3.apk\","
                + "\"notes\": \"v2.3 summary\","
                + "\"history\": ["
                + "  {\"versionCode\": 8, \"versionName\": \"2.3\", \"notes\": \"New Xbox Wi-Fi default & hover hints\"},"
                + "  {\"versionCode\": 7, \"versionName\": \"2.2\", \"notes\": \"Auto-hide menu on controller\"},"
                + "  {\"versionCode\": 6, \"versionName\": \"2.1\", \"notes\": \"Old release\"}"
                + "]"
                + "}";

        final AppUpdateParser.UpdateInfo info = AppUpdateParser.resolve(versionJson, null, 6L, "2.1");
        assertTrue(info.updateAvailable);
        assertEquals("2.3", info.versionName);
        assertEquals(8L, info.versionCode);
        assertEquals("https://example.com/v2.3.apk", info.apkUrl);
        assertTrue(info.recentNotes.contains("v2.3"));
        assertTrue(info.recentNotes.contains("New Xbox Wi-Fi default"));
        assertTrue(info.recentNotes.contains("v2.2"));
        assertTrue(info.recentNotes.contains("Auto-hide menu on controller"));
        assertFalse(info.recentNotes.contains("Old release"));

        final AppUpdateParser.UpdateInfo same = AppUpdateParser.resolve(versionJson, null, 8L, "2.3");
        assertFalse(same.updateAvailable);
    }

    @Test
    public void updateResolver_picksNewerGithubReleaseAndSkipsDrafts() {
        final String versionJson = "{"
                + "\"versionCode\": 6,"
                + "\"versionName\": \"2.1\","
                + "\"apkUrl\": \"https://example.com/v2.1.apk\","
                + "\"notes\": \"v2.1 notes\""
                + "}";

        final String releasesJson = "["
                + "  {\"tag_name\": \"v2.4-beta\", \"prerelease\": true, \"body\": \"Pre\", \"assets\": [{\"name\": \"app.apk\", \"browser_download_url\": \"https://example.com/pre.apk\"}]},"
                + "  {\"tag_name\": \"v2.3\", \"draft\": false, \"prerelease\": false, \"body\": \"### Enhancements\\n**Better UI**\", \"assets\": [{\"name\": \"GTA-Sixty-Quest3.apk\", \"browser_download_url\": \"https://example.com/gh-2.3.apk\"}]},"
                + "  {\"tag_name\": \"v2.2\", \"draft\": false, \"prerelease\": false, \"body\": \"Faster Xbox Wi-Fi\", \"assets\": [{\"name\": \"GTA-Sixty-Quest3.apk\", \"browser_download_url\": \"https://example.com/gh-2.2.apk\"}]}"
                + "]";

        final AppUpdateParser.UpdateInfo info = AppUpdateParser.resolve(versionJson, releasesJson, 6L, "2.1");
        assertTrue(info.updateAvailable);
        assertEquals("2.3", info.versionName);
        assertEquals("https://example.com/gh-2.3.apk", info.apkUrl);
        assertTrue(info.recentNotes.contains("v2.3"));
        assertTrue(info.recentNotes.contains("Better UI"));
        assertTrue(info.recentNotes.contains("v2.2"));
        assertTrue(info.recentNotes.contains("Faster Xbox Wi-Fi"));
        assertFalse(info.recentNotes.contains("v2.4-beta"));
    }

    @Test
    public void simpleEnhanceModes_mapAndCycleCleanly() {
        // Capture: Off (0) -> 60+ (4) -> 90+ (6) -> Off (0)
        assertEquals(0, CaptureProtocolHelper.normalizeCaptureEnhanceMode(0));
        assertEquals(4, CaptureProtocolHelper.normalizeCaptureEnhanceMode(1));
        assertEquals(4, CaptureProtocolHelper.normalizeCaptureEnhanceMode(4));
        assertEquals(6, CaptureProtocolHelper.normalizeCaptureEnhanceMode(5));
        assertEquals(6, CaptureProtocolHelper.normalizeCaptureEnhanceMode(8));

        assertEquals(4, CaptureProtocolHelper.nextCaptureEnhanceMode(0));
        assertEquals(6, CaptureProtocolHelper.nextCaptureEnhanceMode(4));
        assertEquals(0, CaptureProtocolHelper.nextCaptureEnhanceMode(6));

        assertEquals("Off", CaptureProtocolHelper.simpleCaptureEnhanceLabel(0));
        assertEquals("60+", CaptureProtocolHelper.simpleCaptureEnhanceLabel(4));
        assertEquals("90+", CaptureProtocolHelper.simpleCaptureEnhanceLabel(6));

        // Direct (Xbox Wi-Fi): Off (0) -> 60+ (4) -> 90+ (6) -> Off (0)
        assertEquals(0, CaptureProtocolHelper.normalizeDirectEnhanceMode(0));
        assertEquals(4, CaptureProtocolHelper.normalizeDirectEnhanceMode(2));
        assertEquals(4, CaptureProtocolHelper.normalizeDirectEnhanceMode(4));
        assertEquals(6, CaptureProtocolHelper.normalizeDirectEnhanceMode(5));
        assertEquals(6, CaptureProtocolHelper.normalizeDirectEnhanceMode(6));
        assertEquals(4, CaptureProtocolHelper.nextDirectEnhanceMode(0));
        assertEquals(6, CaptureProtocolHelper.nextDirectEnhanceMode(4));
        assertEquals(0, CaptureProtocolHelper.nextDirectEnhanceMode(6));
        assertEquals("Off", CaptureProtocolHelper.simpleDirectEnhanceLabel(0));
        assertEquals("60+", CaptureProtocolHelper.simpleDirectEnhanceLabel(4));
        assertEquals("90+", CaptureProtocolHelper.simpleDirectEnhanceLabel(6));
    }
}
