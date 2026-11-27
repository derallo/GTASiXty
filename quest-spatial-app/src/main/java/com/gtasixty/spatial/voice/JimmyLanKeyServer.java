package com.gtasixty.spatial.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ephemeral, zero-dependency HTTP/1.1 key-entry server for local Wi-Fi phone setup.
 * Binds a raw {@link ServerSocket} on the Quest's LAN IP, serves an HTML form at {@code /key},
 * accepts one valid POST submission, delivers the extracted keys, and closes itself immediately.
 */
final class JimmyLanKeyServer {

    private static final int[] CANDIDATE_PORTS = {8091, 8092, 8093, 3001};
    private static final int MAX_BODY_BYTES = 8192;
    private static final int SOCKET_TIMEOUT_MS = 15_000;

    static final Pattern GEMINI_API_KEY_PATTERN = Pattern.compile("AIza[0-9A-Za-z_-]{30,50}");
    static final Pattern ELEVENLABS_API_KEY_PATTERN = Pattern.compile("sk_[0-9A-Za-z_-]{20,120}");

    static final class ReceivedKeys {
        final String geminiKey;
        final String elevenLabsKey;
        final String voiceId;
        final String geminiModel;
        final boolean enableNow;

        ReceivedKeys(String geminiKey, String elevenLabsKey, String voiceId, boolean enableNow) {
            this(geminiKey, elevenLabsKey, voiceId, "", enableNow);
        }

        ReceivedKeys(String geminiKey, String elevenLabsKey, String voiceId, String geminiModel, boolean enableNow) {
            this.geminiKey = geminiKey == null ? "" : geminiKey.trim();
            this.elevenLabsKey = elevenLabsKey == null ? "" : elevenLabsKey.trim();
            this.voiceId = voiceId == null ? "" : voiceId.trim();
            this.geminiModel = geminiModel == null ? "" : geminiModel.trim();
            this.enableNow = enableNow;
        }
    }

    interface Listener {
        void onKeysReceived(ReceivedKeys keys);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean currentHasGemini;
    private volatile boolean currentHasElevenLabs;
    private volatile String currentVoiceId = JimmyConfig.DEFAULT_VOICE_ID;
    private volatile Listener currentListener;

    boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    int getBoundPort() {
        final ServerSocket ss = serverSocket;
        return ss != null ? ss.getLocalPort() : -1;
    }

