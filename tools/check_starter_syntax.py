"""初めて習う構文を、その構文を教える問題のひな形が先に書いていないかを検査する。

tools/check-starter-syntax.sh から呼ばれる。

  python3 tools/check_starter_syntax.py          … 検査する
  python3 tools/check_starter_syntax.py --list   … 構文ごとの判定を全部出す

## なぜ必要か

`4-5`（switch で値ごとに分ける）は `switch` を初めて習うレッスンなのに、ひな形へ
`switch (day) {` が最初から書かれていて、学習者が書くのは `case` の中だけだった。
**その構文を教える章を終えても、外枠を一度も打っていない**状態になる。
2026-08-19に利用者から指摘があり、14問（第2・4・6・9・10・11・13・15・26・32・38章）を
そろえた。`import` について同じことを見ている `check_starter_imports.py` の構文版である。

## 判定

構文ごとに「学習者が最初に書く問題」を下の表で決め、3つを確かめる。

  失敗1  その問題のひな形（コメントを除いた本文）に、その構文が書かれている
  失敗2  その問題の模範解答に、その構文が出てこない（表の紐づけが古い）
  失敗3  カリキュラム順でそれより前の問題のひな形が、その構文を先に書いている

コメントは「ここに書こう」の案内なので数えない（`// class Dog extends Animal を書く`
のように、書く形を示すのは構わない）。2問目以降は対象外で、同じ外枠を何度も
打ち直させない——この線引きは `import` の側と同じにしてある
（`docs/guide.md`「初めて習う構文は、ひな形が先に書かない」）。

意図してひな形に残すものは `ALLOWED` へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')

# 構文名, 正規表現, 学習者が最初に書く問題ID（`レッスンID#問題番号`）
CONSTRUCTS = [
    ('if',                 r'\bif\s*\(',                            '4-1#1'),
    ('else',               r'\belse\b',                             '4-1#1'),
    ('else if',            r'\belse\s+if\b',                        '4-2#1'),
    ('&&',                 r'&&',                                   '4-3#1'),
    ('||',                 r'\|\|',                                 '4-3#2'),
    ('equals',             r'\.equals\s*\(',                        '4-4#1'),
    ('switch',             r'\bswitch\s*[({]',                      '4-5#1'),
    ('for',                r'\bfor\s*\([^:)]*;',                    '5-1#1'),
    ('while',              r'\bwhile\s*\(',                         '5-3#1'),
    ('break',              r'\bbreak\b',                            '5-4#1'),
    ('continue',           r'\bcontinue\b',                         '5-4#1'),
    ('do-while',           r'\bdo\s*\{',                            '5-6#1'),
    ('配列リテラル',          r'\]\s*\w+\s*=\s*\{',                     '6-1#1'),
    ('new 配列',            r'\bnew\s+\w+\s*\[',                      '6-1#2'),
    ('拡張for',             r'\bfor\s*\([^;)]*:\s*',                  '6-3#1'),
    ('2次元配列',            r'\]\s*\[',                               '6-5#1'),
    ('メソッド定義',          r'\bstatic\s+(?!void\s+main\b)[\w<>\[\], ]+\s+\w+\s*\([^)]*\)\s*\{', '7-1#1'),
    ('void メソッド',        r'\bstatic\s+void\s+(?!main\b)\w+\s*\(',   '7-2#1'),
    ('クラス定義',           r'\bclass\s+(?!Main\b)\w+',                '8-1#1'),
    ('コンストラクタ',        r'^\s{4}[A-Z]\w*\s*\([^)]*\)\s*\{',         '8-2#1'),
    ('this',               r'\bthis\b',                             '8-2#1'),
    ('static フィールド',     r'\bstatic\s+(?:final\s+)?\w+\s+\w+\s*(?:=|;)', '8-5#1'),
    ('private',            r'\bprivate\b',                          '9-1#1'),
    ('final フィールド',      r'\bfinal\b',                            '9-2#1'),
    ('extends',            r'\bextends\b',                          '10-1#1'),
    ('@Override',          r'@Override',                            '10-2#1'),
    ('super',              r'\bsuper\b',                            '10-3#1'),
    ('instanceof',         r'\binstanceof\b',                       '10-4#1'),
    ('abstract',           r'\babstract\b',                         '11-3#1'),
    ('interface',          r'\binterface\b',                        '12-1#1'),
    ('implements',         r'\bimplements\b',                       '12-1#1'),
    ('default メソッド',      r'\bdefault\s+\w',                       '12-3#1'),
    ('try-catch',          r'\btry\s*\{',                           '13-1#1'),
    ('catch',              r'\bcatch\s*\(',                         '13-1#1'),
    ('throw',              r'\bthrow\s+new\b',                      '13-3#1'),
    ('finally',            r'\bfinally\b',                          '13-3#1'),
    ('throws',             r'\bthrows\b',                           '13-4#1'),
    ('try-with-resources', r'\btry\s*\(',                           '13-5#1'),
    ('StringBuilder',      r'\bnew\s+StringBuilder\b',              '14-4#1'),
    ('List',               r'\bList\s*<',                           '16-1#1'),
    ('Map',                r'\bMap\s*<',                            '16-4#1'),
    ('ラムダ',              r'(?:\)|\w)\s*->',                        '17-1#1'),
    ('メソッド参照',          r'\w\s*::\s*\w',                          '17-3#2'),
    ('stream',             r'\.stream\s*\(',                        '17-5#1'),
    ('var',                r'\bvar\s+\w+\s*=',                      '18-1#1'),
    ('switch 式',           r'=\s*switch\s*\(',                      '18-2#1'),
    ('テキストブロック',        r'"""',                                 '18-3#1'),
    ('record',             r'\brecord\s+\w+',                       '18-4#1'),
    ('enum',               r'\benum\s+\w+',                         '18-5#1'),
    ('import static',      r'\bimport\s+static\b',                  '20-1#1'),
    ('synchronized',       r'\bsynchronized\b',                     '32-2#2'),
    ('ジェネリクスの型引数',      r'\b(?:class|interface|record)\s+\w+\s*<[A-Z]', '41-1#1'),
    ('sealed',             r'\bsealed\b',                           '43-1#1'),
    ('@Test',              r'@Test\b',                              '45-2#1'),
]

