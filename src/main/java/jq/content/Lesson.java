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
 * 例外が2つある。**事前確認レッスン**は環境を確かめるだけで問題を持たず、★の対象にもしない。
 * **概念レッスン**は工程名や成果物の対応のように、提出物にすると測る対象がずれてしまう論点を
 * 解説とクイズだけで扱う（提出課題を無理に付けると、V字モデルの理解ではなく {@code Map} の
 * 操作を測ることになる）。概念レッスンはクイズ全問正解で★1つが付き、章クリアの条件に入る。
 *
 * @param libSources このレッスンのコードと一緒にコンパイルする同梱ライブラリ（{@code libs} キー）。
 *                   Jakarta EE の章で使う。ブラウザへは渡さない（{@link #toPublicJson()} 参照）
 * @param preflight  外部ツールの事前確認。通常レッスンではnull
 * @param concept    概念レッスン（解説とクイズだけ）かどうか
 */
public record Lesson(
        String id,
        String chapterId,
        String title,
        String explanation,
        List<Sample> samples,
        List<Task> tasks,
        List<Quiz> quizzes,
        List<SourceFile> libSources,
        PreflightSpec preflight,
        boolean concept,
        List<String> objectiveIds) {

    /**
     * 概念レッスンの★を保存するときの問題ID。
     *
     * 問題IDは並び順の連番（{@code "1"} {@code "2"} …）なので、数字でない値を選べば
     * 既存の進捗キーとぶつからない。{@code progress.json} には {@code 70-1#q} の形で入る。
     */
    public static final String CONCEPT_TASK_ID = "q";

    public boolean isPreflight() {
        return preflight != null;
    }

    /** 概念レッスンの★のキー。概念レッスン以外ではnull。 */
    public String conceptKey() {
        return concept ? taskKey(id, CONCEPT_TASK_ID) : null;
    }

    /** 進捗キーが概念レッスンの★かどうか。{@link jq.progress.ProgressStore} が復習の対象から外す。 */
    public static boolean isConceptKey(String taskKey) {
        return taskKey != null && taskKey.endsWith("#" + CONCEPT_TASK_ID);
    }

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

    /**
     * このレッスンで章クリアに必要な問題のキー。任意発展問題は含めない。
     *
     * 概念レッスンは問題を持たないので、クイズ全問正解で付く★のキーを1つだけ返す。
     * 章クリアも★の総数もここを数えているので、この1行で両方に入る。
     */
    public List<String> taskKeys() {
        if (concept) {
            return List.of(taskKey(id, CONCEPT_TASK_ID));
        }
        List<String> keys = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            if (t.required()) keys.add(taskKey(id, t.id()));
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
        m.put("type", isPreflight() ? "preflight" : concept ? "concept" : "lesson");
        m.put("objectiveIds", objectiveIds);
        if (isPreflight()) m.put("preflight", preflight.toPublicJson());

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
        m.put("taskCount", tasks.stream().filter(Task::required).count());
        m.put("optionalTaskCount", tasks.stream().filter(Task::isOptional).count());

        List<Object> quizList = new ArrayList<>();
        for (Quiz q : quizzes) {
            quizList.add(q.toPublicJson());
        }
        m.put("quizzes", quizList);
        return m;
    }
}
