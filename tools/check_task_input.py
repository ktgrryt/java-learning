#!/usr/bin/env python3
"""課題文が「どんな入力が与えられるか」を書いているかを検査する。

tools/check-task-input.sh から呼ばれる。

  python3 tools/check_task_input.py          … 検査する
  python3 tools/check_task_input.py --list   … 書いている問題の言い回しも一覧する

## なぜ必要か

`7-6#1`（画面の `8-6`）は「値valueをmin以上max以下に収める clamp を作って呼び出してください。
**3整数を読み**、結果だけを表示します」だった。整数が3つ来ることは分かるが、**どれが
`value` でどれが `min` なのかが課題文から決まらない**。学習者はテストケースの入力欄と
ひな形の `main` を読んで推測することになる（2026-08-26に利用者から指摘）。

入力を一言も書いていない問題もあった。`22-3#1` は `/items?id=...` の仕様だけを書いており、
標準入力が `GET /items?id=1` のようなリクエスト行だとは、どこにも書いていない。

模範解答は通るので `verify-solutions.sh` では出ない。ケースの文言を見る
`check-case-fairness.sh`、負の範囲を見る `check-input-domain.sh` にも掛からない。
**課題文の情報量だけの欠落**なので、この検査で見る。

## 判定

標準入力を読む `single-file` 問題（テストケースに `stdin` があるもの）について、
課題文が次のどちらかに当てはまるものを失敗とする。

  1. 入力に触れる語（`入力` `読み` `与えられ` `受け取` `並び` `渡され`）が課題文に1つも無い
  2. 入力の値が2つ以上あるのに、**並び方を示す言い回し**が1つも無い
     （`の順で` `1行目` `1行に` `続けて` `### 入力` など。LAYOUT を参照）

2で「値が2つ以上」を条件にするのは、1つしか読まない問題は順番が問題にならないため。
数だけを書いた形（`3整数を読み`）は**通さない** ― 指摘の元がその形で、数が分かっても
どれが何かは決まらない。

## 直し方

課題文の先頭に、教材で最も多い形をそろえて書く。

  整数が3つ、`value` `min` `max` の順で1行に入力されます（`min` は `max` 以下です）。
  1行目に個数 `n`（`0` 以上）、続けて `n` 個の整数（マイナスも来ます）が入力されます。
  入力は **1行が1リクエスト**（`METHOD /パス?クエリ` の形）で、上から順に処理されます。

範囲は**テストケースが実際に含む値に合わせる**こと（`0` が来るなら「正の整数」と書かない。
→ [[check_input_domain.py]] と同じ考え方）。書けない事情があるものは ALLOWED へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')

# 入力に触れているか。`並び` `渡され` は「続く n 行に … が並びます」の形のため。
MENTION = re.compile(r'入力|読み|読ん|与えら|受け取|並び|渡され')

# 値の並び方を示す言い回し。ここに無い形で書いたときは、この一覧へ足す。
LAYOUT = re.compile(
    r'###\s*入力'          # 見出しで節を作る形
    r'|入力は|1行目|行目に|各行|1行が|1行の|1行に|1行で受け取|1行ずつ受け取'
    r'|の順で|の順に|この順|1つ目|それぞれ|ずつ'
    r'|空白区切り|スペース区切り'
    r'|続けて|続く|並びます|の形で'
    r'|個数\s*[`]?n|要素数\s*[`]?n|件数\s*[`]?n|n個の'
    r'|の3整数|の2整数|（2行）'
)

# 意図して書かない問題。足すときは理由を必ず書く。
#   問題ID: 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    checked, failures, described = 0, [], []
    for chapter, pid, task in curriculum_order():
        if task_type(task) != 'single-file':
            continue
        values = input_values(task)
        if not values:
            continue
        checked += 1
        text = task.get('task') or ''
        if pid in ALLOWED:
            continue
        if not MENTION.search(text):
            failures.append((chapter, pid, values, '入力に触れていない'))
            continue
        found = LAYOUT.search(text)
        if values >= 2 and not found:
            failures.append((chapter, pid, values, '値が並ぶ順番を書いていない'))
            continue
        described.append((chapter, pid, values, found.group() if found else '―'))

    print(f'標準入力を読む{checked}問について、課題文が入力の形を書いているかを見ました'
          f'（意図して書かない{len(ALLOWED)}件は対象外）。')
    if listing:
        print('\n書いている問題:')
        for chapter, pid, values, how in described:
            print(f'  {chapter:8} {pid:10} 値{values}個 … 「{how}」')
    if not failures:
        print('  入力の形を書いていないものはありません。')
        return 0

    print(f'\n入力の形を書いていない問題が{len(failures)}件あります。', file=sys.stderr)
    for chapter, pid, values, why in failures:
        print(f'  {chapter} {pid}: 値{values}個 … {why}', file=sys.stderr)
    print('\n何が、どの順で、どんな範囲で入力されるかを課題文の先頭に書いてください'
          '（「整数が3つ、`value` `min` `max` の順で1行に入力されます」）。'
          '範囲はテストケースが実際に含む値に合わせる。'
          '意図して書かないなら ALLOWED へ理由付きで入れる。', file=sys.stderr)
    return 1


def task_type(task):
    return task.get('type') or 'single-file'


def input_values(task):
    """テストケースの入力に並ぶ値の数（いちばん多いケースで数える）。

    行をまたぐ入力もあるので、行ごとではなく**全体の語数**で数える。
    `stdin` が無い（入力を読まない）問題は 0 を返す。
    """
    cases = list(task.get('visibleCases') or []) + list(task.get('hiddenCases') or [])
    counts = [len((case.get('stdin') or '').split()) for case in cases]
    return max(counts) if counts else 0


def curriculum_order():
    """カリキュラム順に [(章id, 問題ID, 問題)] を並べる。"""
    manifest = json.load(open(CONTENT / 'manifest.json', encoding='utf-8'))
    problems = []
    for part in manifest['parts']:
        for filename in part['chapters']:
            data = json.load(open(CONTENT / filename, encoding='utf-8'))
            for lesson in data['lessons']:
                tasks = ([lesson] if lesson.get('task') else []) \
                    + list(lesson.get('extraTasks') or [])
                for number, task in enumerate(tasks, start=1):
                    problems.append((data['id'], f"{lesson['id']}#{number}", task))
    return problems


if __name__ == '__main__':
    sys.exit(main())
