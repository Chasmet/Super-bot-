package com.chasmet.superbot;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

public class PublicationAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = intent == null ? null : intent.getStringExtra("task_id");
        if (id == null) return;
        PublicationTask task = PublicationTaskRepository.find(context, id);
        if (task == null) return;
        dispatchNow(context, task);
    }

    public static boolean dispatchNow(Context context, PublicationTask task) {
        File video = new File(task.videoPath == null ? "" : task.videoPath);
        if (!video.exists()) {
            task.status = "ERREUR • vidéo introuvable";
            PublicationTaskRepository.save(context, task);
            return false;
        }

        task.status = "TRANSMIS AU BOT • " + task.platform;
        PublicationTaskRepository.save(context, task);
        context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                .edit()
                .putString("active_task_id", task.id)
                .remove("state_" + task.id)
                .apply();

        String metadata = buildMetadata(task);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Super Bot", metadata));

        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", video);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("video/mp4");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_TEXT, metadata);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String pkg = packageFor(task.platform);
            if (pkg != null) share.setPackage(pkg);
            context.startActivity(share);
            return true;
        } catch (Exception error) {
            task.status = "ERREUR • application indisponible";
            PublicationTaskRepository.save(context, task);
            context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                    .edit().remove("active_task_id").apply();
            return false;
        }
    }

    static String packageFor(String platform) {
        if (platform == null) return null;
        switch (platform) {
            case "TikTok": return "com.zhiliaoapp.musically";
            case "Instagram": return "com.instagram.android";
            case "YouTube Shorts":
            case "YouTube classique": return "com.google.android.youtube";
            default: return null;
        }
    }

    static String buildMetadata(PublicationTask task) {
        StringBuilder b = new StringBuilder();
        if (task.title != null && !task.title.isEmpty()) b.append(task.title.trim());
        if (task.description != null && !task.description.isEmpty()) {
            if (b.length() > 0) b.append("\n\n");
            b.append(task.description.trim());
        }
        if (task.hashtags != null && !task.hashtags.isEmpty()) {
            if (b.length() > 0) b.append("\n\n");
            b.append(task.hashtags.trim());
        }
        return b.toString();
    }
}
