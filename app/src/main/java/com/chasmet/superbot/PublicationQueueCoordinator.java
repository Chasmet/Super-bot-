package com.chasmet.superbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PublicationQueueCoordinator {
    public static final int MAX_QUEUE = 7;
    private static final String PREFS = "superbot_bot_state";
    private static final String ACTIVE = "active_task_id";
    private static final String QUEUED_PREFIX = "queued";

    private PublicationQueueCoordinator() {}

    public static synchronized EnqueueResult enqueue(Context context, PublicationTask task) {
        if (context == null || task == null) return new EnqueueResult(false, false, "invalid_task");
        reconcileActive(context);
        collapseQueuedDuplicates(context);

        PublicationTask duplicate = findPendingDuplicate(context, task);
        if (duplicate != null && !duplicate.id.equals(task.id)) {
            task.status = "failed: duplicate_pending";
            PublicationTaskRepository.save(context, task);
            RemoteTaskReporter.failed(context, task, "duplicate_pending");
            return new EnqueueResult(false, false, "duplicate_pending:" + duplicate.id);
        }

        int pending = countPending(context);
        if (pending >= MAX_QUEUE) {
            task.status = "failed: queue_full_7";
            PublicationTaskRepository.save(context, task);
            RemoteTaskReporter.failed(context, task, "queue_full:7");
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
                    RemoteTaskReporter.progress(context, task, "queued", "publication_queued");
                    return new EnqueueResult(true, false, "publication_queued:" + task.id + ":position=" + position);
                }
            }
            boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
            if (started) {
                task.status = "android_started";
                PublicationTaskRepository.save(context, task);
                RemoteTaskReporter.progress(context, task, "android_started", "publication_dispatched");
                return new EnqueueResult(true, true, "publication_dispatched:" + task.id + ":" + task.platform);
            }
            PublicationTask latest = PublicationTaskRepository.find(context, task.id);
            String status = latest == null ? task.status : latest.status;
            if (status == null || status.trim().isEmpty()) status = "dispatch_failed";
            RemoteTaskReporter.failed(context, task, status);
            return new EnqueueResult(false, false, "publication_dispatch_failed:" + task.id + ":" + status);
        }

        int position = queuedCount(context) + 1;
        task.status = QUEUED_PREFIX + " • position " + position + "/" + MAX_QUEUE;
        PublicationTaskRepository.save(context, task);
        RemoteTaskReporter.progress(context, task, "queued", "publication_queued");
        return new EnqueueResult(true, false, "publication_queued:" + task.id + ":position=" + position);
    }

    public static synchronized void startNextAfterConfirmed(Context context) {
        if (context == null) return;
        reconcileActive(context);
        collapseQueuedDuplicates(context);
        if (hasActive(context)) return;
        PublicationTask next = firstQueued(context);
        if (next != null) startQueued(context, next, "démarrage après confirmation précédente");
    }

    public static synchronized void recoverIfIdle(Context context) {
        if (context == null) return;
        reconcileActive(context);
        collapseQueuedDuplicates(context);
        if (hasActive(context)) return;
        PublicationTask next = firstQueued(context);
        if (next != null) startQueued(context, next, "reprise automatique après état inactif");
    }

    private static boolean startQueued(Context context, PublicationTask task, String reason) {
        task.status = "android_starting";
        PublicationTaskRepository.save(context, task);
        boolean started = PublicationAlarmReceiver.dispatchNow(context, task);
        if (started) {
            task.status = "android_started";
            PublicationTaskRepository.save(context, task);
            RemoteTaskReporter.progress(context, task, "android_started", reason);
            return true;
        }
        PublicationTask latest = PublicationTaskRepository.find(context, task.id);
        String error = latest == null ? "dispatch_failed" : latest.status;
        RemoteTaskReporter.failed(context, task, error);
        return false;
    }

    private static void reconcileActive(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String activeId = prefs.getString(ACTIVE, "");
        if (activeId == null || activeId.isEmpty()) return;
        PublicationTask active = PublicationTaskRepository.find(context, activeId);
        if (active == null || isTerminal(active.status) || isQueued(active.status)) prefs.edit().remove(ACTIVE).apply();
    }

    private static boolean hasActive(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "");
        return id != null && !id.isEmpty();
    }

    private static PublicationTask firstQueued(Context context) {
        for (PublicationTask task : PublicationTaskRepository.load(context)) if (isQueued(task.status)) return task;
        return null;
    }

    private static PublicationTask findPendingDuplicate(Context context, PublicationTask incoming) {
        for (PublicationTask task : PublicationTaskRepository.load(context)) {
            if (task.id == null || task.id.equals(incoming.id) || isTerminal(task.status)) continue;
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
            task.status = "failed: duplicate_removed";
            PublicationTaskRepository.save(context, task);
            RemoteTaskReporter.failed(context, task, "duplicate_removed");
        }
    }

    private static boolean sameMission(PublicationTask a, PublicationTask b) { return missionKey(a).equals(missionKey(b)); }

    private static String missionKey(PublicationTask t) {
        String path = t.videoPath == null ? "" : t.videoPath;
        String platform = t.platform == null ? "" : t.platform;
        String title = t.title == null ? "" : t.title;
        String description = t.description == null ? "" : t.description;
        String hashtags = t.hashtags == null ? "" : t.hashtags;
        return platform + "|" + path + "|" + t.scheduledAt + "|" + title + "|" + description + "|" + hashtags;
    }

    private static boolean isQueued(String status) { return status != null && status.startsWith(QUEUED_PREFIX); }

    private static boolean isTerminal(String status) {
        if (status == null) return false;
        String s = status.toLowerCase();
        return s.startsWith("completed") || s.startsWith("failed") || s.startsWith("programmé") || s.startsWith("erreur") || s.startsWith("annulé") || s.startsWith("annule");
    }

    private static int countPending(Context context) { return (hasActive(context) ? 1 : 0) + queuedCount(context); }

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
        EnqueueResult(boolean ok, boolean started, String message) { this.ok = ok; this.started = started; this.message = message; }
    }
}
