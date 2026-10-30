package app.noam.extension.chesscom.stockfish;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import app.noam.extension.chesscom.Features;
import app.noam.extension.chesscom.Utils;
import app.noam.extension.chesscom.arrows.Arrows;
import app.noam.extension.chesscom.gamereview.GameReview;

/**
 * Stockfish on the phone, offline: best-move arrows, and an eval bar where chess.com has none, on
 * the boards of the screens below. Live and daily games, puzzles and anything else played against
 * a person never get it.
 */
public final class Analysis {
    private static final Set<String> SCREENS = new HashSet<>(Arrays.asList(
        "com.chess.features.analysis.selfengineless.AnalysisSelfEnginelessActivity",
        "com.chess.features.explorer.GameExplorerActivity",
        "com.chess.features.live.archive.ArchivedLiveGameActivity",
        "com.chess.features.versusbots.archive.ArchivedBotGameActivityV2"
    ));
    private static final String REVIEW_SCREEN = "com.chess.gamereview.v2.GameReviewActivity";
    private static final Set<String> ARROW_SCREENS = new HashSet<>(Arrays.asList(
        "com.chess.features.analysis.standalone.StandaloneAnalysisActivity",
        "com.chess.features.analysis.standalonev2.StandaloneAnalysisActivityV2",
        REVIEW_SCREEN
    ));

    /** Tersedia opsi Fast (8) seperti di main.js hingga Deep (22). */
    public static final int[] DEPTHS = {8, 14, 18, 22};
    public static final String[] DEPTH_NAMES = {"Fast", "Quick", "Normal", "Deep"};
    private static final String DEPTH = "stockfish_depth";
    private static final String RANDOM_DEPTH = "stockfish_random_depth";
    private static final String LINES = "stockfish_lines";
    private static final String ARROWS = "stockfish_arrows";
    private static final String BAR = "stockfish_bar";

    /** Waktu tunggu sebelum mematikan engine saat menganggur (2 menit). */
    private static final long IDLE_MS = 120_000L;
    /** Debounce (seperti di main.js) agar scrubbing papan yang cepat tidak membanjiri pipa UCI. */
    private static final long DEBOUNCE_MS = 65L;
    /** Batas waktu maksimal per pencarian live analysis agar UI tidak pernah menggantung. */
    private static final long LIVE_MOVETIME_MS = 8_500L;
    /** Batas percobaan ulang otomatis jika proses native crash. */
    private static final int MAX_ATTEMPTS = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MorpheStockfish");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final Engine ENGINE = new Engine();
    private static final Runnable IDLE = () -> WORKER.execute(ENGINE::quit);

    private static final AtomicReference<Runnable> NEXT_TASK = new AtomicReference<>();
    private static final AtomicBoolean WORKER_SCHEDULED = new AtomicBoolean(false);
    /** Setara dengan AbortController di main.js untuk membatalkan hasil dari sesi pencarian lama. */
    private static final AtomicLong SEARCH_GEN = new AtomicLong(0);

    private static final Runnable DRAIN_TASKS = () -> {
        try {
            while (true) {
                Runnable task = NEXT_TASK.getAndSet(null);
                if (task == null) break;
                task.run();
            }
        } finally {
            WORKER_SCHEDULED.set(false);
            if (NEXT_TASK.get() != null && WORKER_SCHEDULED.compareAndSet(false, true)) {
                WORKER.execute(Analysis.DRAIN_TASKS);
            }
        }
    };

    private static Runnable pendingDebounce;
    private static volatile String wanted;
    private static volatile WeakReference<View> wantedScreen = new WeakReference<>(null);
    private static volatile boolean searching;
    private static volatile boolean toastShown;
    private static volatile Method fenGetter;
    private static volatile Class<?> fenGetterOwner;

    private Analysis() {}

    private static String fenMethod() {
        return "";
    }

    public static boolean enabled() {
        return Features.stockfishPatched() && Features.isEnabled(Features.STOCKFISH);
    }

