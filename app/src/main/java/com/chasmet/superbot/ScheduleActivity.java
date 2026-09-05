package com.chasmet.superbot;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

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
        c.set(Calendar.HOUR_OF_DAY, timePicker.getHour());
        c.set(Calendar.MINUTE, timePicker.getMinute());
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
        task.status = "PROGRAMMÉ";
        PublicationTaskRepository.save(this, task);
        scheduleAlarm(task);
        Toast.makeText(this, "Publication programmée", Toast.LENGTH_LONG).show();
        finish();
    }

    private void scheduleAlarm(PublicationTask task) {
        AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, PublicationAlarmReceiver.class);
        intent.putExtra("task_id", task.id);
        PendingIntent pending = PendingIntent.getBroadcast(
                this,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        if (alarm != null) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            } catch (SecurityException e) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            }
        }
    }
}
