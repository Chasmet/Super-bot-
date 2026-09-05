package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class LibraryActivity extends Activity {
    private LinearLayout list;
    private TextView summary;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        list = findViewById(R.id.libraryList);
        summary = findViewById(R.id.librarySummary);
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonStudio).setOnClickListener(v -> startActivity(new Intent(this, VideoStudioActivity.class)));
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        File root = new File(getExternalFilesDir(null), "Movies/SuperBot");
        List<File> videos = new ArrayList<>();
        collect(root, videos);
        videos.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        long bytes = 0;
        for (File f : videos) bytes += f.length();
        summary.setText(videos.size() + " vidéo(s) • " + readable(bytes));
        if (videos.isEmpty()) {
            TextView empty = rowText("Aucune vidéo exportée. Utilise le Studio vidéo.");
            list.addView(empty);
            return;
        }
        for (File file : videos) list.addView(videoRow(file));
    }

    private View videoRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 14, 16, 14);
        row.setBackgroundColor(0xFF141D33);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, 0, 0, 12);
        row.setLayoutParams(rp);

        TextView name = rowText(file.getName());
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(16);
        row.addView(name);
        TextView meta = rowText(readable(file.length()) + " • " + file.getParentFile().getName());
        meta.setTextColor(0xFF9FB0D0);
        meta.setTextSize(12);
        row.addView(meta);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button program = new Button(this);
        program.setText("Programmer");
        program.setOnClickListener(v -> {
            Intent i = new Intent(this, ScheduleActivity.class);
            i.putExtra("video_path", file.getAbsolutePath());
            startActivity(i);
        });
        Button share = new Button(this);
        share.setText("Partager");
        share.setOnClickListener(v -> share(file));
        Button delete = new Button(this);
        delete.setText("Supprimer");
        delete.setOnClickListener(v -> {
            if (file.delete()) refresh(); else Toast.makeText(this, "Suppression impossible", Toast.LENGTH_SHORT).show();
        });
        actions.addView(program, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(actions);
        return row;
    }

    private void share(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Partager la vidéo"));
        } catch (Exception e) {
            Toast.makeText(this, "Partage impossible", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView rowText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(4, 6, 4, 6);
        return t;
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().toLowerCase().endsWith(".mp4")) out.add(f);
        }
    }

    private static String readable(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " Ko";
        return String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / 1048576d);
    }
}
