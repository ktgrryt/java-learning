"""コードブロックの桁揃えが、画面でも揃っているかを検査する。

tools/check-block-alignment.sh から呼ばれる。

  python3 tools/check_block_alignment.py          … 検査する
  python3 tools/check_block_alignment.py --list   … 惜しい（閾値未満の）ずれも出す

## なぜ必要か

2026-08-19に、5-1の「エラーの行はこう読む」で `^^^^` の下に日本語のラベルを並べた図が
画面でずれていた。原因は**等幅でも日本語は半角2文字ぶんにならない**ことにある。
実測（macOS / Chrome / `--mono` の先頭は SF Mono）:

  半角 `a` `→` `─` `↑` … 8.730px      日本語・全角 … 14.500px（半角1.661文字ぶん）

Latinの等幅フォントは1文字を 0.55〜0.6em で送るのに、日本語のフォールバックは 1.0em で送る。
だから比は環境で 1.66（macOS）〜1.82（Windows: Consolas + Yu Gothic）に変わり、
**どのブラウザでも「全角=2文字ぶん」にはならない**。端末やエディタでは2文字ぶんなので、
書いた本人の画面では揃って見え、アプリでだけずれる。

## 判定

  失敗 … 上を指す矢印（`↑` `^`）の行で、矢印より左に全角がある
          → 矢印の位置が決まらない。「1行に矢印1つ」にして、ラベルは矢印の右に書く
  失敗 … 矢印だけの行に矢印が2つ以上あり、その下の行に全角のラベルを並べている（5-1がこれだった）
          → ラベルの幅が読めないので下に並べられない。同じく「1行に矢印1つ」にする
  失敗 … `text` などのブロックで、空白2つ以上で作った列に全角の行が混ざり、画面で0.5文字ぶん以上ずれる
          → markdownの表にする（`| 例 | 意味 |`）か、桁揃えをやめて空白1つにする
  失敗 … 全角を含まない行だけで作った列が、数え違いでずれている（差が3文字以内＝揃える意図あり）
          → 空白の数を直せば正確に揃う

`java` のブロックで、コード自体に日本語（文字列リテラル）があるときの `// ` の列は見ていない。
どう詰めても環境ごとに 0.6文字ぶんほど残るので、直せる余地が無い。
"""
import glob
import json
import pathlib
import re
import sys
import unicodedata

CONTENT = pathlib.Path('content')
RATIOS = (14.500 / 8.730, 1.82)      # macOS実測 / Windowsの見込み
FENCE = re.compile(r'^```(\w*)[ \t]*(.*)$')
POINTER_LINE = re.compile(r'^\s*(?://+)?\s*[\^↑]')
COMMENT_COL = re.compile(r'^(.*?\S)( {2,})(//.*)$')
NEAR = 3                             # 列の差がこれ以内なら「揃える意図があった」とみなす
TOLERANCE = 0.5                      # 画面で許すずれ（文字ぶん）


def is_wide(ch):
    return unicodedata.east_asian_width(ch) in ('W', 'F')


def width(text, ratio):
    return sum(ratio if is_wide(ch) else 1.0 for ch in text)


def fields(line):
    """空白2つ以上を区切りとした列の (開始文字位置, 中身)。"""
    return [(m.start(1), m.group(1))
            for m in re.finditer(r'(?:^|(?<=  ))\s*(\S.*?)(?=  |$)', line)]


def blocks(text):
    """```で囲まれたブロックを (言語, 行の一覧) で返す。```svg は図なので見ない。"""
    lines = text.replace('\r\n', '\n').split('\n')
    i = 0
    while i < len(lines):
        opened = FENCE.match(lines[i])
        if not opened:
            i += 1
            continue
        lang, body = opened.group(1), []
        i += 1
        while i < len(lines) and not re.match(r'^```\s*$', lines[i]):
            body.append(lines[i])
            i += 1
        i += 1
        if lang != 'svg' and len(body) >= 2:
            yield lang, body


def pointer_problems(body):
    """位置の決められない矢印の行を (行, 理由) で返す。"""
    found = []
    for index, line in enumerate(body):
        if not POINTER_LINE.match(line):
            continue
        for m in re.finditer(r'[\^↑]', line):
            if any(is_wide(ch) for ch in line[:m.start()]):
                found.append((line, '矢印より左に全角がある'))
                break
        else:
            # 矢印だけの行（空白と矢印しかない）が2つ以上を指しているなら、
            # その下に全角のラベルを並べても桁は合わない
            groups = re.findall(r'[\^↑]+', line)
            if len(groups) >= 2 and re.fullmatch(r'[\s\^↑]+', line):
                below = next((l for l in body[index + 1:] if l.strip()), '')
                if any(is_wide(ch) for ch in below):
                    found.append((line, '矢印が2つ以上あり、下の行に全角のラベルを並べている'))
    return found


