"""content/*.json の中の教材コードを、詰め込まれたものだけ整形して書き戻す。

tools/format-content-code.sh から呼ばれる。第1引数は整形器を含むクラスパス。

  python3 tools/format_content_code.py build/classes [--check]

--check を付けると書き戻さず、対象件数と検査結果だけを出す。

判定と整形は jq.format.JavaSnippetFormatter に任せる（アプリが表示に使うのと同じもの）。
Pythonが受け持つのはJSONの読み書きだけで、整形結果は必ず次の2つを満たすことを確かめる:

  * トークン列が整形前後で一致する（空白と改行以外は変わっていない）
  * もう一度整形しても結果が変わらない（冪等）

どちらかが崩れたら中止して、1件も書き戻さない。
"""
import json
import glob
import subprocess
import sys

HEADER = '@@JQFMT '


def main():
    if len(sys.argv) < 2:
        sys.exit('使い方: python3 tools/format_content_code.py <classpath> [--check]')
    classpath = sys.argv[1]
    check_only = '--check' in sys.argv[2:]

    plans = []
    for path in sorted(glob.glob('content/ch*.json')):
        data = json.load(open(path, encoding='utf-8'))
        slots = list(collect(data))
        if slots:
            plans.append((path, data, slots))

    total = sum(len(slots) for _, _, slots in plans)
    if total == 0:
        print('詰め込まれたコードはありません。')
        return

    # 整形とその検査はまとめて1回のJVMで済ませる
    codes = [code for _, _, slots in plans for _, code, _ in slots]
    results = format_all(classpath, codes)

    bad = []
    index = 0
    for path, data, slots in plans:
        for setter, _, label in slots:
            formatted, header = results[index]
            index += 1
            if 'tokens=same' not in header or 'idempotent=yes' not in header:
                bad.append((label, header))
            elif not check_only:
                setter(formatted)

    if bad:
        print(f'⚠️ 検査に通らなかった {len(bad)} 件があるので、何も書き戻していません:', file=sys.stderr)
        for label, header in bad[:40]:
            print(f'   {label}: {header}', file=sys.stderr)
        sys.exit(1)

    if check_only:
        print(f'対象 {total} 件（検査のみ）。トークン列は全件一致、整形は全件冪等。')
        return

    for path, data, _ in plans:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print(f'{total} 件を整形し、{len(plans)} ファイルを更新しました。')


def collect(data):
    """(書き戻す関数, いまのコード, 見出し) を、詰め込まれているものだけ返す。"""
    for lesson in data['lessons']:
        for sample in lesson.get('samples') or []:
            if is_compact(sample.get('code')):
                yield (lambda v, o=sample: o.__setitem__('code', v),
                       sample['code'], f"{lesson['id']} sample")
        tasks = [(lesson['id'], lesson)] + [
            (f"{lesson['id']}-x{i + 1}", extra)
            for i, extra in enumerate(lesson.get('extraTasks') or [])]
        for task_id, task in tasks:
            if not task.get('task'):
                continue
            # artifact の solution は XML や Dockerfile であり、Java整形器へ渡してはいけない。
            if task.get('type') in ('artifact', 'project', 'runtime-lab'):
                continue
            for key in ('starterCode', 'solution'):
                if is_compact(task.get(key)):
                    yield (lambda v, o=task, k=key: o.__setitem__(k, v),
                           task[key], f'{task_id} {key}')


def format_all(classpath, snippets):
    """NUL区切りで整形器へ渡し、(整形後, 検査ヘッダー) のリストを返す。"""
    proc = subprocess.run(
        ['java', '-Dfile.encoding=UTF-8', '-cp', classpath, 'FormatContentCode'],
        input='\0'.join(snippets).encode('utf-8'), capture_output=True)
    if proc.returncode != 0:
        sys.exit('整形器の呼び出しに失敗しました:\n'
                 + proc.stderr.decode('utf-8', 'replace')[:3000])
    records = proc.stdout.decode('utf-8').split('\0')
    if len(records) != len(snippets):
        sys.exit(f'整形器の応答数が合いません: {len(records)} != {len(snippets)}')
    out = []
    for record in records:
        header, _, body = record.partition('\n')
        if not header.startswith(HEADER):
            sys.exit(f'整形器の応答が読めません: {header[:80]!r}')
        out.append((body, header))
    return out


def block_braces(line):
    """ブロックを開く `{` の数。配列初期化子・ラムダ・式の中のものは数えない。"""
    count = 0
    for index, ch in enumerate(line):
        if ch != '{':
            continue
        before = line[:index].rstrip()
        if before.endswith(('=', ',', '{', '(', '->', '+', ']')):
            continue
        count += 1
    return count


def is_compact(code):
    """1行に複数の文やブロックが詰め込まれているか（Java側 isCompact と同じ判定）。

    **ブロックの `{` が2つ以上ある行は、長さや文の数によらず詰め込み。** 宣言だけを詰めた
    `public class Main {public static void main(String[] args){}}` は文が0個なので、
    長さの条件だけでは拾えず、実際に3件見落としていた。
    """
    if not code or not code.strip():
        return False
    for line in blank_out_literals(code).split('\n'):
        statements = blocks = depth = 0
        for ch in line:
            if ch in '([':
                depth += 1
            elif ch in ')]':
                depth -= 1
            elif ch == ';' and depth <= 0:
                statements += 1
            elif ch == '{':
                blocks += 1
        if block_braces(line) >= 2:
            return True
        if len(line) > 100 and (statements >= 2 or blocks >= 2):
            return True
        if len(line) > 60 and blocks >= 2 and statements >= 1:
            return True
    return False


def blank_out_literals(src):
    """文字列・文字・コメントの中身を空白へ置き換える（長さと改行は保つ）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            while i < n and src[i] != '\n':
                out.append(' ')
                i += 1
        elif c == '/' and nxt == '*':
            end = src.find('*/', i + 2)
            stop = n if end < 0 else end + 2
            while i < stop:
                out.append('\n' if src[i] == '\n' else ' ')
                i += 1
        elif c in '"\'':
            out.append(' ')
            i += 1
            while i < n and src[i] != c and src[i] != '\n':
                step = 2 if src[i] == '\\' else 1
                out.append(' ' * step)
                i += step
            if i < n and src[i] == c:
                out.append(' ')
                i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)


main()
