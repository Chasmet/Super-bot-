package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BotAccessibilityService extends AccessibilityService {
    private long lastActionAt;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable retryRunnable = this::processCurrentWindow;
    private String activeTask = "";
    private int retryCount;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String taskId = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("active_task_id", "");
        if (taskId.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, taskId);
        if (task == null) return;
        String expectedPackage = PublicationAlarmReceiver.packageFor(task.platform);
        if (expectedPackage == null || !expectedPackage.equals(event.getPackageName().toString())) return;
        if (System.currentTimeMillis() - lastActionAt < 650L) return;
        handler.removeCallbacks(retryRunnable);
        processTask(task);
        handler.postDelayed(retryRunnable, 900L);
    }

    private void processCurrentWindow() {
        String taskId = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("active_task_id", "");
        if (taskId.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, taskId);
        if (task == null) return;
        processTask(task);
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, 900L);
    }

    private void processTask(PublicationTask task) {
        if (!task.id.equals(activeTask)) {
            activeTask = task.id;
            retryCount = 0;
            clearPickerState(task.id);
        }
        if (++retryCount > 240) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — délai dépassé, vérifier l'écran");
            handler.removeCallbacks(retryRunnable);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            CharSequence pkg = root.getPackageName();
            String expected = PublicationAlarmReceiver.packageFor(task.platform);
            if (pkg == null || expected == null || !expected.equals(pkg.toString())) return;
            if (isTikTok(task)) runTikTok(root, task);
            else runGeneric(root, task);
        } finally {
            root.recycle();
        }
    }

    private void runTikTok(AccessibilityNodeInfo root, PublicationTask task) {
        String state = getState(task.id);
        if ("TIKTOK_PAUSED".equals(state)) return;

        if ("TIKTOK_CONFIRMING".equals(state)
                && containsAny(root, "publication programmée", "post scheduled", "scheduled")) {
            finish(task, "PROGRAMMÉ");
            return;
        }

        if (containsAny(root, "Date et heure de publication", "Date and time of publication")) {
            adjustTikTokPicker(root, task);
            return;
        }

        if (!getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getBoolean("meta_ok_" + task.id, false)) {
            if (!ensureTikTokMetadata(root, task)) return;
            mark(task, "TIKTOK_METADATA", "TIKTOK — métadonnées vérifiées");
            return;
        }

        if (containsAny(root, "Plus d’options", "Plus d'options", "More options")) {
            if (clickFirst(root, "Plus d’options", "Plus d'options", "More options")) {
                mark(task, "TIKTOK_MORE_OPTIONS", "TIKTOK — Plus d'options ouvert");
                return;
            }
        }

        if ("TIKTOK_MORE_OPTIONS".equals(state) || "TIKTOK_MORE_OPTIONS_SCROLL".equals(state)
                || containsAny(root, "Programmer la publication", "Schedule post")) {
            if (clickScheduleControl(root)) {
                clearPickerState(task.id);
                mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — programmation ouverte");
                return;
            }
            if (scrollForward(root)) {
                mark(task, "TIKTOK_MORE_OPTIONS_SCROLL", "TIKTOK — recherche de Programmer la publication");
                return;
            }
        }

        if ("TIKTOK_SCHEDULE_READY".equals(state)) {
            if (clickFirst(root, "Publier", "Post")) {
                mark(task, "TIKTOK_CONFIRMING", "TIKTOK — validation de la programmation");
            }
            return;
        }

        if (clickFirst(root, "Suivant", "Next", "Continuer", "Continue")) {
            mark(task, state, "TIKTOK — navigation en cours");
        }
    }

    private boolean ensureTikTokMetadata(AccessibilityNodeInfo root, PublicationTask task) {
        String expected = PublicationAlarmReceiver.buildMetadata(task).trim();
        if (expected.isEmpty()) {
            getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                    .putBoolean("meta_ok_" + task.id, true).apply();
            return true;
        }
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditable(root, fields);
        try {
            for (AccessibilityNodeInfo field : fields) {
                if (!field.isVisibleToUser()) continue;
                String current = field.getText() == null ? "" : field.getText().toString().trim();
                if (expected.equals(current)) {
                    getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                            .putBoolean("meta_ok_" + task.id, true).apply();
                    return true;
                }
                String d = descriptor(field);
                if (!(d.contains("description") || d.contains("caption") || d.contains("légende")
                        || d.contains("legende") || fields.size() == 1)) continue;
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                boolean ok = setText(field, expected);
                if (!ok) {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Super Bot", expected));
                        ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                }
                mark(task, "TIKTOK_METADATA_PENDING",
                        ok ? "TIKTOK — métadonnées envoyées" : "TIKTOK — champ métadonnées inaccessible");
                return false;
            }
        } finally {
            recycle(fields);
        }
        if (clickFirst(root, "Ajouter une description", "Add description", "Add a description")) {
            mark(task, "TIKTOK_METADATA_PENDING", "TIKTOK — ouverture du champ description");
        }
        return false;
    }

    private void adjustTikTokPicker(AccessibilityNodeInfo root, PublicationTask task) {
        if (task.scheduledAt <= System.currentTimeMillis() + 60000L) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — date programmée trop proche ou dépassée");
            return;
        }

        List<AccessibilityNodeInfo> wheels = findPickerWheels(root);
        try {
            if (wheels.size() != 3) {
                mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — les 3 roues ne sont pas encore accessibles");
                return;
            }

            Calendar target = Calendar.getInstance();
            target.setTimeInMillis(task.scheduledAt);

            String dateText = centeredText(wheels.get(0));
            Calendar currentDate = parseVisibleDate(dateText);
            if (currentDate == null) {
                mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — lecture de la date en cours");
                return;
            }
            if (!sameDay(currentDate, target)) {
                int direction = target.after(currentDate) ? 1 : -1;
                resetVerify(task.id);
                boolean moved = swipeWheel(wheels.get(0), direction);
                mark(task, "TIKTOK_PICKER_DATE", moved
                        ? "TIKTOK — défilement de la date vers " + formatDate(target)
                        : "TIKTOK — impossible de faire défiler la date");
                return;
            }

            Integer currentHour = parseCenteredNumber(wheels.get(1));
            if (currentHour == null) {
                mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — lecture de l'heure en cours");
                return;
            }
            int targetHour = target.get(Calendar.HOUR_OF_DAY);
            if (currentHour != targetHour) {
                int direction = targetHour > currentHour ? 1 : -1;
                resetVerify(task.id);
                boolean moved = swipeWheel(wheels.get(1), direction);
                mark(task, "TIKTOK_PICKER_HOUR", moved
                        ? "TIKTOK — réglage de l'heure à " + String.format(Locale.FRANCE, "%02d", targetHour)
                        : "TIKTOK — impossible de faire défiler l'heure");
                return;
            }

            Integer currentMinute = parseCenteredNumber(wheels.get(2));
            if (currentMinute == null) {
                mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — lecture des minutes en cours");
                return;
            }
            int targetMinute = target.get(Calendar.MINUTE);
            if (currentMinute != targetMinute) {
                int direction = targetMinute > currentMinute ? 1 : -1;
                resetVerify(task.id);
                boolean moved = swipeWheel(wheels.get(2), direction);
                mark(task, "TIKTOK_PICKER_MINUTE", moved
                        ? "TIKTOK — réglage des minutes à " + String.format(Locale.FRANCE, "%02d", targetMinute)
                        : "TIKTOK — impossible de faire défiler les minutes");
                return;
            }

            int verified = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                    .getInt("picker_verified_" + task.id, 0) + 1;
            getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                    .putInt("picker_verified_" + task.id, verified).apply();
            if (verified < 2) {
                mark(task, "TIKTOK_PICKER_VERIFY", "TIKTOK — contrôle final de la date et de l'heure");
                return;
            }

            if (clickFirst(root, "Terminé", "Done")) {
                mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — date et heure validées");
            }
        } finally {
            recycle(wheels);
        }
    }

    private List<AccessibilityNodeInfo> findPickerWheels(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectScrollable(root, candidates);
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        for (AccessibilityNodeInfo node : candidates) {
            Rect box = new Rect();
            node.getBoundsInScreen(box);
            if (!node.isVisibleToUser() || box.width() < 30 || box.height() < 80) continue;
            boolean containsOther = false;
            for (AccessibilityNodeInfo other : candidates) {
                if (node == other) continue;
                Rect child = new Rect();
                other.getBoundsInScreen(child);
                if (!box.equals(child) && box.contains(child)) {
                    containsOther = true;
                    break;
                }
            }
            if (!containsOther) leaves.add(AccessibilityNodeInfo.obtain(node));
        }
        recycle(candidates);
        Collections.sort(leaves, (a, b) -> {
            Rect x = new Rect();
            Rect y = new Rect();
            a.getBoundsInScreen(x);
            b.getBoundsInScreen(y);
            return Integer.compare(x.centerX(), y.centerX());
        });
        if (leaves.size() > 3) {
            List<AccessibilityNodeInfo> three = new ArrayList<>();
            for (int i = 0; i < 3; i++) three.add(AccessibilityNodeInfo.obtain(leaves.get(i)));
            recycle(leaves);
            return three;
        }
        return leaves;
    }

    private boolean swipeWheel(AccessibilityNodeInfo wheel, int direction) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Rect box = new Rect();
        wheel.getBoundsInScreen(box);
        if (box.width() < 20 || box.height() < 60 || !wheel.isVisibleToUser()) return false;

        float row = Math.max(28f * getResources().getDisplayMetrics().density, box.height() * 0.18f);
        row = Math.min(row, box.height() * 0.32f);
        float startY = box.centerY() + (direction > 0 ? row * 0.55f : -row * 0.55f);
        float endY = box.centerY() + (direction > 0 ? -row * 0.55f : row * 0.55f);
        Path path = new Path();
        path.moveTo(box.centerX(), startY);
        path.lineTo(box.centerX(), endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 360)).build();
        boolean accepted = dispatchGesture(gesture, null, null);
        lastActionAt = System.currentTimeMillis();
        return accepted;
    }

    private static String centeredText(AccessibilityNodeInfo wheel) {
        Rect wheelBox = new Rect();
        wheel.getBoundsInScreen(wheelBox);
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collectLabels(wheel, labels);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : labels) {
            if (!node.isVisibleToUser() || node.getText() == null) continue;
            Rect box = new Rect();
            node.getBoundsInScreen(box);
            int distance = Math.abs(box.centerY() - wheelBox.centerY());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node.getText().toString().trim();
            }
        }
        recycle(labels);
        return best;
    }

    private static Integer parseCenteredNumber(AccessibilityNodeInfo wheel) {
        String value = centeredText(wheel).replaceAll("[^0-9]", "");
        if (value.isEmpty()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Calendar parseVisibleDate(String text) {
        if (text == null) return null;
        String normalized = text.toLowerCase(Locale.FRANCE)
                .replace(".", "").replace("’", "'").trim();
        Calendar today = Calendar.getInstance();
        zeroTime(today);
        if (normalized.contains("aujourd'hui") || normalized.contains("today")) return today;

        for (int offset = 0; offset <= 31; offset++) {
            Calendar probe = (Calendar) today.clone();
            probe.add(Calendar.DAY_OF_YEAR, offset);
            if (dateLabelMatches(normalized, probe)) return probe;
        }
        return null;
    }

    private static boolean dateLabelMatches(String normalized, Calendar date) {
        String day = String.valueOf(date.get(Calendar.DAY_OF_MONTH));
        String month = new SimpleDateFormat("MMM", Locale.FRANCE).format(date.getTime())
                .toLowerCase(Locale.FRANCE).replace(".", "");
        return normalized.contains(month)
                && java.util.regex.Pattern.compile("(?<![0-9])" + day + "(?![0-9])")
                .matcher(normalized).find();
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static String formatDate(Calendar c) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(c.getTime());
    }

    private void resetVerify(String taskId) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                .remove("picker_verified_" + taskId).apply();
    }

    private void clearPickerState(String taskId) {
        resetVerify(taskId);
    }

    private boolean clickScheduleControl(AccessibilityNodeInfo root) {
        return clickFirst(root, "Programmer la publication", "Programmer", "Schedule post", "Schedule");
    }

    private static boolean scrollForward(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (root.isScrollable() && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                boolean ok = scrollForward(child);
                child.recycle();
                if (ok) return true;
            }
        }
        return false;
    }

    private void runGeneric(AccessibilityNodeInfo root, PublicationTask task) {
        if (containsAny(root, "publié", "published", "upload complete")) {
            finish(task, "PUBLIÉ");
            return;
        }
        if (fillMetadata(root, task)) {
            mark(task, getState(task.id), "MÉTADONNÉES REMPLIES");
            return;
        }
        if (clickFirst(root, "Suivant", "Next", "Continuer", "Continue")) {
            mark(task, getState(task.id), "NAVIGATION EN COURS");
            return;
        }
        if (clickFirst(root, "Publier", "Post", "Mettre en ligne", "Upload")) {
            mark(task, getState(task.id), "VALIDATION ENVOYÉE");
        }
    }

    private boolean fillMetadata(AccessibilityNodeInfo root, PublicationTask task) {
        List<AccessibilityNodeInfo> editable = new ArrayList<>();
        collectEditable(root, editable);
        if (editable.isEmpty()) return false;
        String combined = PublicationAlarmReceiver.buildMetadata(task);
        boolean changed = false;
        try {
            for (AccessibilityNodeInfo node : editable) {
                if (!node.isEditable()) continue;
                CharSequence current = node.getText();
                if (current != null && current.length() > 0) continue;
                String d = descriptor(node);
                String value = null;
                if (d.contains("title") || d.contains("titre")) value = safe(task.title);
                else if (d.contains("description") || d.contains("caption") || d.contains("légende")
                        || d.contains("legende")) value = joined(task.description, task.hashtags);
                else if (d.contains("hashtag")) value = safe(task.hashtags);
                else if (editable.size() == 1) value = combined;
                if (!TextUtils.isEmpty(value) && setText(node, value)) changed = true;
            }
        } finally {
            recycle(editable);
        }
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

    private static void collectScrollable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        String className = cls == null ? "" : cls.toString();
        if (node.isScrollable() || className.contains("NumberPicker")) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScrollable(child, out);
                child.recycle();
            }
        }
    }

    private static void collectLabels(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.getText() != null && node.getChildCount() == 0) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectLabels(child, out);
                child.recycle();
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
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.isEditable() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static boolean clickFirst(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            try {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickNode(node)) return true;
                }
            } finally {
                recycle(nodes);
            }
        }
        return false;
    }

    private static boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
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
            recycle(nodes);
            if (found) return true;
        }
        return false;
    }

    private boolean mark(PublicationTask task, String state, String status) {
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                .putString("state_" + task.id, state).apply();
        task.status = status;
        PublicationTaskRepository.save(this, task);
        lastActionAt = System.currentTimeMillis();
        return true;
    }

    private String getState(String taskId) {
        return getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("state_" + taskId, "");
    }

    private void finish(PublicationTask task, String status) {
        task.status = status;
        PublicationTaskRepository.save(this, task);
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                .remove("active_task_id")
                .remove("state_" + task.id)
                .remove("picker_verified_" + task.id)
                .remove("meta_ok_" + task.id)
                .apply();
        handler.removeCallbacks(retryRunnable);
    }

    private static boolean isTikTok(PublicationTask task) {
        return task.platform != null && task.platform.toLowerCase(Locale.ROOT).contains("tiktok");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String joined(String a, String b) {
        String x = safe(a);
        String y = safe(b);
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + "\n\n" + y;
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) {
            if (node != null) node.recycle();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
