package com.chasmet.superbot;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;

public class ScheduleActivity extends Activity {
    private String videoPath;
    private Spinner platform;
    private EditText title;
    private EditText description;
    private EditText hashtags;
    private Spinner visibility;
    private DatePicker datePicker;
    private TimePicker timePicker;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);
        videoPath = getIntent().getStringExtra("video_path");
        platform = findViewById(R.id.spinnerPlatform);
        title = findViewById(R.id.editTitle);
        description = findViewById(R.id.editDescription);
        hashtags = findViewById(R.id.editHashtags);
        visibility = findViewById(R.id.spinnerVisibility);
        datePicker = findViewById(R.id.datePicker);
        timePicker = findViewById(R.id.timePicker);
        timePicker.setIs24HourView(true);

        String[] platforms = {"TikTok", "Instagram", "YouTube Shorts", "YouTube classique"};
        platform.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, platforms));
        String[] visibilities = {"Public", "Non répertorié", "Privé"};
        visibility.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, visibilities));

        TextView videoName = findViewById(R.id.textScheduledVideo);
        if (videoPath != null) videoName.setText(new File(videoPath).getName());
        else videoName.setText("Aucune vidéo sélectionnée");

        findViewById(R.id.buttonSaveSchedule).setOnClickListener(v -> saveSchedule());
        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        findViewById(R.id.buttonOpenQueue).setOnClickListener(v -> startActivity(new Intent(this, QueueActivity.class)));
    }

    private void saveSchedule() {
        if (videoPath == null || !new File(videoPath).exists()) {
            Toast.makeText(this, "Vidéo introuvable", Toast.LENGTH_SHORT).show();
            return;
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, datePicker.getYear());
        c.set(Calendar.MONTH, datePicker.getMonth());
        c.set(Calendar.DAY_OF_MONTH, datePicker.getDayOfMonth());
        int hour;
        int minute;
        if (Build.VERSION.SDK_INT >= 23) {
            hour = timePicker.getHour();
            minute = timePicker.getMinute();
        } else {
            Integer h = timePicker.getCurrentHour();
            Integer m = timePicker.getCurrentMinute();
            hour = h == null ? 0 : h;
            minute = m == null ? 0 : m;
        }
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            Toast.makeText(this, "Choisis une heure future", Toast.LENGTH_SHORT).show();
            return;
        }

        PublicationTask task = new PublicationTask();
        task.videoPath = videoPath;
        task.platform = String.valueOf(platform.getSelectedItem());
        task.title = title.getText().toString().trim();
        task.description = description.getText().toString().trim();
        task.hashtags = hashtags.getText().toString().trim();
        task.visibility = String.valueOf(visibility.getSelectedItem());
        task.scheduledAt = c.getTimeInMillis();

        if ("TikTok".equals(task.platform)) {
            task.status = "À TRANSMETTRE AU BOT TIKTOK";
            PublicationTaskRepository.save(this, task);
            boolean sent = PublicationAlarmReceiver.dispatchNow(this, task);
            if (sent) {
                Toast.makeText(this, "Mission transmise au Bot TikTok : il va programmer la vidéo maintenant pour la date choisie.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Impossible de transmettre la mission au Bot TikTok", Toast.LENGTH_LONG).show();
            }
        } else {
            task.status = "PROGRAMMÉ";
            PublicationTaskRepository.save(this, task);
            BootReceiver.schedule(this, task);
            Toast.makeText(this, "Publication programmée. Le bot spécialisé de ce réseau sera calibré dans une prochaine mise à jour.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
