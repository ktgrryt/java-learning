"""道具を初めて使う問題のひな形が、その道具の `import` と準備行を先に書いていないかを検査する。

tools/check-starter-imports.sh から呼ばれる。

  python3 tools/check_starter_imports.py          … 検査する
  python3 tools/check_starter_imports.py --list   … 道具ごとの初出と判定を全部出す

## なぜ必要か

`3-2`（キーボードから数を受け取る）は `Scanner` の使い方そのものが到達目標なのに、
ひな形へ `import java.util.Scanner;` と `Scanner sc = new Scanner(System.in);` が
最初から書かれていた。**第3章を終えても、最初の書き方を一度も打っていない**状態になり得る。

同じことをすでに学習者に書かせている章もあった（`6-5#2` の `Arrays`、`20-1` の
`import static`）。ルールが無いために章ごとにばらついていたので、判定を機械で固定する。

## 判定

到達目標がバッククォートで名指ししたクラス名について、**その目標を測る問題のうち
カリキュラム順で最初の1問**（初出）を決め、その問題のひな形を見る。

  失敗1  `import <パッケージ>.<クラス>;` がひな形に書かれている
  失敗2  コメントを除いたひな形の本文にそのクラス名が出てくる（準備行を渡している）

2問目以降は対象外である。同じ外枠を何度も打ち直させないため、**書かせるのは道具ごとに1回**
という決めにしてある（`docs/guide.md`「道具の import は、初めて使う問題では書かない」）。

見ないものが2つある。

  ワイルドカード（`import java.util.*;`）だけの場合。どのクラスを渡しているかは
  パッケージの表を持たないと分からず、実践編以降のひな形はこの書き方を前提にしている。
  `java-se`（Java基礎編）以外の編。道具を渡したうえで判断を測る作りなので、初出の考え方が合わない。

直し方は3つ。①ひな形からその `import` と準備行を外し、書く場所をコメントで示す
②紐づけ（`objectiveIds`）を直す ③目標文を実態へ合わせる。
意図してひな形に残すものは `ALLOWED` へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
# 検査する編。ここに無い編は、道具を渡したうえで判断を測る作りなので対象にしない。
PARTS = {'java-se'}
# クラス名らしい語だけを道具として扱う。大文字始まりで、小文字を1つ以上含み、`_` を含まないもの。
# `APPEND` や `MAX_VALUE` のような定数は「import する道具」ではないので、この形で外れる。
CLASS_NAME = re.compile(r'^[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*$')
# import が要らないので、ひな形に書かれていて当たり前のクラス（`java.lang` は自動で使える）。
NO_IMPORT_NEEDED = {
    'String', 'StringBuilder', 'StringBuffer', 'Object', 'Class', 'Record', 'Enum',
    'Integer', 'Long', 'Double', 'Float', 'Short', 'Byte', 'Character', 'Boolean',
    'Number', 'Math', 'System', 'Thread', 'Runnable', 'Comparable', 'Iterable',
    'CharSequence', 'AutoCloseable', 'Throwable', 'Error', 'Exception',
    'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
    'NullPointerException', 'NumberFormatException', 'ArithmeticException',
    'IndexOutOfBoundsException', 'ArrayIndexOutOfBoundsException',
    'StringIndexOutOfBoundsException', 'UnsupportedOperationException',
    'ClassCastException', 'InterruptedException', 'Override',
}
IMPORT_LINE = re.compile(r'^\s*import\s+(?:static\s+)?([\w.$]+)\s*;', re.M)
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')

# 初出のひな形に残していてよいもの。足すときは理由を必ず書く。
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    problems = curriculum_order()
    first_use = first_use_per_class(problems)

    failures, skipped = [], []
    for name, (pid, task, chapter) in sorted(first_use.items()):
        if name in NO_IMPORT_NEEDED:
            skipped.append(name)
            if listing:
                print(f'  --  {chapter} {pid}: {name} … java.lang なので import が要らない')
            continue
        verdict = judge(name, task)
        if listing:
            print(f'  {"NG " if verdict else "OK "} {chapter} {pid}: {name}'
                  f'{" … " + verdict if verdict else ""}')
        if verdict and (pid, name) not in ALLOWED:
            failures.append((pid, name, verdict))

    print(f'到達目標が名指しした道具{len(first_use) - len(skipped)}件について、初めて使う問題の'
          f'ひな形を見ました（java.lang の{len(skipped)}件と、意図して残す{len(ALLOWED)}件は'
          f'対象外）。')
    if not failures:
        print('  初めて使う問題では、import と準備行を学習者が書く形になっています。')
        return 0

    print(f'\nひな形が先に書いてしまっている道具が{len(failures)}件あります。', file=sys.stderr)
    for pid, name, verdict in failures:
        print(f'  {pid}: {name} … {verdict}', file=sys.stderr)
    print('\n直し方は3つです。ひな形からその import と準備行を外して書く場所をコメントで示す／'
          '紐づけ（objectiveIds）を直す／目標文を実態へ合わせる。', file=sys.stderr)
    return 1


def curriculum_order():
    """カリキュラム順に [(問題ID, 問題, 章id, その問題が測る目標文の一覧)] を並べる。"""
    manifest = json.load(open(CONTENT / 'manifest.json', encoding='utf-8'))
    problems = []
    for part in manifest['parts']:
        if part['id'] not in PARTS:
            continue
        for filename in part['chapters']:
            data = json.load(open(CONTENT / filename, encoding='utf-8'))
            objectives = {o['id']: o['text'] for o in data.get('objectives', [])}
            for lesson in data['lessons']:
                base = lesson.get('objectiveIds') or []
                tasks = ([lesson] if lesson.get('task') else []) \
                    + list(lesson.get('extraTasks') or [])
                for number, task in enumerate(tasks, start=1):
                    if not task.get('starterCode'):
                        continue
                    texts = [objectives.get(oid, '') for oid in (task.get('objectiveIds') or base)]
                    problems.append((f"{lesson['id']}#{number}", task, data['id'], texts))
    return problems


def first_use_per_class(problems):
    """クラス名 -> その道具を測る最初の問題。"""
    first = {}
    for pid, task, chapter, texts in problems:
        for text in texts:
            for term in code_spans(text):
                if CLASS_NAME.match(term):
                    first.setdefault(term, (pid, task, chapter))
    return first


def judge(name, task):
    """ひな形がその道具を渡してしまっているなら理由。渡していなければ空文字。"""
    starter = task['starterCode']
    for imported in IMPORT_LINE.findall(starter):
        if imported.rsplit('.', 1)[-1] == name:
            return f'ひな形に `import {imported};` が書かれています'
    if re.search(r'\b' + re.escape(name) + r'\b', without_comments(starter)):
        return 'ひな形の本文が使っています（準備行を渡している）'
    return ''


def without_comments(code):
    """コメントを除いたコード。コメントは「ここに書こう」の案内なので数えない。"""
    return LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', code))


def code_spans(text):
    """`…` で囲まれた部分だけを順に返す（check_objective_terms.py と同じ取り方）。"""
    parts = text.split('`')
    return [parts[i] for i in range(1, len(parts), 2)]


if __name__ == '__main__':
    sys.exit(main())
