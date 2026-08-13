#!/usr/bin/env python3
"""
全問題の模範解答を提出して、全テストケースを通ることを確かめる。

verify-solutions.sh から呼ばれる（直接実行する場合は第1引数にポート番号を渡す）。

1レッスンに練習問題が複数あるので、ひな形・模範解答・ヒントは問題ごとに検査する。
サンプルコードと確認クイズはレッスン単位。

チェックしているのは次の5点。
  1. content/*.json が読み込めること
  2. single-fileのひな形、artifact、project、runtime-labを実行できること
  3. 各問題の solution が全テストケース・構成検査・project/runtime実テストを通ること
  4. 各サンプルコードが実行でき、expected があれば出力も一致すること
  5. 確認クイズに正解がちょうど1つあり、解説が書かれていること
"""
import json
import sys
import urllib.error
import urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "8123"
BASE = f"http://localhost:{PORT}/api/"

# 第2引数以降にレッスンIDの先頭を並べると、その章・レッスンだけを検査する。
# 章を1つ書いている間、1122件すべてを走らせ直さなくて済むようにするための指定。
# 例:  tools/verify-solutions.sh --only 21      （第21章だけ）
#      tools/verify-solutions.sh --only 21-3    （そのレッスンだけ）
ONLY = [p for p in sys.argv[2:] if p]

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def normalize_output(text):
    """Judge.normalize と同じ規則で、行末空白と末尾空行を無視する。"""
    lines = str(text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    lines = [line.rstrip() for line in lines]
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


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

    if task.get("type") in ("project", "runtime-lab"):
        workspace_key = "runtimeLab" if task.get("type") == "runtime-lab" else "project"
        starter_files = {
            f["path"]: f["content"] for f in task[workspace_key]["files"] if f["editable"]
        }
        starter = post("submit", {
            "lessonId": lid, "taskId": tid,
            "files": starter_files, "review": True
        })
        if task.get("type") == "runtime-lab" and not starter.get("available", True):
            warnings.append(f"{lid}#{tid}: runtime環境なしのため実行を省略: {starter.get('error', '')}")
        elif not starter.get("started"):
            problems.append(f"{where}のproject検証を開始できない: {starter.get('error')}")
    elif task.get("type") == "artifact":
        starter = post("submit", {
            "lessonId": lid, "taskId": tid,
            "code": task["starterCode"], "review": True
        })
        if not starter.get("syntaxValid"):
            problems.append(f"{where}のひな形を{task['artifact']['format']}として読めない")
            print(f"      {RED}{starter.get('syntaxError', '')}{RESET}")
    else:
        # libLessonId は同梱ライブラリを引き当てるためだけのもの（保存はされない）
        starter = post("run", {"code": task["starterCode"], "libLessonId": lid})
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
    expected_key = "files" if task.get("type") in ("project", "runtime-lab") else "solution"
    if expected_key not in got:
        problems.append(f"{where}の模範解答を取得できない: {got.get('error')}")
        return 0, 0

    payload = {"lessonId": lid, "taskId": tid}
    if task.get("type") in ("project", "runtime-lab"):
        payload["files"] = got["files"]
    else:
        payload["code"] = got["solution"]
    res = post("submit", payload)
    if task.get("type") in ("project", "runtime-lab"):
        if task.get("type") == "runtime-lab" and not res.get("available", True):
            return 0, task.get("totalCaseCount", 0)
        if not res.get("started"):
            problems.append(f"{where}の模範解答でproject検証を開始できない: {res.get('error')}")
            return 0, 1
        if not res.get("allPass"):
            problems.append(f"{where}の模範解答でprojectテストが失敗した")
            output = res.get("output", "").splitlines()
            for line in output[-10:]:
                print(f"      {RED}{line}{RESET}")
            return 0, 1
        if task.get("type") == "runtime-lab":
            return res.get("passedCount", 0), len(res.get("checks", []))
        return 1, 1

    if task.get("type") == "artifact":
        total = len(res.get("checks", []))
        passed = res.get("passedCount", 0)
        if not res.get("syntaxValid"):
            problems.append(f"{where}の模範解答を{task['artifact']['format']}として読めない")
            print(f"      {RED}{res.get('syntaxError', '')}{RESET}")
            return 0, total
        if not res.get("allPass"):
            problems.append(f"{where}の模範解答が {total - passed}件の構成検査で落ちた")
            for check in res.get("checks", []):
                if not check["pass"]:
                    print(f"      {RED}✗ {check['message']}{RESET}")
        return passed, total

    if not res.get("compiled"):
        problems.append(f"{where}の模範解答がコンパイルできない")
        show_diagnostics(res.get("diagnostics", []))
        return 0, 0

    total = len(res["cases"])
    passed = res.get("passedCount", 0)
    if not res.get("allPass"):
        output_failures = total - passed
        source_failures = res.get("sourceFailures", [])
        if output_failures:
            problems.append(f"{where}の模範解答が {output_failures}件のケースで落ちた")
        if source_failures:
            problems.append(f"{where}の模範解答が指定された書き方を満たしていない")
        for failure in source_failures:
            print(f"      {RED}✗ 書き方: {failure}{RESET}")
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


def wanted(lesson_id):
    """--only が指定されていれば、その先頭に一致するレッスンだけ検査する。"""
    return not ONLY or any(lesson_id.startswith(p) for p in ONLY)


def main():
    state = get("state")
    chapters = [ch for ch in state["chapters"]
                if any(wanted(l["id"]) for l in ch["lessons"])]
    lessons = [(ch, l) for ch in chapters for l in ch["lessons"] if wanted(l["id"])]
    tasks = [t for _, l in lessons for t in l["tasks"]]
    required_tasks = [t for t in tasks if t.get("required", True)]
    optional_tasks = [t for t in tasks if not t.get("required", True)]
    scope = f"（{' '.join(ONLY)} に絞って検査）" if ONLY else ""
    optional_note = f" + 任意発展 {len(optional_tasks)}件" if optional_tasks else ""
    print(f"問題 {len(required_tasks)}件{optional_note} / レッスン {len(lessons)}件 / 章 {len(chapters)}件 "
          f"を検査します{scope}\n")
    if not lessons:
        print(f"{RED}指定に一致するレッスンがありません: {' '.join(ONLY)}{RESET}")
        return 1

    failures = []
    warnings = []

    for chapter in chapters:
        print(f"{chapter['emoji']} 第{chapter['number']}章 {chapter['title']}")

        for lesson in chapter["lessons"]:
            lid = lesson["id"]
            if not wanted(lid):
                continue
            problems = []

            # 事前確認は実行結果が端末ごとに違うため、ready自体は合否にしない。
            # 定義した全項目が安全な専用APIで実測され、結果が返ることだけを確認する。
            if lesson.get("type") == "preflight":
                preflight = post("preflight", {"lessonId": lid})
                expected_checks = lesson.get("preflight", {}).get("checks", [])
                if not preflight.get("preflight"):
                    problems.append(f"事前確認APIを実行できない: {preflight.get('error')}")
                elif len(preflight.get("checks", [])) != len(expected_checks):
                    problems.append("事前確認の定義数と実行結果数が一致しない")
                elif any("pass" not in check or "required" not in check
                         for check in preflight.get("checks", [])):
                    problems.append("事前確認の実行結果に必須項目が無い")

            # ── サンプルコードが実行できるか ──────────────────────────
            for i, sample in enumerate(lesson["samples"]):
                res = post("run", {"code": sample["code"], "stdin": sample.get("stdin", ""),
                                   "libLessonId": lid})
                if not res.get("compiled"):
                    problems.append(f"サンプル{i + 1}がコンパイルできない")
                    show_diagnostics(res.get("diagnostics", []))
                elif res["run"]["timedOut"]:
                    problems.append(f"サンプル{i + 1}がタイムアウト")
                elif res["run"]["stderr"]:
                    problems.append(f"サンプル{i + 1}で実行時エラー")
                    print(f"      {RED}{res['run']['stderr'].splitlines()[0]}{RESET}")
                elif "expected" in sample:
                    actual = res["run"].get("stdout", "")
                    if normalize_output(actual) != normalize_output(sample["expected"]):
                        problems.append(f"サンプル{i + 1}の出力が期待値と違う")
                        print(f"      {RED}期待: {sample['expected']!r}{RESET}")
                        print(f"      {RED}実際: {actual!r}{RESET}")

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
            is_preflight = lesson.get("type") == "preflight"
            cases = (f"事前確認{len(lesson['preflight']['checks'])}項目"
                     if is_preflight else (f"{passed}/{total}ケース" if total else "ケース無し"))
            hidden_n = sum(t["hiddenCaseCount"] for t in lesson["tasks"])
            hidden = f" (隠し{hidden_n})" if hidden_n else ""
            required_n = sum(1 for t in lesson["tasks"] if t.get("required", True))
            optional_n = len(lesson["tasks"]) - required_n
            optional_note = f" + 任意{optional_n}問" if optional_n else ""
            task_note = " / ★対象外" if is_preflight else f" / {required_n}問{optional_note}"
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
    for t in required_tasks:
        kinds[t["label"]] = kinds.get(t["label"], 0) + 1
    breakdown = " / ".join(f"{k}{v}問" for k, v in kinds.items())
    optional_summary = f" + 任意発展{len(optional_tasks)}問" if optional_tasks else ""
    problem_summary = (f"問題{len(required_tasks)}問（{breakdown}）{optional_summary}" if breakdown
                       else "問題0問（事前確認のみ）")
    print(f"{GREEN}すべて合格{RESET}  "
          f"レッスン{len(lessons)}件 / {problem_summary}"
          f" / テストケース{case_total}件（うち隠し{hidden_total}件）"
          f" / 確認クイズ{quiz_total}問")
    return 0


if __name__ == "__main__":
    sys.exit(main())
