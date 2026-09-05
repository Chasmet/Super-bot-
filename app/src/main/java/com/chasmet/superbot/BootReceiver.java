package com.chasmet.superbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        List<PublicationTask> tasks = PublicationTaskRepository.load(context);
        for (PublicationTask task : tasks) {
            if (task.scheduledAt <= now) continue;
            if ("TikTok".equals(task.platform)) continue;
            if (!"PROGRAMMÉ".equals(task.status)) continue;
            schedule(context, task);
        }
    }

    public static void schedule(Context context, PublicationTask task) {
        if (task == null || "TikTok".equals(task.platform)) return;
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        Intent i = new Intent(context, PublicationAlarmReceiver.class);
        i.putExtra("task_id", task.id);
        PendingIntent pending = PendingIntent.getBroadcast(
                context,
                task.id.hashCode(),
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            } else {
                alarm.set(AlarmManager.RTC_WAKEUP, task.scheduledAt, pending);
            }
        }
    }
}
