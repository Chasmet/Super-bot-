package com.chasmet.superbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@SuppressLint("NewApi")
public class BotAccessibilityService extends AccessibilityService {
    private long lastActionAt;
    private int tries;
    private String activeTaskId = "";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable retry = this::tick;

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("superbot_bot_state", MODE_PRIVATE);
    }

    private boolean awake() {
        return PublicationAlarmReceiver.isSuperBotAwake(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!awake() || event == null || event.getPackageName() == null) return;
        String id = prefs().getString("active_task_id", "");
        if (id.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, id);
        if (task == null) return;
        String expectedPackage = PublicationAlarmReceiver.packageFor(task.platform);
        if (expectedPackage == null || !expectedPackage.equals(event.getPackageName().toString())) return;
        if (System.currentTimeMillis() - lastActionAt < 380) return;
        handler.removeCallbacks(retry);
        process(task);
        handler.postDelayed(retry, 650);
    }

    private void tick() {
        if (!awake()) {
            handler.removeCallbacks(retry);
            return;
        }
        String id = prefs().getString("active_task_id", "");
        if (id.isEmpty()) return;
        PublicationTask task = PublicationTaskRepository.find(this, id);
        if (task == null) return;
        process(task);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 650);
    }

    private void process(PublicationTask task) {
        if (!task.id.equals(activeTaskId)) {
            activeTaskId = task.id;
            tries = 0;
            clearPicker(task.id);
        }
        if (++tries > 520) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — délai dépassé");
            handler.removeCallbacks(retry);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String expectedPackage = PublicationAlarmReceiver.packageFor(task.platform);
            if (root.getPackageName() == null || expectedPackage == null || !expectedPackage.equals(root.getPackageName().toString())) return;
            if (isTikTok(task)) runTikTok(root, task); else runGeneric(root, task);
        } finally {
            root.recycle();
        }
    }

    private void runTikTok(AccessibilityNodeInfo root, PublicationTask task) {
        String state = state(task.id);
        if ("TIKTOK_PAUSED".equals(state)) return;

        if ("TIKTOK_CONFIRMING".equals(state) && has(root, "publication programmée", "post scheduled", "scheduled")) {
            finish(task, "PROGRAMMÉ");
            return;
        }

        if (has(root, "Date et heure de publication", "Date and time of publication")) {
            handlePicker(root, task);
            return;
        }

        if ("TIKTOK_SCHEDULE_READY".equals(state) || "TIKTOK_RETURN_POST".equals(state)) {
            if (has(root, "Plus d’options", "Plus d'options", "More options", "Programmer la publication", "Schedule post")) {
                if (click(root, "Fermer", "Close")) {
                    mark(task, "TIKTOK_RETURN_POST", "TIKTOK — Plus d'options fermé");
                    return;
                }
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    mark(task, "TIKTOK_RETURN_POST", "TIKTOK — retour vers Publier");
                    return;
                }
            }
            if (click(root, "Publier", "Post")) {
                mark(task, "TIKTOK_CONFIRMING", "TIKTOK — programmation envoyée");
            }
            return;
        }

        if (has(root, "Ta Story", "Your Story") && click(root, "Suivant", "Next")) {
            mark(task, "TIKTOK_NEXT", "TIKTOK — Suivant");
            return;
        }

        if (has(root, "Programmer la publication", "Schedule post") && click(root, "Programmer la publication", "Schedule post")) {
            clearPicker(task.id);
            mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — programmation ouverte");
            return;
        }

        boolean postScreen = has(root, "Publier", "Post", "Brouillons", "Drafts", "Ajouter un lien", "Add link", "Plus d’options", "Plus d'options", "More options");
        if (postScreen) {
            if (!prefs().getBoolean("meta_ok_" + task.id, false)) {
                if (!fillMetadata(root, task)) return;
                mark(task, "TIKTOK_METADATA", "TIKTOK — métadonnées vérifiées");
                return;
            }
            if (click(root, "Plus d’options", "Plus d'options", "More options")) {
                mark(task, "TIKTOK_MORE_OPTIONS", "TIKTOK — Plus d'options");
                return;
            }
            if (scroll(root) || swipeUp()) {
                mark(task, "TIKTOK_FIND_MORE_OPTIONS", "TIKTOK — recherche Plus d'options");
                return;
            }
        }

        if ("TIKTOK_MORE_OPTIONS".equals(state) || "TIKTOK_FIND_SCHEDULE".equals(state) || "TIKTOK_FIND_MORE_OPTIONS".equals(state)) {
            if (click(root, "Programmer la publication", "Schedule post")) {
                clearPicker(task.id);
                mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — programmation ouverte");
                return;
            }
            if (scroll(root) || swipeUp()) {
                mark(task, "TIKTOK_FIND_SCHEDULE", "TIKTOK — recherche programmation");
                return;
            }
        }

        if (click(root, "Suivant", "Next", "Continuer", "Continue")) {
            mark(task, "TIKTOK_NEXT", "TIKTOK — navigation");
        }
    }

    private boolean fillMetadata(AccessibilityNodeInfo root, PublicationTask task) {
        String wanted = PublicationAlarmReceiver.buildMetadata(task).trim();
        if (wanted.isEmpty()) {
            prefs().edit().putBoolean("meta_ok_" + task.id, true).apply();
            return true;
        }
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditable(root, fields);
        try {
            for (AccessibilityNodeInfo field : fields) {
                if (!field.isVisibleToUser()) continue;
                String current = field.getText() == null ? "" : field.getText().toString().trim();
                if (wanted.equals(current)) {
                    prefs().edit().putBoolean("meta_ok_" + task.id, true).apply();
                    return true;
                }
                String description = describe(field);
                if (!(description.contains("description") || description.contains("caption") || description.contains("légende") || description.contains("legende") || fields.size() == 1)) continue;
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                boolean ok = setText(field, wanted);
                if (!ok) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Super Bot", wanted));
                        ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                }
                mark(task, "TIKTOK_METADATA_PENDING", ok ? "TIKTOK — texte envoyé" : "TIKTOK — texte inaccessible");
                return false;
            }
        } finally {
            recycle(fields);
        }
        if (click(root, "Ajouter une description", "Add description", "Add a description")) {
            mark(task, "TIKTOK_METADATA_PENDING", "TIKTOK — champ description ouvert");
        }
        return false;
    }

    private void handlePicker(AccessibilityNodeInfo root, PublicationTask task) {
        if (Build.VERSION.SDK_INT < 24) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — Android < 24");
            return;
        }
        if (task.scheduledAt <= System.currentTimeMillis() + 60000) {
            mark(task, "TIKTOK_PAUSED", "TIKTOK — date dépassée ou trop proche");
            return;
        }

        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.width() <= 0 || screen.height() <= 0) return;

        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(task.scheduledAt);
        PickerColumns columns = readColumns(root, screen, task.id);
        saveDiagnostic(root, task, columns, target, screen);

        if (!columns.complete()) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — lecture des roues incomplète");
            return;
        }

        Calendar selectedDate = parseDate(columns.date.value);
        Integer selectedHour = number(columns.hour.value);
        Integer selectedMinute = number(columns.minute.value);
        if (selectedDate == null || selectedHour == null || selectedMinute == null) {
            mark(task, "TIKTOK_PICKER_WAIT", "TIKTOK — valeurs centrales non reconnues");
            return;
        }

        if (!sameDay(selectedDate, target)) {
            resetVerification(task.id);
            moveWheel(root, columns.date, target.after(selectedDate) ? 1 : -1, task, "date → " + formatDate(target));
            return;
        }

        int targetHour = target.get(Calendar.HOUR_OF_DAY);
        if (selectedHour != targetHour) {
            resetVerification(task.id);
            moveWheel(root, columns.hour, circularDirection(selectedHour, targetHour, 24), task, "heure → " + two(targetHour));
            return;
        }

        int targetMinute = target.get(Calendar.MINUTE);
        if (selectedMinute != targetMinute) {
            resetVerification(task.id);
            int direction = circularDirection(selectedMinute, targetMinute, 60);
            moveWheel(root, columns.minute, direction, task, "minutes " + two(selectedMinute) + " → " + two(targetMinute));
            return;
        }

        int verified = prefs().getInt("picker_verified_" + task.id, 0) + 1;
        prefs().edit().putInt("picker_verified_" + task.id, verified).apply();
        if (verified < 2) {
            mark(task, "TIKTOK_PICKER_VERIFY", "TIKTOK — date/heure/minutes exactes, contrôle 2/2");
            return;
        }

        if (click(root, "Terminé", "Done")) {
            mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — Terminé validé");
        } else {
            mark(task, "TIKTOK_PICKER_VERIFY", "TIKTOK — valeurs exactes, recherche bouton Terminé");
        }
    }

    private PickerColumns readColumns(AccessibilityNodeInfo root, Rect screen, String id) {
        List<AccessibilityNodeInfo> leaves = new ArrayList<>();
        collectLabels(root, leaves);
        List<PickerLabel> dates = new ArrayList<>();
        List<PickerLabel> hours = new ArrayList<>();
        List<PickerLabel> minutes = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo node : leaves) {
                if (!node.isVisibleToUser()) continue;
                String value = text(node);
                if (value.isEmpty()) continue;
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (b.isEmpty()) continue;
                float x = (b.centerX() - screen.left) / (float) screen.width();
                if (isDateLabel(value) && x < 0.48f) dates.add(new PickerLabel(value, b.centerX(), b.centerY()));
                else if (value.matches("[0-9]{1,2}")) {
                    if (x >= 0.43f && x < 0.72f) hours.add(new PickerLabel(value, b.centerX(), b.centerY()));
                    else if (x >= 0.72f) minutes.add(new PickerLabel(value, b.centerX(), b.centerY()));
                }
            }
        } finally {
            recycle(leaves);
        }

        int centerY = prefs().getInt("picker_center_y_" + id, -1);
        if (centerY < 0) {
            for (PickerLabel label : dates) {
                String normalized = normalize(label.value);
                if (normalized.contains("aujourd'hui") || normalized.contains("today")) {
                    centerY = label.y;
                    break;
                }
            }
            if (centerY < 0 && !dates.isEmpty()) {
                sortLabels(dates);
                centerY = dates.get(dates.size() / 2).y;
            }
            if (centerY >= 0) prefs().edit().putInt("picker_center_y_" + id, centerY).apply();
        }

        return new PickerColumns(nearest(dates, centerY), nearest(hours, centerY), nearest(minutes, centerY), spacing(dates), spacing(hours), spacing(minutes));
    }

    private void moveWheel(AccessibilityNodeInfo root, PickerLabel label, int direction, PublicationTask task, String description) {
        if (label == null) return;
        if (scrollAt(root, label.x, label.y, direction)) {
            mark(task, "TIKTOK_PICKER_SCROLL", "TIKTOK — " + description);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float distance = Math.max(18f * density, label.spacing * 0.92f);
        distance = Math.min(distance, 72f * density);
        float start = label.y + (direction > 0 ? distance * 0.55f : -distance * 0.55f);
        float end = label.y + (direction > 0 ? -distance * 0.55f : distance * 0.55f);
        dispatchWheelGesture(label.x, start, end, task, description);
    }

    private boolean dispatchWheelGesture(float x, float startY, float endY, PublicationTask task, String description) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 320))
                .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, 620);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, 620);
            }
        }, null);
        mark(task, "TIKTOK_PICKER_GESTURE", accepted ? "TIKTOK — " + description : "TIKTOK — geste refusé " + description);
        return accepted;
    }

    private boolean scrollAt(AccessibilityNodeInfo node, float x, float y, int direction) {
        if (node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.contains((int) x, (int) y) && node.isScrollable()) {
            int action = direction > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (node.performAction(action)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean ok = scrollAt(child, x, y, direction);
                child.recycle();
                if (ok) return true;
            }
        }
        return false;
    }

    private void saveDiagnostic(AccessibilityNodeInfo root, PublicationTask task, PickerColumns columns, Calendar target, Rect screen) {
        StringBuilder out = new StringBuilder();
        out.append("task=").append(task.id).append('\n')
                .append("state=").append(state(task.id)).append('\n')
                .append("target=").append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(target.getTime())).append('\n')
                .append("selectedDate=").append(columns.date == null ? "<missing>" : columns.date.value).append('\n')
                .append("selectedHour=").append(columns.hour == null ? "<missing>" : columns.hour.value).append('\n')
                .append("selectedMinute=").append(columns.minute == null ? "<missing>" : columns.minute.value).append('\n')
                .append("screen=").append(screen.left).append(',').append(screen.top).append(',').append(screen.right).append(',').append(screen.bottom).append("\nNODES\n");
        dump(root, out, 0);
        getSharedPreferences("superbot_diagnostic", MODE_PRIVATE).edit().putString("clock", out.toString()).apply();
    }

    private void dump(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || out.length() > 24000 || depth > 24) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        String value = text(node);
        if (!value.isEmpty() || node.isClickable() || node.isScrollable()) {
            out.append(depth).append("|class=").append(node.getClassName())
                    .append("|text=").append(value.replace('\n', ' '))
                    .append("|id=").append(node.getViewIdResourceName())
                    .append("|bounds=").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom)
                    .append("|click=").append(node.isClickable())
                    .append("|scroll=").append(node.isScrollable())
                    .append("|actions=").append(node.getActions()).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dump(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    private void runGeneric(AccessibilityNodeInfo root, PublicationTask task) {
        if (has(root, "publié", "published", "upload complete")) {
            finish(task, "PUBLIÉ");
            return;
        }
        List<AccessibilityNodeInfo> fields = new ArrayList<>();
        collectEditable(root, fields);
        try {
            String all = PublicationAlarmReceiver.buildMetadata(task);
            for (AccessibilityNodeInfo field : fields) {
                if (field.isEditable() && (field.getText() == null || field.getText().length() == 0) && setText(field, all)) {
                    mark(task, state(task.id), "MÉTADONNÉES REMPLIES");
                    return;
                }
            }
        } finally {
            recycle(fields);
        }
        if (click(root, "Suivant", "Next", "Continuer", "Continue")) mark(task, state(task.id), "NAVIGATION EN COURS");
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
        if ((node.getText() != null || node.getContentDescription() != null) && node.getChildCount() == 0) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectLabels(child, out);
                child.recycle();
            }
        }
    }

    private static String text(AccessibilityNodeInfo node) {
        if (node.getText() != null && !node.getText().toString().trim().isEmpty()) return node.getText().toString().trim();
        return node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
    }

    private static String describe(AccessibilityNodeInfo node) {
        StringBuilder b = new StringBuilder();
        if (node.getViewIdResourceName() != null) b.append(node.getViewIdResourceName());
        if (node.getContentDescription() != null) b.append(' ').append(node.getContentDescription());
        if (Build.VERSION.SDK_INT >= 26 && node.getHintText() != null) b.append(' ').append(node.getHintText());
        if (node.getText() != null) b.append(' ').append(node.getText());
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.isEditable() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static boolean click(AccessibilityNodeInfo root, String... labels) {
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

    private static boolean has(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            boolean found = nodes != null && !nodes.isEmpty();
            recycle(nodes);
            if (found) return true;
        }
        return false;
    }

    private boolean swipeUp() {
        if (Build.VERSION.SDK_INT < 24) return false;
        android.util.DisplayMetrics d = getResources().getDisplayMetrics();
        Path path = new Path();
        path.moveTo(d.widthPixels * 0.5f, d.heightPixels * 0.78f);
        path.lineTo(d.widthPixels * 0.5f, d.heightPixels * 0.38f);
        boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 420)).build(), null, null);
        if (ok) lastActionAt = System.currentTimeMillis();
        return ok;
    }

    private static boolean scroll(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (root.isScrollable() && root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                boolean ok = scroll(child);
                child.recycle();
                if (ok) return true;
            }
        }
        return false;
    }

    private static int circularDirection(int current, int target, int modulo) {
        int forward = (target - current + modulo) % modulo;
        int backward = (current - target + modulo) % modulo;
        return forward <= backward ? 1 : -1;
    }

    private static Integer number(String s) {
        if (s == null) return null;
        String n = s.replaceAll("[^0-9]", "");
        if (n.isEmpty()) return null;
        try { return Integer.parseInt(n); } catch (Exception e) { return null; }
    }

    private static boolean isDateLabel(String s) {
        if (s == null) return false;
        String v = s.toLowerCase(Locale.FRANCE).replace("’", "'");
        return v.contains("aujourd'hui") || v.contains("today") || v.matches(".*(janv|févr|fevr|mars|avr|mai|juin|juil|août|aout|sept|oct|nov|déc|dec).*\\d{1,2}.*");
    }

    private Calendar parseDate(String s) {
        if (s == null) return null;
        String n = normalize(s);
        Calendar today = Calendar.getInstance();
        zero(today);
        if (n.contains("aujourd'hui") || n.contains("today")) return today;
        for (int i = 0; i <= 31; i++) {
            Calendar q = (Calendar) today.clone();
            q.add(Calendar.DAY_OF_YEAR, i);
            String day = String.valueOf(q.get(Calendar.DAY_OF_MONTH));
            String month = normalize(new SimpleDateFormat("MMM", Locale.FRANCE).format(q.getTime()));
            if (n.contains(month) && java.util.regex.Pattern.compile("(?<![0-9])" + day + "(?![0-9])").matcher(n).find()) return q;
        }
        return null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.FRANCE).replace(".", "").replace("’", "'")
                .replace("é", "e").replace("è", "e").replace("ê", "e").replace("û", "u")
                .replace("ù", "u").replace("ô", "o").replace("î", "i").replace("ï", "i")
                .replace("à", "a").trim();
    }

    private static void zero(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String formatDate(Calendar c) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(c.getTime());
    }

    private static String two(int v) {
        return String.format(Locale.FRANCE, "%02d", v);
    }

    private static void sortLabels(List<PickerLabel> labels) {
        Collections.sort(labels, new Comparator<PickerLabel>() {
            @Override public int compare(PickerLabel a, PickerLabel b) {
                return a.y < b.y ? -1 : (a.y == b.y ? 0 : 1);
            }
        });
    }

    private static PickerLabel nearest(List<PickerLabel> labels, int y) {
        if (labels.isEmpty() || y < 0) return null;
        PickerLabel best = null;
        int distance = Integer.MAX_VALUE;
        for (PickerLabel label : labels) {
            int d = Math.abs(label.y - y);
            if (d < distance) {
                distance = d;
                best = label;
            }
        }
        return best;
    }

    private static float spacing(List<PickerLabel> labels) {
        if (labels.size() < 2) return 42f;
        sortLabels(labels);
        List<Integer> distances = new ArrayList<>();
        for (int i = 1; i < labels.size(); i++) {
            int d = labels.get(i).y - labels.get(i - 1).y;
            if (d > 8) distances.add(d);
        }
        if (distances.isEmpty()) return 42f;
        Collections.sort(distances);
        return distances.get(distances.size() / 2);
    }

    private void resetVerification(String id) {
        prefs().edit().remove("picker_verified_" + id).apply();
    }

    private void clearPicker(String id) {
        prefs().edit().remove("picker_verified_" + id).remove("picker_center_y_" + id).apply();
    }

    private void mark(PublicationTask task, String state, String status) {
        prefs().edit().putString("state_" + task.id, state).apply();
        task.status = status;
        PublicationTaskRepository.save(this, task);
        lastActionAt = System.currentTimeMillis();
    }

    private String state(String id) {
        return prefs().getString("state_" + id, "");
    }

    private void finish(PublicationTask task, String status) {
        task.status = status;
        PublicationTaskRepository.save(this, task);
        prefs().edit()
                .remove("active_task_id")
                .remove("state_" + task.id)
                .remove("picker_verified_" + task.id)
                .remove("picker_center_y_" + task.id)
                .remove("meta_ok_" + task.id)
                .apply();
        handler.removeCallbacks(retry);
    }

    private static boolean isTikTok(PublicationTask task) {
        return task.platform != null && task.platform.toLowerCase(Locale.ROOT).contains("tiktok");
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) {
        if (nodes != null) for (AccessibilityNodeInfo node : nodes) if (node != null) node.recycle();
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
        PickerColumns(PickerLabel date, PickerLabel hour, PickerLabel minute, float dateSpacing, float hourSpacing, float minuteSpacing) {
            this.date = date;
            this.hour = hour;
            this.minute = minute;
            if (date != null) date.spacing = dateSpacing;
            if (hour != null) hour.spacing = hourSpacing;
            if (minute != null) minute.spacing = minuteSpacing;
        }
        boolean complete() { return date != null && hour != null && minute != null; }
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
