package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindAction(R.id.cardStudio, "Studio vidéo");
        bindAction(R.id.cardLibrary, "Bibliothèque");
        bindAction(R.id.cardSchedule, "Programmation");
        bindAction(R.id.cardTikTok, "Bot TikTok");
        bindAction(R.id.cardInstagram, "Bot Instagram");
        bindAction(R.id.cardShorts, "Bot YouTube Shorts");
        bindAction(R.id.cardYouTube, "Bot YouTube classique");

        View settings = findViewById(R.id.cardSettings);
        if (settings != null) {
            settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }
    }

    private void bindAction(int id, String label) {
        View view = findViewById(id);
        if (view != null) {
            view.setOnClickListener(v -> Toast.makeText(this, label + " — module en préparation", Toast.LENGTH_SHORT).show());
        }
    }
}
