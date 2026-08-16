"""解説の中で「この式はこう出る」と書いた箇所を、実際に実行して確かめる。

tools/check-explanation-output.sh から呼ばれる。

  python3 tools/check_explanation_output.py           … 検査する
  python3 tools/check_explanation_output.py --list    … 照合した組を全部出す
  python3 tools/check_explanation_output.py --skipped … 実行できなかった組と理由を出す

## なぜ必要か

`samples[].expected` は `verify-solutions.sh` が実行して照合する。しかし**解説の本文に書いた
出力は誰も検証していなかった**。2026-08-16のレビューで、この穴に落ちた誤りが2件見つかった。

  `"istanbul".toUpperCase(...)` の結果を `İNSTANBUL` と書いていた（正しくは `İSTANBUL`）
  `quote("C:\\temp")` の結果を `"C:\\\\temp"` と書いていた（正しくは `"C:\\temp"`）

解説のコードは断片が多く、全部は実行できない。**実行できる形で書いた組だけを検査する**という
約束にして、残りは「実行できなかった組」として数える（`--skipped` で一覧できる）。

## 何を検査するか

  A. `System.out.println(式);   // 期待値`  … 期待値の直前に印を入れて実行し、出た値と比べる
  B. `式;   // 期待値`                      … その行を `println(式)` へ置き換えて比べる
  C. `class Main` を含むブロック + 直後の出力ブロック … プログラムとして実行し、出力全体を比べる

**ブロックの行は消さずにそのまま実行する。** `System.out.println(a++);` のように出力そのものが
状態を変える行があるので、前置きから消すと後ろの値が変わってしまう（実際にこれで誤検出した）。

期待値として読むのは、**日本語を含まない短い1行**だけ（`// 3.5（変えてから割る）` のような
説明は対象外）。`Point@1b6d3586` のようなハッシュや、`// ?` のような問いかけも外す。

## 何を検査しないか（安全と再現性のため）

入出力・時刻・乱数・スレッドを含むブロックは実行しない（`Files.` `Scanner` `now()`
`Math.random` `Thread` など）。型やメソッドの宣言を含む断片、かっこが閉じていない断片、
くり返しの中にある行も外す。**外した組は数えて表示する**（黙って減らすと検査が弱くなる）。
"""
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

CONTENT = pathlib.Path('content')
BLOCK = re.compile(r'```(\w*)[^\n]*\n(.*?)```', re.S)

MARK = '\u0001'          # 期待値の直前に入れる印
FAIL = '\u0002'          # 例外で落ちたときの印

# 期待値として読める文字（ラテン文字・記号・トルコ語のIなど）。日本語が来たらそこで切る。
VALUE_COMMENT = re.compile(r'^[\x20-\x7e\u00c0-\u024f\u0130\u0131]{1,48}$')
JAPANESE = re.compile(r'[\u3000-\u30ff\u3400-\u9fff\uff00-\uffef←→✗✓❌⚠💡]')
PRINT_LINE = re.compile(r'^(\s*)(System\.out\.print(?:ln|f)?\(.+\);)\s*//\s*(.+?)\s*$')
EXPRESSION_LINE = re.compile(r'^(\s*)([A-Za-z_"\'(\[][^;]*)\;\s*//\s*(.+?)\s*$')

UNSAFE = ('Files.', 'Path.of', 'Scanner', 'HttpClient', 'HttpRequest', 'Thread', 'Executor',
          'now()', 'currentTimeMillis', 'nanoTime', 'Math.random', 'System.exit', 'System.gc',
          'while (true)', 'new Random', 'getenv', 'getProperty', 'JFR', 'jfr')
MUTATING = ('.append(', '.add(', '.put(', '.remove(', '.set(', '.clear(', '.sort(', '++', '--')
DECLARATION = re.compile(r'^\s*(?:public|private|protected|abstract|final|static|sealed|non-sealed|@)'
                         r'[\w\s@.<>,()]*\b(?:class|interface|record|enum)\b|^\s*(?:class|interface|record|enum)\s')
LOOP_OR_BRANCH = re.compile(r'^\s*(?:for|while|do|if|else|switch|case|default|try|catch|finally)\b')

IMPORTS = """import java.math.*;
import java.text.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
"""


def main():
    args = sys.argv[1:]
    units, skipped = collect()
    if not units:
        print('解説の中に、実行して確かめられる出力の組はありませんでした。')
        return 0

    workdir = pathlib.Path(tempfile.mkdtemp(prefix='jq-explanation-'))
    try:
        results = run_all(units, workdir, skipped)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    mismatched = [r for r in results if not r['ok']]
    print(f'解説の中の出力を{len(results)}件、実際に実行して照合しました'
          f'（実行できなかった組は{len(skipped)}件）。')

    if '--list' in args:
        for r in results:
            print(f'  {"OK " if r["ok"] else "NG "} {r["lesson"]}: {r["source"]}')
            print(f'        解説 {r["expected"]!r} / 実際 {r["actual"]!r}')
    if '--skipped' in args:
        for s in skipped:
            print(f'  - {s["lesson"]}: {s["reason"]}\n      {s["source"]}')

    if not mismatched:
        print('  書いてある出力と実際の出力は、すべて一致しています。')
        return 0

    print(f'\n実際の出力と違う箇所が{len(mismatched)}件あります。', file=sys.stderr)
    for r in mismatched:
        print(f'  {r["lesson"]}: {r["source"]}', file=sys.stderr)
        print(f'      解説 {r["expected"]!r}', file=sys.stderr)
        print(f'      実際 {r["actual"]!r}', file=sys.stderr)
    print('\n解説の値を、実際の出力へ直してください。実行結果を書かないなら、'
          '期待値を行コメントへ置かずに文章で説明します。', file=sys.stderr)
    return 1


