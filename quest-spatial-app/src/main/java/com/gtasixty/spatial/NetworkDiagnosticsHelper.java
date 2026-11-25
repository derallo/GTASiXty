package com.gtasixty.spatial;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in network, LAN discovery, TCP reachability, USB-C UVC, and Xbox Direct diagnostics
 * helper so users can quickly verify Quest 3 connectivity and apply discovered host IPs.
 */
final class NetworkDiagnosticsHelper {

    private static final int TCP_PORT = 7660;
    private static final int DISCOVERY_UDP_PORT = 7661;

    interface HostApplyCallback {
        void onApplyDiscoveredHost(String ip);
    }

    static final class DiscoveredServer {
        final String ip;
        final String offer;
        final int port;
        final String summary;
        final String mac;

        DiscoveredServer(String ip, String offer, int port, String summary, String mac) {
            this.ip = ip;
            this.offer = offer;
            this.port = port;
            this.summary = summary;
            this.mac = mac;
        }
    }

    private NetworkDiagnosticsHelper() {}

    static List<DiscoveredServer> scanLanForOffers(List<InetAddress> targets, int listenMs) {
        final Map<String, DiscoveredServer> found = new LinkedHashMap<>();
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            final byte[] query = "GTA60_DISCOVER".getBytes(StandardCharsets.US_ASCII);
            for (InetAddress target : targets) {
                try {
                    ds.send(new DatagramPacket(query, query.length, target, DISCOVERY_UDP_PORT));
                } catch (Exception ignored) {
                }
            }
            final long deadline = System.currentTimeMillis() + listenMs;
            final byte[] buf = new byte[512];
            while (true) {
                final long remain = deadline - System.currentTimeMillis();
                if (remain <= 20L) break;
                ds.setSoTimeout((int) Math.min(remain, 500L));
                try {
                    final DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    ds.receive(resp);
                    final String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.US_ASCII).trim();
                    if (!msg.startsWith("GTA60_OFFER")) continue;
                    final String ip = resp.getAddress().getHostAddress();
                    if (ip == null || ip.isEmpty() || found.containsKey(ip)) continue;

                    final String[] parts = msg.split("\\|");
                    int port = TCP_PORT;
                    if (parts.length >= 2) {
                        try {
                            port = Integer.parseInt(parts[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    final StringBuilder sum = new StringBuilder();
                    if (parts.length >= 6) {
                        sum.append(parts[2]).append("x").append(parts[3])
                                .append(" @ ").append(parts[4]).append("fps ")
                                .append(parts[5]);
                    } else {
                        sum.append(msg);
                    }
                    final String mac = (parts.length >= 7) ? parts[6].trim() : "";
                    found.put(ip, new DiscoveredServer(ip, msg, port, sum.toString(), mac));
                } catch (SocketTimeoutException ignored) {
                    // Keep listening until overall deadline so multiple servers can reply.
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(found.values());
    }

    static void show(
            final Activity activity,
            final String activeModeLabel,
            final String currentHostField,
            final String captureHost,
            final String pcHost,
            final List<InetAddress> discoveryTargets,
            final boolean xboxSignedIn,
            final HostApplyCallback applyCallback) {

        final ScrollView sv = new ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText("Running network & device diagnostics...\n\n"
                + "Scanning Wi-Fi, LAN (UDP 7661), host TCP 7660, USB-C capture cards, and Xbox Direct status.");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        final int pad = dp(activity, 16);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Network & Device Check (" + activeModeLabel + ")")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Rescan", null)
                .setNegativeButton("Setup Guide", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button rescanBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            final Button guideBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            guideBtn.setOnClickListener(v -> showSetupGuide(activity));
            rescanBtn.setOnClickListener(v -> runChecksAsync(
                    activity, dialog, tv, currentHostField, captureHost, pcHost,
                    discoveryTargets, xboxSignedIn, applyCallback));
            runChecksAsync(
                    activity, dialog, tv, currentHostField, captureHost, pcHost,
                    discoveryTargets, xboxSignedIn, applyCallback);
        });
        dialog.show();
    }

    private static void runChecksAsync(
            final Activity activity,
            final AlertDialog dialog,
            final TextView tv,
            final String currentHostField,
            final String captureHost,
            final String pcHost,
            final List<InetAddress> discoveryTargets,
            final boolean xboxSignedIn,
            final HostApplyCallback applyCallback) {

        tv.setText("Scanning LAN (UDP 7661) and checking configured hosts...\nPlease wait ~1.5 seconds.");

        new Thread(() -> {
            final StringBuilder report = new StringBuilder();
            final List<String> tips = new ArrayList<>();

            // 1. Quest Wi-Fi / LAN interface check
            final List<String> localIps = getLocalIpv4Addresses();
            final String primaryIp = localIps.isEmpty() ? "" : localIps.get(0);
            report.append("=== 1. QUEST 3 WI-FI / LAN ===\n");
            if (localIps.isEmpty()) {
                report.append("[FAIL] No active Wi-Fi IPv4 address.\n");
                tips.add("Connect Quest 3 to your local Wi-Fi network (not offline / airplane mode).");
            } else {
                report.append("[OK] Quest IP: ").append(String.join(", ", localIps)).append("\n");
            }

            // 2. Configured hosts & subnet comparison
            report.append("\n=== 2. CONFIGURED HOSTS & SUBNET ===\n");
            final String capTrim = captureHost == null ? "" : captureHost.trim();
            final String pcTrim = pcHost == null ? "" : pcHost.trim();
            final String curTrim = currentHostField == null ? "" : currentHostField.trim();
            report.append("Capture Host : ").append(capTrim.isEmpty() ? "(none)" : capTrim).append("\n");
            report.append("PC Host      : ").append(pcTrim.isEmpty() ? "(auto-discover via LAN)" : pcTrim).append("\n");
            if (!curTrim.isEmpty() && !curTrim.equals(capTrim) && !curTrim.equals(pcTrim)) {
                report.append("Input Field  : ").append(curTrim).append("\n");
            }

            if (!primaryIp.isEmpty()) {
                checkSubnet(primaryIp, capTrim, "Capture Host", report, tips);
                if (!pcTrim.isEmpty() && !pcTrim.equals(capTrim)) {
                    checkSubnet(primaryIp, pcTrim, "PC Host", report, tips);
                }
            }

            // 3. LAN discovery (UDP 7661)
            report.append("\n=== 3. LAN DISCOVERY (UDP ").append(DISCOVERY_UDP_PORT).append(") ===\n");
            final List<DiscoveredServer> servers = scanLanForOffers(discoveryTargets, 1400);
            if (servers.isEmpty()) {
                report.append("[WARN] No GTA-Sixty server answered UDP 7661.\n");
                report.append("       (Normal if the Capture server or PC streamer is currently stopped.)\n");
            } else {
                final SharedPreferences prefs = activity.getSharedPreferences("gta60", Context.MODE_PRIVATE);
                for (DiscoveredServer srv : servers) {
                    report.append("[OK] ").append(srv.ip).append(":").append(srv.port)
                            .append(" -> ").append(srv.summary);
                    if (!srv.mac.isEmpty()) {
                        report.append(" [MAC ").append(srv.mac).append("]");
                        if (prefs.getString("pcMac", "").isEmpty()) {
                            prefs.edit().putString("pcMac", srv.mac).apply();
                        }
                    }
                    report.append("\n");
                }
            }

            // 4. TCP 7660 reachability on configured hosts
            report.append("\n=== 4. STREAMER PORT CHECK (TCP ").append(TCP_PORT).append(") ===\n");
            final Set<String> hostsToProbe = new LinkedHashSet<>();
            if (!curTrim.isEmpty() && !"127.0.0.1".equals(curTrim)) hostsToProbe.add(curTrim);
            if (!capTrim.isEmpty() && !"127.0.0.1".equals(capTrim)) hostsToProbe.add(capTrim);
            if (!pcTrim.isEmpty() && !"127.0.0.1".equals(pcTrim)) hostsToProbe.add(pcTrim);
            if (hostsToProbe.isEmpty()) {
                report.append("[INFO] No host IP configured to probe directly.\n");
            } else {
                for (String host : hostsToProbe) {
                    probeTcpPort(host, TCP_PORT, report, tips);
                }
            }

            // 5. USB-C capture card check
            report.append("\n=== 5. USB-C CAPTURE CARD (UVC) ===\n");
            inspectUsbCaptureDevices(activity, report);

            // 6. Xbox Direct mode status
            report.append("\n=== 6. XBOX DIRECT MODE ===\n");
            if (xboxSignedIn) {
                report.append("[OK] Microsoft/Xbox account is signed in.\n");
            } else {
                report.append("[INFO] Not signed in for Direct mode (switch to Mode: Direct -> Sign in).\n");
            }
            report.append("       Direct mode locates your Xbox via your account (no IP required).\n");

            // 7. Actionable recommendations
            if (!tips.isEmpty()) {
                report.append("\n=== SUGGESTED FIXES ===\n");
                for (String tip : tips) {
                    report.append("\u2022 ").append(tip).append("\n");
                }
            }

            final String finalReport = report.toString();
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || !dialog.isShowing()) return;
                tv.setText(finalReport);
                if (!servers.isEmpty() && applyCallback != null) {
                    final Button rescanBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                    if (servers.size() == 1 && !servers.get(0).ip.equals(curTrim)) {
                        final String foundIp = servers.get(0).ip;
                        rescanBtn.setText("Use " + foundIp);
                        rescanBtn.setOnClickListener(v -> {
                            applyCallback.onApplyDiscoveredHost(foundIp);
                            dialog.dismiss();
                        });
                    } else if (servers.size() > 1) {
                        rescanBtn.setText("Pick Found IP (" + servers.size() + ")");
                        rescanBtn.setOnClickListener(v -> showServerPicker(activity, servers, applyCallback, dialog));
                    }
                }
            });
        }, "GTA60-NetDiag").start();
    }

