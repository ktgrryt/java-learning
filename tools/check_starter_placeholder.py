#!/usr/bin/env python3
"""ひな形の「仮の return」に、仮だと分かる注記が付いているかを検査する。

tools/check-starter-placeholder.sh から呼ばれる。

  python3 tools/check_starter_placeholder.py          … 検査する
  python3 tools/check_starter_placeholder.py --list   … 注記の付いている行も一覧する

## なぜ必要か

`starterCode` は**単体でコンパイルできる必要がある**ので、戻り値のあるメソッドを
学習者に書かせる問題では `return 0;` のような値を置くしかない。ところがそれが
**答えの一部なのか、消してよい仮の値なのかが読み取れない**。

`7-6#1`（画面の `8-6`）は次の形で、`return 0;` のあとに `// TODO` があった。

    static int clamp(int value, int min, int max) {
        return 0;
        // TODO          ← return の下なので、そこへ書いても実行されない
    }

2026-08-26に利用者から「`return 0;` が雛形に入っているが回答に使わないので意図が謎」と
指摘があり、教材全体で87か所が同じ形だった（うち49か所は印が一切なかった）。

ひな形は**コンパイルが通る**ので `verify-solutions.sh` では出ない。`--strict-starters` も
「ひな形が合格しないこと」を見るだけで、読み手に伝わるかは見ていない。

## 判定

`starterCode` の中の `return <意味のない値>;`（`0` `""` `null` `false` … 下記 DUMMY）を
数え、次の両方を満たしていなければ失敗とする。

  1. 行末に `// 仮の値` の注記が付いている
  2. その行より上（4行以内）に `TODO` がある ― 何を書けばよいかの手がかり

**模範解答に同じ行があるものは対象外**。`return 0;` が答えの一部（早期returnなど）であり、
仮の値ではないため。ここを見ないと本物のコードにまで注記を付けることになる。

## 直し方

    int total(int count) {
        // TODO: price × count を返す
        return 0;   // 仮の値。書けたら消す
    }

`TODO` は**何を返すメソッドか**を1行で書く（解き方ではなく、返すもの）。
注記の文字列は NOTE 定数と同じにする。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')

NOTE = '仮の値'
# 「意味のない値」の一覧。ここに無い形の仮置きを使ったときは足す。
DUMMY = re.compile(r'^\s*(?:/\*\s*TODO\s*\*/\s*)?return\s+'
                   r'(?:0|0L|0\.0|""|null|false|true|-1|"TODO"'
                   r'|new ArrayList<>\(\)|Optional\.empty\(\)|List\.of\("TODO"\))\s*;')
LOOK_BACK = 4

# 意図して注記を付けない行。足すときは理由を必ず書く。
#   (問題ID, 行の中身): 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    checked, failures, marked = 0, [], []
    for chapter, pid, task in curriculum_order():
        starter = task.get('starterCode') or ''
        if not starter:
            continue
        in_solution = {line.strip() for line in (task.get('solution') or '').split('\n')}
        lines = starter.split('\n')
        for index, line in enumerate(lines):
            if not DUMMY.match(line) or line.strip() in in_solution:
                continue
            checked += 1
            body = line.strip()
            if (pid, body) in ALLOWED:
                continue
            above = '\n'.join(lines[max(0, index - LOOK_BACK):index])
            if NOTE not in line:
                failures.append((chapter, pid, body, f'`{NOTE}` の注記が無い'))
            elif 'TODO' not in above:
                failures.append((chapter, pid, body, 'すぐ上に TODO が無い'))
            else:
                marked.append((chapter, pid, body))

    print(f'ひな形の仮の return {checked}か所について、仮だと分かる注記を見ました'
          f'（意図して付けない{len(ALLOWED)}件は対象外）。')
    if listing:
        print('\n注記の付いている行:')
        for chapter, pid, body in marked:
            print(f'  {chapter:8} {pid:10} {body}')
    if not failures:
        print('  注記の無いものはありません。')
        return 0

    print(f'\n仮だと分からない return が{len(failures)}か所あります。', file=sys.stderr)
    for chapter, pid, body, why in failures:
        print(f'  {chapter} {pid}: {body} … {why}', file=sys.stderr)
    print(f'\n上の行に `// TODO: 何を返すか` を書き、return の行末へ'
          f' `// {NOTE}。書けたら消す` を付けてください。'
          '模範解答にも同じ行があるもの（＝本物のコード）はこの検査に出ません。'
          '意図して付けないなら ALLOWED へ理由付きで入れる。', file=sys.stderr)
    return 1


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
