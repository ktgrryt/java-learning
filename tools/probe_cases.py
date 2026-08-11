#!/usr/bin/env python3
"""
候補の入力を模範解答へ流し、実際の出力を見せる（テストケースを足すための下調べ）。

新しいテストケースを書くとき、期待する出力を手計算すると間違える。
かわりに「入力だけを自分で決めて、出力は模範解答に出させる」ための道具。
出てきた出力が問題文の仕様どおりかは**必ず目で確かめる**こと。
模範解答が間違っていれば、その間違いをそのまま期待値に焼き付けてしまう。

  tools/probe-cases.sh /path/to/spec.json

spec.json の形:
  [
    {"lesson": "40-1", "task": "1", "label": "空入力", "stdin": "0"},
    ...
  ]

出力は、そのまま content の hiddenCases へ写せる JSON も併記する。
"""
import json
import sys
import urllib.error
import urllib.request

PORT = sys.argv[1]
SPEC = sys.argv[2]
BASE = f"http://localhost:{PORT}/api/"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def get(endpoint):
    with urllib.request.urlopen(BASE + endpoint, timeout=60) as res:
        return json.load(res)


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


def normalize(text):
    """Judge.normalize と同じ規則（行末空白と末尾空行を落とす）。"""
    lines = str(text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    lines = [line.rstrip() for line in lines]
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


def main():
    state = get("state")
    # レッスンID -> 問題ID -> 模範解答。solution は state に載らないので /api/solution から取る
    # （クリア済みかヒント全開示が条件なので、ここでは content を直接読む）
    solutions = {}
    for chapter in state["chapters"]:
        for lesson in chapter["lessons"]:
            for task in lesson["tasks"]:
                solutions[(lesson["id"], task["id"])] = None

    import glob
    import os
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for path in glob.glob(os.path.join(root, "content", "ch*.json")):
        data = json.load(open(path, encoding="utf-8"))
        for lesson in data["lessons"]:
            tasks = [lesson] + lesson.get("extraTasks", [])
            for index, task in enumerate(tasks, 1):
                solutions[(lesson["id"], str(index))] = task.get("solution", "")

    spec = json.load(open(SPEC, encoding="utf-8"))
    by_task = {}
    for entry in spec:
        by_task.setdefault((entry["lesson"], entry["task"]), []).append(entry)

    for (lesson_id, task_id), entries in by_task.items():
        solution = solutions.get((lesson_id, task_id))
        if not solution:
            print(f"{RED}模範解答が見つかりません{RESET}: {lesson_id}#{task_id}")
            continue
        print(f"\n{'=' * 74}")
        print(f"{lesson_id}#{task_id}")
        cases = []
        for entry in entries:
            stdin = entry.get("stdin", "")
            res = post("run", {"code": solution, "stdin": stdin,
                               "libLessonId": lesson_id})
            if not res.get("compiled"):
                msgs = [d.get("message", "") for d in res.get("diagnostics", [])]
                print(f"  {RED}コンパイル不可{RESET} {entry['label']}: {msgs[:1]}")
                continue
            run = res.get("run", {})
            out = normalize(run.get("stdout", ""))
            err = normalize(run.get("stderr", ""))
            flag = ""
            if run.get("timedOut"):
                flag = f" {RED}[タイムアウト]{RESET}"
            elif err:
                flag = f" {YELLOW}[stderr有り]{RESET}"
            print(f"  {DIM}label {RESET}{entry['label']}{flag}")
            print(f"  {DIM}stdin {RESET}{stdin!r}")
            for line in out.split("\n"):
                print(f"  {GREEN}out   {RESET}{line}")
            if err:
                for line in err.split("\n")[:4]:
                    print(f"  {YELLOW}err   {RESET}{line}")
            if not run.get("timedOut") and not err:
                cases.append({"label": entry["label"], "stdin": stdin,
                              "expected": out})
        if cases:
            print(f"  {DIM}--- hiddenCases へ写す形 ---{RESET}")
            print("  " + json.dumps(cases, ensure_ascii=False))


if __name__ == "__main__":
    main()
