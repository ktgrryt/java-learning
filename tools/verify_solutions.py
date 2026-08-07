#!/usr/bin/env python3
"""
全レッスンの模範解答を提出して、全テストケースを通ることを確かめる。

verify-solutions.sh から呼ばれる（直接実行する場合は第1引数にポート番号を渡す）。

チェックしているのは次の4点。
  1. content/*.json が読み込めること
  2. 各レッスンの starterCode がコンパイルできること（ひな形が壊れていないか）
  3. 各レッスンの solution が全テストケースを通ること
  4. 各サンプルコードが実行できること（解説に載せたコードが動かないと最悪なので）
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


def main():
    state = get("state")
    lessons = [(ch, l) for ch in state["chapters"] for l in ch["lessons"]]
    print(f"レッスン {len(lessons)}件 / 章 {len(state['chapters'])}件 を検査します\n")

    failures = []
    warnings = []

    for chapter in state["chapters"]:
        print(f"{chapter['emoji']} 第{chapter['number']}章 {chapter['title']}")

        for lesson in chapter["lessons"]:
            lid = lesson["id"]
            problems = []

            # ── ひな形がコンパイルできるか ────────────────────────────
            starter = post("run", {"code": lesson["starterCode"]})
            if not starter.get("compiled"):
                problems.append("ひな形がコンパイルできない")
                show_diagnostics(starter.get("diagnostics", []))

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

            # ── 模範解答が全ケースを通るか ────────────────────────────
            if not lesson["hasSolution"]:
                warnings.append(f"{lid}: 模範解答が無い")
                total = passed = 0
            else:
                # 模範解答は hint を全部開けないと取得できないので、先に開ける
                for i in range(lesson["hintCount"]):
                    post("hint", {"lessonId": lid, "index": i})
                got = post("solution", {"lessonId": lid})
                if "solution" not in got:
                    problems.append(f"模範解答を取得できない: {got.get('error')}")
                    total = passed = 0
                else:
                    res = post("submit", {"lessonId": lid, "code": got["solution"]})
                    if not res.get("compiled"):
                        problems.append("模範解答がコンパイルできない")
                        show_diagnostics(res.get("diagnostics", []))
                        total = passed = 0
                    else:
                        total = len(res["cases"])
                        passed = res.get("passedCount", 0)
                        if not res.get("allPass"):
                            problems.append(f"模範解答が {total - passed}件のケースで落ちた")
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

            # ── ヒントの件数チェック ──────────────────────────────────
            if lesson["hintCount"] == 0:
                warnings.append(f"{lid}: ヒントが無い")

            mark = f"{GREEN}✅{RESET}" if not problems else f"{RED}❌{RESET}"
            cases = f"{passed}/{total}ケース" if total else "ケース無し"
            hidden = f" (隠し{lesson['hiddenCaseCount']})" if lesson["hiddenCaseCount"] else ""
            print(f"   {mark} {lid:4} {lesson['title'][:26]:28} {cases}{hidden}")
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

    hidden_total = sum(l["hiddenCaseCount"] for _, l in lessons)
    case_total = sum(l["totalCaseCount"] for _, l in lessons)
    print(f"{GREEN}すべて合格{RESET}  "
          f"レッスン{len(lessons)}件 / テストケース{case_total}件（うち隠し{hidden_total}件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
