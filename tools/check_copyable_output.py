"""計算しないで入力を写すだけで通る問題を検査する。

tools/check-copyable-output.sh から呼ばれる。

  python3 tools/check_copyable_output.py          … 検査する
  python3 tools/check_copyable_output.py --list   … 判定の材料（写せる値と模範解答の行）を出す

## なぜ必要か

`64-3#2` は「合計＝単価×個数」を出したあと「単価＝合計÷個数」を出す問題だった。
しかし `(単価 × 個数) ÷ 個数` は**必ず単価に戻る**ので、割り算を書かずに単価をそのまま
表示すれば全ケース通る。0で割らないようにする、という学習内容（`ch64-o4`）を書かずに
★が取れていた。おまけに「割り切れないとき」の隠しケースも作れておらず、
ラベルだけが `割り切れない` と言っていた（2026-08-19に利用者から指摘）。

`check_constant_output.py` が「**全ケースで同じ出力**なら写すだけで通る」を見ているのに対し、
ここは「**入力と同じ値**なら計算せずに写せる」を見る。1段ゆるい同じ穴である。

## 判定

ケースが3件以上あり、入力と期待出力に含まれる数の個数がどのケースでも同じ問題について、

  1. 期待出力の同じ目印（`単価=` のような語）に続く数が、**どのケースでも**入力の同じ位置の数と
     一致する（＝写せる）
  2. その目印を表示している模範解答の行が、文字列を除いても `*` `/` `%` を含む（＝計算している）

の両方が成り立つものを失敗とする。1だけなら、入力をそのまま表示する問題（`3-3#2` の
`入力 3.9` など）なので正常である。2で文字列を除くのは、`"3/4"` のような区切りや
`printf` の `%.2f` を計算と数えないためである。

**数の個数ではなく目印で対応を取る。** `単価=不明` のように数が出ないケースが混ざると個数が
そろわず、位置で数えると `64-3#2` 自身を見落とす（実際に最初はそう書いて取り逃した）。
目印ごとに、数が出ているケースだけをMIN_CASES件以上集めて比べる。

## 直し方

割る数（掛ける数）を**独立した入力**にする。`64-3#2` は「単価・個数・人数を読み、
合計＝単価×個数、1人あたり＝合計÷人数」に変えた。人数は他の入力から求められないので、
割り算を書かないと通らず、割り切れないケースも作れる。
意図してそのままにするものは ALLOWED へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
NUMBER = re.compile(r'-?\d+(?:\.\d+)?')
# `+` は println の文字列連結なので計算と数えない。文字列リテラルは先に落とす。
ARITHMETIC = re.compile(r'[*/%]')
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')
MIN_CASES = 3

# 意図して残すもの。足すときは理由を必ず書く。
#   (問題ID, ラベル): 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    checked, failures = 0, []
    for part, chapter, pid, task in curriculum_order():
        cases = list(task.get('visibleCases') or []) + list(task.get('hiddenCases') or [])
        rows = readable_cases(cases)
        if not rows:
            continue
        checked += 1
        for label in copyable_labels(rows):
            line = computing_line(label, task.get('solution') or '')
            if not line or (pid, label) in ALLOWED:
                continue
            failures.append((chapter, pid, label, line))
            if listing:
                print(f'  NG  {chapter} {pid}: {label!r} … {line}')

    print(f'ケースが{MIN_CASES}件以上ある問題{checked}件について、'
          f'計算せずに入力を写して通せる値を探しました（意図して残す{len(ALLOWED)}件は対象外）。')
    if not failures:
        print('  入力を写すだけで通る計算はありません。')
        return 0

    print(f'\n計算を書かずに入力を写せば通る問題が{len(failures)}件あります。', file=sys.stderr)
    for chapter, pid, label, line in failures:
        print(f'  {chapter} {pid}: {label!r} の値は入力と同じです … {line}', file=sys.stderr)
    print('\n直し方は、割る数（掛ける数）を他の入力から求められない独立した入力にすることです。'
          '意図して残すなら ALLOWED へ理由付きで入れてください。', file=sys.stderr)
    return 1


def readable_cases(cases):
    """[(入力の数, {目印: 数})]。入力の数の個数がそろわない問題は見ない（位置の対応が取れない）。"""
    rows = []
    for case in cases:
        stdin, expected = case.get('stdin'), case.get('expected')
        if not stdin or not isinstance(expected, str):
            return []
        numbers = NUMBER.findall(stdin)
        if not numbers:
            return []
        rows.append((numbers, labelled_numbers(expected)))
    if len(rows) < MIN_CASES or len({len(inp) for inp, _ in rows}) != 1:
        return []
    return rows


def labelled_numbers(expected):
    """期待出力の数を `(目印, 何番目か)` で引けるようにする。同じ目印が何度も出る出力があるため。"""
    found, seen = {}, {}
    for line in expected.split('\n'):
        for match in NUMBER.finditer(line):
            head = line[:match.start()].strip()
            label = head.split()[-1] if head else ''
            if not label:
                continue
            occurrence = seen.get(label, 0)
            seen[label] = occurrence + 1
            found[(label, occurrence)] = match.group()
    return found


def copyable_labels(rows):
    """写せる目印。<b>その目印で出る数が何個あっても、全部が入力と一致する</b>ものだけを返す。

    1個目だけ一致する出力は写しても通らない（`38-3#1` の指数バックオフは1回目の待ち時間が
    必ず `base` と同じだが、2回目からは倍になるので計算しないと合わない）。
    """
    keys = set()
    for _, outputs in rows:
        keys.update(outputs)
    labels = {}
    for label, occurrence in keys:
        labels.setdefault(label, []).append(occurrence)
    result = []
    for label in sorted(labels):
        judged = [copyable(rows, (label, occurrence)) for occurrence in sorted(labels[label])]
        if judged and all(verdict is True for verdict in judged):
            result.append(label)
    return result


def copyable(rows, key):
    """その `(目印, 何番目か)` の数が、どのケースでも入力の同じ位置の数と一致するか。

    数が出ているケースがMIN_CASES 件件未満のときは判定しない（`None`）。少ないと偶然一致する。
    """
    values = [(inp, outputs[key]) for inp, outputs in rows if key in outputs]
    if len(values) < MIN_CASES:
        return None
    for position in range(len(values[0][0])):
        if all(float(value) == float(inp[position]) for inp, value in values):
            return True
    return False


def computing_line(label, solution):
    """その目印を表示している模範解答の行。計算していなければ空文字を返す。

    目印（`単価=`）は文字列リテラルの中にあるので<b>元の行</b>で探し、計算しているかは
    文字列を外した同じ行で見る。外してから探すと目印ごと消えて何も見つからない。
    """
    for line in solution.split('\n'):
        if label in line and ARITHMETIC.search(code_only(line)):
            return line.strip()
    return ''


def code_only(code):
    """コメントと文字列リテラルを外したコード。区切りの `/` や書式の `%` を計算と数えないため。"""
    return STRING.sub('""', LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', code)))


def curriculum_order():
    manifest = json.load(open(CONTENT / 'manifest.json', encoding='utf-8'))
    problems = []
    for part in manifest['parts']:
        for filename in part['chapters']:
            data = json.load(open(CONTENT / filename, encoding='utf-8'))
            for lesson in data['lessons']:
                tasks = ([lesson] if lesson.get('task') else []) \
                    + list(lesson.get('extraTasks') or [])
                for number, task in enumerate(tasks, start=1):
                    problems.append((part['id'], data['id'],
                                     f"{lesson['id']}#{number}", task))
    return problems


if __name__ == '__main__':
    sys.exit(main())
