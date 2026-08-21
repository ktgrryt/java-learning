#!/usr/bin/env python3
"""入力に取る整数の範囲を書いていない問題を検査する。

tools/check-input-domain.sh から呼ばれる。

  python3 tools/check_input_domain.py          … 検査する
  python3 tools/check_input_domain.py --list   … 判定の材料（範囲を書いている問題も）を出す

## なぜ必要か

`5-2#1` は「整数 `n` が入力されます。`1` から `n` までの合計を出力してください」だった。
しかし `n` が負のとき「1からnまで」は存在せず、何を出力すべきか課題文からは決まらない。
テストケースは `0` 以上しか無いので模範解答は通り、`verify-solutions.sh` でも
`check-case-fairness.sh` でも出ない。**課題文が入力の範囲を広く言いすぎている**という、
文言だけの食い違いである（2026-08-21に利用者から指摘。ch05の9問と `8-5#1` が同じ形だった）。

`3-3#1`（読んだ整数を2で割って「切り捨て」と書いていた。Javaの `/` は0方向へ切るので、
負では切り捨てにならない）も同じ食い違いだが、**くり返しの回数ではない**のでこの検査は見ない。
割り算・添字・日付のように「範囲によって意味が変わる計算」を全部見ようとすると、
何を計算に使ったかを読む必要があり、当てにならない判定が増える。ここはくり返しに絞る。

学習者側から見ると、負を考えるかどうかで書くコードが変わる（`if (n < 0)` を足すか、
`for` の初期値を変えるか）。測っていないものを考えさせるのは、ループの練習として素直ではない。

## 判定

次の3つが揃うものを失敗とする。

  1. 課題文が入力を **範囲の付かない「整数」** として紹介している
     （`0` 以上・正の整数・自然数のような範囲語が課題文のどこにも無い）
  2. 模範解答で、その整数を `sc.nextInt()` で読み、**大きさがくり返しの回数を決めている**
     （`for` の条件か初期値、`while` の条件、配列の大きさ、`repeat`）
  3. テストケースに負の数が1つも無い（＝負のふるまいを決めていない）

3を条件に入れるのは、負を試している問題（`5-6#1` の `n` や `13-5#1` の例外）は
**負のふるまいを課題文で決めている**ので直す必要がないため。

「1行目に件数 `n`、続けて `n` 行」の形は対象外にする。後ろに続くデータの個数なので、
入力が整っている限り負になりえない。**語（`件数`）ではなく、そのループの中で入力を
読み進めているか**で判定する。語で見ると「`n` 行 `n` 列の表」や「全体の回数」のような、
くり返しの回数そのものを指す語に引っかかって取り逃す（最初にそう書いて4問見落とした）。

## 直し方

課題文の紹介を「`0` 以上の整数 `n` が入力されます。」のように直す
（範囲は隠しケースの最小値と、課題文が「`n` が `0` のときは…」と決めている内容に合わせる）。
負も含めて測りたい問題なら、逆に**負のテストケースを足して**課題文でふるまいを決める。
意図して範囲を書かないものは ALLOWED へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')

# 入力を「整数」として紹介している文（`。` と改行で切った1文）。
INTRO = re.compile(r'[^\n。]*整数[^\n。]*(?:入力されます|与えられ|読み|読んで)[^\n。]*')
# 紹介の文にこれがあれば、範囲を書いていると見なす。比べる前に `…` の記号を落とす
# ― 教材は「`0` 以上」と数を囲んで書くので、そのままでは `0 以上` に合致しない
# （`0` 以上 `12` 以下 で取り逃した）。
INTRO_RANGE = ('正の整数', '0 以上', '0以上', '1 以上', '1以上', '2 以上', '2以上',
               '自然数', '以上', '以下', '範囲')
# こちらは課題文のどこにあってもよい。負を話に出しているなら、書き手は負を考えている。
TEXT_RANGE = ('正の整数', '自然数', '以上の整数', '負', 'マイナス')
NUMBER = re.compile(r'-?\d+')
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')
READ_INT = re.compile(r'\b(?:int|long)\s+(\w+)\s*=\s*\w+\.next(?:Int|Long)\s*\(\s*\)')
READ_PARSED = re.compile(r'\b(?:int|long)\s+(\w+)\s*=\s*Integer\.parseInt\s*\(')

# 意図して範囲を書かない問題。足すときは理由を必ず書く。
#   (問題ID, 変数名): 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    checked, failures, ranged = 0, [], []
    for chapter, pid, task in curriculum_order():
        text = task.get('task') or ''
        if not INTRO.search(text):
            continue
        drivers = repetition_drivers(task.get('solution') or '')
        if not drivers:
            continue
        checked += 1
        if has_negative_case(task):
            continue
        if states_range(text):
            ranged.append((chapter, pid, drivers))
            continue
        drivers = [name for name in drivers if (pid, name) not in ALLOWED]
        if drivers:
            failures.append((chapter, pid, drivers, INTRO.search(text).group().strip()))

    print(f'くり返しの回数を決める整数を読む{checked}問について、'
          f'課題文が入力の範囲を書いているかを見ました'
          f'（意図して書かない{len(ALLOWED)}件は対象外）。')
    if listing and ranged:
        print('\n範囲を書いている問題:')
        for chapter, pid, drivers in ranged:
            print(f'  {chapter:8} {pid:10} {", ".join(drivers)}')
    if not failures:
        print('  範囲を書いていないものはありません。')
        return 0

    print(f'\n入力の範囲を書いていない問題が{len(failures)}件あります。', file=sys.stderr)
    for chapter, pid, drivers, intro in failures:
        print(f'  {chapter} {pid}: {", ".join(drivers)} … 「{intro}」', file=sys.stderr)
    print('\n負を入れたときに何を出力するかが課題文から決まらず、テストケースにも無い状態です。'
          '課題文の紹介を「`0` 以上の整数」のように直すか、'
          '負のテストケースを足して課題文でふるまいを決める。'
          '意図して書かないなら ALLOWED へ理由付きで入れる。', file=sys.stderr)
    return 1


def states_range(text):
    """入力の範囲を書いているか。

    範囲は**紹介の文**で見る。課題文のどこかに `1` 以上 があればよい、とすると
    「`n` が `1` 以上のあいだくり返す」のような**処理の説明**を範囲と読み違える
    （`5-3#1` がそれで検査を通り抜けた）。負を話に出しているものだけは、
    どこに書いてあっても書き手が負を考えている証拠なので通す。
    """
    intro = INTRO.search(text)
    plain = (intro.group() if intro else '').replace('`', '')
    return any(word in plain for word in INTRO_RANGE) \
        or any(word in text for word in TEXT_RANGE)


def repetition_drivers(solution):
    """読んだ整数のうち、大きさがくり返しの回数を決めているものの名前。"""
    code = STRING.sub('""', LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', solution)))
    names = set(READ_INT.findall(code)) | set(READ_PARSED.findall(code))
    drivers = []
    for name in sorted(names):
        var = re.escape(name)
        loop = (
            re.search(r'for\s*\([^)]*[<>]=?\s*' + var + r'\b[^)]*\)', code)         # for の条件
            or re.search(r'for\s*\(\s*(?:int|long)\s+\w+\s*=\s*' + var + r'\b[^)]*\)', code)  # 降順の初期値
            or re.search(r'while\s*\([^)]*\b' + var + r'\b[^)]*\)', code)           # while の条件
            or re.search(r'new\s+\w+\s*\[\s*' + var + r'\s*\]', code)             # 配列の大きさ
            or re.search(r'\.repeat\s*\(\s*' + var + r'\s*\)', code)               # 文字列のくり返し
        )
        if loop and not reads_more_input(code, loop.end()):
            drivers.append(name)
    return drivers


def reads_more_input(code, after):
    """そのループの中で入力を読み進めているか（＝続くデータの個数として使っている）。

    `for (int i = 0; i < n; i++) { 名前を読む }` のように中で読むなら、`n` は後ろに続く
    データの件数である。入力が整っていれば負は来ないので、範囲を書かなくてよい。
    ループ本体は `{` からの対応で切り出す（`{` が無い1文のループは次の `;` まで）。
    """
    rest = code[after:]
    brace, semicolon = rest.find('{'), rest.find(';')
    if brace < 0 or (0 <= semicolon < brace):
        return semicolon >= 0 and '.next' in rest[:semicolon + 1]
    depth, index = 0, brace
    while index < len(rest):
        if rest[index] == '{':
            depth += 1
        elif rest[index] == '}':
            depth -= 1
            if depth == 0:
                break
        index += 1
    return '.next' in rest[brace:index]


def has_negative_case(task):
    """テストケースの入力に負の数があるか。"""
    cases = list(task.get('visibleCases') or []) + list(task.get('hiddenCases') or [])
    return any(int(found.group()) < 0
               for case in cases
               for found in NUMBER.finditer(case.get('stdin') or ''))


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
