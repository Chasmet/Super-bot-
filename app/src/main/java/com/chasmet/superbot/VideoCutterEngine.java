package com.chasmet.superbot;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class VideoCutterEngine {
    private VideoCutterEngine() {}

    public static long getDurationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    public static List<File> split(Context context, Uri source, int segmentSeconds, Progress progress) throws Exception {
        long durationMs = getDurationMs(context, source);
        if (durationMs <= 0) throw new IllegalStateException("Durée vidéo introuvable");
        int safeSeconds = Math.max(1, segmentSeconds);
        long segmentMs = safeSeconds * 1000L;
        File root = new File(context.getExternalFilesDir(null), "Movies/SuperBot");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Dossier de sortie impossible");
        File work = new File(root, "Video_" + System.currentTimeMillis());
        if (!work.mkdirs()) throw new IllegalStateException("Dossier vidéo impossible");

        List<File> outputs = new ArrayList<>();
        int total = (int) Math.ceil(durationMs / (double) segmentMs);
        for (int i = 0; i < total; i++) {
            long startMs = i * segmentMs;
            long endMs = Math.min(durationMs, startMs + segmentMs);
            File out = new File(work, String.format("part_%03d.mp4", i + 1));
            cut(context, source, out, startMs, endMs);
            outputs.add(out);
            if (progress != null) progress.onProgress(i + 1, total, out);
        }
        return outputs;
    }

    public static File trim(Context context, Uri source, long startMs, long endMs) throws Exception {
        long duration = getDurationMs(context, source);
        long safeStart = Math.max(0L, startMs);
        long safeEnd = Math.min(duration, endMs <= 0 ? duration : endMs);
        if (safeEnd <= safeStart) throw new IllegalArgumentException("Intervalle invalide");
        File root = new File(context.getExternalFilesDir(null), "Movies/SuperBot/Trims");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Dossier de sortie impossible");
        File out = new File(root, "trim_" + System.currentTimeMillis() + ".mp4");
        cut(context, source, out, safeStart, safeEnd);
        return out;
    }

    private static void cut(Context context, Uri source, File output, long startMs, long endMs) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(context, source, null);
            int trackCount = extractor.getTrackCount();
            int[] map = new int[trackCount];
            for (int i = 0; i < trackCount; i++) map[i] = -1;
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    extractor.selectTrack(i);
                    map[i] = muxer.addTrack(format);
                }
            }
            muxer.start();
            muxerStarted = true;
            long startUs = startMs * 1000L;
            long endUs = endMs * 1000L;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long firstPts = -1L;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                long pts = extractor.getSampleTime();
                if (pts < 0 || pts > endUs) break;
                if (pts < startUs) {
                    extractor.advance();
                    continue;
                }
                int outTrack = track < map.length ? map[track] : -1;
                if (outTrack < 0) {
                    extractor.advance();
                    continue;
                }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (firstPts < 0) firstPts = pts;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0L, pts - firstPts);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outTrack, buffer, info);
                extractor.advance();
            }
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                }
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    public interface Progress {
        void onProgress(int done, int total, File latest);
    }
}
