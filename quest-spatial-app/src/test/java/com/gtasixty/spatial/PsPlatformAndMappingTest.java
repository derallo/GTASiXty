package com.gtasixty.spatial;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pure-JVM unit tests for console platform switching, PSN Account ID encoding/parsing,
 * PlayStation console store JSON serialization, DualSense/DS4 gamepad mapping, and
 * libchiaki discovery/registration string parsers.
 */
public class PsPlatformAndMappingTest {

    @Test
    public void consolePlatform_defaultsToXboxAndRoundTrips() {
        assertEquals(ConsolePlatform.XBOX, ConsolePlatform.fromPrefValue(null));
        assertEquals(ConsolePlatform.XBOX, ConsolePlatform.fromPrefValue(""));
        assertEquals(ConsolePlatform.XBOX, ConsolePlatform.fromPrefValue("xbox"));
        assertEquals(ConsolePlatform.PLAYSTATION, ConsolePlatform.fromPrefValue("playstation"));
        assertEquals(ConsolePlatform.PLAYSTATION, ConsolePlatform.fromPrefValue("ps5"));
        assertEquals("xbox", ConsolePlatform.prefValue(ConsolePlatform.XBOX));
        assertEquals("playstation", ConsolePlatform.prefValue(ConsolePlatform.PLAYSTATION));
    }

    @Test
    public void psnAccountId_parsesBase64AndLittleEndianDecimal() {
        // 0x0102030405060708 = 72623859790382856 decimal -> LE bytes: 08 07 06 05 04 03 02 01
        final byte[] fromDecimal = ConsolePlatform.parsePsnAccountId("72623859790382856");
        assertNotNull(fromDecimal);
        assertArrayEquals(new byte[]{8, 7, 6, 5, 4, 3, 2, 1}, fromDecimal);

        final String b64 = ConsolePlatform.encodePsnAccountIdBase64(fromDecimal);
        final byte[] fromB64 = ConsolePlatform.parsePsnAccountId(b64);
        assertArrayEquals(fromDecimal, fromB64);

        assertNull(ConsolePlatform.parsePsnAccountId(""));
        assertNull(ConsolePlatform.parsePsnAccountId("not-valid-base64!"));
        assertNull(ConsolePlatform.parsePsnAccountId("AAAA")); // only 3 bytes
    }

    @Test
    public void oauthAndWakeHelpers_extractCodeAndWakeCredential() {
        final String url = "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=v3.abc123&cid=xyz";
        assertEquals("v3.abc123", ConsolePlatform.extractOAuthCodeFromRedirectUrl(url));
        assertNull(ConsolePlatform.extractOAuthCodeFromRedirectUrl("https://example.com/no-code"));

        final byte[] registKey16 = new byte[16];
        final byte[] ascii = "1a2b3c4d\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, registKey16, 0, 16);
        assertEquals(0x1a2b3c4dL, ConsolePlatform.parseRegistKeyWakeCredential(registKey16));
    }

    @Test
    public void psConsoleStore_serializesAndRestoresJsonList() {
        final PsConsoleStore.PsConsole ps5 = new PsConsoleStore.PsConsole(
                "192.168.1.55",
                "Living Room PS5",
                true,
                1000100,
                "aabbccddeeff",
                "31323334353637380000000000000000",
                "00112233445566778899aabbccddeeff",
                "CAcGBQQDAgE=");
        final PsConsoleStore.PsConsole ps4 = new PsConsoleStore.PsConsole(
                "192.168.1.60",
                "Bedroom PS4",
                false,
                1000,
                "112233445566",
                "61626364000000000000000000000000",
                "ffeeddccbbaa99887766554433221100",
                "CAcGBQQDAgE=");

        final String json = PsConsoleStore.toJsonArrayString(Arrays.asList(ps5, ps4));
        final List<PsConsoleStore.PsConsole> parsed = PsConsoleStore.fromJsonArrayString(json);
        assertEquals(2, parsed.size());
        assertEquals("192.168.1.55", parsed.get(0).host);
        assertEquals("Living Room PS5", parsed.get(0).nickname);
        assertTrue(parsed.get(0).isPs5);
        assertEquals("192.168.1.60", parsed.get(1).host);
        assertFalse(parsed.get(1).isPs5);
        assertEquals(16, parsed.get(0).registKeyBytes().length);
        assertEquals(16, parsed.get(0).morningBytes().length);
    }