def column_drift(body):
    """空白で作った列のうち、画面でずれる列を (列番号, ずれ) で返す。"""
    rows = [fields(line) for line in body]
    worst = []
    for k in range(1, max((len(r) for r in rows), default=0)):
        starts, has_wide = [], False
        for line, row in zip(body, rows):
            if len(row) > k:
                head = line[:row[k][0]]
                starts.append([width(head, r) for r in RATIOS])
                has_wide = has_wide or any(is_wide(ch) for ch in head)
        if len(starts) < 2 or not has_wide:
            continue
        # 想定グリッド（全角=2）でほぼ揃っている列だけを「揃える意図があった」とみなす
        grid = [sum(2 if is_wide(ch) else 1 for ch in line[:row[k][0]])
                for line, row in zip(body, rows) if len(row) > k]
        if max(grid) - min(grid) > 1:
            continue
        drift = max(max(c) - min(c) for c in zip(*starts))
        if drift > 0:
            worst.append((k + 1, drift))
    return worst


def counting_slips(body):
    """全角を含まない行だけで作った // の列が、数え違いでずれているもの。"""
    cols = {}
    for line in body:
        m = COMMENT_COL.match(line)
        if not m or any(is_wide(ch) for ch in m.group(1)):
            continue
        cols.setdefault(len(m.group(1)) + len(m.group(2)), []).append(line)
    slips = []
    keys = sorted(cols)
    for a in keys:
        near = [b for b in keys if a < b <= a + NEAR]
        for b in near:
            slips.append((a, b, cols[a][0], cols[b][0]))
    return slips


def main():
    listing = '--list' in sys.argv[1:]
    pointers, drifts, slips, close_calls = [], [], [], []
    blocks_seen = 0
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data['lessons']:
            for where, text in texts_of(lesson):
                for lang, body in blocks(text):
                    blocks_seen += 1
                    for line, reason in pointer_problems(body):
                        pointers.append((where, line, reason))
                    for column, drift in column_drift(body):
                        target = drifts if (lang != 'java' and drift >= TOLERANCE) else close_calls
                        target.append((where, lang, column, drift, body[0]))
                    for a, b, line_a, line_b in counting_slips(body):
                        slips.append((where, a, b, line_a, line_b))

    print(f'コードブロック{blocks_seen}件の桁揃えを画面の幅で測りました'
          f'（全角=半角{RATIOS[0]:.2f}〜{RATIOS[1]:.2f}文字ぶん）。', flush=True)
    if listing:
        for where, lang, column, drift, head in close_calls:
            reason = 'コード側に日本語があるので詰め直せない' if lang == 'java' \
                else f'許容の{TOLERANCE:.2f}文字ぶん未満'
            print(f'  参考 {where}: {lang or "(指定なし)"} の列{column} が {drift:.2f}文字ぶん'
                  f'（{reason}） {head[:40]!r}', flush=True)

    problems = len(pointers) + len(drifts) + len(slips)
    if not problems:
        print('  画面でずれる桁揃えはありません。')
        return 0

    print(f'\n画面でずれる桁揃えが{problems}件あります。', file=sys.stderr)
    for where, line, reason in pointers:
        print(f'  {where}: {reason} → 1行に矢印1つにして、ラベルは矢印の右に書く'
              f'  {line.strip()!r}', file=sys.stderr)
    for where, lang, column, drift, head in drifts:
        print(f'  {where}: 列{column} が画面で{drift:.2f}文字ぶんずれる → 表にするか桁揃えをやめる'
              f'  {head[:40]!r}', file=sys.stderr)
    for where, a, b, line_a, line_b in slips:
        print(f'  {where}: // の列が{a}桁と{b}桁に分かれている（半角だけなので正確に揃えられる）\n'
              f'      {line_a.strip()[:60]!r}\n      {line_b.strip()[:60]!r}', file=sys.stderr)
    return 1


def texts_of(lesson):
    """レッスンの中で、学習者が読む文（コードブロックを含みうるもの）を全部返す。"""
    yield from [(lesson['id'], lesson.get(key) or '')
                for key in ('explanation', 'task')]
    for index, task in enumerate(lesson.get('extraTasks') or [], start=1):
        yield (f"{lesson['id']} 発展{index}", task.get('task') or '')
    for index, quiz in enumerate(lesson.get('quiz') or [], start=1):
        yield (f"{lesson['id']} クイズ{index}", quiz.get('question') or '')
        yield (f"{lesson['id']} クイズ{index}", quiz.get('explanation') or '')


if __name__ == '__main__':
    sys.exit(main())