    synchronized String start(
            boolean hasGeminiKey,
            boolean hasElevenLabsKey,
            String voiceId,
            Listener listener) throws IOException {
        stop();
        final String lanIp = getDeviceLanIp();
        if (lanIp.isEmpty()) {
            throw new IOException("No Wi-Fi LAN IPv4 address found on this Quest.");
        }

        ServerSocket bound = null;
        IOException lastErr = null;
        for (int port : CANDIDATE_PORTS) {
            try {
                final ServerSocket ss = new ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"));
                bound = ss;
                break;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        if (bound == null) {
            throw (lastErr != null ? lastErr : new IOException("Could not bind key setup port."));
        }

        beginAcceptLoop(bound, hasGeminiKey, hasElevenLabsKey, voiceId, listener);
        return "http://" + lanIp + ":" + bound.getLocalPort() + "/key";
    }

    synchronized String startOnPortForTest(
            int port,
            boolean hasGeminiKey,
            boolean hasElevenLabsKey,
            String voiceId,
            Listener listener) throws IOException {
        stop();
        final ServerSocket ss = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        beginAcceptLoop(ss, hasGeminiKey, hasElevenLabsKey, voiceId, listener);
        return "http://127.0.0.1:" + ss.getLocalPort() + "/key";
    }

    private void beginAcceptLoop(
            ServerSocket bound,
            boolean hasGeminiKey,
            boolean hasElevenLabsKey,
            String voiceId,
            Listener listener) {
        this.currentHasGemini = hasGeminiKey;
        this.currentHasElevenLabs = hasElevenLabsKey;
        this.currentVoiceId = (voiceId == null || voiceId.trim().isEmpty())
                ? JimmyConfig.DEFAULT_VOICE_ID
                : voiceId.trim();
        this.currentListener = listener;
        this.serverSocket = bound;
        this.running.set(true);

        final Thread t = new Thread(() -> {
            while (running.get() && !bound.isClosed()) {
                try {
                    final Socket client = bound.accept();
                    final Thread handlerThread = new Thread(() -> handleConnection(client), "Jimmy-LanHttpConn");
                    handlerThread.setDaemon(true);
                    handlerThread.start();
                } catch (IOException ignored) {
                    if (!running.get() || bound.isClosed()) break;
                }
            }
        }, "Jimmy-LanKeyAccept");
        t.setDaemon(true);
        t.start();
        this.acceptThread = t;
    }

    synchronized void stop() {
        running.set(false);
        final ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {}
        }
        final Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();

            final String requestLine = readAsciiLine(in, 2048);
            if (requestLine == null || requestLine.isEmpty()) {
                writeHttp(out, "400 Bad Request", "text/plain; charset=utf-8", "Bad Request");
                return;
            }

            final String[] parts = requestLine.split(" ");
            final String method = parts.length >= 1 ? parts[0].toUpperCase(Locale.US) : "";
            final String rawPath = parts.length >= 2 ? parts[1] : "/";
            final int qIdx = rawPath.indexOf('?');
            final String path = qIdx >= 0 ? rawPath.substring(0, qIdx) : rawPath;

            int contentLength = 0;
            String line;
            int headerCount = 0;
            while ((line = readAsciiLine(in, 4096)) != null && !line.isEmpty()) {
                headerCount++;
                if (headerCount > 64) {
                    writeHttp(out, "400 Bad Request", "text/plain; charset=utf-8", "Too many headers");
                    return;
                }
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    final String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    final String value = line.substring(colon + 1).trim();
                    if ("content-length".equals(name)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                            writeHttp(out, "400 Bad Request", "text/plain; charset=utf-8", "Invalid Content-Length");
                            return;
                        }
                    }
                }
            }

            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeHttp(out, "413 Payload Too Large", "text/plain; charset=utf-8", "Payload too large (max 8 KB).");
                return;
            }

            if ("GET".equals(method)) {
                if ("/".equals(path) || "/key".equals(path)) {
                    writeHttp(
                            out,
                            "200 OK",
                            "text/html; charset=utf-8",
                            buildFormHtml(currentHasGemini, currentHasElevenLabs, currentVoiceId, null));
                } else {
                    writeHttp(out, "404 Not Found", "text/plain; charset=utf-8", "Not Found");
                }
                return;
            }

            if ("POST".equals(method) && "/key".equals(path)) {
                final byte[] bodyBytes = readExactBytes(in, contentLength);
                final String body = new String(bodyBytes, StandardCharsets.UTF_8);
                final Map<String, String> form = parseFormUrlEncoded(body);

                final String gemini = extractGeminiKey(form.get("geminiKey"));
                final String eleven = extractElevenLabsKey(form.get("elevenLabsKey"));
                final String voice = safeTrim(form.get("voiceId"));
                final String model = safeTrim(form.get("geminiModel"));
                final String enableRaw = safeTrim(form.get("enableNow"));
                final boolean enableNow = enableRaw.isEmpty()
                        ? form.containsKey("enableNow")
                        : ("on".equalsIgnoreCase(enableRaw)
                                || "true".equalsIgnoreCase(enableRaw)
                                || "1".equals(enableRaw));

                if (gemini.isEmpty() && !currentHasGemini && eleven.isEmpty()) {
                    writeHttp(
                            out,
                            "200 OK",
                            "text/html; charset=utf-8",
                            buildFormHtml(
                                    currentHasGemini,
                                    currentHasElevenLabs,
                                    currentVoiceId,
                                    "Please paste at least your Gemini API key."));
                    return;
                }

                writeHttp(out, "200 OK", "text/html; charset=utf-8", buildSavedHtml(
                        !gemini.isEmpty(), !eleven.isEmpty()));

                final Listener cb = currentListener;
                final ReceivedKeys received = new ReceivedKeys(gemini, eleven, voice, model, enableNow);
                stop();
                if (cb != null) {
                    cb.onKeysReceived(received);
                }
                return;
            }

