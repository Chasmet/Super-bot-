package com.chasmet.superbot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class SettingsActivity extends Activity {
    private AppUpdateManager updateManager;
    private TextView currentVersion;
    private TextView latestVersion;
    private TextView automationStatus;
    private ProgressBar updateProgress;
    private File downloadedApk;
    private boolean installerLaunched;
    private boolean waitingPermission;
    private boolean busy;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        updateManager = new AppUpdateManager(this);
        currentVersion = findViewById(R.id.textCurrentVersion);
        latestVersion = findViewById(R.id.textLatestVersion);
        automationStatus = findViewById(R.id.textAutomationStatus);
        updateProgress = findViewById(R.id.updateProgress);

        if (savedInstanceState != null) {
            String path = savedInstanceState.getString("update_apk");
            if (path != null) downloadedApk = new File(path);
            waitingPermission = savedInstanceState.getBoolean("update_permission");
            installerLaunched = savedInstanceState.getBoolean("update_installer");
        }
        currentVersion.setText("Version installée : " + AppUpdateManager.installedVersion(this));

        findViewById(R.id.rowAutomation).setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { toast("Réglages d'accessibilité indisponibles"); }
        });
        findViewById(R.id.rowAccounts).setOnClickListener(v -> toast("Super Bot utilise les comptes déjà connectés dans TikTok, Instagram et YouTube"));
        findViewById(R.id.buttonMcpConnect).setOnClickListener(v -> toast("MCP prévu pour la prochaine mise à jour"));
        findViewById(R.id.buttonMcpTest).setOnClickListener(v -> toast("Le moteur local fonctionne sans MCP"));
        findViewById(R.id.buttonCheckUpdate).setOnClickListener(v -> checkForUpdate());
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAutomationStatus();
        if (waitingPermission) {
            waitingPermission = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                installDownloaded();
            } else {
                latestVersion.setText("Autorisation refusée. Appuie sur le bouton pour réessayer.");
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (downloadedApk != null) out.putString("update_apk", downloadedApk.getAbsolutePath());
        out.putBoolean("update_permission", waitingPermission);
        out.putBoolean("update_installer", installerLaunched);
    }

    private void installDownloaded() {
        try {
            if (installerLaunched) return;
            updateManager.validateApk(downloadedApk);
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                waitingPermission = true;
                latestVersion.setText("Autorise Super Bot à installer la mise à jour, puis reviens ici.");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            }
            installerLaunched = true;
            latestVersion.setText("APK vérifié • confirme la mise à jour dans Android");
            updateManager.launchInstaller(this, downloadedApk);
        } catch (Exception e) {
            installerLaunched = false;
            waitingPermission = false;
            latestVersion.setText("Installation impossible : " + e.getMessage());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 2002) {
            installerLaunched = false;
            downloadedApk = null;
            latestVersion.setText(result == RESULT_OK ? "Mise à jour installée"
                    : "Installation annulée ou refusée par Android. Tu peux réessayer.");
        }
    }

    private void refreshAutomationStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        automationStatus.setText(enabled
                ? "Service Super Bot : ACTIVÉ • contrôle des publications autorisé"
                : "Service Super Bot : DÉSACTIVÉ • appuie ici pour l'activer");
        automationStatus.setTextColor(enabled ? 0xFF59E391 : 0xFFFFB84D);
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName component = new ComponentName(this, BotAccessibilityService.class);
        String expected = component.flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private void checkForUpdate() {
        if (busy || installerLaunched || waitingPermission) return;
        if (downloadedApk != null && downloadedApk.exists()) { installDownloaded(); return; }
        busy = true;
        findViewById(R.id.buttonCheckUpdate).setEnabled(false);
        latestVersion.setText("Vérification de GitHub Releases…");
        updateProgress.setProgress(0);
        installerLaunched = false;
        updateManager.check(new AppUpdateManager.Listener() {
            @Override public void onCheckResult(AppUpdateManager.ReleaseInfo release) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (release.latestVersion == null || release.latestVersion.isEmpty()) {
                        finishDownload();
                        latestVersion.setText("Aucune Release disponible pour le moment");
                        return;
                    }
                    latestVersion.setText("Dernière version : " + release.latestVersion);
                    if (!release.newer) {
                        finishDownload();
                        toast("Super Bot est déjà à jour");
                        return;
                    }
                    latestVersion.setText("Mise à jour " + release.latestVersion + " disponible • téléchargement…");
                    updateManager.downloadAndInstall(release, this);
                });
            }

            @Override public void onDownloadProgress(int percent) {
                runOnUiThread(() -> { if (isDestroyed()) return; updateProgress.setProgress(percent); latestVersion.setText("Téléchargement : " + percent + " %"); });
            }

            @Override public void onDownloaded(File apk) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    downloadedApk = apk;
                    finishDownload();
                    updateProgress.setProgress(100);
                    installDownloaded();
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    finishDownload();
                    latestVersion.setText("Mise à jour indisponible : " + message);

                });
            }
        });
    }

    private void finishDownload() {
        busy = false;
        findViewById(R.id.buttonCheckUpdate).setEnabled(true);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (updateManager != null) updateManager.close();
        super.onDestroy();
    }
}