    private static void showServerPicker(
            Activity activity,
            List<DiscoveredServer> servers,
            HostApplyCallback applyCallback,
            AlertDialog parentDialog) {
        final String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            final DiscoveredServer s = servers.get(i);
            labels[i] = s.ip + "  (" + s.summary + ")";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Select Discovered Host")
                .setItems(labels, (d, which) -> {
                    applyCallback.onApplyDiscoveredHost(servers.get(which).ip);
                    if (parentDialog != null && parentDialog.isShowing()) parentDialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void checkSubnet(
            String questIp, String hostIp, String label, StringBuilder report, List<String> tips) {
        if (!hostIp.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || "127.0.0.1".equals(hostIp)) {
            return;
        }
        final String qPrefix = subnetPrefix24(questIp);
        final String hPrefix = subnetPrefix24(hostIp);
        if (!qPrefix.isEmpty() && !qPrefix.equals(hPrefix)) {
            report.append("[WARN] ").append(label).append(" (").append(hostIp)
                    .append(") is on a different /24 subnet than Quest (").append(questIp).append(").\n");
            tips.add(label + " (" + hostIp + ") and Quest (" + questIp
                    + ") are on different subnets. Disable Guest Wi-Fi / AP isolation or connect both to the same router.");
        } else {
            report.append("[OK] ").append(label).append(" shares the ").append(qPrefix).append(".* LAN subnet.\n");
        }
    }

    private static void probeTcpPort(String host, int port, StringBuilder report, List<String> tips) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 1200);
            report.append("[OK] ").append(host).append(":").append(port).append(" open (streamer ready)\n");
        } catch (ConnectException ce) {
            report.append("[INFO] ").append(host).append(" reachable on LAN, but nothing listening on TCP ")
                    .append(port).append(" yet.\n");
            tips.add(host + " is reachable, but the streamer isn't running yet. Tap Start Stream (or Wake PC) to launch it.");
        } catch (Exception e) {
            report.append("[FAIL] ").append(host).append(":").append(port)
                    .append(" timed out / unreachable (").append(e.getClass().getSimpleName()).append(")\n");
            tips.add(host + " did not respond on TCP " + port + ": verify the machine is powered on, check its IP, "
                    + "and on Windows allow inbound TCP " + port + " + UDP 7661/7662 in Windows Firewall.");
        }
    }

