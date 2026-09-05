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
        if (System.currentTimeMillis() - lastActionAt < 900L) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isTikTok(task)) {
                if (runTikTok(root, task)) return;
            }
            runGeneric(root, task);
        } finally { root.recycle(); }
    }

    private boolean runTikTok(AccessibilityNodeInfo root, PublicationTask task) {
        String state = getState(task.id);
        if (containsAny(root, "publication programmée", "post scheduled", "programmé", "scheduled")) {
            finish(task, "PROGRAMMÉ"); return true;
        }
        if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
            finish(task, "PUBLIÉ"); return true;
        }
        boolean future = task.scheduledAt > System.currentTimeMillis() + 60000L;
        if (!future) return false;

        if (fillMetadata(root, task)) return mark(task, "TIKTOK_METADATA", "MÉTADONNÉES REMPLIES");

        if (containsAny(root, "programmer la publication", "schedule post")) {
            if (clickFirst(root, "programmer la publication", "schedule post"))
                return mark(task, "TIKTOK_SCHEDULE_OPEN", "TIKTOK — PROGRAMMATION OUVERTE");
        }
        if (containsAny(root, "plus d’options", "plus d'options", "more options")) {
            if (clickFirst(root, "plus d’options", "plus d'options", "more options"))
                return mark(task, "TIKTOK_MORE_OPTIONS", "TIKTOK — PLUS D'OPTIONS");
        }

        if (state.startsWith("TIKTOK_SCHEDULE") || containsAny(root, "date", "heure", "time")) {
            String day = new SimpleDateFormat("d", Locale.FRANCE).format(new Date(task.scheduledAt));
            String hour = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(task.scheduledAt));
            if (!state.equals("TIKTOK_DATE_SET") && clickExact(root, day))
                return mark(task, "TIKTOK_DATE_SET", "TIKTOK — DATE CHOISIE");
            if (!state.equals("TIKTOK_TIME_SET") && clickExact(root, hour))
                return mark(task, "TIKTOK_TIME_SET", "TIKTOK — HEURE CHOISIE");
            if (clickFirst(root, "terminé", "done"))
                return mark(task, "TIKTOK_SCHEDULE_READY", "TIKTOK — DATE ET HEURE VALIDÉES");
        }

        if (state.equals("TIKTOK_SCHEDULE_READY") && clickFirst(root, "publier", "post"))
            return mark(task, "TIKTOK_CONFIRMING", "TIKTOK — VALIDATION DE LA PROGRAMMATION");

        if (clickFirst(root, "suivant", "next", "continuer", "continue"))
            return mark(task, state, "TIKTOK — NAVIGATION EN COURS");
        return false;
    }

    private void runGeneric(AccessibilityNodeInfo root, PublicationTask task) {
        if (containsAny(root, "publié", "published", "mise en ligne terminée", "upload complete")) {
            finish(task, "PUBLIÉ"); return;
        }
        if (fillMetadata(root, task)) { mark(task, getState(task.id), "MÉTADONNÉES REMPLIES"); return; }
        if (clickFirst(root, "suivant", "next", "continuer", "continue")) { mark(task, getState(task.id), "NAVIGATION EN COURS"); return; }
        if (clickFirst(root, "publier", "post", "mettre en ligne", "upload")) mark(task, getState(task.id), "VALIDATION ENVOYÉE");
    }

    private boolean fillMetadata(AccessibilityNodeInfo root, PublicationTask task) {
        List<AccessibilityNodeInfo> editable = new ArrayList<>(); collectEditable(root, editable);
        if (editable.isEmpty()) return false;
        String combined = PublicationAlarmReceiver.buildMetadata(task); boolean changed = false;
        for (AccessibilityNodeInfo node : editable) {
            if (!node.isEditable()) continue;
            CharSequence currentText = node.getText(); if (currentText != null && currentText.length() > 0) continue;
            String d = descriptor(node); String value = null;
            if (d.contains("title") || d.contains("titre")) value = safe(task.title);
            else if (d.contains("description") || d.contains("caption") || d.contains("légende") || d.contains("legende")) value = joined(task.description, task.hashtags);
            else if (d.contains("hashtag")) value = safe(task.hashtags);
            else if (editable.size() == 1) value = combined;
            if (!TextUtils.isEmpty(value) && setText(node, value)) changed = true;
        }
        for (AccessibilityNodeInfo node : editable) node.recycle(); return changed;
    }

    private static void collectEditable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return; if (node.isEditable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo c=node.getChild(i); if(c!=null){collectEditable(c,out);c.recycle();} }
    }
    private static String descriptor(AccessibilityNodeInfo node) {
        StringBuilder b=new StringBuilder(); if(node.getViewIdResourceName()!=null)b.append(node.getViewIdResourceName()).append(' ');
        if(node.getContentDescription()!=null)b.append(node.getContentDescription()).append(' ');
        if(Build.VERSION.SDK_INT>=26&&node.getHintText()!=null)b.append(node.getHintText()).append(' '); return b.toString().toLowerCase(Locale.ROOT);
    }
    private static boolean setText(AccessibilityNodeInfo node,String value){Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return node.isEditable()&&node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a);}
    private static boolean clickExact(AccessibilityNodeInfo root,String label){List<AccessibilityNodeInfo> n=root.findAccessibilityNodeInfosByText(label);if(n==null)return false;for(AccessibilityNodeInfo x:n){CharSequence t=x.getText();if(t!=null&&label.equalsIgnoreCase(t.toString().trim())&&clickNode(x)){for(AccessibilityNodeInfo z:n)z.recycle();return true;}}for(AccessibilityNodeInfo z:n)z.recycle();return false;}
    private static boolean clickFirst(AccessibilityNodeInfo root,String... labels){for(String l:labels){List<AccessibilityNodeInfo> n=root.findAccessibilityNodeInfosByText(l);if(n==null)continue;for(AccessibilityNodeInfo x:n){if(clickNode(x)){for(AccessibilityNodeInfo z:n)z.recycle();return true;}}for(AccessibilityNodeInfo z:n)z.recycle();}return false;}
    private static boolean clickNode(AccessibilityNodeInfo n){AccessibilityNodeInfo t=n;while(t!=null&&!t.isClickable())t=t.getParent();return t!=null&&t.isEnabled()&&t.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    private static boolean containsAny(AccessibilityNodeInfo root,String... labels){for(String l:labels){List<AccessibilityNodeInfo> n=root.findAccessibilityNodeInfosByText(l);boolean f=n!=null&&!n.isEmpty();if(n!=null)for(AccessibilityNodeInfo x:n)x.recycle();if(f)return true;}return false;}
    private boolean mark(PublicationTask task,String state,String status){getSharedPreferences("superbot_bot_state",MODE_PRIVATE).edit().putString("state_"+task.id,state).apply();task.status=status;PublicationTaskRepository.save(this,task);lastActionAt=System.currentTimeMillis();return true;}
    private String getState(String id){return getSharedPreferences("superbot_bot_state",MODE_PRIVATE).getString("state_"+id,"");}
    private void finish(PublicationTask task,String status){task.status=status;PublicationTaskRepository.save(this,task);getSharedPreferences("superbot_bot_state",MODE_PRIVATE).edit().remove("active_task_id").remove("state_"+task.id).apply();}
    private static boolean isTikTok(PublicationTask t){return t.platform!=null&&t.platform.toLowerCase(Locale.ROOT).contains("tiktok");}
    private static String safe(String v){return v==null?"":v.trim();}
    private static String joined(String a,String b){String x=safe(a),y=safe(b);if(x.isEmpty())return y;if(y.isEmpty())return x;return x+"\n\n"+y;}
    @Override public void onInterrupt() {}
}