    public static void onPosition(Object position) {
        if (position == null || !enabled()) return;
        Activity activity = Utils.topActivity();
        if (activity == null || activity.getWindow() == null) return;
        String name = activity.getClass().getName();
        boolean withBar = SCREENS.contains(name);
        if (!withBar && !ARROW_SCREENS.contains(name)) return;
        String fen = fen(position);
        if (fen == null) return;
        View decor = activity.getWindow().getDecorView();

        if (name.equals(REVIEW_SCREEN) && GameReview.lichessReview() && GameReview.lichessArrow(fen)) {
            abortCurrentSearch();
            MAIN.post(() -> Arrows.clearEngine(decor));
            return;
        }
        if (fen.equals(wanted) && wantedScreen.get() == decor) return;

        WeakReference<View> screen = new WeakReference<>(decor);
        wanted = fen;
        wantedScreen = screen;
        final long gen = SEARCH_GEN.incrementAndGet();

        if (searching) ENGINE.stopSearch();
        MAIN.removeCallbacks(IDLE);
        if (pendingDebounce != null) MAIN.removeCallbacks(pendingDebounce);

        MAIN.post(() -> {
            View view = screen.get();
            if (view != null) Arrows.clearEngine(view);
        });

        pendingDebounce = () -> scheduleLatest(() -> analyse(fen, screen, withBar, gen));
        MAIN.postDelayed(pendingDebounce, DEBOUNCE_MS);
    }

    private static void abortCurrentSearch() {
        wanted = null;
        SEARCH_GEN.incrementAndGet();
        if (pendingDebounce != null) MAIN.removeCallbacks(pendingDebounce);
        if (searching) ENGINE.stopSearch();
    }

    private static void scheduleLatest(Runnable task) {
        NEXT_TASK.set(task);
        if (WORKER_SCHEDULED.compareAndSet(false, true)) {
            WORKER.execute(DRAIN_TASKS);
        }
    }

    private static void analyse(String fen, WeakReference<View> screen, boolean withBar, long gen) {
        if (gen != SEARCH_GEN.get() || !fen.equals(wanted)) return;
        Context context = Utils.context();
        if (context == null) return;

        int targetDepth = effectiveDepth();
        int targetLines = lines();
        Engine.Result result = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (gen != SEARCH_GEN.get() || !fen.equals(wanted)) return;

            if (!ENGINE.ready()) {
                if (!ENGINE.start(context)) {
                    if (attempt == MAX_ATTEMPTS && !toastShown) {
                        toastShown = true;
                        MAIN.post(() -> Utils.toast("Stockfish could not start on this phone"));
                    }
                    continue;
                }
                toastShown = false;
            }

            if (gen != SEARCH_GEN.get() || !fen.equals(wanted)) return;
            searching = true;
            try {
                result = ENGINE.analyse(
                    fen,
                    targetDepth,
                    LIVE_MOVETIME_MS,
                    targetLines,
                    partial -> publish(fen, partial, screen, withBar, gen)
                );
            } finally {
                searching = false;
            }

            // Jika pencarian valid atau sengaja dibatalkan oleh posisi baru, keluar dari loop retry
            if ((result != null && result.valid()) || gen != SEARCH_GEN.get() || !fen.equals(wanted)) {
                break;
            }
            // Engine crash di tengah jalan, paksa restart pada iterasi berikutnya
            ENGINE.quit();
        }

