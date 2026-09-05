package com.chasmet.superbot;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateManager {
    private static final String RELEASE_API = "https://api.github.com/repos/Chasmet/Super-bot-/releases/latest";
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AppUpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void check(Listener listener) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(RELEASE_API);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code);
                String json;
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    json = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
                JSONObject release = new JSONObject(json);
                String tag = cleanVersion(release.optString("tag_name", ""));
                String body = release.optString("body", "");
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = "";
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "").toLowerCase();
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                String current = installedVersion(context);
                boolean newer = compare(tag, current) > 0;
                listener.onCheckResult(new ReleaseInfo(tag, current, apkUrl, body, newer));
            } catch (Exception e) {
                listener.onError(e.getMessage() == null ? "Erreur de mise à jour" : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void downloadAndInstall(ReleaseInfo release, Listener listener) {
        if (release == null || release.apkUrl == null || release.apkUrl.isEmpty()) {
            listener.onError("Aucun APK trouvé dans la Release GitHub");
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(release.apkUrl);
                connection.setInstanceFollowRedirects(true);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("Téléchargement HTTP " + code);
                long length = connection.getContentLengthLong();
                File cacheRoot = context.getExternalCacheDir();
                if (cacheRoot == null) cacheRoot = context.getCacheDir();
                File dir = new File(cacheRoot, "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Dossier de mise à jour impossible");
                File apk = new File(dir, "Super-Bot-" + release.latestVersion + ".apk");
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        done += read;
                        int percent = length > 0 ? (int)Math.min(100, (done * 100L) / length) : 0;
                        listener.onDownloadProgress(percent);
                    }
                }
                listener.onDownloaded(apk);
            } catch (Exception e) {
                listener.onError(e.getMessage() == null ? "Téléchargement impossible" : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void launchInstaller(Context activityContext, File apk) {
        if (apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activityContext.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activityContext.getPackageName()));
            activityContext.startActivity(permission);
            return;
        }
        Uri uri = FileProvider.getUriForFile(activityContext,
                activityContext.getPackageName() + ".files", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activityContext.startActivity(install);
    }

    public void close() {
        executor.shutdownNow();
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "SuperBot-Android");
        return connection;
    }

    public static String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "0.0.0" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private static String cleanVersion(String value) {
        String v = value == null ? "" : value.trim();
        while (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static int compare(String a, String b) {
        String[] aa = cleanVersion(a).split("\\.");
        String[] bb = cleanVersion(b).split("\\.");
        int max = Math.max(aa.length, bb.length);
        for (int i = 0; i < max; i++) {
            int av = number(i < aa.length ? aa[i] : "0");
            int bv = number(i < bb.length ? bb[i] : "0");
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int number(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (Exception ignored) { return 0; }
    }

    public static final class ReleaseInfo {
        public final String latestVersion;
        public final String currentVersion;
        public final String apkUrl;
        public final String notes;
        public final boolean newer;

        ReleaseInfo(String latestVersion, String currentVersion, String apkUrl, String notes, boolean newer) {
            this.latestVersion = latestVersion;
            this.currentVersion = currentVersion;
            this.apkUrl = apkUrl;
            this.notes = notes;
            this.newer = newer;
        }
    }

    public interface Listener {
        void onCheckResult(ReleaseInfo release);
        void onDownloadProgress(int percent);
        void onDownloaded(File apk);
        void onError(String message);
    }
}