# 初出より前のひな形に残していてよいもの。足すときは理由を必ず書く。
#   (問題ID, 構文名): 理由。問題IDを `*` にすると全問が対象。
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    problems = curriculum_order()
    index = {pid: (task, chapter) for pid, task, chapter in problems}

    failures = []
    for name, pattern, pid in CONSTRUCTS:
        regex = re.compile(pattern, re.M)
        if pid not in index:
            failures.append((pid, name, '表が指す問題がありません'))
            continue
        task, chapter = index[pid]
        verdicts = judge(name, regex, pid, task, problems)
        if listing:
            mark = 'NG ' if verdicts else 'OK '
            print(f'  {mark} {chapter} {pid}: {name}'
                  + (' … ' + ' / '.join(verdicts) if verdicts else ''))
        for verdict in verdicts:
            failures.append((pid, name, verdict))

    print(f'構文{len(CONSTRUCTS)}件について、学習者が最初に書く問題のひな形を見ました'
          f'（意図して残す{len(ALLOWED)}件は対象外）。')
    if not failures:
        print('  初めて習う構文は、学習者が自分で書く形になっています。')
        return 0

    print(f'\nひな形が先に書いてしまっている構文が{len(failures)}件あります。', file=sys.stderr)
    for pid, name, verdict in failures:
        print(f'  {pid}: {name} … {verdict}', file=sys.stderr)
    print('\n直し方は3つです。ひな形からその構文を外して書く場所をコメントで示す／'
          '表の「最初に書く問題」を実態へ合わせる／意図して残すなら ALLOWED へ理由付きで入れる。',
          file=sys.stderr)
    return 1


def judge(name, regex, pid, task, problems):
    """その構文について、ひな形が先に書いてしまっている理由を並べる。"""
    verdicts = []
    if regex.search(without_comments(task['starterCode'])):
        verdicts.append('ひな形の本文が使っています')
    if not regex.search(task.get('solution') or ''):
        verdicts.append('模範解答に出てこません（表の紐づけが古い）')
    for earlier, earlier_task, chapter in problems:
        if earlier == pid:
            break
        if allowed(earlier, name):
            continue
        if regex.search(without_comments(earlier_task['starterCode'])):
            verdicts.append(f'{earlier} のひな形が先に書いています')
            break
    return verdicts


def allowed(pid, name):
    return ('*', name) in ALLOWED or (pid, name) in ALLOWED


def curriculum_order():
    """カリキュラム順に [(問題ID, 問題, 章id)] を並べる。"""
    manifest = json.load(open(CONTENT / 'manifest.json', encoding='utf-8'))
    problems = []
    for part in manifest['parts']:
        for filename in part['chapters']:
            data = json.load(open(CONTENT / filename, encoding='utf-8'))
            for lesson in data['lessons']:
                tasks = ([lesson] if lesson.get('task') else []) \
                    + list(lesson.get('extraTasks') or [])
                for number, task in enumerate(tasks, start=1):
                    if not task.get('starterCode'):
                        continue
                    problems.append((f"{lesson['id']}#{number}", task, data['id']))
    return problems


def without_comments(code):
    """コメントを除いたコード。コメントは「ここに書こう」の案内なので数えない。"""
    return LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', code))


if __name__ == '__main__':
    sys.exit(main())
