package com.chasmet.superbot;

import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TikTokScheduleVerifier {
    private TikTokScheduleVerifier() {}

    static boolean matchesTargetPackage(String platform, CharSequence packageName) {
        if (packageName == null) return false;
        String pkg = packageName.toString();
        String[] allowed = PublicationAlarmReceiver.packagesFor(platform);
        if (allowed == null) return false;
        for (String candidate : allowed) {
            if (candidate.equals(pkg)) return true;
        }
        return false;
    }

    static boolean scheduleSummaryMatches(AccessibilityNodeInfo root, long scheduledAt) {
        if (root == null || scheduledAt <= 0) return false;
        String screen = collectText(root).toLowerCase(Locale.ROOT);
        if (screen.isEmpty()) return false;

        Date date = new Date(scheduledAt);
        String hhmm = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(date);
        String hSpaceM = new SimpleDateFormat("H mm", Locale.FRANCE).format(date);
        String dm = new SimpleDateFormat("dd/MM", Locale.FRANCE).format(date);
        String dmy = new SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(date);
        String dmyDash = new SimpleDateFormat("dd-MM-yyyy", Locale.FRANCE).format(date);
        String dMonth = new SimpleDateFormat("d MMM", Locale.FRANCE).format(date).toLowerCase(Locale.ROOT);
        String dMonthLong = new SimpleDateFormat("d MMMM", Locale.FRANCE).format(date).toLowerCase(Locale.ROOT);

        boolean time = screen.contains(hhmm.toLowerCase(Locale.ROOT))
                || screen.contains(hSpaceM.toLowerCase(Locale.ROOT))
                || screen.contains(hhmm.replace(':', 'h').toLowerCase(Locale.ROOT));
        boolean day = screen.contains(dm.toLowerCase(Locale.ROOT))
                || screen.contains(dmy.toLowerCase(Locale.ROOT))
                || screen.contains(dmyDash.toLowerCase(Locale.ROOT))
                || screen.contains(dMonth)
                || screen.contains(dMonthLong);
        return time && day;
    }

    static boolean accepted(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String s = collectText(root).toLowerCase(Locale.ROOT);
        return s.contains("publication programmée")
                || s.contains("publication programmee")
                || s.contains("vidéo programmée")
                || s.contains("video programmee")
                || s.contains("post scheduled")
                || s.contains("scheduled successfully")
                || s.contains("scheduled post")
                || s.contains("programmation réussie")
                || s.contains("programmation reussie");
    }

    private static String collectText(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();
        append(root, out, 0);
        return out.toString();
    }

    private static void append(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 20 || out.length() > 20000) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(' ').append(text);
        if (desc != null && desc.length() > 0) out.append(' ').append(desc);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    append(child, out, depth + 1);
                } finally {
                    child.recycle();
                }
            }
        }
    }
}
