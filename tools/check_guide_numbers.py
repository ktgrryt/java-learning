#!/usr/bin/env python3
"""
docs/guide.md に書いた集計を、content/ から数え直した値と突き合わせる。

check-guide-numbers.sh から呼ばれる。`--list` で全項目の実測値を出す。

数え方は `jq.content.Task` / `Lesson` の公開表現（`/api/state`）と同じにしてある。
サーバーは起動しない（content/*.json を直接読む）。
"""
import glob
import json
import os
import re
import sys

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"

GUIDE = "docs/guide.md"


def task_type(task):
    """Task#type と同じ判定。type が無ければ入れ物の有無から決める。"""
    if task.get("type"):
        return task["type"]
    if task.get("artifact"):
        return "artifact"
    if task.get("project"):
        return "project"
    if task.get("runtimeLab"):
        return "runtime-lab"
    return "single-file"


def is_optional(task):
    """Task#isOptional() と同じ。教材側は `"required": false` と書く（既定は必須）。"""
    return task.get("required") is False


def task_label(task):
    """Task#label() と同じ。任意発展は kind より優先する。"""
    if is_optional(task):
        return "任意発展"
    return {"drill": "ドリル", "applied": "応用"}.get(task.get("kind"), "練習問題")


def case_counts(task):
    """Task#toPublicJson の totalCaseCount / hiddenCaseCount と同じ数え方。"""
    cases = list(task.get("visibleCases") or []) + list(task.get("hiddenCases") or [])
    hidden = len(task.get("hiddenCases") or [])
    kind = task_type(task)
    if kind == "artifact":
        total = len((task.get("artifact") or {}).get("checks") or [])
    elif kind == "project":
        total = 1
    elif kind == "runtime-lab":
        total = len((task.get("runtimeLab") or {}).get("checks") or [])
    else:
        total = len(cases)
    return total, hidden


def measure():
    """content/ から、ガイドが書いている数を全部数える。"""
    m = {
        "chapters": 0, "lessons": 0, "required": 0, "optional": 0,
        "concept": 0, "preflight": 0, "quizzes": 0, "quiz_lessons": 0,
        "quiz_zero_lessons": 0, "quiz_one_lessons": 0, "quiz_multiple_lessons": 0,
        "objectives": 0, "source_checks": 0, "rubric_tasks": 0,
        "cases": 0, "hidden_cases": 0,
    }
    labels = {}
    types = {}
    rubric_types = {}
    quizzes_per_chapter = []
    multi_file_chapters = set()

    for path in sorted(glob.glob("content/ch*.json")):
        chapter = json.load(open(path, encoding="utf-8"))
        m["chapters"] += 1
        m["objectives"] += len(chapter.get("objectives") or [])
        chapter_quizzes = 0
        chapter_number = re.sub(r"^ch0*", "", chapter["id"])

        for lesson in chapter.get("lessons") or []:
            m["lessons"] += 1
            quizzes = len(lesson.get("quiz") or lesson.get("quizzes") or [])
            chapter_quizzes += quizzes
            m["quizzes"] += quizzes
            if quizzes:
                m["quiz_lessons"] += 1
            if quizzes == 0:
                m["quiz_zero_lessons"] += 1
            elif quizzes == 1:
                m["quiz_one_lessons"] += 1
            else:
                m["quiz_multiple_lessons"] += 1
            if lesson.get("lessonType") == "concept":
                m["concept"] += 1
            if lesson.get("lessonType") == "preflight" or lesson.get("preflight"):
                m["preflight"] += 1

            tasks = ([lesson] if lesson.get("task") is not None else []) \
                + list(lesson.get("extraTasks") or [])
            for task in tasks:
                if is_optional(task):
                    m["optional"] += 1
                else:
                    m["required"] += 1
                labels[task_label(task)] = labels.get(task_label(task), 0) + 1
                kind = task_type(task)
                types[kind] = types.get(kind, 0) + 1
                if kind != "single-file":
                    multi_file_chapters.add(int(chapter_number))
                total, hidden = case_counts(task)
                m["cases"] += total
                m["hidden_cases"] += hidden
                checks = task.get("sourceChecks") or []
                m["source_checks"] += len(checks)
                if task.get("rubric"):
                    m["rubric_tasks"] += 1
                    rubric_types[kind] = rubric_types.get(kind, 0) + 1

        quizzes_per_chapter.append(chapter_quizzes)

    m["labels"] = labels
    m["types"] = types
    m["rubric_types"] = rubric_types
    m["stars"] = m["required"] + m["concept"]     # 概念レッスンはクイズ全問正解で★1つ
    m["quiz_min"] = min(q for q in quizzes_per_chapter if q)
    m["quiz_max"] = max(quizzes_per_chapter)
    m["multi_file_chapters"] = " ".join(str(n) for n in sorted(multi_file_chapters))
    return m


