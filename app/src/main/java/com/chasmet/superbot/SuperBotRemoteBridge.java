package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SuperBotRemoteBridge {
    private static final String BASE_URL = "https://asset-chk-mcp.onrender.com/superbot";
    private static final String DEVICE_ID = "superbot-phone";
    private static final long POLL_MS = 1800L;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            io.execute(() -> {
                try {
                    postState();
                    pollCommands();
                } catch (Exception ignored) {
                } finally {
                    if (running) main.postDelayed(loop, POLL_MS);
                }
            });
        }
    };

    public SuperBotRemoteBridge(AccessibilityService service) {
        this.service = service;
    }

    public void start() {
        if (running) return;
        running = true;
        io.execute(() -> {
            try {
                JSONObject register = new JSONObject();
                register.put("deviceId", DEVICE_ID);
                register.put("androidSdk", Build.VERSION.SDK_INT);
                postJson(BASE_URL + "/device/register", register);
            } catch (Exception ignored) {
            }
            main.post(loop);
        });
    }

    public void stop() {
        running = false;
        main.removeCallbacks(loop);
        io.shutdownNow();
    }

    private void postState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("deviceId", DEVICE_ID);
        state.put("awake", PublicationAlarmReceiver.isSuperBotAwake(service));

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            state.put("packageName", JSONObject.NULL);
            state.put("screenText", "");
            state.put("nodes", new JSONArray());
            postJson(BASE_URL + "/device/state", state);
            return;
        }

        try {
            CharSequence pkg = root.getPackageName();
            state.put("packageName", pkg == null ? JSONObject.NULL : pkg.toString());
            JSONArray nodes = new JSONArray();
            StringBuilder screenText = new StringBuilder();
            collect(root, nodes, screenText, 0);
            state.put("nodes", nodes);
            state.put("screenText", screenText.toString());
        } finally {
            root.recycle();
        }

        postJson(BASE_URL + "/device/state", state);
    }

    private void collect(AccessibilityNodeInfo node, JSONArray out, StringBuilder text, int depth) throws Exception {
        if (node == null || depth > 18 || out.length() >= 220) return;

        String label = nodeText(node);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!label.isEmpty() || node.isClickable() || node.isScrollable() || node.isEditable()) {
            JSONObject item = new JSONObject();
            item.put("text", label);
            item.put("className", node.getClassName() == null ? "" : node.getClassName().toString());
            item.put("viewId", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
            item.put("clickable", node.isClickable());
            item.put("scrollable", node.isScrollable());
            item.put("editable", node.isEditable());
            item.put("enabled", node.isEnabled());
            item.put("visible", node.isVisibleToUser());
            item.put("left", bounds.left);
            item.put("top", bounds.top);
            item.put("right", bounds.right);
            item.put("bottom", bounds.bottom);
            out.put(item);
            if (!label.isEmpty() && text.length() < 12000) {
                if (text.length() > 0) text.append('\n');
                text.append(label);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collect(child, out, text, depth + 1);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    private String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString().trim();
    }

    private void pollCommands() throws Exception {
        String raw = get(BASE_URL + "/device/commands?deviceId=" + DEVICE_ID);
        JSONObject body = new JSONObject(raw);
        JSONArray commands = body.optJSONArray("commands");
        if (commands == null) return;
        for (int i = 0; i < commands.length(); i++) {
            JSONObject command = commands.optJSONObject(i);
            if (command == null) continue;
            execute(command);
        }
    }

    private void execute(JSONObject command) {
        final String commandId = command.optString("id", "");
        final String type = command.optString("type", "");
        final JSONObject payload = command.optJSONObject("payload") == null ? new JSONObject() : command.optJSONObject("payload");

        main.post(() -> {
            boolean ok = false;
            String message;
            try {
                if (!PublicationAlarmReceiver.isSuperBotAwake(service)) {
                    message = "superbot_disconnected";
                } else if (!isAllowedPackage()) {
                    message = "package_not_allowed";
                } else {
                    switch (type) {
                        case "click_text":
                            ok = clickText(payload.optString("text", ""));
                            message = ok ? "clicked_text" : "text_not_clickable";
                            break;
                        case "click_point":
                            ok = clickPoint((float) payload.optDouble("x", -1), (float) payload.optDouble("y", -1));
                            message = ok ? "clicked_point" : "click_failed";
                            break;
                        case "swipe":
                            ok = swipe(
                                    (float) payload.optDouble("x1", -1),
                                    (float) payload.optDouble("y1", -1),
                                    (float) payload.optDouble("x2", -1),
                                    (float) payload.optDouble("y2", -1),
                                    payload.optInt("durationMs", 350));
                            message = ok ? "swipe_started" : "swipe_failed";
                            break;
                        case "back":
                            ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            message = ok ? "back_done" : "back_failed";
                            break;
                        case "cancel":
                            ok = true;
                            message = "cancel_received";
                            break;
                        default:
                            message = "unsupported_command:" + type;
                    }
                }
            } catch (Exception e) {
                message = e.getClass().getSimpleName() + ":" + e.getMessage();
            }
            final boolean resultOk = ok;
            final String resultMessage = message;
            io.execute(() -> sendResult(commandId, resultOk, resultMessage));
        });
    }

    private boolean isAllowedPackage() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            return pkg.equals("com.zhiliaoapp.musically")
                    || pkg.equals("com.ss.android.ugc.trill")
                    || pkg.equals("com.instagram.android")
                    || pkg.equals("com.google.android.youtube")
                    || pkg.equals("com.twitter.android")
                    || pkg.equals("com.x.android");
        } finally {
            root.recycle();
        }
    }

    private boolean clickText(String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(wanted.trim());
            if (matches == null) return false;
            try {
                for (AccessibilityNodeInfo node : matches) {
                    if (node == null || !node.isVisibleToUser()) continue;
                    if (clickNode(node)) return true;
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    if (!b.isEmpty() && clickPoint(b.centerX(), b.centerY())) return true;
                }
            } finally {
                for (AccessibilityNodeInfo node : matches) if (node != null) node.recycle();
            }
            return false;
        } finally {
            root.recycle();
        }
    }

    private boolean clickNode(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(start);
        try {
            while (node != null) {
                if (node.isEnabled() && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = node.getParent();
                node.recycle();
                node = parent;
            }
            return false;
        } finally {
            if (node != null) node.recycle();
        }
    }

    private boolean clickPoint(float x, float y) {
        if (Build.VERSION.SDK_INT < 24 || x < 0 || y < 0) return false;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 1, y + 1);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 90))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (Build.VERSION.SDK_INT < 24 || x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(120, Math.min(1200, durationMs))))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private void sendResult(String commandId, boolean ok, String message) {
        if (commandId == null || commandId.isEmpty()) return;
        try {
            JSONObject result = new JSONObject();
            result.put("ok", ok);
            result.put("message", message);
            result.put("timestamp", System.currentTimeMillis());
            postJson(BASE_URL + "/device/commands/" + commandId + "/result", result);
        } catch (Exception ignored) {
        }
    }

    private String get(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        return readResponse(connection);
    }

    private String postJson(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return readResponse(connection);
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        } finally {
            connection.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + result);
        return result.toString();
    }
}