def collect():
    """解説から「実行するコード1つ + その中の期待値いくつか」の単位を集める。"""
    units, skipped = [], []
    for path in sorted(CONTENT.glob('ch*.json')):
        chapter = json.loads(path.read_text(encoding='utf-8'))
        for lesson in chapter['lessons']:
            text = lesson.get('explanation', '') or ''
            blocks = [(m.group(1), m.group(2)) for m in BLOCK.finditer(text)]
            for index, (language, body) in enumerate(blocks):
                if language != 'java':
                    continue
                following = blocks[index + 1] if index + 1 < len(blocks) else None
                unit = read_block(lesson['id'], body, following, skipped)
                if unit:
                    units.append(unit)
    return units, skipped


def read_block(lesson_id, body, following, skipped):
    unsafe = [word for word in UNSAFE if word in body]

    # C: 完結したプログラム + 直後の出力ブロック
    if 'class Main' in body and 'void main' in body:
        if not (following and following[0] in ('', 'text', 'console', 'output')):
            return None
        if unsafe:
            skipped.append({'lesson': lesson_id, 'source': 'class Main のプログラム',
                            'reason': f'実行しない語を含む: {unsafe[0]}'})
            return None
        return {'lesson': lesson_id, 'source': 'class Main のプログラム全体', 'body': body,
                'whole': True, 'claims': [{'expected': following[1].strip(),
                                           'source': 'class Main のプログラム全体'}]}

    # A / B: 行コメントに期待値を書いた式
    lines, claims, depth = [], [], 0
    for line in body.split('\n'):
        claim = read_claim(line)
        if claim is None:
            if DECLARATION.search(line):
                return None                      # 宣言を含む断片は動かない
            # 期待値の付いていない出力は、前の期待値の区間へ混ざる。捨てる印で区切る。
            if 'System.out.' in line:
                lines.append(f'{line[:len(line) - len(line.lstrip())]}'
                             f'System.out.println("{MARK}-1");')
            lines.append(line)
            depth += line.count('{') - line.count('}')
            continue
        expression, expected, printing, indent = claim
        if unsafe:
            skipped.append({'lesson': lesson_id, 'source': line.strip(),
                            'reason': f'実行しない語を含む: {unsafe[0]}'})
            lines.append(line)
            continue
        if depth > 0 or LOOP_OR_BRANCH.match(line):
            skipped.append({'lesson': lesson_id, 'source': line.strip(),
                            'reason': 'くり返しや分岐の中なので、1回の値として比べられない'})
            lines.append(line)
            continue
        index = len(claims)
        lines.append(f'{indent}System.out.println("{MARK}{index}");')
        lines.append(line if printing else f'{indent}System.out.println({expression});')
        claims.append({'expected': expected, 'source': line.strip()})

    if not claims:
        return None
    text = '\n'.join(x for x in lines if not x.strip().startswith(('import ', 'package ')))
    if text.count('{') != text.count('}') or text.count('(') != text.count(')'):
        skipped.append({'lesson': lesson_id, 'source': claims[0]['source'],
                        'reason': 'かっこが閉じていない断片なので実行できない'})
        return None
    return {'lesson': lesson_id, 'source': claims[0]['source'], 'body': text,
            'whole': False, 'claims': claims}


def value_of(comment):
    """行コメントの先頭にある値だけを取り出す。読めなければ None。

    教材は `// 3     わる` のように、値のうしろへ**2つ以上の空白**を置いて説明を続ける。
    そこで区切った前半だけを値として読む。`// 10日後` `// import 不要` のように区切りが無く
    日本語が続くものは、値ではなく説明文なので読まない（読むと誤検出になる）。
    """
    parts = re.split(r'\s{2,}', comment.strip(), 1)
    head, rest = parts[0].strip(), (parts[1] if len(parts) > 1 else '')
    if JAPANESE.search(head):
        return None                     # 値の位置に日本語がある。値ではなく説明文
    if not rest and JAPANESE.search(comment):
        return None                     # 区切りが無いのに日本語がある。`// 10日後` のような説明
    if not head or not VALUE_COMMENT.match(head):
        return None
    if re.search(r'@[0-9a-f]{4,}', head):
        return None                     # ハッシュは実行ごとに変わる
    if head.upper() in ('OK', 'NG', 'TODO') or head.endswith(('(', ',', '+', '-', '*', '/')):
        return None
    return head


