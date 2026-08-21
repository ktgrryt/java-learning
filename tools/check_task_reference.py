"""課題文とヒントが「上の問題」を指していないか検査する。

tools/check-task-reference.sh から呼ばれる。

  python3 tools/check_task_reference.py          … 検査する
  python3 tools/check_task_reference.py --list   … 当たった箇所をその場で出す

## なぜ必要か

**復習では1問だけを出す**（`renderReviewTask`）。レッスン画面のように問題が縦に並ばないので、
「上の問題では三項演算子を1段だけ使いました」と書いてあると、**上に何も無いのにそう言う**
画面になる（2026-08-22に利用者から指摘。`4-6#2` ほか26か所を書き換えた）。
1問だけ復習する経路（`#review/4-6/2`）と、章の途中から問題2を開いた人にも同じことが起きる。

言い換えると、課題文とヒントは**その1問だけで読めること**が条件である。学習内容の積み上がりを
書きたいときは、位置（上・1問目）ではなく**中身**を名前で書く ―
「上の問題では `Math.max` を使いました」ではなく「解説の最後に出てきた `Math.min` を使います」。

## 見る場所

問題に紐づく文字列だけを見る（`task` `hints` `solution` のコメントなど）。**レッスンの解説は
見ない** ― 解説はレッスン1枚ぶんの文章で、復習でも「このレッスンの解説をもう一度読む」から
全文が開く。そこに「問題1では〜」と書くのは、同じ文書の中の案内なので成り立つ（`16-5` など）。

`要件上の問題` `法令上の問題` の「上の問題」は別の意味なので、直前が漢字なら当てない。

## 直し方

位置で指すのをやめて、中身を書く。それでも書き分けが要るなら ALLOWED へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')

# 位置で前の問題を指す言い方。`要件上の問題` を避けるため、直前が漢字のときは当てない
PATTERNS = [
    re.compile(r'(?<![一-龥])(?:上|前|直前|1つ前|ひとつ前)の問題'),
    re.compile(r'(?<![0-9])[1-9]問目'),
    re.compile(r'前問'),
    re.compile(r'(?:さきほど|先ほど)の問題'),
]
# 問題に紐づく文字列のうち、学習者の画面に出るもの
FIELDS = ('task', 'hints', 'starterCode', 'solution', 'visibleCases', 'hiddenCases', 'sourceChecks')

# 意図して残すもの。足すときは理由を必ず書く。
#   (問題ID, 当たった言い方): 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    checked, failures = 0, []
    for chapter, pid, task in curriculum_order():
        checked += 1
        for field, phrase, excerpt in references(task):
            if (pid, phrase) in ALLOWED:
                continue
            failures.append((chapter, pid, field, phrase, excerpt))
            if listing:
                print(f'  NG  {chapter} {pid} の {field}: {phrase} … {excerpt}')

    print(f'問題{checked}件の課題文・ヒント・模範解答について、'
          f'前の問題を位置で指す言い方を探しました（意図して残す{len(ALLOWED)}件は対象外）。')
    if not failures:
        print('  1問だけ出しても読めない課題文はありません。')
        return 0

    print(f'\n上に問題が無くても読めるようにしてください（{len(failures)}件）。', file=sys.stderr)
    for chapter, pid, field, phrase, excerpt in failures:
        print(f'  {chapter} {pid} の {field}: {phrase} … {excerpt}', file=sys.stderr)
    print('\n復習は1問だけを出すので、位置ではなく中身を書きます'
          '（「上の問題では Math.max を使いました」→「解説に出てきた Math.min を使います」）。'
          '意図して残すなら ALLOWED へ理由付きで入れてください。', file=sys.stderr)
    return 1


def references(task):
    """[(場所, 当たった言い方, 前後の抜粋)]。"""
    found = []
    for field in FIELDS:
        for path, text in strings(task.get(field), field):
            for pattern in PATTERNS:
                for match in pattern.finditer(text):
                    head = max(0, match.start() - 24)
                    excerpt = text[head:match.end() + 24].replace('\n', ' ')
                    found.append((path, match.group(), excerpt))
    return found


def strings(value, path):
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, dict):
        for key, child in value.items():
            yield from strings(child, f'{path}.{key}')
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from strings(child, f'{path}[{index}]')


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
