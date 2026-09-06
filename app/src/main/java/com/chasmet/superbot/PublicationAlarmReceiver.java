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

    public static boolean isSuperBotAwake(Context context) {
        return context.getSharedPreferences("superbot_runtime", Context.MODE_PRIVATE)
                .getBoolean("awake", true);
    }

    public static void setSuperBotAwake(Context context, boolean awake) {
        context.getSharedPreferences("superbot_runtime", Context.MODE_PRIVATE)
                .edit().putBoolean("awake", awake).apply();
        if (!awake) {
            context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                    .edit().remove("active_task_id").apply();
        }
    }

    public static boolean dispatchNow(Context context, PublicationTask task) {
        if (!isSuperBotAwake(context)) {
            task.status = "EN VEILLE • Super Bot déconnecté";
            PublicationTaskRepository.save(context, task);
            context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                    .edit().remove("active_task_id").apply();
            return false;
        }

        File video = new File(task.videoPath == null ? "" : task.videoPath);
        if (!video.exists() || !video.isFile()) {
            task.status = "ERREUR • vidéo introuvable";
            PublicationTaskRepository.save(context, task);
            return false;
        }

        task.status = "TRANSMIS AU BOT • " + task.platform;
        PublicationTaskRepository.save(context, task);
        context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                .edit().putString("active_task_id", task.id).remove("state_" + task.id).apply();

        String metadata = buildMetadata(task);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Super Bot", metadata));

        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", video);
            String[] packages = packagesFor(task.platform);
            if (packages == null || packages.length == 0) {
                throw new IllegalStateException("plateforme sans package cible");
            }

            Exception last = null;
            for (String pkg : packages) {
                try {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("video/mp4");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.putExtra(Intent.EXTRA_TEXT, metadata);
                    share.setClipData(ClipData.newRawUri("Super Bot video", uri));
                    share.setPackage(pkg);
                    share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(share);
                    task.status = "BOT OUVERT • " + pkg;
                    PublicationTaskRepository.save(context, task);
                    return true;
                } catch (Exception e) {
                    last = e;
                }
            }

            if (last != null) throw last;
            throw new IllegalStateException("application cible indisponible");
        } catch (Exception error) {
            String detail = error.getClass().getSimpleName();
            if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
                detail += " • " + error.getMessage().trim();
            }
            task.status = "ERREUR DISPATCH • " + detail;
            PublicationTaskRepository.save(context, task);
            context.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE)
                    .edit()
                    .remove("active_task_id")
                    .putString("last_dispatch_error", detail)
                    .apply();
            return false;
        }
    }

    static String packageFor(String platform) {
        String[] packages = packagesFor(platform);
        return packages == null || packages.length == 0 ? null : packages[0];
    }

    static String[] packagesFor(String platform) {
        if (platform == null) return null;
        switch (platform) {
            case "TikTok": return new String[]{"com.zhiliaoapp.musically", "com.ss.android.ugc.trill"};
            case "Instagram": return new String[]{"com.instagram.android"};
            case "YouTube Shorts":
            case "YouTube classique": return new String[]{"com.google.android.youtube"};
            case "X": return new String[]{"com.twitter.android", "com.x.android"};
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
