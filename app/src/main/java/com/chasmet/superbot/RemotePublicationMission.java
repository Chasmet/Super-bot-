package com.chasmet.superbot;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public final class RemotePublicationMission {
    private RemotePublicationMission() {}

    public static Result dispatch(Context context, JSONObject payload) {
        try {
            String platform = normalize(payload.optString("platform", ""));
            if (platform == null) return new Result(false, "invalid_platform");

            PublicationTask task = new PublicationTask();
            task.platform = platform;
            task.videoPath = resolveVideoPath(context, payload.optString("mediaUri", ""));
            task.title = payload.optString("title", "");
            task.description = payload.optString("description", "");
            task.hashtags = payload.optString("hashtags", "");
            task.visibility = payload.optString("visibility", "Public");
            long when = payload.optLong("scheduledAt", System.currentTimeMillis());
            if (when > 0 && when < 100000000000L) when *= 1000L;
            task.scheduledAt = when;
            task.status = "MISSION MCP • reçue";
            PublicationTaskRepository.save(context, task);

            if (task.videoPath == null || task.videoPath.isEmpty() || !new File(task.videoPath).exists()) {
                task.status = "ERREUR MCP • vidéo introuvable";
                PublicationTaskRepository.save(context, task);
                return new Result(false, "media_not_found:" + task.id);
            }

            boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
            return new Result(started,
                    started ? "publication_dispatched:" + task.id + ":" + task.platform
                            : "publication_dispatch_failed:" + task.id);
        } catch (Exception e) {
            return new Result(false, e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private static String normalize(String value) {
        String p = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (p.equals("tiktok") || p.equals("tik tok")) return "TikTok";
        if (p.equals("instagram") || p.equals("instagram reels") || p.equals("reels")) return "Instagram";
        if (p.equals("youtube shorts") || p.equals("youtube short") || p.equals("shorts")) return "YouTube Shorts";
        if (p.equals("youtube") || p.equals("youtube classique")) return "YouTube classique";
        if (p.equals("x") || p.equals("twitter")) return "X";
        return null;
    }

    private static String resolveVideoPath(Context context, String mediaUri) {
        String raw = mediaUri == null ? "" : mediaUri.trim();
        if (!raw.isEmpty()) {
            if (raw.startsWith("file://")) {
                String path = Uri.parse(raw).getPath();
                if (path != null && new File(path).exists()) return path;
            } else {
                File direct = new File(raw);
                if (direct.exists()) return direct.getAbsolutePath();
            }
        }
        File root = new File(context.getExternalFilesDir(null), "Movies/SuperBot");
        File latest = newestMp4(root, null);
        return latest == null ? "" : latest.getAbsolutePath();
    }

    private static File newestMp4(File dir, File best) {
        if (dir == null || !dir.exists()) return best;
        File[] files = dir.listFiles();
        if (files == null) return best;
        File current = best;
        for (File file : files) {
            if (file.isDirectory()) current = newestMp4(file, current);
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")
                    && (current == null || file.lastModified() > current.lastModified())) current = file;
        }
        return current;
    }

    public static final class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }
}
