#!/usr/bin/env python3
"""
全問題の模範解答を提出して、全テストケースを通ることを確かめる。

verify-solutions.sh から呼ばれる（直接実行する場合は第1引数にポート番号を渡す）。

1レッスンに練習問題が複数あるので、ひな形・模範解答・ヒントは問題ごとに検査する。
サンプルコードと確認クイズはレッスン単位。

チェックしているのは次の5点。
  1. content/*.json が読み込めること
  2. 各問題の starterCode がコンパイルできること（ひな形が壊れていないか）
  3. 各問題の solution が全テストケースを通ること
  4. 各サンプルコードが実行できること（解説に載せたコードが動かないと最悪なので）
  5. 確認クイズに正解がちょうど1つあり、解説が書かれていること
"""
import json
import sys
import urllib.error
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "8123"
BASE = f"http://localhost:{PORT}/api/"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def post(endpoint, payload):
    req = urllib.request.Request(
        BASE + endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            return json.load(res)
    except urllib.error.HTTPError as e:
        return json.load(e)


def get(endpoint):
    with urllib.request.urlopen(BASE + endpoint, timeout=60) as res:
        return json.load(res)


def show_diagnostics(diagnostics, indent="      "):
    for d in diagnostics:
        where = f"{d['line']}行目: " if d.get("line") else ""
        print(f"{indent}{RED}{where}{d['message'].splitlines()[0]}{RESET}")
        if d.get("hint"):
            print(f"{indent}{DIM}→ {d['hint']}{RESET}")


def verify_task(lid, task, problems, warnings):
    """問題1問を検査して (通ったケース数, 全ケース数) を返す。"""
    tid = task["id"]
    where = f"問題{tid}"

    # ── ひな形がコンパイルできるか ────────────────────────────────
    starter = post("run", {"code": task["starterCode"]})
    if not starter.get("compiled"):
        problems.append(f"{where}のひな形がコンパイルできない")
        show_diagnostics(starter.get("diagnostics", []))

    if not task["task"].strip():
        problems.append(f"{where}の問題文が空")

    # ── 模範解答が全ケースを通るか ────────────────────────────────
    if not task["hasSolution"]:
        warnings.append(f"{lid}#{tid}: 模範解答が無い")
        return 0, 0
    if task["hintCount"] == 0:
        warnings.append(f"{lid}#{tid}: ヒントが無い")

    # 模範解答は hint を全部開けないと取得できないので、先に開ける
    for i in range(task["hintCount"]):
        post("hint", {"lessonId": lid, "taskId": tid, "index": i})
    got = post("solution", {"lessonId": lid, "taskId": tid})
    if "solution" not in got:
        problems.append(f"{where}の模範解答を取得できない: {got.get('error')}")
        return 0, 0

    res = post("submit", {"lessonId": lid, "taskId": tid, "code": got["solution"]})
    if not res.get("compiled"):
        problems.append(f"{where}の模範解答がコンパイルできない")
        show_diagnostics(res.get("diagnostics", []))
        return 0, 0

    total = len(res["cases"])
    passed = res.get("passedCount", 0)
    if not res.get("allPass"):
        problems.append(f"{where}の模範解答が {total - passed}件のケースで落ちた")
        for c in res["cases"]:
            if c["pass"]:
                continue
            tag = "隠し" if c["hidden"] else "表示"
            print(f"      {RED}✗ [{tag}] {c['label']}{RESET}")
            print(f"        入力    : {c['stdin']!r}")
            print(f"        期待    : {c['expected']!r}")
            print(f"        実際    : {c['actual']!r}")
            if c["stderr"]:
                print(f"        エラー  : {c['stderr'].splitlines()[0]}")
    return passed, total


def main():
    state = get("state")
    lessons = [(ch, l) for ch in state["chapters"] for l in ch["lessons"]]
    tasks = [t for _, l in lessons for t in l["tasks"]]
    print(f"問題 {len(tasks)}件 / レッスン {len(lessons)}件 / 章 {len(state['chapters'])}件 "
          f"を検査します\n")

    failures = []
    warnings = []

    for chapter in state["chapters"]:
        print(f"{chapter['emoji']} 第{chapter['number']}章 {chapter['title']}")

        for lesson in chapter["lessons"]:
            lid = lesson["id"]
            problems = []

            # ── サンプルコードが実行できるか ──────────────────────────
            for i, sample in enumerate(lesson["samples"]):
                res = post("run", {"code": sample["code"], "stdin": sample.get("stdin", "")})
                if not res.get("compiled"):
                    problems.append(f"サンプル{i + 1}がコンパイルできない")
                    show_diagnostics(res.get("diagnostics", []))
                elif res["run"]["timedOut"]:
                    problems.append(f"サンプル{i + 1}がタイムアウト")
                elif res["run"]["stderr"]:
                    problems.append(f"サンプル{i + 1}で実行時エラー")
                    print(f"      {RED}{res['run']['stderr'].splitlines()[0]}{RESET}")

            # ── 問題ごとに、ひな形・模範解答・ヒントを検査 ────────────
            passed = total = 0
            for task in lesson["tasks"]:
                p, t = verify_task(lid, task, problems, warnings)
                passed += p
                total += t

            # ── 確認クイズ ────────────────────────────────────────────
            # 正解の番号はブラウザへ渡されないので、全選択肢を投げて
            # 「ちょうど1つだけ correct になるか」を確かめる。
            for qi, quiz in enumerate(lesson.get("quizzes", [])):
                choices = quiz["choices"]
                if not quiz["question"].strip():
                    problems.append(f"クイズ{qi + 1}の question が空")
                if len(choices) < 2:
                    problems.append(f"クイズ{qi + 1}の選択肢が少ない")
                if len(set(choices)) != len(choices):
                    problems.append(f"クイズ{qi + 1}に同じ選択肢がある")
                if any(not c.strip() for c in choices):
                    problems.append(f"クイズ{qi + 1}に空の選択肢がある")

                corrects = []
                explanation = ""
                for ci in range(len(choices)):
                    res = post("quiz", {"lessonId": lid, "index": qi, "choice": ci})
                    if "correct" not in res:
                        problems.append(f"クイズ{qi + 1}に答えられない: {res.get('error')}")
                        break
                    if res["correct"]:
                        corrects.append(ci)
                    explanation = res.get("explanation", "")
                else:
                    if len(corrects) != 1:
                        problems.append(f"クイズ{qi + 1}の正解が{len(corrects)}個ある")
                    if not explanation.strip():
                        problems.append(f"クイズ{qi + 1}に解説が無い")

            mark = f"{GREEN}✅{RESET}" if not problems else f"{RED}❌{RESET}"
            cases = f"{passed}/{total}ケース" if total else "ケース無し"
            hidden_n = sum(t["hiddenCaseCount"] for t in lesson["tasks"])
            hidden = f" (隠し{hidden_n})" if hidden_n else ""
            task_note = f" / {len(lesson['tasks'])}問"
            quiz_n = len(lesson.get("quizzes", []))
            quiz_note = f" / クイズ{quiz_n}問" if quiz_n else ""
            print(f"   {mark} {lid:4} {lesson['title'][:26]:28} "
                  f"{task_note} {cases}{hidden}{quiz_note}")
            for p in problems:
                print(f"      {RED}· {p}{RESET}")
                failures.append(f"{lid}: {p}")
        print()

    # ── まとめ ────────────────────────────────────────────────────────
    print("─" * 64)
    if warnings:
        print(f"{YELLOW}注意 {len(warnings)}件{RESET}")
        for w in warnings:
            print(f"  · {w}")
    if failures:
        print(f"{RED}失敗 {len(failures)}件{RESET}")
        for f in failures:
            print(f"  · {f}")
        print(f"\n{RED}検査に失敗しました{RESET}")
        return 1

    hidden_total = sum(t["hiddenCaseCount"] for t in tasks)
    case_total = sum(t["totalCaseCount"] for t in tasks)
    quiz_total = sum(len(l.get("quizzes", [])) for _, l in lessons)
    kinds = {}
    for t in tasks:
        kinds[t["label"]] = kinds.get(t["label"], 0) + 1
    breakdown = " / ".join(f"{k}{v}問" for k, v in kinds.items())
    print(f"{GREEN}すべて合格{RESET}  "
          f"レッスン{len(lessons)}件 / 問題{len(tasks)}問（{breakdown}）"
          f" / テストケース{case_total}件（うち隠し{hidden_total}件）"
          f" / 確認クイズ{quiz_total}問")
    return 0


if __name__ == "__main__":
    sys.exit(main())
