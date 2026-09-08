package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        McpConnectionService.start(this);
        setContentView(R.layout.activity_main);

        findViewById(R.id.cardStudio).setOnClickListener(v -> startActivity(new Intent(this, VideoStudioActivity.class)));
        findViewById(R.id.cardLibrary).setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.cardSchedule).setOnClickListener(v -> startActivity(new Intent(this, QueueActivity.class)));
        bindBot(R.id.cardTikTok, "Bot TikTok");
        bindBot(R.id.cardInstagram, "Bot Instagram");
        bindBot(R.id.cardShorts, "Bot YouTube Shorts");
        bindBot(R.id.cardYouTube, "Bot YouTube classique");

        View settings = findViewById(R.id.cardSettings);
        if (settings != null) settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override protected void onResume() {
        super.onResume();
        McpConnectionService.start(this);
        refreshBotChef();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if(!isFinishing())PublicationCoordinator.menuReached(this);
        },1200);
    }

    private void refreshBotChef() {
        TextView summary = findViewById(R.id.textBotChefSummary);
        TextView detail = findViewById(R.id.textBotChefDetail);
        if (summary == null || detail == null) return;

        List<PublicationTask> tasks = PublicationTaskRepository.load(this);
        int pending = 0;
        int errors = 0;
        String lastError = "";

        for (int i = tasks.size() - 1; i >= 0; i--) {
            PublicationTask task = tasks.get(i);
            String status = task.status == null ? "" : task.status.trim();
            String upper = status.toUpperCase();
            boolean error = upper.contains("ERREUR") || upper.contains("FAILED") || upper.contains("INTRouvable".toUpperCase());
            boolean done = "completed".equals(PublicationCoordinator.prefs(this).getString("outcome_"+task.id,""));
            if (error) {
                errors++;
                if (lastError.isEmpty()) lastError = status;
            } else if (!done) {
                pending++;
            }
        }

        String dispatchError = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("last_dispatch_error", "");
        if (dispatchError != null && !dispatchError.trim().isEmpty() && lastError.isEmpty()) {
            lastError = "ERREUR DISPATCH • " + dispatchError.trim();
            errors = Math.max(errors, 1);
        }

        summary.setText(pending + " publication" + (pending > 1 ? "s" : "")
                + " en attente • " + errors + " erreur" + (errors > 1 ? "s" : ""));

        if (errors > 0) {
            detail.setVisibility(View.VISIBLE);
            detail.setText(lastError);
        } else {
            detail.setVisibility(View.GONE);
            detail.setText("");
        }
    }

    private void bindBot(int id, String name) {
        View view = findViewById(id);
        if (view == null) return;
        view.setOnClickListener(v -> {
            McpConnectionService.start(this);
            Toast.makeText(this, name + " utilise le service d'automatisation Android", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
    }
}