def checks(m):
    """(説明, 正規表現, 期待値の並び) の一覧。捕獲した数を順に突き合わせる。

    正規表現は「いま書いてある文」に合わせて具体的にしてある。過去の実測を残した表
    （`| 574問 | 57,000 | …`）を巻き込まないためで、文を書き換えたら
    ここも直す（合致しなくなったら失敗として知らせる）。
    """
    t = m["types"]
    r = m["rubric_types"]
    return [
        ("カリキュラムの見出し",
         r"## カリキュラム（全(\d+)章 / (\d+)レッスン / 必須(\d+)問 \+ 任意発展(\d+)問）",
         [m["chapters"], m["lessons"], m["required"], m["optional"]]),
        ("★は必須問題ごと",
         r"★ は必須問題ごと（全(\d+)問）に付きます",
         [m["required"]]),
        ("★の総数の内訳",
         r"概念レッスン\*\*（(\d+)レッスン）",
         [m["concept"]]),
        ("★の総数の計算",
         r"★の総数は (\d+) \+ (\d+) = (\d+) です",
         [m["required"], m["concept"], m["stars"]]),
        ("完走時の★",
         r"全(\d+)★を取り終えると",
         [m["stars"]]),
        ("確認クイズの総数と章あたりの幅",
         r"\*\*4択の確認クイズ\*\* は全(\d+)問で、\*\*章あたり(\d+)〜(\d+)問\*\*",
         [m["quizzes"], m["quiz_min"], m["quiz_max"]]),
        ("クイズを置いたレッスン数",
         r"(\d+)レッスンのうち\*\*(\d+)レッスン\*\*に1問以上置いてあり"
         r"（1問だけ(\d+)レッスン、複数問(\d+)レッスン）、残り(\d+)レッスンには0問",
         [m["lessons"], m["quiz_lessons"], m["quiz_one_lessons"],
          m["quiz_multiple_lessons"], m["quiz_zero_lessons"]]),
        ("事前確認と概念レッスンの数",
         r"このうち(\d+)レッスンは★対象外の環境事前確認で、(\d+)レッスンはクイズだけで",
         [m["preflight"], m["concept"]]),
        ("到達目標の総数",
         r"到達目標\*\*（`objectives`）を2〜5個、全(\d+)章で(\d+)個",
         [m["chapters"], m["objectives"]]),
        ("目標と紐づく問題・クイズ",
         r"- (\d+)問と(\d+)クイズが必ずどれかの目標へ解決される",
         [m["required"], m["quizzes"]]),
        ("rubricを書いた問題数と内訳",
         r"現在は必須(\d+)問のうち\*\*(\d+)問\*\*へ書いてあります（`single-file` (\d+)・"
         r"`runtime-lab` (\d+)・`artifact` (\d+)・\n`project` (\d+)）",
         [m["required"], m["rubric_tasks"], r.get("single-file", 0),
          r.get("runtime-lab", 0), r.get("artifact", 0), r.get("project", 0)]),
        ("章末演習を置いた章数",
         r"\*\*章末演習は全(\d+)章に、通常レッスンとは別に収録しています\*\*",
         [m["chapters"]]),
        ("テストケースと隠しテスト・sourceChecks",
         r"実テストは全(\d+)件で、そのうち \*\*(\d+)件が隠しテスト\*\*です"
         r"（`sourceChecks` の(\d+)件",
         [m["cases"], m["hidden_cases"], m["source_checks"]]),
        ("確認クイズの総数（カリキュラムの節）",
         r"加えて、各章に \*\*4択の確認クイズ\*\* が入っています（全(\d+)問）",
         [m["quizzes"]]),
        ("verify-solutionsが確かめる問題数",
         r"必須(\d+)問と任意発展(\d+)問について次を確認します",
         [m["required"], m["optional"]]),
        ("verify-solutionsの集計例",
         r"最後に「レッスン(\d+)件 / 問題(\d+)問（練習問題(\d+)問 / ドリル(\d+)問 / 応用(\d+)問）"
         r" \+ 任意発展(\d+)問 /\nテストケース(\d+)件（うち隠し(\d+)件） / 確認クイズ(\d+)問",
         [m["lessons"], m["required"], m["labels"].get("練習問題", 0),
          m["labels"].get("ドリル", 0), m["labels"].get("応用", 0), m["optional"],
          m["cases"], m["hidden_cases"], m["quizzes"]]),
        ("全件実行の件数（所要の説明）",
         r"(\d+)件のテストケース・構成検査・project/runtime実テストを実行するので",
         [m["cases"]]),
        ("全件実行の件数（範囲の説明）",
         r"全件実行は(\d+)件のテストケースとproject/runtimeの実テストを回すので",
         [m["cases"]]),
        ("single-file問題数と、非single-fileを含む章",
         r"非single-file問題を持つ章（([0-9 ]+)）\s*\|\s*(\d+)問の `single-file`",
         [m["multi_file_chapters"], t.get("single-file", 0)]),
    ]


