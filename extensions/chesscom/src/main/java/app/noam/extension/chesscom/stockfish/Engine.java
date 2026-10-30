package app.noam.extension.chesscom.stockfish;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import app.noam.extension.chesscom.Utils;

public final class Engine {
    private static final long READY_TIMEOUT_MS = 30_000L;
    private static final long SEARCH_TIMEOUT_MS = 60_000L;
    public static final float MATE_SCORE = 99f;
    /** Melaporkan progres lebih awal (mulai depth 6) agar UI terasa jauh lebih responsif. */
    private static final int PROGRESS_MIN_DEPTH = 6;
    private static final long PROGRESS_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(160);

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MorpheUCIWatchdog");
        t.setDaemon(true);
        return t;
    });

    public interface Progress {
        void onProgress(Result partial);
    }

    public static final class Result {
        public final List<String> moves;
        public final float score;
        public final int mate;
        public final int depth;
        public final boolean over;
        public final List<String> pv;
        public final float[] lineScores;

        Result(List<String> moves, float score, int mate, int depth, boolean over, List<String> pv, float[] lineScores) {
            this.moves = moves;
            this.score = score;
            this.mate = mate;
            this.depth = depth;
            this.over = over;
            this.pv = pv;
            this.lineScores = lineScores;
        }

        public boolean valid() {
            return !moves.isEmpty() || over;
        }
    }

    private Process process;
    private PrintWriter stdin;
    private BufferedReader stdout;
    private volatile boolean ready;
    private int multiPv = -1;
    private final int threads, hash;

    public Engine() {
        this(0, 0);
    }

    public Engine(int threads, int hashMb) {
        this.threads = threads;
        this.hash = hashMb;
    }

    public synchronized boolean start(Context context) {
        quit();
        if (context == null) return false;
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libstockfish.so");
        if (!binary.canExecute()) {
            Utils.log("Stockfish is not in " + binary.getParent());
            return false;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath());
            builder.redirectErrorStream(true);
            process = builder.start();
            stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            multiPv = -1;
            send("uci");
            if (!waitFor("uciok", READY_TIMEOUT_MS)) return failed();

            int cores = Runtime.getRuntime().availableProcessors();
            int threadCount = threads > 0 ? threads : Math.max(1, Math.min(4, cores / 2));
            int hashSize = hash > 0 ? hash : hashMb(context);
            send("setoption name Threads value " + threadCount);
            send("setoption name Hash value " + hashSize);
            send("ucinewgame");
            send("isready");
            if (!waitFor("readyok", READY_TIMEOUT_MS)) return failed();
            ready = true;
            return true;
        } catch (Throwable e) {
            Utils.logError("Stockfish did not start", e);
            return failed();
        }
    }

    public boolean ready() {
        Process p = process;
        if (!ready || p == null) return false;
        try {
            p.exitValue();
            ready = false;
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    public Result analyse(String fen, int depth, int lines, Progress progress) {
        return analyse(fen, depth, 0L, lines, progress);
    }

    public Result analyse(String fen, int depth, long moveTimeMs, int lines, Progress progress) {
        if (fen == null || fen.isEmpty()) return empty();
        return search("position fen " + fen, isWhiteToMove(fen), depth, 0L, moveTimeMs, lines, progress);
    }

    public Result analyseGame(String startFen, List<String> history, String positionFen, int depth, long nodes,
                              int lines) {
        if (positionFen == null || positionFen.isEmpty()) return empty();
        String position;
        if (startFen != null && !startFen.isEmpty() && history != null && !history.isEmpty()) {
            StringBuilder sb = new StringBuilder(startFen.length() + history.size() * 5 + 24);
            sb.append("position fen ").append(startFen).append(" moves");
            for (int i = 0, n = history.size(); i < n; i++) {
                sb.append(' ').append(history.get(i));
            }
            position = sb.toString();
        } else {
            position = "position fen " + positionFen;
        }
        return search(position, isWhiteToMove(positionFen), depth, nodes, 0L, lines, null);
    }

    /** Deteksi giliran jalan yang presisi pada field ke-2 FEN tanpa alokasi array split(). */
    public static boolean isWhiteToMove(String fen) {
        if (fen == null) return true;
        int space = fen.indexOf(' ');
        if (space < 0 || space + 1 >= fen.length()) return true;
        return fen.charAt(space + 1) != 'b';
    }

    public void newGame() {
        if (!ready()) return;
        send("ucinewgame");
        send("isready");
        try {
            if (!waitFor("readyok", READY_TIMEOUT_MS)) ready = false;
        } catch (IOException e) {
            ready = false;
        }
    }

    private Result search(String position, boolean whiteToMove, int depth, long nodes, long moveTimeMs,
                          int lines, Progress progress) {
        if (!ready()) return empty();
        int safeLines = Math.max(1, lines);
        try {
            send("isready");
            if (!waitFor("readyok", READY_TIMEOUT_MS)) {
                quit();
                return empty();
            }
            if (safeLines != multiPv) {
                send("setoption name MultiPV value " + safeLines);
                multiPv = safeLines;
            }
            send(position);
            StringBuilder go = new StringBuilder(48).append("go");
            if (depth > 0) go.append(" depth ").append(depth);
            if (nodes > 0) go.append(" nodes ").append(nodes);
            if (moveTimeMs > 0) go.append(" movetime ").append(moveTimeMs);
            send(go.toString());
            return readSearch(safeLines, whiteToMove, depth, moveTimeMs, progress);
        } catch (Throwable e) {
            quit();
            return empty();
        }
    }

    public void stopSearch() {
        send("stop");
    }

    public synchronized void quit() {
        ready = false;
        PrintWriter writer = stdin;
        stdin = null;
        if (writer != null) {
            try {
                writer.println("quit");
                writer.close();
            } catch (Throwable ignored) {}
        }
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {}
        }
        BufferedReader reader = stdout;
        stdout = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {}
        }
    }

    private Result readSearch(int lines, boolean whiteToMove, int targetDepth, long moveTimeMs,
                              Progress progress) throws IOException {
        String[] firstMoves = new String[lines];
        float[] scores = new float[lines];
        boolean[] exact = new boolean[lines];
        @SuppressWarnings("unchecked")
        List<String>[] pvs = new List[lines];
        int mate = 0, reached = 0, reported = 0;
        boolean over = false;
        String bestmove = null;
        long lastReport = 0;

        BufferedReader reader = stdout;
        if (reader == null) return empty();

        long softTimeout = moveTimeMs > 0 ? Math.min(SEARCH_TIMEOUT_MS, moveTimeMs + 2_500L) : SEARCH_TIMEOUT_MS;
        ScheduledFuture<?> softStop = WATCHDOG.schedule(this::stopSearch, softTimeout, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> hardKill = WATCHDOG.schedule(this::quit, softTimeout + READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    ready = false;
                    break;
                }
                if (line.startsWith("bestmove")) {
                    int start = 9;
                    int len = line.length();
                    while (start < len && line.charAt(start) == ' ') start++;
                    if (start < len) {
                        int end = line.indexOf(' ', start);
                        if (end < 0) end = len;
                        String move = line.substring(start, end);
                        if (!"(none)".equals(move) && !"0000".equals(move)) bestmove = move;
                        else over = true;
                    } else {
                        over = true;
                    }
                    break;
                }
                if (!line.startsWith("info ") || line.startsWith("info string") || !line.contains(" score ")) continue;

                int pv = 1, depth = 0, value = 0, pvStart = -1;
                boolean bound = false, isMate = false, scored = false;
                int len = line.length();
                int pos = 5;

                while (pos < len) {
                    while (pos < len && line.charAt(pos) == ' ') pos++;
                    if (pos >= len) break;
                    int nextSpace = line.indexOf(' ', pos);
                    if (nextSpace < 0) nextSpace = len;

                    int tokenLen = nextSpace - pos;
                    char c0 = line.charAt(pos);

                    if (tokenLen == 5 && c0 == 'd' && line.startsWith("depth", pos)) {
                        pos = nextSpace + 1;
                        while (pos < len && line.charAt(pos) == ' ') pos++;
                        int valEnd = line.indexOf(' ', pos);
                        if (valEnd < 0) valEnd = len;
                        depth = parseIntRange(line, pos, valEnd);
                        pos = valEnd + 1;
                    } else if (tokenLen == 7 && c0 == 'm' && line.startsWith("multipv", pos)) {
                        pos = nextSpace + 1;
                        while (pos < len && line.charAt(pos) == ' ') pos++;
                        int valEnd = line.indexOf(' ', pos);
                        if (valEnd < 0) valEnd = len;
                        pv = parseIntRange(line, pos, valEnd);
                        pos = valEnd + 1;
                    } else if (tokenLen == 5 && c0 == 's' && line.startsWith("score", pos)) {
                        pos = nextSpace + 1;
                        while (pos < len && line.charAt(pos) == ' ') pos++;
                        int typeEnd = line.indexOf(' ', pos);
                        if (typeEnd > pos) {
                            isMate = (typeEnd - pos == 4) && line.startsWith("mate", pos);
                            pos = typeEnd + 1;
                            while (pos < len && line.charAt(pos) == ' ') pos++;
                            int valEnd = line.indexOf(' ', pos);
                            if (valEnd < 0) valEnd = len;
                            if (pos < valEnd) {
                                value = parseIntRange(line, pos, valEnd);
                                scored = true;
                            }
                            pos = valEnd + 1;
                        } else {
                            break;
                        }
                    } else if (tokenLen == 10 && ((c0 == 'l' && line.startsWith("lowerbound", pos))
                            || (c0 == 'u' && line.startsWith("upperbound", pos)))) {
                        bound = true;
                        pos = nextSpace + 1;
                    } else if (tokenLen == 2 && c0 == 'p' && line.charAt(pos + 1) == 'v') {
                        pos = nextSpace + 1;
                        while (pos < len && line.charAt(pos) == ' ') pos++;
                        if (pos < len) pvStart = pos;
                        break;
                    } else {
                        pos = nextSpace + 1;
                    }
                }

                if (!scored || pv < 1 || pv > lines) continue;
                int index = pv - 1;
                if (bound && exact[index]) continue;
                if (depth == 0 && pvStart < 0) over = true;

                float score;
                int whiteMate = 0;
                if (isMate) {
                    whiteMate = whiteToMove ? value : -value;
                    if (value == 0) score = whiteToMove ? -MATE_SCORE : MATE_SCORE;
                    else score = whiteMate > 0 ? Math.max(1f, MATE_SCORE - whiteMate) : Math.min(-1f, -MATE_SCORE - whiteMate);
                } else {
                    score = (whiteToMove ? value : -value) * 0.01f;
                }
                scores[index] = score;
                if (!bound) exact[index] = true;

                if (pvStart > 0 && pvStart < len) {
                    int firstEnd = line.indexOf(' ', pvStart);
                    if (firstEnd < 0) firstEnd = len;
                    String firstMove = line.substring(pvStart, firstEnd);
                    firstMoves[index] = firstMove;
                    if (!bound) {
                        List<String> fullLine = new ArrayList<>(16);
                        fullLine.add(firstMove);
                        int mPos = firstEnd + 1;
                        while (mPos < len) {
                            while (mPos < len && line.charAt(mPos) == ' ') mPos++;
                            if (mPos >= len) break;
                            int mEnd = line.indexOf(' ', mPos);
                            if (mEnd < 0) mEnd = len;
                            fullLine.add(line.substring(mPos, mEnd));
                            mPos = mEnd + 1;
                        }
                        pvs[index] = fullLine;
                    }
                }
                if (index == 0) {
                    if (depth > reached) reached = depth;
                    mate = isMate && value != 0 ? whiteMate : 0;
                }

                if (progress != null && index == 0 && !bound && depth >= PROGRESS_MIN_DEPTH && depth > reported
                    && (targetDepth <= 0 || depth < targetDepth) && firstMoves[0] != null) {
                    long now = System.nanoTime();
                    if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                        reported = depth;
                        lastReport = now;
                        progress.onProgress(result(firstMoves, scores, mate, depth, false, null, pvs));
                    }
                }
            }
        } finally {
            softStop.cancel(false);
            hardKill.cancel(false);
        }
        return result(firstMoves, scores, mate, reached, over, bestmove, pvs);
    }

    private static Result result(String[] firstMoves, float[] scores, int mate, int depth, boolean over,
                                  String bestmove, List<String>[] pvs) {
        List<String> list = new ArrayList<>(firstMoves.length);
        for (int i = 0; i < firstMoves.length; i++) {
            String move = (i == 0 && bestmove != null) ? bestmove : firstMoves[i];
            if (move != null && !list.contains(move)) list.add(move);
        }
        boolean finished = over && list.isEmpty();
        List<String> pv = pvs.length > 0 && pvs[0] != null ? pvs[0]
            : list.isEmpty() ? Collections.emptyList() : Collections.singletonList(list.get(0));
        return new Result(list, scores[0], mate, depth, finished, pv, scores.clone());
    }

    private static Result empty() {
        return new Result(Collections.emptyList(), 0f, 0, 0, false, Collections.emptyList(), new float[0]);
    }

    private static int hashMb(Context context) {
        long totalMb = 0;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            if (info.lowMemory) return 16;
            totalMb = info.totalMem / (1024L * 1024L);
        }
        if (totalMb >= 7_000) return 256;
        if (totalMb >= 3_000) return 128;
        return 32;
    }

    private boolean failed() {
        quit();
        return false;
    }

    private void send(String command) {
        PrintWriter writer = stdin;
        if (writer != null) {
            writer.println(command);
            if (writer.checkError()) ready = false;
        }
    }

    private boolean waitFor(String token, long timeoutMs) throws IOException {
        BufferedReader reader = stdout;
        if (reader == null) return false;
        ScheduledFuture<?> killer = WATCHDOG.schedule(this::quit, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            while (true) {
                String line = reader.readLine();
                if (line == null) return false;
                if (line.startsWith(token)) return true;
            }
        } finally {
            killer.cancel(false);
        }
    }

    private static int parseIntRange(String s, int start, int end) {
        if (start >= end) return 0;
        boolean neg = false;
        char c0 = s.charAt(start);
        if (c0 == '-') {
            neg = true;
            start++;
        } else if (c0 == '+') {
            start++;
        }
        int result = 0;
        for (int i = start; i < end; i++) {
            int digit = s.charAt(i) - '0';
            if (digit < 0 || digit > 9) return 0;
            result = result * 10 + digit;
        }
        return neg ? -result : result;
    }
}