        publish(fen, result, screen, withBar, gen);
        MAIN.removeCallbacks(IDLE);
        MAIN.postDelayed(IDLE, IDLE_MS);
    }

    private static void publish(String fen, Engine.Result result, WeakReference<View> screen, boolean withBar, long gen) {
        if (gen != SEARCH_GEN.get() || !fen.equals(wanted) || result == null || !result.valid()) return;
        int[][] moves = arrows() ? squares(result.moves) : new int[0][];
        float score = withBar && bar() ? result.score : Float.NaN;
        MAIN.post(() -> {
            View view = screen.get();
            if (view != null && gen == SEARCH_GEN.get() && fen.equals(wanted)) {
                Arrows.showEngine(view, moves, score, result.mate, result.over);
            }
        });
    }

    private static int[][] squares(List<String> moves) {
        int size = moves.size();
        int[][] squares = new int[size][];
        for (int i = 0; i < size; i++) {
            String move = moves.get(i);
            if (move != null && move.length() >= 4) {
                int fromFile = move.charAt(0) - 'a';
                int fromRank = move.charAt(1) - '1';
                int toFile = move.charAt(2) - 'a';
                int toRank = move.charAt(3) - '1';
                if ((fromFile | fromRank | toFile | toRank) >= 0
                        && fromFile < 8 && fromRank < 8 && toFile < 8 && toRank < 8) {
                    squares[i] = new int[]{
                        fromFile + (fromRank << 3),
                        toFile + (toRank << 3),
                    };
                    continue;
                }
            }
            squares[i] = new int[]{0, 0};
        }
        return squares;
    }

    private static String fen(Object position) {
        try {
            Class<?> posClass = position.getClass();
            Method getter = fenGetter;
            if (getter == null || fenGetterOwner != posClass) {
                getter = findFenGetter(posClass);
                if (getter == null) return null;
                fenGetter = getter;
                fenGetterOwner = posClass;
            }
            Object value = getter.invoke(position);
            if (!(value instanceof String)) return null;
            String raw = (String) value;
            int len = raw.length();
            StringBuilder sb = new StringBuilder(len);
            int fieldCount = 0;
            int slashCount = 0;
            boolean inToken = false;

            for (int i = 0; i < len; i++) {
                char c = raw.charAt(i);
                if (c <= ' ') {
                    if (inToken) inToken = false;
                } else {
                    if (!inToken) {
                        if (fieldCount > 0) sb.append(' ');
                        fieldCount++;
                        inToken = true;
                    }
                    if (fieldCount == 1 && c == '/') slashCount++;
                    sb.append(c);
                }
            }
            return (fieldCount == 6 && slashCount == 7) ? sb.toString() : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Method findFenGetter(Class<?> type) {
        String name = fenMethod();
        if (name == null || name.isEmpty()) return null;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> implemented : c.getInterfaces()) {
                try {
                    Method method = implemented.getMethod(name);
                    if (method.getReturnType() == String.class && method.getParameterTypes().length == 0) {
                        method.setAccessible(true);
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            try {
                Method method = c.getDeclaredMethod(name);
                if (method.getReturnType() == String.class && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    public static int depthIndex() {
        SharedPreferences preferences = Features.preferences();
        int index = preferences == null ? 2 : preferences.getInt(DEPTH, 2);
        return Math.max(0, Math.min(DEPTHS.length - 1, index));
    }

    public static void setDepthIndex(int index) {
        SharedPreferences preferences = Features.preferences();
        if (preferences != null) preferences.edit().putInt(DEPTH, index).apply();
        abortCurrentSearch();
    }

    public static boolean randomDepth() {
        SharedPreferences preferences = Features.preferences();
        return preferences != null && preferences.getBoolean(RANDOM_DEPTH, false);
    }

    public static void setRandomDepth(boolean random) {
        SharedPreferences preferences = Features.preferences();
        if (preferences != null) preferences.edit().putBoolean(RANDOM_DEPTH, random).apply();
        abortCurrentSearch();
    }

    private static int effectiveDepth() {
        int base = DEPTHS[depthIndex()];
        int max = DEPTHS[DEPTHS.length - 1];
        if (randomDepth() && base < max) {
            return ThreadLocalRandom.current().nextInt(base, max + 1);
        }
        return base;
    }

    public static int lines() {
        SharedPreferences preferences = Features.preferences();
        int lines = preferences == null ? 2 : preferences.getInt(LINES, 2);
        return Math.max(1, Math.min(3, lines));
    }

    public static void setLines(int lines) {
        SharedPreferences preferences = Features.preferences();
        if (preferences != null) preferences.edit().putInt(LINES, lines).apply();
        abortCurrentSearch();
    }

    public static boolean arrows() {
        SharedPreferences preferences = Features.preferences();
        return preferences == null || preferences.getBoolean(ARROWS, true);
    }

    public static void setArrows(boolean on) {
        SharedPreferences preferences = Features.preferences();
        if (preferences != null) preferences.edit().putBoolean(ARROWS, on).apply();
        wanted = null;
    }

    public static boolean bar() {
        SharedPreferences preferences = Features.preferences();
        return preferences == null || preferences.getBoolean(BAR, true);
    }

    public static void setBar(boolean on) {
        SharedPreferences preferences = Features.preferences();
        if (preferences != null) preferences.edit().putBoolean(BAR, on).apply();
        wanted = null;
    }
}