def main():
    if not os.path.exists(GUIDE):
        print(f"{RED}{GUIDE} がありません。プロジェクトのルートから実行してください。{RESET}")
        return 1
    guide = open(GUIDE, encoding="utf-8").read()
    m = measure()

    if "--list" in sys.argv:
        print("content/ から数え直した値:")
        for key in ("chapters", "lessons", "required", "optional", "stars", "concept",
                    "preflight", "quizzes", "quiz_lessons", "quiz_zero_lessons",
                    "quiz_one_lessons", "quiz_multiple_lessons", "quiz_min", "quiz_max",
                    "objectives", "source_checks", "rubric_tasks", "cases", "hidden_cases"):
            print(f"  {key:14} {m[key]}")
        print(f"  labels         {m['labels']}")
        print(f"  types          {m['types']}")
        print(f"  rubric_types   {m['rubric_types']}")
        print(f"  非single-fileの章 {m['multi_file_chapters']}")
        return 0

    failures = []
    checked = 0
    for label, pattern, expected in checks(m):
        found = re.findall(pattern, guide)
        if not found:
            failures.append(
                f"{label}: 該当する記述が見つかりません（文を書き換えたなら "
                f"tools/check_guide_numbers.py の正規表現も直してください）")
            continue
        if len(found) > 1:
            failures.append(f"{label}: 同じ記述が{len(found)}箇所あります（どれが正か決められません）")
            continue
        actual = found[0] if isinstance(found[0], tuple) else (found[0],)
        for got, want in zip(actual, expected):
            checked += 1
            if str(got).strip() != str(want).strip():
                failures.append(f"{label}: ガイドは「{got}」だが実際は「{want}」")

    print(f"docs/guide.md の数字を{checked}件、content/ と突き合わせました。")
    if failures:
        print(f"{RED}食い違い {len(failures)}件{RESET}")
        for f in failures:
            print(f"  {RED}· {f}{RESET}")
        print(f"\n{DIM}実測値の一覧は --list で出せます。{RESET}")
        return 1
    print(f"  {GREEN}ガイドの集計は教材と一致しています。{RESET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
