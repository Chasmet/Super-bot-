package com.chasmet.superbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestre les missions sociales une par une.
 * Une mission reçue n'est jamais considérée comme exécutée tant qu'un vrai dispatch Android n'a pas démarré.
 */
public final class PublicationQueueCoordinator {
    public static final int MAX_QUEUE = 7;
    private static final String PREFS = "superbot_bot_state";
    private static final String ACTIVE = "active_task_id";
    private static final String QUEUED_PREFIX = "EN FILE MCP";

    private PublicationQueueCoordinator() {}

    public static synchronized EnqueueResult enqueue(Context context, PublicationTask task) {
        if (context == null || task == null) return new EnqueueResult(false, false, "invalid_task");

        reconcileActive(context);
        collapseQueuedDuplicates(context);

        PublicationTask duplicate = findPendingDuplicate(context, task);
        if (duplicate != null && !duplicate.id.equals(task.id)) {
            task.status = "ANNULÉ MCP • doublon de " + duplicate.id;
            PublicationTaskRepository.save(context, task);
            return new EnqueueResult(true, false, "duplicate_pending:" + duplicate.id);
        }

        int pending = countPending(context);
        if (pending >= MAX_QUEUE) {
            task.status = "ERREUR MCP • file pleine (7 vidéos max)";
            PublicationTaskRepository.save(context, task);
            return new EnqueueResult(false, false, "queue_full:7");
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String activeId = prefs.getString(ACTIVE, "");
        if (activeId == null || activeId.isEmpty()) {
            PublicationTask queued = firstQueued(context);
            if (queued != null && !queued.id.equals(task.id)) {
                boolean recovered = startQueued(context, queued, "reprise automatique de la file");
                if (recovered) {
                    int position = queuedCount(context) + 1;
                    task.status = QUEUED_PREFIX + " • position " + position + "/" + MAX_QUEUE;
                    PublicationTaskRepository.save(context, task);
                    return new EnqueueResult(true, false, "publication_queued:" + task.id + ":position=" + position);
                }
            }

            boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
            if (started) return new EnqueueResult(true, true, "publication_dispatched:" + task.id + ":" + task.platform);

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
        reconcileActive(context);
        collapseQueuedDuplicates(context);
        if (hasActive(context)) return;
        PublicationTask next = firstQueued(context);
        if (next != null) startQueued(context, next, "démarrage après confirmation précédente");
    }

    /**
     * Secours utilisé au démarrage/heartbeat : si active_task_id est fantôme, il est supprimé
     * puis la première vraie mission en file est exécutée sur le téléphone.
     */
    public static synchronized void recoverIfIdle(Context context) {
        if (context == null) return;
        reconcileActive(context);
        collapseQueuedDuplicates(context);
        if (hasActive(context)) return;
        PublicationTask next = firstQueued(context);
        if (next != null) startQueued(context, next, "reprise automatique après état inactif");
    }

    private static boolean startQueued(Context context, PublicationTask task, String reason) {
        task.status = "MISSION MCP • " + reason;
        PublicationTaskRepository.save(context, task);
        boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
        if (!started) {
            PublicationTask latest = PublicationTaskRepository.find(context, task.id);
            if (latest != null && latest.status != null && latest.status.startsWith("ERREUR")) {
                // On s'arrête sur l'erreur : aucune autre vidéo ne doit démarrer derrière.
                return false;
            }
        }
        return started;
    }

    private static void reconcileActive(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String activeId = prefs.getString(ACTIVE, "");
        if (activeId == null || activeId.isEmpty()) return;
        PublicationTask active = PublicationTaskRepository.find(context, activeId);
        if (active == null || isTerminal(active.status) || isQueued(active.status)) {
            prefs.edit().remove(ACTIVE).apply();
        }
    }

    private static boolean hasActive(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "");
        return id != null && !id.isEmpty();
    }

    private static PublicationTask firstQueued(Context context) {
        for (PublicationTask task : PublicationTaskRepository.load(context)) {
            if (isQueued(task.status)) return task;
        }
        return null;
    }

    private static PublicationTask findPendingDuplicate(Context context, PublicationTask incoming) {
        for (PublicationTask task : PublicationTaskRepository.load(context)) {
            if (task.id == null || task.id.equals(incoming.id)) continue;
            if (isTerminal(task.status)) continue;
            if (sameMission(task, incoming)) return task;
        }
        return null;
    }

    private static void collapseQueuedDuplicates(Context context) {
        Set<String> seen = new HashSet<>();
        for (PublicationTask task : PublicationTaskRepository.load(context)) {
            if (!isQueued(task.status)) continue;
            String key = missionKey(task);
            if (seen.add(key)) continue;
            task.status = "ANNULÉ MCP • doublon retiré de la file";
            PublicationTaskRepository.save(context, task);
        }
    }

    private static boolean sameMission(PublicationTask a, PublicationTask b) {
        return missionKey(a).equals(missionKey(b));
    }

    private static String missionKey(PublicationTask t) {
        String path = t.videoPath == null ? "" : t.videoPath;
        String platform = t.platform == null ? "" : t.platform;
        return platform + "|" + path + "|" + t.scheduledAt;
    }

    private static boolean isQueued(String status) {
        return status != null && status.startsWith(QUEUED_PREFIX);
    }

    private static boolean isTerminal(String status) {
        if (status == null) return false;
        String s = status.toUpperCase();
        return s.startsWith("PROGRAMMÉ") || s.startsWith("ERREUR") || s.startsWith("ANNULÉ") || s.startsWith("ANNULE");
    }

    private static int countPending(Context context) {
        int count = hasActive(context) ? 1 : 0;
        count += queuedCount(context);
        return count;
    }

    private static int queuedCount(Context context) {
        int count = 0;
        List<PublicationTask> tasks = PublicationTaskRepository.load(context);
        for (PublicationTask task : tasks) if (isQueued(task.status)) count++;
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
