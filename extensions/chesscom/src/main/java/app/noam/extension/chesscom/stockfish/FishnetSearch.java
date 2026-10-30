package app.noam.extension.chesscom.stockfish;

import android.app.ActivityManager;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import app.noam.extension.chesscom.lichesspuzzle.San;

public final class FishnetSearch {
    private static final int CHUNK = 6;
    private static final int THREADS = 1, HASH_MB = 16;
    private static final long POLL_MS = 180;
    /** Mengadopsi konsep MAX_ATTEMPTS dari main.js agar Game Review tahan banting saat RAM penuh. */
    private static final int MAX_ATTEMPTS = 3;

    public interface Progress {
        void searched(float fraction) throws Throwable;
    }

    private FishnetSearch() {}

    public static Engine.Result[] search(Context context, String startFen, List<String> moves, String[] fens,
                                         long nodesPerMove, int engines, Progress progress) throws Throwable {
        if (fens == null || fens.length == 0) return new Engine.Result[0];

        long nodes = Math.max(1_000L, nodesPerMove * CHUNK / (CHUNK + 1));
        int last = fens.length - 1;
        List<int[]> chunks = new ArrayList<>((last / (CHUNK - 1)) + 2);
        int total = 0;
        for (int first = 0; first <= last; first += CHUNK - 1) {
            int[] chunk = new int[Math.min(CHUNK - 1, last - first + 1) + 1];
            chunk[0] = last - Math.max(0, first - 1);
            for (int k = 1; k < chunk.length; k++) chunk[k] = last - (first + k - 1);
            chunks.add(chunk);
            total += chunk.length;
        }

        final int totalWork = Math.max(1, total);
        Engine.Result[] results = new Engine.Result[last + 1];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger searched = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        List<Engine> running = Collections.synchronizedList(new ArrayList<>(engines));

        int workerCount = Math.max(1, Math.min(engines, chunks.size()));
        Thread[] workers = new Thread[workerCount];
        for (int w = 0; w < workerCount; w++) {
            workers[w] = new Thread(() -> {
                Engine engine = new Engine(THREADS, HASH_MB);
                running.add(engine);
                try {
                    if (!startWithRetry(engine, context, stop)) return;

                    for (int c; !stop.get() && (c = next.getAndIncrement()) < chunks.size(); ) {
                        int[] chunk = chunks.get(c);
                        engine.newGame();
                        for (int k = 0; k < chunk.length && !stop.get(); k++) {
                            int ply = chunk[k];
                            Engine.Result result = finished(fens[ply]);
                            if (result == null) {
                                List<String> history = (moves != null && ply <= moves.size())
                                    ? moves.subList(0, ply) : null;

                                for (int attempt = 1; attempt <= MAX_ATTEMPTS && !stop.get(); attempt++) {
                                    result = engine.analyseGame(startFen, history, fens[ply], 0, nodes, 1);
                                    if (result != null && result.valid()) break;
                                    if (attempt < MAX_ATTEMPTS && startWithRetry(engine, context, stop)) {
                                        engine.newGame();
                                    }
                                }
                                if (result == null || !result.valid()) {
                                    stop.set(true);
                                    return;
                                }
                            }
                            if (k > 0) results[ply] = result;
                            searched.incrementAndGet();
                        }
                    }
                } finally {
                    engine.quit();
                }
            }, "MorpheFishnet" + w);
            workers[w].setPriority(Thread.NORM_PRIORITY - 1);
            workers[w].start();
        }

        try {
            for (Thread worker : workers) {
                while (worker.isAlive()) {
                    if (progress != null) {
                        progress.searched(Math.min(1f, (float) searched.get() / totalWork));
                    }
                    worker.join(POLL_MS);
                }
            }
            if (progress != null && !stop.get()) {
                progress.searched(1f);
            }
        } catch (Throwable stopped) {
            stop.set(true);
            synchronized (running) {
                for (Engine engine : running) engine.stopSearch();
            }
            for (Thread worker : workers) {
                try {
                    worker.join(1_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            throw stopped;
        }
        for (int i = 0; i <= last; i++) {
            if (results[i] == null) {
                Engine.Result term = finished(fens[i]);
                if (term != null) results[i] = term;
                else throw new IOException("Stockfish could not analyse the game");
            }
        }
        return results;
    }

    private static boolean startWithRetry(Engine engine, Context context, AtomicBoolean stop) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !stop.get(); attempt++) {
            if (engine.start(context)) return true;
            try {
                Thread.sleep(100L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static Engine.Result finished(String fen) {
        boolean whiteToMove = Engine.isWhiteToMove(fen);
        if (San.checkmate(fen)) {
            float score = whiteToMove ? -Engine.MATE_SCORE : Engine.MATE_SCORE;
            return new Engine.Result(Collections.emptyList(), score, 0, 0, true, Collections.emptyList(), new float[]{score});
        }
        boolean stalemate = San.stalemate(fen);
        if (stalemate || San.insufficientMaterial(fen)) {
            return new Engine.Result(Collections.emptyList(), 0f, 0, 0, stalemate, Collections.emptyList(), new float[]{0f});
        }
        return null;
    }

    public static int defaultEngines(Context context) {
        long totalMb = 0;
        long availMb = Long.MAX_VALUE;
        if (context != null) {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                if (info.lowMemory) return 1;
                totalMb = info.totalMem / (1024L * 1024L);
                availMb = info.availMem / (1024L * 1024L);
            }
        }
        int byTotal = totalMb >= 7_000 ? 4 : totalMb >= 5_000 ? 3 : totalMb >= 3_000 ? 2 : 1;
        int byAvail = (int) Math.max(1L, (availMb - 300L) / 180L);
        int byMemory = Math.min(byTotal, byAvail);
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), byMemory));
    }
}