            writeHttp(out, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed");
        } catch (IOException ignored) {
        }
    }

    static String extractGeminiKey(String raw) {
        final String trimmed = safeTrim(raw);
        if (trimmed.isEmpty()) return "";
        final Matcher m = GEMINI_API_KEY_PATTERN.matcher(trimmed);
        return m.find() ? m.group() : trimmed;
    }

    static String extractElevenLabsKey(String raw) {
        final String trimmed = safeTrim(raw);
        if (trimmed.isEmpty()) return "";
        final Matcher m = ELEVENLABS_API_KEY_PATTERN.matcher(trimmed);
        return m.find() ? m.group() : trimmed;
    }

    static Map<String, String> parseFormUrlEncoded(String body) {
        final Map<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) return out;
        final String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            final int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    final String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name());
                    final String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                    out.put(k, v);
                } else {
                    final String k = URLDecoder.decode(pair, StandardCharsets.UTF_8.name());
                    out.put(k, "");
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    static String buildFormHtml(
            boolean hasGeminiKey,
            boolean hasElevenLabsKey,
            String currentVoiceId,
            String errorMessage) {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>GTA-Sixty Quest 3 — Jimmy Key Setup</title>")
          .append("<style>")
          .append("body{font-family:system-ui,-apple-system,sans-serif;background:#0b1120;color:#e2e8f0;margin:0;padding:20px;}")
          .append(".card{max-width:460px;margin:0 auto;background:#111827;border:1px solid #1e293b;border-radius:14px;padding:20px;}")
          .append("h1{font-size:20px;margin:0 0 8px;color:#22d3ee;}")
          .append("p{font-size:14px;color:#94a3b8;line-height:1.4;margin:0 0 14px;}")
          .append("label{display:block;font-size:13px;font-weight:600;color:#cbd5e1;margin:14px 0 6px;}")
          .append("input[type=password],input[type=text],select{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #334155;background:#1e293b;color:#fff;font-size:15px;}")
          .append(".links{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:10px;}")
          .append(".links a{color:#38bdf8;font-size:13px;text-decoration:none;border:1px solid #1e293b;padding:6px 10px;border-radius:8px;background:#0f172a;}")
          .append(".chk{display:flex;align-items:center;gap:8px;margin:16px 0;font-size:14px;color:#e2e8f0;}")
          .append(".chk input{width:18px;height:18px;}")
          .append("button{width:100%;padding:13px;border:0;border-radius:10px;background:#0284c7;color:#fff;font-size:16px;font-weight:700;cursor:pointer;}")
          .append(".err{background:#7f1d1d;color:#fecaca;padding:10px 12px;border-radius:8px;font-size:13px;margin-bottom:12px;}")
          .append("</style></head><body><div class=\"card\">")
          .append("<h1>Jimmy AI — Quest 3 Setup</h1>")
          .append("<p>Paste your API keys from your phone and send them straight to your Quest 3 over local Wi-Fi.</p>")
          .append("<div class=\"links\">")
          .append("<a href=\"https://aistudio.google.com/apikey\" target=\"_blank\" rel=\"noopener\">Get Gemini Key &rarr;</a>")
          .append("<a href=\"https://elevenlabs.io/app/settings/api-keys\" target=\"_blank\" rel=\"noopener\">Get ElevenLabs Key &rarr;</a>")
          .append("</div>");
        if (errorMessage != null && !errorMessage.isEmpty()) {
            sb.append("<div class=\"err\">").append(escapeHtml(errorMessage)).append("</div>");
        }
        sb.append("<form method=\"POST\" action=\"/key\">")
          .append("<label for=\"geminiKey\">Gemini API Key (Required)</label>")
          .append("<input id=\"geminiKey\" name=\"geminiKey\" type=\"password\" autocomplete=\"off\" placeholder=\"")
          .append(hasGeminiKey ? "Saved on Quest (leave blank to keep)" : "AIza...")
          .append("\">")
          .append("<label for=\"elevenLabsKey\">ElevenLabs API Key (Optional — falls back to Gemini Voice)</label>")
          .append("<input id=\"elevenLabsKey\" name=\"elevenLabsKey\" type=\"password\" autocomplete=\"off\" placeholder=\"")
          .append(hasElevenLabsKey ? "Saved on Quest (leave blank to keep)" : "sk_... (optional)")
          .append("\">")
          .append("<label for=\"voiceId\">ElevenLabs Voice</label>")
          .append("<select id=\"voiceId\" name=\"voiceId\">")
          .append("<option value=\"\">Keep current voice</option>");
        for (int i = 0; i < JimmyConfig.VOICE_IDS.length; i++) {
            final String id = JimmyConfig.VOICE_IDS[i];
            final String label = JimmyConfig.VOICE_LABELS[i];
            sb.append("<option value=\"").append(escapeHtml(id)).append("\"");
            if (id.equals(currentVoiceId)) sb.append(" selected");
            sb.append(">").append(escapeHtml(label)).append("</option>");
        }
        sb.append("</select>")
          .append("<label for=\"geminiModel\">Gemini Model</label>")
          .append("<select id=\"geminiModel\" name=\"geminiModel\">")
          .append("<option value=\"\">Keep current model</option>");
        for (String modelOpt : JimmyConfig.MODEL_OPTIONS) {
            sb.append("<option value=\"").append(escapeHtml(modelOpt)).append("\"");
            if (JimmyConfig.DEFAULT_GEMINI_MODEL.equals(modelOpt)) sb.append(" selected");
            sb.append(">").append(escapeHtml(modelOpt)).append("</option>");
        }
        sb.append("</select>")
          .append("<label class=\"chk\"><input type=\"checkbox\" name=\"enableNow\" checked> Enable Jimmy AI immediately</label>")
          .append("<button type=\"submit\">Send Keys to Quest 3</button>")
          .append("</form></div></body></html>");
        return sb.toString();
    }

    private static String buildSavedHtml(boolean gotGemini, boolean gotEleven) {
        final String what = gotGemini && gotEleven
                ? "Gemini + ElevenLabs keys"
                : (gotGemini ? "Gemini key" : (gotEleven ? "ElevenLabs key" : "settings"));
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Saved to Quest 3</title>"
                + "<style>body{font-family:system-ui,-apple-system,sans-serif;background:#0b1120;color:#e2e8f0;padding:24px;text-align:center;}"
                + ".card{max-width:420px;margin:40px auto;background:#111827;border:1px solid #16a34a;border-radius:14px;padding:24px;}"
                + "h1{color:#4ade80;font-size:20px;margin:0 0 10px;}p{color:#cbd5e1;font-size:15px;margin:0;}</style>"
                + "</head><body><div class=\"card\">"
                + "<h1>&#10003; Saved to Quest 3</h1>"
                + "<p>Your " + escapeHtml(what) + " arrived safely. The temporary Wi-Fi server is now closed — put your headset back on and say \"Jimmy\"!</p>"
                + "</div></body></html>";
    }

    static String getDeviceLanIp() {
        String fallback = "";
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                final String ifName = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                final boolean virtualIf = ifName.contains("tun") || ifName.contains("tap") || ifName.contains("rmnet");
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    final String host = addr.getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    if (!virtualIf && addr.isSiteLocalAddress()) {
                        return host;
                    }
                    if (fallback.isEmpty()) {
                        fallback = host;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String readAsciiLine(InputStream in, int maxLen) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') {
                baos.write(b);
                if (baos.size() >= maxLen) break;
            }
        }
        if (b == -1 && baos.size() == 0) return null;
        return new String(baos.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        if (length <= 0) return new byte[0];
        final byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            final int n = in.read(buf, offset, length - offset);
            if (n == -1) break;
            offset += n;
        }
        if (offset == length) return buf;
        final byte[] truncated = new byte[offset];
        System.arraycopy(buf, 0, truncated, 0, offset);
        return truncated;
    }

    private static void writeHttp(OutputStream out, String status, String contentType, String body) throws IOException {
        final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        final String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        out.flush();
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