    private static void inspectUsbCaptureDevices(Context context, StringBuilder report) {
        try {
            final UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (um == null) {
                report.append("[INFO] UsbManager unavailable.\n");
                return;
            }
            int count = 0;
            for (UsbDevice dev : um.getDeviceList().values()) {
                boolean hasVideo = false;
                for (int i = 0; i < dev.getInterfaceCount(); i++) {
                    final UsbInterface ui = dev.getInterface(i);
                    if (ui.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                        hasVideo = true;
                        break;
                    }
                }
                if (!hasVideo) continue;
                count++;
                final String name = dev.getProductName() != null ? dev.getProductName() : "USB Video Device";
                final String perm = um.hasPermission(dev) ? "Granted" : "Not granted yet (tap Start USB)";
                report.append(String.format(Locale.US, "[OK] %s (%04x:%04x) \u2014 USB permission: %s\n",
                        name, dev.getVendorId(), dev.getProductId(), perm));
            }
            if (count == 0) {
                report.append("[INFO] No USB-C UVC capture card plugged into Quest 3.\n");
            } else {
                report.append("       Tip: Close Meta Quest HDMI Link before tapping Start USB.\n");
            }
        } catch (Exception e) {
            report.append("[WARN] USB inspection error: ").append(e.getMessage()).append("\n");
        }
    }

