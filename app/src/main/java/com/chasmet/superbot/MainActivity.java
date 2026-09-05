package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    private void bindBot(int id, String name) {
        View view = findViewById(id);
        if (view == null) return;
        view.setOnClickListener(v -> {
            Toast.makeText(this, name + " utilise le service d'automatisation Android", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });
    }
}
