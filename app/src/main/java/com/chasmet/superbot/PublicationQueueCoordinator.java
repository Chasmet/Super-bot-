package com.chasmet.superbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Orchestre les missions sociales une par une.
 * Une nouvelle vidéo n'est jamais ouverte tant que la précédente n'est pas réellement terminée.
 */
public final class PublicationQueueCoordinator {
    public static final int MAX_QUEUE = 7;
    private static final String PREFS = "superbot_bot_state";
    private static final String ACTIVE = "active_task_id";
    private static final String QUEUED_PREFIX = "EN FILE MCP";

    private PublicationQueueCoordinator() {}

    public static synchronized EnqueueResult enqueue(Context context, PublicationTask task) {
        if (context == null || task == null) return new EnqueueResult(false, false, "invalid_task");

        int pending = countPending(context);
        if (pending >= MAX_QUEUE) {
            task.status = "ERREUR MCP • file pleine (7 vidéos max)";
            PublicationTaskRepository.save(context, task);
            return new EnqueueResult(false, false, "queue_full:7");
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String activeId = prefs.getString(ACTIVE, "");
        if (!PublicationCoordinator.busy(context)) {
            boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
            if (started) return new EnqueueResult(true, true, "publication_running:" + task.id + ":" + task.platform);

            PublicationTask latest = PublicationTaskRepository.find(context, task.id);
            String status = latest == null ? task.status : latest.status;
            if (status == null || status.trim().isEmpty()) status = "dispatch_failed";
            return new EnqueueResult(false, false, "publication_dispatch_failed:" + task.id + ":" + status);
        }

        int position = queuedCount(context) + 1;
        task.status = QUEUED_PREFIX + " • position " + position + "/" + MAX_QUEUE;
        PublicationTaskRepository.save(context, task);
        return new EnqueueResult(true, false, "publication_queued:" + task.id + ":position=" + position);
    }

    /** Appelé uniquement après confirmation finale du réseau social. */
    public static synchronized void startNextAfterConfirmed(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String activeId = prefs.getString(ACTIVE, "");
        if (PublicationCoordinator.busy(context)) return;

        for (PublicationTask task : PublicationTaskRepository.load(context)) {
            if (task.status != null && task.status.startsWith(QUEUED_PREFIX)) {
                task.status = "MISSION MCP • démarrage après confirmation précédente";
                PublicationTaskRepository.save(context, task);
                // Si le dispatch échoue, on s'arrête : aucune vidéo suivante n'est lancée automatiquement.
                PublicationAlarmReceiver.dispatchNow(context, task);
                return;
            }
        }
    }

    private static int countPending(Context context) {
        int count = 0;
        String active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "");
        if (active != null && !active.isEmpty()) count++;
        if (!PublicationCoordinator.prefs(context).getString("return_task_id", "").isEmpty()) count++;
        count += queuedCount(context);
        return count;
    }

    private static int queuedCount(Context context) {
        int count = 0;
        List<PublicationTask> tasks = PublicationTaskRepository.load(context);
        for (PublicationTask task : tasks) {
            if (task.status != null && task.status.startsWith(QUEUED_PREFIX)) count++;
        }
        return count;
    }

    public static final class EnqueueResult {
        public final boolean ok;
        public final boolean started;
        public final String message;

        EnqueueResult(boolean ok, boolean started, String message) {
            this.ok = ok;
            this.started = started;
            this.message = message;
        }
    }
}
