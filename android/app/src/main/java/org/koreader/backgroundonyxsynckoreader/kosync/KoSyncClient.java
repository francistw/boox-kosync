package org.koreader.backgroundonyxsynckoreader.kosync;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal KOReader-Sync (kosync) protocol client. */
public class KoSyncClient {

    private static final String TAG = "KoSyncClient";
    private static final int TIMEOUT_MS = 15000;

    public enum Error {
        OK, NO_CREDENTIALS, NETWORK_ERROR, AUTH_FAILED, SERVER_ERROR, JSON_ERROR, NOT_FOUND
    }

    public static class Progress {
        public String document;
        public String progress;
        public double percentage;
        public String device;
        public String deviceId;
        public long timestampSec;
    }

    public static class Result {
        public final Error error;
        public final int httpCode;
        public final Progress progress;
        public final String serverMessage;

        Result(Error error, int httpCode, Progress progress) {
            this(error, httpCode, progress, null);
        }

        Result(Error error, int httpCode, Progress progress, String serverMessage) {
            this.error = error;
            this.httpCode = httpCode;
            this.progress = progress;
            this.serverMessage = serverMessage;
        }
    }

    private final SyncSettings settings;
    private final String deviceName;
    private final String deviceId;

    public KoSyncClient(SyncSettings settings, String deviceName, String deviceId) {
        this.settings = settings;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
    }

    public Result authenticate() {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/users/auth", "GET");
            int code = conn.getResponseCode();
            String body = readBody(conn);
            String message = extractServerMessage(body);
            Log.d(TAG, "auth -> " + code + (message != null ? " (" + message + ")" : ""));
            if (code == 200) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null, message);
            return new Result(Error.SERVER_ERROR, code, null, message);
        } catch (Exception e) {
            Log.w(TAG, "auth failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null, e.getMessage());
        }
    }

    public Result getProgress(String documentHash) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/syncs/progress/" + documentHash, "GET");
            int code = conn.getResponseCode();
            String body = readBody(conn);
            Log.d(TAG, "getProgress " + documentHash + " -> " + code);
            if (code == 200 && body != null) {
                try {
                    JSONObject json = new JSONObject(body);
                    Progress p = new Progress();
                    p.document = documentHash;
                    p.progress = json.optString("progress", "");
                    p.percentage = json.optDouble("percentage", 0.0);
                    p.device = json.optString("device", "");
                    p.deviceId = json.optString("device_id", "");
                    p.timestampSec = json.optLong("timestamp", 0);
                    if (p.timestampSec == 0 && !json.has("percentage")) {
                        return new Result(Error.NOT_FOUND, code, null);
                    }
                    return new Result(Error.OK, code, p);
                } catch (Exception e) {
                    Log.w(TAG, "getProgress JSON parse failed: " + e);
                    return new Result(Error.JSON_ERROR, code, null, summarizeBody(body));
                }
            }
            String message = extractServerMessage(body);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null, message);
            if (code == 404) return new Result(Error.NOT_FOUND, code, null, message);
            return new Result(Error.SERVER_ERROR, code, null, message);
        } catch (Exception e) {
            Log.w(TAG, "getProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null, e.getMessage());
        }
    }

    public Result updateProgress(String documentHash, String progress, double percentage) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            JSONObject json = new JSONObject();
            json.put("document", documentHash);
            json.put("progress", progress);
            json.put("percentage", percentage);
            json.put("device", deviceName);
            json.put("device_id", deviceId);
            byte[] requestBody = json.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = open("/syncs/progress", "PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(requestBody);
            }
            int code = conn.getResponseCode();
            String body = readBody(conn);
            String message = extractServerMessage(body);
            Log.d(TAG, "updateProgress " + documentHash + " " + percentage + " -> " + code
                    + (message != null ? " (" + message + ")" : ""));
            if (code == 200 || code == 202) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null, message);
            return new Result(Error.SERVER_ERROR, code, null, message);
        } catch (Exception e) {
            Log.w(TAG, "updateProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null, e.getMessage());
        }
    }

    public static String errorString(Result result) {
        String base;
        switch (result.error) {
            case OK: base = "Success"; break;
            case NO_CREDENTIALS: base = "No credentials configured"; break;
            case NETWORK_ERROR: base = "Network error"; break;
            case AUTH_FAILED: base = "Authentication failed"; break;
            case SERVER_ERROR: base = "Server error (HTTP " + result.httpCode + ")"; break;
            case JSON_ERROR: base = "Bad server response"; break;
            case NOT_FOUND: base = "No progress found on server"; break;
            default: base = "Unknown error"; break;
        }
        if (result.serverMessage != null && !result.serverMessage.isEmpty()) {
            return base + ": " + result.serverMessage;
        }
        return base;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(settings.getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/vnd.koreader.v1+json");

        conn.setRequestProperty("x-auth-user", settings.getUsername());
        conn.setRequestProperty("x-auth-key", settings.getPasswordMd5());

        String plaintextPassword = settings.getPasswordPlain();
        if (!plaintextPassword.isEmpty()) {
            String credentials = settings.getUsername() + ":" + plaintextPassword;
            String encoded = Base64.encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        }
        return conn;
    }

    private static String extractServerMessage(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            String message = json.optString("message", "");
            if (!message.isEmpty()) return message;
            if (json.has("error")) return "error=" + json.optString("error");
        } catch (Exception ignored) {
        }
        return summarizeBody(body);
    }

    private static String summarizeBody(String body) {
        if (body == null) return null;
        String text = body.replaceAll("\\s+", " ").trim();
        if (text.length() > 180) text = text.substring(0, 180) + "…";
        return text;
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream stream = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            conn.disconnect();
        }
    }
}
