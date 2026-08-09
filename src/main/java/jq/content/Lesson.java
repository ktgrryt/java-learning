package jq.content;

import jq.format.JavaSnippetFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * レッスン1つ（解説 + サンプル + 練習問題1問以上 + 確認クイズ）。
 *
 * 解説で教えた論点には、なるべく対応する練習問題を1つ置く方針なので、
 * 1レッスンに複数問入ることがある（{@link Task}）。クイズは任意で、0問でもよい。
 *
 * @param libSources このレッスンのコードと一緒にコンパイルする同梱ライブラリ（{@code libs} キー）。
 *                   Jakarta EE の章で使う。ブラウザへは渡さない（{@link #toPublicJson()} 参照）
 */
public record Lesson(
        String id,
        String chapterId,
        String title,
        String explanation,
        List<Sample> samples,
        List<Task> tasks,
        List<Quiz> quizzes,
        List<SourceFile> libSources) {

    /** 問題キー（進捗の保存単位）。 */
    public static String taskKey(String lessonId, String taskId) {
        return lessonId + "#" + taskId;
    }

    public Optional<Task> task(String taskId) {
        for (Task t : tasks) {
            if (t.id().equals(taskId)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** このレッスンの全問題のキー。 */
    public List<String> taskKeys() {
        List<String> keys = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            keys.add(taskKey(id, t.id()));
        }
        return keys;
    }

    /**
     * ブラウザへ渡す表現。問題ごとの中身は {@link Task#toPublicJson()} に任せる。
     *
     * {@code libSources} は意図的に含めない。画面が描くのに要らないうえ、同梱ライブラリの
     * ソース全文を載せると /api/state の応答が無駄に膨らむ（すでに解説とサンプルで数百KBある）。
     * コンパイルはサーバ側でやるので、ブラウザが中身を知る必要はない。
     */
    public Map<String, Object> toPublicJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("chapterId", chapterId);
        m.put("title", title);
        m.put("explanation", explanation);

        List<Object> sampleList = new ArrayList<>();
        for (Sample s : samples) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("caption", s.caption());
            sm.put("code", JavaSnippetFormatter.formatIfCompact(s.code()));
            sm.put("stdin", s.stdin());
            if (s.expected() != null) {
                sm.put("expected", s.expected());
            }
            sampleList.add(sm);
        }
        m.put("samples", sampleList);

        List<Object> taskList = new ArrayList<>();
        for (Task t : tasks) {
            taskList.add(t.toPublicJson());
        }
        m.put("tasks", taskList);
        m.put("taskCount", tasks.size());

        List<Object> quizList = new ArrayList<>();
        for (Quiz q : quizzes) {
            quizList.add(q.toPublicJson());
        }
        m.put("quizzes", quizList);
        return m;
    }
}
