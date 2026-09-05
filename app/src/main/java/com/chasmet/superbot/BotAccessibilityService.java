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
                if (runTikTok(root, task)) return;
            }
            runGeneric(root, task);
        } finally {
            root.recycle();
        }
    }

    private boolean runTikTok(AccessibilityNodeInfo root, PublicationTask task) {
        String state = getState(task.id);

        if (containsAny(root, "publication programmée", "post scheduled", "programmé", "scheduled")) {
            finish(task, "PROGRAMMÉ");
            return true;
        }
        if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
            finish(task, "PUBLIÉ");
            return true;
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

        // Sur certaines versions TikTok, l'option est plus bas dans la feuille « Plus d'options ».
        if (state.equals("TIKTOK_MORE_OPTIONS")) {
            if (clickScheduleControl(root)) {
                return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
            }
            if (scrollForward(root)) {
                return mark(task, "TIKTOK_MORE_OPTIONS_SCROLL", "TIKTOK — RECHERCHE DE PROGRAMMER LA PUBLICATION");
            }
        }

        // Après défilement, rechercher de nouveau le libellé ou le commutateur associé.
        if (state.equals("TIKTOK_MORE_OPTIONS_SCROLL")) {
            if (clickScheduleControl(root)) {
                return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
            }
            if (scrollForward(root)) {
                return mark(task, "TIKTOK_MORE_OPTIONS_SCROLL", "TIKTOK — RECHERCHE DE PROGRAMMER LA PUBLICATION");
            }
        }

        // Cas où l'option est déjà visible sans passer par l'état attendu.
        if (!state.startsWith("TIKTOK_PICKER_")
                && !state.equals("TIKTOK_SCHEDULE_READY")
                && !state.equals("TIKTOK_CONFIRMING")
                && clickScheduleControl(root)) {
            return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
        }

        if (state.equals("TIKTOK_SCHEDULE_OPEN") || state.startsWith("TIKTOK_PICKER_")
                || containsAny(root, "date et heure de publication", "date and time", "date", "heure", "time")) {

            Date when = new Date(task.scheduledAt);
            String day = new SimpleDateFormat("d", Locale.FRANCE).format(when);
            String hourMinute = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(when);
            String hour = new SimpleDateFormat("HH", Locale.FRANCE).format(when);
            String minute = new SimpleDateFormat("mm", Locale.FRANCE).format(when);

            if (state.equals("TIKTOK_SCHEDULE_OPEN")) {
                if (clickExact(root, day)) {
                    return mark(task, "TIKTOK_PICKER_DATE_SET", "TIKTOK — DATE CHOISIE");
                }
            }

            if (state.equals("TIKTOK_PICKER_DATE_SET")) {
                if (clickExact(root, hourMinute)) {
                    return mark(task, "TIKTOK_PICKER_TIME_SET", "TIKTOK — HEURE CHOISIE");
                }
                if (clickExact(root, hour)) {
                    return mark(task, "TIKTOK_PICKER_HOUR_SET", "TIKTOK — HEURE CHOISIE");
                }
            }

            if (state.equals("TIKTOK_PICKER_HOUR_SET")) {
                if (clickExact(root, minute)) {
                    return mark(task, "TIKTOK_PICKER_TIME_SET", "TIKTOK — MINUTES CHOISIES");
                }
            }

            if (state.equals("TIKTOK_PICKER_TIME_SET") && clickFirst(root, "terminé", "done")) {
                return mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — DATE ET HEURE VALIDÉES");
            }
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
        for (AccessibilityNodeInfo node : editable) node.recycle();
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