def read_claim(line):
    """(式, 期待値, すでに出力している行か, 字下げ) を返す。読めなければ None。"""
    printing = PRINT_LINE.match(line)
    matched = printing or EXPRESSION_LINE.match(line)
    if not matched:
        return None
    indent, expression = matched.group(1), matched.group(2).strip()
    comment = value_of(matched.group(3))
    if comment is None:
        return None                                  # 値として読めない注釈
    if not printing:
        if '=' in expression and not re.search(r'[=!<>]=', expression):
            return None                              # 代入は式として出力できない
        if any(word in expression for word in MUTATING):
            return None                              # 状態を変える式は値を比べても意味がない
        if expression.endswith(('{', '}')):
            return None
    return expression, comment, bool(printing), indent


def run_all(units, workdir, skipped):
    names = []
    for index, unit in enumerate(units):
        name = f'Snippet{index}'
        names.append(name)
        if unit['whole']:
            source = re.sub(r'\bclass\s+Main\b', f'class {name}', unit['body'], count=1)
        else:
            source = (IMPORTS + f'\npublic class {name} {{\n'
                      '    public static void main(String[] args) throws Exception {\n'
                      + unit['body'] + '\n    }\n}\n')
        (workdir / f'{name}.java').write_text(source, encoding='utf-8')

    broken = compile_snippets(workdir, names)
    alive = [(i, n) for i, n in enumerate(names) if n not in broken]
    write_runner(workdir, alive)
    if subprocess.run(['javac', '-nowarn', '-cp', str(workdir), '-d', str(workdir),
                       str(workdir / 'Runner.java')], capture_output=True).returncode != 0:
        raise SystemExit('検査用の起動コードをコンパイルできませんでした')

    run = subprocess.run(['java', '-cp', str(workdir), 'Runner'],
                         capture_output=True, text=True, timeout=300)
    outputs = split_output(run.stdout)

    results = []
    for index, unit in enumerate(units):
        if names[index] in broken:
            skipped.append({'lesson': unit['lesson'], 'source': unit['source'],
                            'reason': 'コードの断片なのでコンパイルできない'})
            continue
        segments = outputs.get(index, {})
        for claim_index, claim in enumerate(unit['claims']):
            key = 'whole' if unit['whole'] else claim_index
            actual = segments.get(key)
            if actual is None:
                skipped.append({'lesson': unit['lesson'], 'source': claim['source'],
                                'reason': 'その行まで実行が届かなかった'})
                continue
            if isinstance(actual, list):            # 印が複数回出た（くり返しの中）
                skipped.append({'lesson': unit['lesson'], 'source': claim['source'],
                                'reason': '同じ行が複数回実行された'})
                continue
            results.append({'lesson': unit['lesson'], 'source': claim['source'],
                            'expected': claim['expected'], 'actual': actual,
                            'ok': same(actual, claim['expected'])})
    return results


def compile_snippets(workdir, names):
    """まとめてコンパイルし、落ちたものの名前を返す（断片は落ちるので切り離して再試行する）。"""
    broken = set()
    for _ in range(8):
        targets = [str(workdir / f'{n}.java') for n in names if n not in broken]
        if not targets:
            break
        result = subprocess.run(['javac', '-nowarn', '-d', str(workdir), *targets],
                                capture_output=True, text=True)
        if result.returncode == 0:
            break
        failed = {pathlib.Path(m).stem
                  for m in re.findall(r'([^\s:]*Snippet\d+\.java)', result.stderr)}
        if not failed:
            broken |= {n for n in names if n not in broken}
            break
        broken |= failed
    return broken


def write_runner(workdir, alive):
    runner = ['public class Runner {', '    public static void main(String[] a) {']
    for index, name in alive:
        runner.append(f'        System.out.println("\\u0001unit{index}");')
        runner.append(f'        try {{ {name}.main(new String[0]); }}')
        runner.append(f'        catch (Throwable t) {{ System.out.println("\\u0002"'
                      f' + t.getClass().getSimpleName()); }}')
    runner += ['    }', '}']
    (workdir / 'Runner.java').write_text('\n'.join(runner), encoding='utf-8')


def split_output(stdout):
    """印で区切って {単位の番号: {期待値の番号: 実際の出力}} にする。"""
    units, unit, key, buffer = {}, None, None, []

    def flush():
        if unit is None or key is None or key == -1:
            return
        text = '\n'.join(buffer).strip()
        store = units.setdefault(unit, {})
        store[key] = [store[key], text] if key in store else text

    for line in stdout.split('\n'):
        if line.startswith(MARK + 'unit'):
            flush()
            unit, key, buffer = int(line[len(MARK) + 4:]), 'whole', []
        elif line.startswith(MARK):
            flush()
            key, buffer = int(line[1:]), []
        elif line.startswith(FAIL):
            buffer.append(line[1:])
        else:
            buffer.append(line)
    flush()
    return units


def same(actual, expected):
    def normalize(text):
        return '\n'.join(line.rstrip() for line in text.strip().split('\n'))
    actual, expected = normalize(actual), normalize(expected)
    if actual == expected:
        return True
    # 空文字を `""` と書く書き方を許す
    return expected == '""' and actual == ''


if __name__ == '__main__':
    sys.exit(main())
