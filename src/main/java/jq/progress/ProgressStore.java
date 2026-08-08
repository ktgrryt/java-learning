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
 *  - クリア済みの問題（クリア日、使ったヒント数、提出回数）
 *  - 問題ごとに最後に書いたコード（再訪時に復元する）
 *  - 確認クイズで選んだ選択肢（正解かどうかは保存せず、出題側と突き合わせて毎回求める）
 *  - 何か1問クリアした日付の集合（連続学習日数の計算に使う）
 *
 * 1レッスンに練習問題が複数あるので、★もコードもヒントも **問題ごと** に持つ。
 * キーは {@code レッスンID#連番}（{@link jq.content.Lesson#taskKey}）。
 * クイズだけはレッスン単位なので {@code レッスンID#クイズ番号} を別のマップに持つ。
 *
 * サーバは複数リクエストを並行に処理するので、状態変更は全て synchronized で守る。
 */
public final class ProgressStore {

    private final Path file;

    /** 問題キー -> クリア情報 */
    private final Map<String, Cleared> cleared = new LinkedHashMap<>();
    /** 問題キー -> 最後に書いたコード */
    private final Map<String, String> codes = new LinkedHashMap<>();
    /** 問題キー -> 開示済みヒント数 */
    private final Map<String, Integer> hintsRevealed = new LinkedHashMap<>();
    /** 問題キー -> 提出回数 */
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    /** 問題キー -> これまでで最も多く通ったケース数（画面の「通過したテストケース」に使う） */
    private final Map<String, Integer> bestPassed = new LinkedHashMap<>();
    /** "レッスンID#クイズ番号" -> 選んだ選択肢の番号 */
    private final Map<String, Integer> quizChoices = new LinkedHashMap<>();
    /** 何かをクリアした日付 */
    private final Set<String> clearDates = new TreeSet<>();

    public record Cleared(String clearedAt, int hintsUsed, int attempts) {
    }

    public ProgressStore(Path file) {
        this.file = file;
        load();
    }

    // ------------------------------------------------------------------ read

    /** クリア済みの問題キー。 */
    public synchronized Set<String> clearedIds() {
        return new LinkedHashSet<>(cleared.keySet());
    }

    public synchronized String savedCode(String taskKey) {
        return codes.get(taskKey);
    }

    public synchronized int hintsRevealed(String taskKey) {
        return hintsRevealed.getOrDefault(taskKey, 0);
    }

    /** そのクイズに選んだ選択肢の番号。まだ答えていなければ null。 */
    public synchronized Integer quizChoice(String lessonId, int index) {
        return quizChoices.get(quizKey(lessonId, index));
    }

    /** その問題でこれまでに最も多く通ったケース数。一度も提出していなければ 0。 */
    public synchronized int bestPassed(String taskKey) {
        return bestPassed.getOrDefault(taskKey, 0);
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

    /**
     * ブラウザへ渡す進捗。
     *
     * 画面が実際に使うのは★の数・連続日数・提出回数だけなので、それだけを渡す。
     * 書きかけのコードは各問題の savedCode として別に載るため、ここで
     * codes を丸ごと足すと同じものを二重に送ることになる（問題が増えるほど重くなる）。
     */
    public synchronized Object toClientJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("starCount", cleared.size());
        m.put("streak", streak());
        m.put("attempts", new LinkedHashMap<>(attempts));
        return m;
    }

    // ----------------------------------------------------------------- write

    public synchronized void saveCode(String taskKey, String code) {
        if (code == null) {
            return;
        }
        codes.put(taskKey, code);
        persist();
    }

    public synchronized int recordAttempt(String taskKey) {
        int n = attempts.merge(taskKey, 1, Integer::sum);
        persist();
        return n;
    }

    /**
     * 採点結果のうち「通ったケース数」を記録する。
     *
     * これまでの最高記録だけを残す。あと一歩まで来ていた人の記録が、
     * その後の失敗した提出で下がってしまわないようにするため。
     */
    public synchronized void recordPassed(String taskKey, int passed) {
        bestPassed.merge(taskKey, passed, Math::max);
        persist();
    }

    /**
     * クリアを記録する。
     *
     * @return 今回はじめてクリアしたなら true（★獲得の演出に使う）
     */
    public synchronized boolean markCleared(String taskKey) {
        boolean isNew = !cleared.containsKey(taskKey);
        String today = LocalDate.now().toString();
        if (isNew) {
            cleared.put(taskKey, new Cleared(
                    today,
                    hintsRevealed.getOrDefault(taskKey, 0),
                    attempts.getOrDefault(taskKey, 1)));
        }
        clearDates.add(today);
        persist();
        return isNew;
    }

    /** ヒントを1つ開示したことを記録し、開示済み総数を返す。 */
    public synchronized int revealHint(String taskKey, int index) {
        int current = hintsRevealed.getOrDefault(taskKey, 0);
        int next = Math.max(current, index + 1);
        hintsRevealed.put(taskKey, next);
        persist();
        return next;
    }

    /** クイズの回答を記録する（答え直したら上書きする）。 */
    public synchronized void recordQuiz(String lessonId, int index, int choice) {
        quizChoices.put(quizKey(lessonId, index), choice);
        persist();
    }

    /** 進捗を全て消す。 */
    public synchronized void resetAll() {
        cleared.clear();
        codes.clear();
        hintsRevealed.clear();
        attempts.clear();
        bestPassed.clear();
        quizChoices.clear();
        clearDates.clear();
        persist();
    }

    private static String quizKey(String lessonId, int index) {
        return lessonId + "#" + index;
    }

    /**
     * 昔の進捗ファイルのキーを問題キーに読み替える。
     *
     * 1レッスン1問だった頃はレッスンIDそのものがキーだった（"5-2"）。
     * いまは問題ごとに "5-2#1" を使うので、"#" を含まない古いキーを1問目として扱う。
     * こうしないと、これまでの★が全部消えたように見えてしまう。
     */
    private static String migrateKey(String key) {
        return key.contains("#") ? key : key + "#1";
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
                cleared.put(migrateKey(id), new Cleared(
                        MiniJson.str(c, "clearedAt", LocalDate.now().toString()),
                        MiniJson.intOf(c, "hintsUsed", 0),
                        MiniJson.intOf(c, "attempts", 1)));
            });
            MiniJson.obj(root, "codes").forEach((id, v) -> {
                if (v instanceof String s) {
                    codes.put(migrateKey(id), s);
                }
            });
            MiniJson.obj(root, "hintsRevealed").forEach((id, v) -> {
                if (v instanceof Number n) {
                    hintsRevealed.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "attempts").forEach((id, v) -> {
                if (v instanceof Number n) {
                    attempts.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "bestPassed").forEach((id, v) -> {
                if (v instanceof Number n) {
                    bestPassed.put(migrateKey(id), n.intValue());
                }
            });
            MiniJson.obj(root, "quizChoices").forEach((key, v) -> {
                if (v instanceof Number n) {
                    quizChoices.put(key, n.intValue());
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
            bestPassed.clear();
            quizChoices.clear();
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
        m.put("bestPassed", new LinkedHashMap<>(bestPassed));
        m.put("quizChoices", new LinkedHashMap<>(quizChoices));
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