    private static List<String> getLocalIpv4Addresses() {
        final List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    final InetAddress addr = ia.getAddress();
                    if (addr == null || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    final String host = addr.getHostAddress();
                    if (host != null && !host.contains(":")) ips.add(host);
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    private static String subnetPrefix24(String ipv4) {
        final String[] p = ipv4.split("\\.");
        return p.length == 4 ? (p[0] + "." + p[1] + "." + p[2]) : "";
    }

    private static void showSetupGuide(Activity activity) {
        final String guide =
                "How GTA-Sixty finds each computer & device:\n\n"
                + "1. Capture (Src: LAN \u2014 Linux MintBox / PC Capture)\n"
                + "   \u2022 Default IP: 192.168.68.90 (or tap LAN to auto-discover via UDP 7661).\n"
                + "   \u2022 Linux capture box runs gta60-capture.service (TCP 7660, UDP 7661) and gta60-updater (UDP 7662).\n"
                + "   \u2022 Windows PC capture runs: GTA-Sixty.exe --capture.\n"
                + "   \u2022 Your Xbox controller stays paired directly to the Xbox.\n\n"
                + "2. Capture (Src: USB \u2014 Direct Quest USB-C)\n"
                + "   \u2022 Plug an HDMI-to-USB-C UVC capture card straight into the Quest 3.\n"
                + "   \u2022 Close Meta Quest HDMI Link so it doesn't hold the USB device, then tap Start USB.\n\n"
                + "3. PC Mode (Windows PC Remote Play Relay)\n"
                + "   \u2022 Tap LAN to auto-discover your Windows PC (UDP 7661), or enter its IP (127.0.0.1 over ADB USB-C).\n"
                + "   \u2022 Run GTA-Sixty.exe --agent (scripts\\install-autostart.ps1) so Quest can wake/start it on UDP 7662.\n"
                + "   \u2022 If TCP 7660 times out, allow GTA-Sixty.exe through Windows Firewall.\n\n"
                + "4. Direct Mode (Quest \u2194 Xbox, No PC)\n"
                + "   \u2022 No IP address is needed. Sign in to your Microsoft account, pick your Xbox, and tap Start direct.\n"
                + "   \u2022 Requires Remote features enabled on the Xbox and both devices on the same LAN.\n\n"
                + "Window Handle Tip:\n"
                + "   \u2022 Grab the white/cyan bar below the screen using either Trigger or Grip/Pinch to move it.\n"
                + "   \u2022 While holding Trigger on the bar, push the thumbstick up/down to move the screen closer or farther.";

        final ScrollView sv = new ScrollView(activity);
        final TextView tv = new TextView(activity);
        tv.setText(guide);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        final int p = dp(activity, 16);
        tv.setPadding(p, p, p, p);
        sv.addView(tv);

        new AlertDialog.Builder(activity)
                .setTitle("GTA-Sixty Network & Device Setup Guide")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
