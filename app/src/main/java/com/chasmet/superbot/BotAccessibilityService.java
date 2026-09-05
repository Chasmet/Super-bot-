package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BotAccessibilityService extends AccessibilityService {
    private long lastActionAt = 0L;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String taskId = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("active_task_id", "");
        if (taskId.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, taskId);
        if (task == null) return;

        String expected = PublicationAlarmReceiver.packageFor(task.platform);
        String current = event.getPackageName().toString();
        if (expected == null || !expected.equals(current)) return;
        if (System.currentTimeMillis() - lastActionAt < 700L) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
                task.status = "PUBLIÉ";
                PublicationTaskRepository.save(this, task);
                clearActiveTask();
                return;
            }

            boolean filled = fillMetadata(root, task);
            if (filled) {
                task.status = "MÉTADONNÉES REMPLIES";
                PublicationTaskRepository.save(this, task);
                lastActionAt = System.currentTimeMillis();
                return;
            }

            if (clickFirst(root, "suivant", "next", "continuer", "continue")) {
                task.status = "NAVIGATION EN COURS";
                PublicationTaskRepository.save(this, task);
                lastActionAt = System.currentTimeMillis();
                return;
            }

            if (clickFirst(root, "publier", "post", "mettre en ligne", "upload")) {
                task.status = "VALIDATION ENVOYÉE";
                PublicationTaskRepository.save(this, task);
                lastActionAt = System.currentTimeMillis();
            }
        } finally {
            root.recycle();
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
            String descriptor = descriptor(node);
            String value = null;
            if (descriptor.contains("title") || descriptor.contains("titre")) {
                value = safe(task.title);
            } else if (descriptor.contains("description") || descriptor.contains("caption") || descriptor.contains("légende") || descriptor.contains("legende")) {
                value = joined(task.description, task.hashtags);
            } else if (descriptor.contains("hashtag")) {
                value = safe(task.hashtags);
            } else if (editable.size() == 1) {
                value = combined;
            }
            if (!TextUtils.isEmpty(value) && setText(node, value)) changed = true;
        }
        for (AccessibilityNodeInfo node : editable) node.recycle();
        return changed;
    }

    private static void collectEditable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isEditable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectEditable(child, out);
                child.recycle();
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
        if (!node.isEditable()) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static boolean clickFirst(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                AccessibilityNodeInfo target = n;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null && target.isEnabled() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    for (AccessibilityNodeInfo x : nodes) x.recycle();
                    return true;
                }
            }
            for (AccessibilityNodeInfo x : nodes) x.recycle();
        }
        return false;
    }

    private static boolean containsAny(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            boolean found = nodes != null && !nodes.isEmpty();
            if (nodes != null) for (AccessibilityNodeInfo n : nodes) n.recycle();
            if (found) return true;
        }
        return false;
    }

    private void clearActiveTask() {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit().remove("active_task_id").apply();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String joined(String a, String b) {
        String x = safe(a), y = safe(b);
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + "\n\n" + y;
    }

    @Override public void onInterrupt() {}
}
