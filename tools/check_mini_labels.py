"""mini実装を使う章に「ここが本物と違う」が書かれているかを検査する（レビュー08-14の §7-7）。

tools/check-mini-labels.sh から呼ばれる。

  python3 tools/check_mini_labels.py           … 検査する
  python3 tools/check_mini_labels.py --list    … 章ごとの状況と、ラベルの中身の不足を出す

## なぜ必要か

教材は Servlet・CDI・JPA・Validation・JUnit・JDBC を**学習用に書き直した最小実装**で練習させる。
書き方は本物と同じにしてあるが、挙動は削っている。**どこまでが本物と同じで、どこからが違うのか**を
章ごとに書いていないと、学習者は「教材でできたこと」を実務でできることだと思い込む。

08-14の §7-7 は「各章で同じラベルで表示すると、章間の粒度が揃う」と書いていた。実際に調べると
`ch22` と `ch45` だけが詳しく、`ch23` は1行も無く、他は解説の途中に1文あるだけだった。

## 何を見るか

`Mini` で始まるクラス（`MiniWeb` `MiniJdbc` `MiniDi` `MiniJpa` `MiniValidator` `MiniJUnit`
`MiniLogger` …）を使う章には、**そのminiが最初に出てくるレッスンの解説**に
`### ⚠️ ここが本物と違う` があること。ラベルの中身は3つを満たすこと。

  1. 違いの箇条書き（`- ` で始まる行が2つ以上）
  2. 「書き方は本物と同じ」に当たる断り
  3. 本物でだけ確かめられること（`💡` の行）

**この検査は文言の有無しか見られない。** 中身が事実と合っているかは書く側が確かめる
（実際に §7-7 の作業で、`MiniJUnit` は `@BeforeEach` に対応しているのに「動かせない」と書いた
問題文が見つかった。ラベルを書くために mini の実装を読んだから気づけた）。
"""
import glob
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
LABEL = '### ⚠️ ここが本物と違う'
MINI = re.compile(r'\bMini[A-Z][A-Za-z]*')

# `Mini` で始まるが、mini実装ではないもの（あれば足す）
NOT_MINI = set()


def main():
    listing = '--list' in sys.argv[1:]
    problems = []
    rows = []
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        minis, first = set(), {}
        for lesson in data['lessons']:
            body = json.dumps(lesson, ensure_ascii=False)
            for name in MINI.findall(body):
                if name in NOT_MINI:
                    continue
                minis.add(name)
                first.setdefault(name, lesson['id'])
        if not minis:
            continue

        labelled = [l['id'] for l in data['lessons']
                    if LABEL in (l.get('explanation') or '')]
        name = pathlib.Path(path).name
        rows.append((name, sorted(minis), sorted(first.values())[0], labelled))
        if not labelled:
            problems.append(f'{name}: {" ".join(sorted(minis))} を使っていますが'
                            f'「{LABEL}」がありません（{sorted(first.values())[0]} の解説へ）')
            continue
        for lesson in data['lessons']:
            explanation = lesson.get('explanation') or ''
            if LABEL not in explanation:
                continue
            block = explanation[explanation.index(LABEL):]
            missing = []
            if len(re.findall(r'^- ', block, re.M)) < 2:
                missing.append('違いの箇条書き（2つ以上）')
            if '本物と同じ' not in block:
                missing.append('「書き方は本物と同じ」の断り')
            if '💡' not in block:
                missing.append('💡 本物でだけ確かめられること')
            if missing:
                problems.append(f'{name} {lesson["id"]}: ラベルに'
                                f'{"、".join(missing)}がありません')

    print(f'mini実装を使う章は{len(rows)}件です。')
    if listing:
        for name, minis, first, labelled in rows:
            mark = '✓' if labelled else '×'
            print(f'  {mark} {name:44} {" ".join(minis):28} 初出={first} ラベル={labelled or "なし"}')

    if not problems:
        print('  どの章にも「ここが本物と違う」があり、3つの要素がそろっています。')
        return 0

    print(f'\n直す箇所が{len(problems)}件あります。', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n書き方は docs/guide.md「mini実装の章に書くこと」にあります。'
          '**中身が事実と合っているかは機械では見られません。**'
          'ラベルを書くときは mini の実装（`content/lib/`）を読んで確かめてください。',
          file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
