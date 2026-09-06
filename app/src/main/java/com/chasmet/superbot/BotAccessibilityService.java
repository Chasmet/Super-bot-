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
        String expected = PublicationAlarmReceiver.packageFor(task.platform);
        if (expected == null || !expected.equals(event.getPackageName().toString())) return;
        if (System.currentTimeMillis() - lastActionAt < 500L) return;
        handler.removeCallbacks(retryRunnable);
        processTask(task);
        handler.postDelayed(retryRunnable, 850L);
    }

    private void processCurrentWindow() {
        String taskId = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getString("active_task_id", "");
        if (taskId.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, taskId);
        if (task == null) return;
        processTask(task);
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, 850L);
    }

    private void processTask(PublicationTask task) {
        if (!task.id.equals(activeTask)) {
            activeTask = task.id;
            retryCount = 0;
            clearPickerState(task.id);
        }
        if (++retryCount > 300) {
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
                String descriptor = descriptor(field);
                if (!(descriptor.contains("description") || descriptor.contains("caption")
                        || descriptor.contains("légende") || descriptor.contains("legende")
                        || fields.size() == 1)) continue;
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
        if (Build.VERSION.SDK_INT < 24) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — Android trop ancien pour piloter les roues");
            return;
        }
        if (task.scheduledAt <= System.currentTimeMillis() + 60000L) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — date programmée trop proche ou dépassée");
            return;
        }

        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.width() <= 0 || screen.height() <= 0) return;

        PickerColumns columns = readPickerColumns(root, screen);
        if (!columns.ready()) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — lecture visuelle des roues en cours");
            return;
        }

        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(task.scheduledAt);

        Calendar currentDate = parseVisibleDate(columns.date.value);
        if (currentDate == null) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — date centrale non reconnue");
            return;
        }
        if (!sameDay(currentDate, target)) {
            int direction = target.after(currentDate) ? 1 : -1;
            resetVerify(task.id);
            dispatchWheelGesture(columns.date, direction, task, "date vers " + formatDate(target));
            return;
        }

        Integer currentHour = parseNumber(columns.hour.value);
        if (currentHour == null) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — heure centrale non reconnue");
            return;
        }
        int wantedHour = target.get(Calendar.HOUR_OF_DAY);
        if (currentHour != wantedHour) {
            int direction = shortestCircularDirection(currentHour, wantedHour, 24);
            resetVerify(task.id);
            dispatchWheelGesture(columns.hour, direction, task,
                    "heure vers " + String.format(Locale.FRANCE, "%02d", wantedHour));
            return;
        }

        Integer currentMinute = parseNumber(columns.minute.value);
        if (currentMinute == null) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — minutes centrales non reconnues");
            return;
        }
        int wantedMinute = target.get(Calendar.MINUTE);
        if (currentMinute != wantedMinute) {
            int direction = shortestCircularDirection(currentMinute, wantedMinute, 60);
            resetVerify(task.id);
            dispatchWheelGesture(columns.minute, direction, task,
                    "minutes vers " + String.format(Locale.FRANCE, "%02d", wantedMinute));
            return;
        }

        int verified = getSharedPreferences("superbot_bot_state", MODE_PRIVATE)
                .getInt("picker_verified_" + task.id, 0) + 1;
        getSharedPreferences("superbot_bot_state", MODE_PRIVATE).edit()
                .putInt("picker_verified_" + task.id, verified).apply();
        if (verified < 2) {
            mark(task, "TIKTOK_PICKER_VERIFY", "TIKTOK — contrôle final date/heure");
            return;
        }
        if (clickFirst(root, "Terminé", "Done")) {
            mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — date et heure validées");
        }
    }

    private PickerColumns readPickerColumns(AccessibilityNodeInfo root, Rect screen) {
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collectLabels(root, labels);
        List<PickerLabel> dates = new ArrayList<>();
        List<PickerLabel> hours = new ArrayList<>();
        List<PickerLabel> minutes = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo node : labels) {
                if (!node.isVisibleToUser() || node.getText() == null) continue;
                String text = node.getText().toString().trim();
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                float x = b.centerX() / (float) screen.width();
                if (isDateLabel(text) && x < 0.55f) {
                    dates.add(new PickerLabel(text, b.centerX(), b.centerY()));
                } else if (text.matches("[0-9]{1,2}")) {
                    if (x >= 0.43f && x < 0.72f) hours.add(new PickerLabel(text, b.centerX(), b.centerY()));
                    else if (x >= 0.72f) minutes.add(new PickerLabel(text, b.centerX(), b.centerY()));
                }
            }
        } finally {
            recycle(labels);
        }
        return new PickerColumns(centerLabel(dates), centerLabel(hours), centerLabel(minutes),
                spacing(dates), spacing(hours), spacing(minutes));
    }

    private static void sortPickerLabels(List<PickerLabel> values) {
        Collections.sort(values, new java.util.Comparator<PickerLabel>() {
            @Override public int compare(PickerLabel a, PickerLabel b) {
                return a.y < b.y ? -1 : (a.y == b.y ? 0 : 1);
            }
        });
    }

    private static PickerLabel centerLabel(List<PickerLabel> values) {
        if (values.isEmpty()) return null;
        sortPickerLabels(values);
        return values.get(values.size() / 2);
    }

    private static float spacing(List<PickerLabel> values) {
        if (values.size() < 2) return 42f;
        sortPickerLabels(values);
        List<Integer> diffs = new ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            int d = values.get(i).y - values.get(i - 1).y;
            if (d > 8) diffs.add(d);
        }
        if (diffs.isEmpty()) return 42f;
        Collections.sort(diffs);
        return diffs.get(diffs.size() / 2);
    }

    private void dispatchWheelGesture(PickerLabel label, int direction, PublicationTask task, String what) {
        float distance = Math.max(34f * getResources().getDisplayMetrics().density, label.spacing * 1.25f);
        distance = Math.min(distance, 120f * getResources().getDisplayMetrics().density);
        float startY = label.y + (direction > 0 ? distance * 0.55f : -distance * 0.55f);
        float endY = label.y + (direction > 0 ? -distance * 0.55f : distance * 0.55f);
        Path path = new Path();
        path.moveTo(label.x, startY);
        path.lineTo(label.x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 480)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                handler.removeCallbacks(retryRunnable);
                handler.postDelayed(retryRunnable, 650L);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                handler.removeCallbacks(retryRunnable);
                handler.postDelayed(retryRunnable, 650L);
            }
        }, null);
        lastActionAt = System.currentTimeMillis();
        mark(task, "TIKTOK_PICKER_GESTURE", accepted
                ? "TIKTOK — défilement tactile " + what
                : "TIKTOK — geste tactile refusé pour " + what);
    }

    private static int shortestCircularDirection(int current, int target, int modulo) {
        int forward = (target - current + modulo) % modulo;
        int backward = (current - target + modulo) % modulo;
        return forward <= backward ? 1 : -1;
    }

    private static Integer parseNumber(String value) {
        if (value == null) return null;
        String only = value.replaceAll("[^0-9]", "");
        if (only.isEmpty()) return null;
        try { return Integer.parseInt(only); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean isDateLabel(String text) {
        if (text == null) return false;
        String v = text.toLowerCase(Locale.FRANCE).replace("’", "'");
        if (v.contains("aujourd'hui") || v.contains("today")) return true;
        return v.matches(".*(janv|févr|fevr|mars|avr|mai|juin|juil|août|aout|sept|oct|nov|déc|dec).*\\d{1,2}.*");
    }

    private Calendar parseVisibleDate(String text) {
        if (text == null) return null;
        String normalized = normalizeDate(text);
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

    private static String normalizeDate(String text) {
        return text.toLowerCase(Locale.FRANCE).replace(".", "").replace("’", "'")
                .replace("é", "e").replace("è", "e").replace("ê", "e")
                .replace("û", "u").replace("ù", "u").replace("ô", "o")
                .replace("î", "i").replace("ï", "i").replace("à", "a").trim();
    }

    private static boolean dateLabelMatches(String normalized, Calendar date) {
        String day = String.valueOf(date.get(Calendar.DAY_OF_MONTH));
        String month = new SimpleDateFormat("MMM", Locale.FRANCE).format(date.getTime());
        month = normalizeDate(month);
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
                String descriptor = descriptor(node);
                String value = null;
                if (descriptor.contains("title") || descriptor.contains("titre")) value = safe(task.title);
                else if (descriptor.contains("description") || descriptor.contains("caption")
                        || descriptor.contains("légende") || descriptor.contains("legende"))
                    value = joined(task.description, task.hashtags);
                else if (descriptor.contains("hashtag")) value = safe(task.hashtags);
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

    private static void collectLabels(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.getText() != null && node.getChildCount() == 0) out.add(AccessibilityNodeInfo.obtain(node));
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
                for (AccessibilityNodeInfo node : nodes) if (clickNode(node)) return true;
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
                if (current.isClickable() && current.isEnabled()) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
                .remove("active_task_id").remove("state_" + task.id)
                .remove("picker_verified_" + task.id).remove("meta_ok_" + task.id).apply();
        handler.removeCallbacks(retryRunnable);
    }

    private static boolean isTikTok(PublicationTask task) {
        return task.platform != null && task.platform.toLowerCase(Locale.ROOT).contains("tiktok");
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String joined(String a, String b) {
        String x = safe(a), y = safe(b);
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + "\n\n" + y;
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) if (node != null) node.recycle();
    }

    private static final class PickerLabel {
        final String value;
        final float x;
        final int y;
        float spacing = 42f;
        PickerLabel(String value, float x, int y) { this.value = value; this.x = x; this.y = y; }
    }

    private static final class PickerColumns {
        final PickerLabel date;
        final PickerLabel hour;
        final PickerLabel minute;
        PickerColumns(PickerLabel date, PickerLabel hour, PickerLabel minute,
                      float dateSpacing, float hourSpacing, float minuteSpacing) {
            this.date = date;
            this.hour = hour;
            this.minute = minute;
            if (date != null) date.spacing = dateSpacing;
            if (hour != null) hour.spacing = hourSpacing;
            if (minute != null) minute.spacing = minuteSpacing;
        }
        boolean ready() { return date != null && hour != null && minute != null; }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
