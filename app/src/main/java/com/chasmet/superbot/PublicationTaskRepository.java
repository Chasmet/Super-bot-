package com.chasmet.superbot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PublicationTaskRepository {
    private static final String PREFS = "superbot_publications";
    private static final String KEY = "tasks";
    private PublicationTaskRepository() {}

    public static synchronized PublicationTask save(Context context, PublicationTask task) {
        if (task.id == null || task.id.isEmpty()) task.id = UUID.randomUUID().toString();
        List<PublicationTask> tasks = load(context);
        boolean replaced = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (task.id.equals(tasks.get(i).id)) {
                tasks.set(i, task);
                replaced = true;
                break;
            }
        }
        if (!replaced) tasks.add(task);
        write(context, tasks);
        return task;
    }

    public static synchronized List<PublicationTask> load(Context context) {
        List<PublicationTask> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                PublicationTask t = new PublicationTask();
                t.id = o.optString("id", "");
                t.videoPath = o.optString("videoPath", "");
                t.platform = o.optString("platform", "");
                t.title = o.optString("title", "");
                t.description = o.optString("description", "");
                t.hashtags = o.optString("hashtags", "");
                t.visibility = o.optString("visibility", "Public");
                t.scheduledAt = o.optLong("scheduledAt", 0L);
                t.status = o.optString("status", "PROGRAMMÉ");
                result.add(t);
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static synchronized PublicationTask find(Context context, String id) {
        for (PublicationTask t : load(context)) if (t.id.equals(id)) return t;
        return null;
    }

    public static synchronized void delete(Context context, String id) {
        List<PublicationTask> tasks = load(context);
        tasks.removeIf(t -> id.equals(t.id));
        write(context, tasks);
    }

    private static void write(Context context, List<PublicationTask> tasks) {
        JSONArray array = new JSONArray();
        for (PublicationTask t : tasks) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("videoPath", t.videoPath);
                o.put("platform", t.platform);
                o.put("title", t.title);
                o.put("description", t.description);
                o.put("hashtags", t.hashtags);
                o.put("visibility", t.visibility);
                o.put("scheduledAt", t.scheduledAt);
                o.put("status", t.status);
                array.put(o);
            } catch (Exception ignored) {}
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY, array.toString()).apply();
    }
}
