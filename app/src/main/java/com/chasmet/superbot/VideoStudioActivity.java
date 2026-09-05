package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoStudioActivity extends Activity {
    private static final int PICK_VIDEO = 1001;
    private Uri selectedVideo;
    private long durationMs;
    private VideoView preview;
    private TextView info;
    private ProgressBar progress;
    private EditText customSeconds;
    private EditText trimStart;
    private EditText trimEnd;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_studio);
        preview = findViewById(R.id.videoPreview);
        info = findViewById(R.id.textVideoInfo);
        progress = findViewById(R.id.exportProgress);
        customSeconds = findViewById(R.id.editCustomSeconds);
        trimStart = findViewById(R.id.editTrimStart);
        trimEnd = findViewById(R.id.editTrimEnd);

        findViewById(R.id.buttonPickVideo).setOnClickListener(v -> pickVideo());
        findViewById(R.id.button15).setOnClickListener(v -> split(15));
        findViewById(R.id.button30).setOnClickListener(v -> split(30));
        findViewById(R.id.button60).setOnClickListener(v -> split(60));
        findViewById(R.id.button90).setOnClickListener(v -> split(90));
        findViewById(R.id.buttonCustom).setOnClickListener(v -> {
            try { split(Integer.parseInt(customSeconds.getText().toString().trim())); }
            catch (Exception e) { toast("Durée personnalisée invalide"); }
        });
        findViewById(R.id.buttonTrim).setOnClickListener(v -> trim());
        findViewById(R.id.buttonLibrary).setOnClickListener(v -> startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedVideo = data.getData();
            try { getContentResolver().takePersistableUriPermission(selectedVideo, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            preview.setVideoURI(selectedVideo);
            preview.start();
            durationMs = VideoCutterEngine.getDurationMs(this, selectedVideo);
            info.setText(String.format(Locale.FRANCE, "Vidéo chargée • %.1f s", durationMs / 1000f));
            trimStart.setText("0");
            trimEnd.setText(String.valueOf((int)Math.ceil(durationMs / 1000d)));
        }
    }

    private void split(int seconds) {
        if (selectedVideo == null) { toast("Choisis d'abord une vidéo"); return; }
        if (seconds < 1 || seconds > 600) { toast("Durée autorisée : 1 à 600 secondes"); return; }
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        info.setText("Découpage en cours…");
        executor.execute(() -> {
            try {
                List<File> files = VideoCutterEngine.split(this, selectedVideo, seconds, (done,total,latest) -> runOnUiThread(() -> {
                    progress.setMax(total);
                    progress.setProgress(done);
                    info.setText("Export " + done + "/" + total + " • " + latest.getName());
                }));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    info.setText(files.size() + " morceau(x) créé(s) dans Super Bot");
                    toast("Découpage terminé");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    info.setText("Erreur : " + e.getMessage());
                    toast("Échec du découpage");
                });
            }
        });
    }

    private void trim() {
        if (selectedVideo == null) { toast("Choisis d'abord une vidéo"); return; }
        try {
            long start = (long)(Double.parseDouble(trimStart.getText().toString().trim()) * 1000d);
            long end = (long)(Double.parseDouble(trimEnd.getText().toString().trim()) * 1000d);
            progress.setVisibility(View.VISIBLE);
            info.setText("Rognage en cours…");
            executor.execute(() -> {
                try {
                    File file = VideoCutterEngine.trim(this, selectedVideo, start, end);
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        info.setText("Rognage terminé • " + file.getName());
                        toast("Vidéo rognée");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { progress.setVisibility(View.GONE); info.setText("Erreur : " + e.getMessage()); });
                }
            });
        } catch (Exception e) { toast("Début ou fin invalide"); }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (preview != null) preview.stopPlayback();
        super.onDestroy();
    }
}
