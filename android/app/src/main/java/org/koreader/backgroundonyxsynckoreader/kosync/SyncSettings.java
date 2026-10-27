package org.koreader.backgroundonyxsynckoreader.kosync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent sync configuration and status, backed by SharedPreferences.
 *
 * The MD5 hex of the password is stored for standard kosync
 * x-auth-user / x-auth-key authentication. The plaintext password is also
 * stored in the app-private preferences so Calibre-Web-Automated's kosync
 * bridge can be accessed with HTTP Basic authentication.
 */
public class SyncSettings {

    public static final String MATCH_BINARY = "binary";     // KOReader partial MD5 (default)
    public static final String MATCH_FILENAME = "filename"; // MD5 of filename

    private static final String PREFS = "kosync_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD_MD5 = "password_md5";
    private static final String KEY_PASSWORD_PLAIN = "password_plain";
    private static final String KEY_MATCH_METHOD = "match_method";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_BOOK = "last_book";

    private static final String DEFAULT_SERVER_URL = "https://sync.koreader.rocks";

    private final SharedPreferences prefs;

    public SyncSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPasswordMd5() {
        return prefs.getString(KEY_PASSWORD_MD5, "");
    }

    public String getPasswordPlain() {
        return prefs.getString(KEY_PASSWORD_PLAIN, "");
    }

    public String getMatchMethod() {
        return prefs.getString(KEY_MATCH_METHOD, MATCH_BINARY);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply();
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username.trim()).apply();
    }

    /**
     * Stores both forms: MD5 for standard kosync and plaintext for CWA's
     * HTTP Basic authentication. SharedPreferences is private to this app.
     */
    public void setPassword(String plaintextPassword) {
        prefs.edit()
                .putString(KEY_PASSWORD_MD5, KoReaderDocumentHash.md5Hex(plaintextPassword))
                .putString(KEY_PASSWORD_PLAIN, plaintextPassword)
                .apply();
    }

    public void setMatchMethod(String method) {
        prefs.edit().putString(KEY_MATCH_METHOD, method).apply();
    }

    public boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPasswordMd5().isEmpty();
    }

    /**
     * Base URL normalized like CrossPoint's KOReaderCredentialStore: default
     * server when empty, http:// prepended when no scheme, trailing slashes
     * stripped.
     */
    public String getBaseUrl() {
        String url = getServerUrl();
        if (url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
        } else if (!url.contains("://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // --- Status area -------------------------------------------------------

    public long getLastSyncMs() {
        return prefs.getLong(KEY_LAST_SYNC_MS, 0);
    }

    public String getLastError() {
        return prefs.getString(KEY_LAST_ERROR, "");
    }

    public String getLastBook() {
        return prefs.getString(KEY_LAST_BOOK, "");
    }

    public void recordSyncSuccess(String bookName) {
        prefs.edit()
                .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                .putString(KEY_LAST_BOOK, bookName)
                .putString(KEY_LAST_ERROR, "")
                .apply();
    }

    public void recordSyncError(String error) {
        prefs.edit().putString(KEY_LAST_ERROR, error).apply();
    }
}