    @Test
    public void psGamepadMapper_mapsButtonsSticksNonInvertedAndTriggers() {
        final PsGamepadMapper pad = new PsGamepadMapper();
        assertTrue(pad.applyKey(PsGamepadMapper.KEYCODE_BUTTON_A, true));
        assertTrue(pad.applyKey(PsGamepadMapper.KEYCODE_BUTTON_Y, true));
        assertTrue(pad.applyKey(PsGamepadMapper.KEYCODE_BUTTON_MODE, true));
        assertEquals(
                PsGamepadMapper.BUTTON_CROSS | PsGamepadMapper.BUTTON_PYRAMID | PsGamepadMapper.BUTTON_PS,
                pad.getButtons());

        assertTrue(pad.applyKey(PsGamepadMapper.KEYCODE_BUTTON_A, false));
        assertEquals(PsGamepadMapper.BUTTON_PYRAMID | PsGamepadMapper.BUTTON_PS, pad.getButtons());

        // Chiaki convention: Android stick down (+1.0) maps to positive (+32767), not inverted.
        pad.applyAxes(-0.5f, 1.0f, 0.25f, -1.0f, 0.0f, 0.5f, 0.8f, 0.0f, -1.0f, 1.0f);
        assertEquals((short) Math.round(-0.5f * 32767f), pad.getLx());
        assertEquals((short) 32767, pad.getLy());
        assertEquals((short) Math.round(0.25f * 32767f), pad.getRx());
        assertEquals((short) -32767, pad.getRy());
        assertEquals(204, pad.getL2()); // from AXIS_BRAKE = 0.8
        assertEquals(128, pad.getR2()); // from AXIS_RTRIGGER = 0.5
        assertTrue((pad.getButtons() & PsGamepadMapper.BUTTON_DPAD_LEFT) != 0);
        assertTrue((pad.getButtons() & PsGamepadMapper.BUTTON_DPAD_DOWN) != 0);

        pad.reset();
        assertEquals(0, pad.getButtons());
        assertEquals(0, pad.getL2());
        assertEquals(0, pad.getLx());
    }

    @Test
    public void psChiakiNative_parsesDiscoveryAndRegistrationResponses() {
        final List<PsChiakiNative.DiscoveredConsole> consoles = PsChiakiNative.parseDiscoveryResults(
                new String[]{
                        "192.168.1.50|PS5-LivingRoom|PS5|1|AABBCCDDEEFF|09.60|1|Gran Turismo 7",
                        "192.168.1.51|PS4-Den|PS4|2|112233445566|11.50|0|"
                });
        assertEquals(2, consoles.size());
        assertEquals("192.168.1.50", consoles.get(0).hostAddr);
        assertTrue(consoles.get(0).isPs5);
        assertEquals("Ready", consoles.get(0).stateLabel());
        assertEquals("Gran Turismo 7", consoles.get(0).runningApp);
        assertFalse(consoles.get(1).isPs5);
        assertEquals("Rest Mode", consoles.get(1).stateLabel());

        final PsChiakiNative.RegistResult ok = PsChiakiNative.parseRegistrationResult(
                "OK|1000100|My PS5|aabbccddeeff|3132|abcd");
        assertTrue(ok.ok);
        assertEquals(1000100, ok.target);
        assertEquals("My PS5", ok.nickname);
        assertEquals("3132", ok.registKeyHex);
        assertEquals("abcd", ok.morningHex);

        final PsChiakiNative.RegistResult err = PsChiakiNative.parseRegistrationResult("ERR|Wrong PIN");
        assertFalse(err.ok);
        assertEquals("Wrong PIN", err.error);
    }
}
