package com.chasmet.superbot;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public final class RemotePublicationMission {
    private RemotePublicationMission() {}

    public static Result dispatch(Context context, JSONObject payload, String commandId) {
        try {
            PublicationTask existing=PublicationTaskRepository.find(context,commandId);
            if(existing!=null)return new Result(true,"publication_already_received:"+existing.id);
            if(PublicationCoordinator.busy(context))return new Result(false,"publication_busy");
            String platform = normalize(payload.optString("platform", ""));
            if (platform == null) return new Result(false, "invalid_platform");

            PublicationTask task = new PublicationTask();
            task.id = commandId;
            task.platform = platform;
            task.videoPath = resolveVideoPath(context, payload.optString("mediaUri", ""));
            task.title = payload.optString("title", "");
            task.description = payload.optString("description", "");
            task.hashtags = payload.optString("hashtags", "");
            task.visibility = payload.optString("visibility", "Public");
            long when = payload.optLong("scheduledAt", System.currentTimeMillis());
            if (when > 0 && when < 100000000000L) when *= 1000L;
            if(when<=System.currentTimeMillis()+60000)return new Result(false,"scheduled_time_expired_or_too_close");
            task.scheduledAt = when;
            task.status = "MISSION MCP • reçue";
            PublicationTaskRepository.save(context, task);
            PublicationCoordinator.prefs(context).edit().putBoolean("remote_"+task.id,true).commit();

            if (task.videoPath == null || task.videoPath.isEmpty() || !new File(task.videoPath).exists()) {
                task.status = "ERREUR MCP • vidéo introuvable";
                PublicationTaskRepository.save(context, task);
                return new Result(false, "media_not_found:" + task.id);
            }

            boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
            if (started) {
                return new Result(true, "publication_running:" + task.id + ":" + task.platform);
            }

            PublicationTask latest = PublicationTaskRepository.find(context, task.id);
            String status = latest == null ? task.status : latest.status;
            if (status == null || status.trim().isEmpty()) status = "dispatch_failed";
            return new Result(false, "publication_dispatch_failed:" + task.id + ":" + status);
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
        if(!raw.isEmpty()){
            java.util.List<File> matches=new java.util.ArrayList<>();findNamed(root,raw,matches);
            return matches.size()==1?matches.get(0).getAbsolutePath():"";
        }
        File latest = newestMp4(root, null);
        return latest == null ? "" : latest.getAbsolutePath();
    }

    private static void findNamed(File dir,String name,java.util.List<File> out){
        File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){if(f.isDirectory())findNamed(f,name,out);else if(f.getName().equals(name))out.add(f);}
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
