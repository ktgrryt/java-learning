package jq.progress;

import jq.json.MiniJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 学習の進捗を progress.json に保存する。
 *
 * 保持するもの:
 *  - クリア済みレッスン（クリア日、使ったヒント数、提出回数）
 *  - レッスンごとに最後に書いたコード（再訪時に復元する）
 *  - 何か1問クリアした日付の集合（連続学習日数の計算に使う）
 *
 * サーバは複数リクエストを並行に処理するので、状態変更は全て synchronized で守る。
 */
public final class ProgressStore {

    private final Path file;

    /** レッスンID -> クリア情報 */
    private final Map<String, Cleared> cleared = new LinkedHashMap<>();
    /** レッスンID -> 最後に書いたコード */
    private final Map<String, String> codes = new LinkedHashMap<>();
    /** レッスンID -> 開示済みヒント数 */
    private final Map<String, Integer> hintsRevealed = new LinkedHashMap<>();
    /** レッスンID -> 提出回数 */
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    /** 何かをクリアした日付 */
    private final Set<String> clearDates = new TreeSet<>();

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    public ProgressStore(Path file) {
        this.file = file;
        load();
    }

    // ------------------------------------------------------------------ read

    public synchronized Set<String> clearedIds() {
        return new LinkedHashSet<>(cleared.keySet());
    }

    public synchronized String savedCode(String lessonId) {
        return codes.get(lessonId);
    }

    public synchronized int hintsRevealed(String lessonId) {
        return hintsRevealed.getOrDefault(lessonId, 0);
    }

    /** 今日を含む連続学習日数。今日も昨日も学習していなければ 0。 */
    public synchronized int streak() {
        if (clearDates.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate cursor;
        if (clearDates.contains(today.toString())) {
            cursor = today;
        } else if (clearDates.contains(today.minusDays(1).toString())) {
            // 今日まだ解いていなくても、昨日までの連続記録は生きている
            cursor = today.minusDays(1);
        } else {
            return 0;
        }
        int count = 0;
        while (clearDates.contains(cursor.toString())) {
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    public synchronized Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> clearedJson = new LinkedHashMap<>();
        cleared.forEach((id, c) -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("clearedAt", c.clearedAt());
            cm.put("hintsUsed", c.hintsUsed());
            cm.put("attempts", c.attempts());
            clearedJson.put(id, cm);
        });
        m.put("cleared", clearedJson);
        m.put("codes", new LinkedHashMap<>(codes));
        m.put("hintsRevealed", new LinkedHashMap<>(hintsRevealed));
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("clearDates", new ArrayList<>(clearDates));
        m.put("streak", streak());
        m.put("starCount", cleared.size());
        return m;
    }

    // ----------------------------------------------------------------- write

    public synchronized void saveCode(String lessonId, String code) {
        if (code == null) {
            return;
        }
        codes.put(lessonId, code);
        persist();
    }

    public synchronized int recordAttempt(String lessonId) {
        int n = attempts.merge(lessonId, 1, Integer::sum);
        persist();
        return n;
    }

    /**
     * クリアを記録する。
     *
     * @return 今回はじめてクリアしたなら true（★獲得の演出に使う）
     */
    public synchronized boolean markCleared(String lessonId) {
        boolean isNew = !cleared.containsKey(lessonId);
        String today = LocalDate.now().toString();
        if (isNew) {
            cleared.put(lessonId, new Cleared(
                    today,
                    hintsRevealed.getOrDefault(lessonId, 0),
                    attempts.getOrDefault(lessonId, 1)));
        }
        clearDates.add(today);
        persist();
        return isNew;
    }

    /** ヒントを1つ開示したことを記録し、開示済み総数を返す。 */
    public synchronized int revealHint(String lessonId, int index) {
        int current = hintsRevealed.getOrDefault(lessonId, 0);
        int next = Math.max(current, index + 1);
        hintsRevealed.put(lessonId, next);
        persist();
        return next;
    }

    /** 進捗を全て消す。 */
    public synchronized void resetAll() {
        cleared.clear();
        codes.clear();
        hintsRevealed.clear();
        attempts.clear();
        clearDates.clear();
        persist();
    }

    // ------------------------------------------------------------ 永続化本体

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            Map<String, Object> root = MiniJson.parseObject(text);

            MiniJson.obj(root, "cleared").forEach((id, v) -> {
                Map<String, Object> c = MiniJson.asObj(v);
                cleared.put(id, new Cleared(
                        MiniJson.str(c, "clearedAt", LocalDate.now().toString()),
                        MiniJson.intOf(c, "hintsUsed", 0),
                        MiniJson.intOf(c, "attempts", 1)));
            });
            MiniJson.obj(root, "codes").forEach((id, v) -> {
                if (v instanceof String s) {
                    codes.put(id, s);
                }
            });
            MiniJson.obj(root, "hintsRevealed").forEach((id, v) -> {
                if (v instanceof Number n) {
                    hintsRevealed.put(id, n.intValue());
                }
            });
            MiniJson.obj(root, "attempts").forEach((id, v) -> {
                if (v instanceof Number n) {
                    attempts.put(id, n.intValue());
                }
            });
            for (Object o : MiniJson.list(root, "clearDates")) {
                if (o instanceof String s && isDate(s)) {
                    clearDates.add(s);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("進捗ファイルを読めません: " + file, e);
        } catch (RuntimeException e) {
            // 壊れたファイルで起動できなくなるのは困るので、退避して作り直す
            System.err.println("進捗ファイルが壊れているようです (" + e.getMessage() + ")。"
                    + file.getFileName() + ".broken に退避して作り直します。");
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 退避に失敗しても、以降の persist() で上書きされる
            }
            cleared.clear();
            codes.clear();
            hintsRevealed.clear();
            attempts.clear();
            clearDates.clear();
        }
    }

    /** 一時ファイルへ書いてから置き換える（書き込み中に落ちても壊れないように）。 */
    private void persist() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MiniJson.write(toJsonRaw()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("進捗を保存できませんでした: " + e.getMessage());
        }
    }

    /** 保存用（streak / starCount のような派生値は保存しない）。 */
    private Object toJsonRaw() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> clearedJson = new LinkedHashMap<>();
        cleared.forEach((id, c) -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("clearedAt", c.clearedAt());
            cm.put("hintsUsed", c.hintsUsed());
            cm.put("attempts", c.attempts());
            clearedJson.put(id, cm);
        });
        m.put("cleared", clearedJson);
        m.put("codes", new LinkedHashMap<>(codes));
        m.put("hintsRevealed", new LinkedHashMap<>(hintsRevealed));
        m.put("attempts", new LinkedHashMap<>(attempts));
        m.put("clearDates", new ArrayList<>(clearDates));
        return m;
    }

    private static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
