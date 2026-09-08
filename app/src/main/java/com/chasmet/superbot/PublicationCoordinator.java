package com.chasmet.superbot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** One phone, one publication. The menu handoff is part of completion. */
final class PublicationCoordinator {
    private PublicationCoordinator() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("superbot_bot_state", Context.MODE_PRIVATE); }
    static boolean busy(Context c) {
        return !prefs(c).getString("active_task_id", "").isEmpty()
                || !prefs(c).getString("return_task_id", "").isEmpty();
    }
    static void completed(Context c, PublicationTask t) {
        prefs(c).edit().putString("outcome_"+t.id,"completed")
                .putString("return_task_id",t.id).remove("active_task_id").commit();
        openMenu(c);
    }
    static void openMenu(Context c) {
        try { c.startActivity(new Intent(c,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (Exception ignored) { /* Keep handoff pending until the menu is actually reached. */ }
    }
    static void menuReached(Context c) {
        String id=prefs(c).getString("return_task_id","");
        if(!id.isEmpty())prefs(c).edit().putBoolean("menu_returned_"+id,true).remove("return_task_id").commit();
        if(busy(c)||!PublicationAlarmReceiver.isSuperBotAwake(c))return;
        for(PublicationTask t:PublicationTaskRepository.load(c)) {
            if("EN FILE • une vidéo à la fois".equals(t.status)) {
                PublicationAlarmReceiver.dispatchNow(c,t);return;
            }
        }
    }
}
