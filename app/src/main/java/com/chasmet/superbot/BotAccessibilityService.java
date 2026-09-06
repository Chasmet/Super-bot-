package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BotAccessibilityService extends AccessibilityService {
    private long lastActionAt = 0L;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private int retryCount;
    private String retryTask = "";
    private final Runnable retry = () -> processWindow();

    private void processWindow() {
        String id = getSharedPreferences("superbot_bot_state", MODE_PRIVATE).getString("active_task_id", "");
        PublicationTask task = PublicationTaskRepository.find(this, id);
        if (!id.equals(retryTask)) { retryTask = id; retryCount = 0; }
        if (task == null || !isTikTok(task) || getState(id).equals("TIKTOK_PAUSED")) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (!PublicationAlarmReceiver.packageFor(task.platform).contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName())) return;
            if (++retryCount > 180) {
                mark(task, "TIKTOK_PAUSED", "TIKTOK — délai dépassé, vérifier la programmation");
                return;
            }
            runTikTok(root, task);
            retryHandler.removeCallbacks(retry);
            retryHandler.postDelayed(retry, 1100);
        } finally { root.recycle(); }
    }

    @Override public void onDestroy() {
        retryHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String taskId = getSharedPreferences("superbot_bot_state", MODE_PRIVATE).getString("active_task_id", "");
        if (taskId.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, taskId);
        if (task == null) return;
        String expected = PublicationAlarmReceiver.packageFor(task.platform);
        String current = event.getPackageName().toString();
        if (expected == null || !expected.equals(current)) return;
        if (System.currentTimeMillis() - lastActionAt < 850L) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isTikTok(task)) {
                if (getState(task.id).equals("TIKTOK_PAUSED")) return;
                retryHandler.removeCallbacks(retry);
                retryHandler.postDelayed(retry, 1100);
                runTikTok(root, task);
                return;
            }
            runGeneric(root, task);
        } finally {
            root.recycle();
        }
    }

    private boolean runTikTok(AccessibilityNodeInfo root, PublicationTask task) {
        String state = getState(task.id);

        if (state.equals("TIKTOK_CONFIRMING") && containsAny(root, "publication programmée", "post scheduled")) {
            finish(task, "PROGRAMMÉ");
            return true;
        }
        if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
            finish(task, "PUBLIÉ");
            return true;
        }

        if (containsAny(root, "Date et heure de publication", "Date and time of publication")) {
            return adjustPicker(root, task);
        }

        boolean future = task.scheduledAt > System.currentTimeMillis() + 60000L;
        if (!future) return false;

        if (!state.startsWith("TIKTOK_") && fillMetadata(root, task)) {
            return mark(task, "TIKTOK_METADATA", "TIKTOK — MÉTADONNÉES REMPLIES");
        }

        if ((state.isEmpty() || state.equals("TIKTOK_METADATA"))
                && containsAny(root, "plus d’options", "plus d'options", "more options")) {
            if (clickFirst(root, "plus d’options", "plus d'options", "more options")) {
                return mark(task, "TIKTOK_MORE_OPTIONS", "TIKTOK — PLUS D'OPTIONS");
            }
        }

        if (state.equals("TIKTOK_MORE_OPTIONS") || state.equals("TIKTOK_MORE_OPTIONS_SCROLL")) {
            if (clickScheduleControl(root)) {
                clearPickerAttempts(task.id);
                return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
            }
            if (scrollForward(root)) {
                return mark(task, "TIKTOK_MORE_OPTIONS_SCROLL", "TIKTOK — RECHERCHE DE PROGRAMMER LA PUBLICATION");
            }
        }

        if (!state.startsWith("TIKTOK_PICKER_")
                && !state.equals("TIKTOK_SCHEDULE_READY")
                && !state.equals("TIKTOK_CONFIRMING")
                && clickScheduleControl(root)) {
            clearPickerAttempts(task.id);
            return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
        }

        if (state.equals("TIKTOK_SCHEDULE_READY") && clickFirst(root, "publier", "post")) {
            return mark(task, "TIKTOK_CONFIRMING", "TIKTOK — VALIDATION DE LA PROGRAMMATION");
        }

        if (!state.startsWith("TIKTOK_PICKER_")
                && !state.equals("TIKTOK_SCHEDULE_READY")
                && !state.equals("TIKTOK_CONFIRMING")
                && clickFirst(root, "suivant", "next", "continuer", "continue")) {
            return mark(task, state, "TIKTOK — NAVIGATION EN COURS");
        }
        return false;
    }

    private boolean adjustPicker(AccessibilityNodeInfo root, PublicationTask task) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectScrollable(root, all);
        List<AccessibilityNodeInfo> wheels = new ArrayList<>();
        for (AccessibilityNodeInfo node : all) {
            android.graphics.Rect box = new android.graphics.Rect();
            node.getBoundsInScreen(box);
            if (!node.isVisibleToUser() || box.isEmpty()) continue;
            boolean containsWheel = false;
            for (AccessibilityNodeInfo other : all) {
                android.graphics.Rect child = new android.graphics.Rect();
                other.getBoundsInScreen(child);
                if (!node.equals(other) && !box.equals(child) && box.contains(child)) containsWheel = true;
            }
            if (!containsWheel) wheels.add(node);
        }
        java.util.Collections.sort(wheels, (a,b) -> {
            android.graphics.Rect x = new android.graphics.Rect(), y = new android.graphics.Rect();
            a.getBoundsInScreen(x); b.getBoundsInScreen(y);
            return Integer.compare(x.centerX(), y.centerX());
        });
        try {
            if (wheels.size() != 3) {
                return mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — roues non lisibles, aucune validation envoyée");
            }
            java.util.Calendar target = java.util.Calendar.getInstance();
            target.setTimeInMillis(task.scheduledAt);
            if (task.scheduledAt <= System.currentTimeMillis() + 60000) {
                return mark(task, "TIKTOK_PAUSED", "TIKTOK — date trop proche ou dépassée");
            }
            if (task.scheduledAt - System.currentTimeMillis() > 31L * 86400000L)
                return mark(task, "TIKTOK_PAUSED", "TIKTOK — date hors de la plage prise en charge");
            for (int i = 0; i < 3; i++) {
                String value = centeredText(wheels.get(i));
                boolean matches;
                int direction = 1;
                if (i == 0) {
                    matches = dateMatches(value, target);
                    if (value.isEmpty()) return mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — date centrale non lisible");
                    java.util.Calendar probe = java.util.Calendar.getInstance();
                    for (int offset=0; offset<=31; offset++) {
                        if (dateMatches(value, probe)) {
                            direction = Long.compare(target.getTimeInMillis(), probe.getTimeInMillis());
                            break;
                        }
                        probe.add(java.util.Calendar.DAY_OF_YEAR, 1);
                    }
                    // Prefer a visible matching date; never confuse a day with an hour.
                    if (!matches && clickDate(wheels.get(i), target))
                        return mark(task, "TIKTOK_PICKER_ADJUST", "TIKTOK — réglage de la date");
                } else {
                    int wanted = target.get(i == 1 ? java.util.Calendar.HOUR_OF_DAY : java.util.Calendar.MINUTE);
                    int current;
                    try { current = Integer.parseInt(value.trim()); }
                    catch (NumberFormatException e) { return mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — valeur centrale non lisible"); }
                    matches = current == wanted;
                    direction = Integer.compare(wanted, current);
                }
                if (!matches) {
                    boolean moved = wheels.get(i).performAction(direction >= 0
                            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    return mark(task, "TIKTOK_PICKER_ADJUST", moved ? "TIKTOK — réglage des roues en cours"
                            : "TIKTOK — roue inaccessible, vérifier l'écran");
                }
            }
            // Require two consecutive reads after animations settle.
            if (!getState(task.id).equals("TIKTOK_PICKER_VERIFIED"))
                return mark(task, "TIKTOK_PICKER_VERIFIED", "TIKTOK — date et heure contrôlées");
            if (clickExact(root, "Terminé") || clickExact(root, "Done"))
                return mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — date et heure validées");
            return true;
        } finally { recycle(all); }
    }

    private static String centeredText(AccessibilityNodeInfo wheel) {
        android.graphics.Rect bounds = new android.graphics.Rect(); wheel.getBoundsInScreen(bounds);
        List<AccessibilityNodeInfo> labels = new ArrayList<>(); collectLabels(wheel, labels);
        String value = ""; int distance = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : labels) {
            android.graphics.Rect box = new android.graphics.Rect(); node.getBoundsInScreen(box);
            int delta = Math.abs(box.centerY() - bounds.centerY());
            if (node.isVisibleToUser() && bounds.contains(box.centerX(), box.centerY()) && delta < distance) {
                distance = delta; value = node.getText().toString();
            }
        }
        recycle(labels); return value;
    }

    private static void collectLabels(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node.getChildCount() == 0 && node.getText() != null) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { collectLabels(child, out); child.recycle(); }
        }
    }

    private static boolean dateMatches(String text, java.util.Calendar target) {
        String value = text.toLowerCase(Locale.FRANCE).replace(".", "").replace("’", "'").trim();
        java.util.Calendar today = java.util.Calendar.getInstance();
        if (value.equals("aujourd'hui") || value.equals("today"))
            return target.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                    && target.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        String day = String.valueOf(target.get(java.util.Calendar.DAY_OF_MONTH));
        String month = new SimpleDateFormat("MMM", Locale.FRANCE).format(target.getTime()).toLowerCase(Locale.FRANCE).replace(".", "");
        return value.contains(month) && java.util.regex.Pattern.compile("(?<![0-9])" + day + "(?![0-9])").matcher(value).find();
    }

    private static boolean clickDate(AccessibilityNodeInfo wheel, java.util.Calendar target) {
        List<AccessibilityNodeInfo> labels = new ArrayList<>(); collectLabels(wheel, labels);
        try {
            for (AccessibilityNodeInfo node : labels)
                if (node.isVisibleToUser() && dateMatches(node.getText().toString(), target)
                        && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            return false;
        } finally { recycle(labels); }
    }

    private static void collectScrollable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isScrollable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScrollable(child, out);
                child.recycle();
            }
        }
    }

    private int getPickerAttempt(String taskId, String kind) {
        return getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getInt("picker_" + kind + "_" + taskId, 0);
    }

    private void setPickerAttempt(String taskId, String kind, int value) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .edit().putInt("picker_" + kind + "_" + taskId, value).apply();
    }

    private void resetPickerAttempt(String taskId, String kind) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .edit().remove("picker_" + kind + "_" + taskId).apply();
    }

    private void clearPickerAttempts(String taskId) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                .remove("picker_date_" + taskId)
                .remove("picker_hour_" + taskId)
                .remove("picker_minute_" + taskId)
                .remove("picker_time_" + taskId)
                .apply();
    }

    private boolean clickScheduleControl(AccessibilityNodeInfo root) {
        String[] labels = {"programmer la publication", "programmer", "schedule post", "schedule"};
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (clickNode(node) || clickNearbyCheckable(node)) {
                    recycle(nodes);
                    return true;
                }
            }
            recycle(nodes);
        }
        return false;
    }

    private static boolean clickNearbyCheckable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int level = 0; level < 4 && current != null; level++) {
                if (clickCheckableDescendant(current)) return true;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private static boolean clickCheckableDescendant(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEnabled() && (node.isCheckable() || node.isClickable())) {
            CharSequence cls = node.getClassName();
            String className = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
            if (node.isCheckable() || className.contains("switch") || className.contains("checkbox")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean clicked = clickCheckableDescendant(child);
                child.recycle();
                if (clicked) return true;
            }
        }
        return false;
    }

    private static boolean scrollForward(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (root.isScrollable() && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                boolean scrolled = scrollForward(child);
                child.recycle();
                if (scrolled) return true;
            }
        }
        return false;
    }

    private void runGeneric(AccessibilityNodeInfo root, PublicationTask task) {
        if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
            finish(task, "PUBLIÉ");
            return;
        }
        if (fillMetadata(root, task)) {
            mark(task, getState(task.id), "MÉTADONNÉES REMPLIES");
            return;
        }
        if (clickFirst(root, "suivant", "next", "continuer", "continue")) {
            mark(task, getState(task.id), "NAVIGATION EN COURS");
            return;
        }
        if (clickFirst(root, "publier", "post", "mettre en ligne", "upload")) {
            mark(task, getState(task.id), "VALIDATION ENVOYÉE");
        }
    }

    private boolean fillMetadata(AccessibilityNodeInfo root, PublicationTask task) {
        List<AccessibilityNodeInfo> editable = new ArrayList<>();
        collectEditable(root, editable);
        if (editable.isEmpty()) return false;
        String combined = PublicationAlarmReceiver.buildMetadata(task);
        boolean changed = false;
        for (AccessibilityNodeInfo node : editable) {
            if (!node.isEditable()) continue;
            CharSequence currentText = node.getText();
            if (currentText != null && currentText.length() > 0) continue;
            String d = descriptor(node);
            String value = null;
            if (d.contains("title") || d.contains("titre")) value = safe(task.title);
            else if (d.contains("description") || d.contains("caption") || d.contains("légende") || d.contains("legende")) value = joined(task.description, task.hashtags);
            else if (d.contains("hashtag")) value = safe(task.hashtags);
            else if (editable.size() == 1) value = combined;
            if (!TextUtils.isEmpty(value) && setText(node, value)) changed = true;
        }
        recycle(editable);
        return changed;
    }

    private static void collectEditable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isEditable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                collectEditable(c, out);
                c.recycle();
            }
        }
    }

    private static String descriptor(AccessibilityNodeInfo node) {
        StringBuilder b = new StringBuilder();
        if (node.getViewIdResourceName() != null) b.append(node.getViewIdResourceName()).append(' ');
        if (node.getContentDescription() != null) b.append(node.getContentDescription()).append(' ');
        if (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) b.append(node.getHintText()).append(' ');
        if (node.getText() != null) b.append(node.getText()).append(' ');
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.isEditable() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
    }

    private static boolean clickExact(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo x : nodes) {
            CharSequence t = x.getText();
            if (t != null && label.equalsIgnoreCase(t.toString().trim()) && clickNode(x)) {
                recycle(nodes);
                return true;
            }
        }
        recycle(nodes);
        return false;
    }

    private static boolean clickFirst(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo x : nodes) {
                if (clickNode(x)) {
                    recycle(nodes);
                    return true;
                }
            }
            recycle(nodes);
        }
        return false;
    }

    private static boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(n);
        try {
            while (current != null) {
                if (current.isClickable() && current.isEnabled()) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            return false;
        } finally {
            if (current != null) current.recycle();
        }
    }

    private static boolean containsAny(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            boolean found = nodes != null && !nodes.isEmpty();
            if (nodes != null) recycle(nodes);
            if (found) return true;
        }
        return false;
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) {
            if (node != null) node.recycle();
        }
    }

    private boolean mark(PublicationTask task, String state, String status) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .edit().putString("state_" + task.id, state).apply();
        task.status = status;
        PublicationTaskRepository.save(this, task);
        lastActionAt = System.currentTimeMillis();
        return true;
    }

    private String getState(String id) {
        return getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("state_" + id, "");
    }

    private void finish(PublicationTask task, String status) {
        clearPickerAttempts(task.id);
        task.status = status;
        PublicationTaskRepository.save(this, task);
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .edit().remove("active_task_id").remove("state_" + task.id).apply();
    }

    private static boolean isTikTok(PublicationTask t) {
        return t.platform != null && t.platform.toLowerCase(Locale.ROOT).contains("tiktok");
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    private static String joined(String a, String b) {
        String x = safe(a), y = safe(b);
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + "\n\n" + y;
    }

    @Override public void onInterrupt() {}
}
