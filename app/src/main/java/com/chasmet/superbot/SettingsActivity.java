package com.chasmet.superbot;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        TextView version = findViewById(R.id.textCurrentVersion);
        if (version != null) {
            version.setText("Version installée : " + BuildConfig.VERSION_NAME);
        }

        bind(R.id.buttonCheckUpdate, "Recherche de mise à jour lancée");
        bind(R.id.buttonMcpTest, "Test de connexion MCP lancé");
        bind(R.id.buttonMcpConnect, "Connexion MCP prête à être configurée");
        bind(R.id.rowAutomation, "Permissions d'automatisation Android");
        bind(R.id.rowAccounts, "Comptes sociaux");

        View back = findViewById(R.id.buttonBack);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
    }

    private void bind(int id, String message) {
        View view = findViewById(id);
        if (view != null) {
            view.setOnClickListener(v -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
        }
    }
}
