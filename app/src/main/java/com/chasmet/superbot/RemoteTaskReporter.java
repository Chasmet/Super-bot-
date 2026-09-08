package com.chasmet.superbot;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteTaskReporter {
    private static final String BASE_URL = "https://asset-chk-mcp.onrender.com/superbot";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private RemoteTaskReporter() {}

    public static void progress(Context context, PublicationTask task, String phase, String message) {
        report(task, true, phase, message);
    }

    public static void completed(Context context, PublicationTask task, String message) {
        report(task, true, "completed", message == null || message.isEmpty() ? "scheduled_confirmed" : message);
    }

    public static void failed(Context context, PublicationTask task, String error) {
        report(task, false, "failed", error == null || error.isEmpty() ? "unknown_error" : error);
    }

    private static void report(PublicationTask task, boolean ok, String phase, String message) {
        if (task == null || task.remoteCommandId == null || task.remoteCommandId.trim().isEmpty()) return;
        final String commandId = task.remoteCommandId.trim();
        IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject result = new JSONObject();
                result.put("ok", ok);
                result.put("phase", phase);
                result.put("message", message);
                result.put("taskId", task.id == null ? "" : task.id);
                result.put("timestamp", System.currentTimeMillis());
                byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(BASE_URL + "/device/commands/" + commandId + "/result").openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
                int code = connection.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (in != null) in.close();
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
