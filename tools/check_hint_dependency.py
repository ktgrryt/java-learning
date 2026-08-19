"""模範解答が要求する道具が、ヒントを開かないと分からない状態になっていないかを検査する。

tools/check-hint-dependency.sh から呼ばれる。

  python3 tools/check_hint_dependency.py          … 検査する
  python3 tools/check_hint_dependency.py --list   … 実践編以降の分も参考として出す

## なぜ必要か

`5-2`（画面の番号。`64-2` の追加問題）は、数字かどうかを確かめる問題なのに
`input.matches("-?[0-9]+")` という書き方がヒントにしか無く、課題文が「ヒントを見てください」と
書いていた。ヒントは開くと報酬が減るうえ、正規表現はこの時点のカリキュラムに出ていないので、
**初見の学習者は開かないと着手できない**。2026-08-19に利用者から指摘があり、解説の
「落ちる前に確かめる」へ `matches` を入れて、課題文からも読めるようにした。

`check_case_fairness.py` が「**必要な文言**がヒントにしか無い」ことを見ているのに対し、
ここは「**必要な道具**（メソッド・クラス）がヒントにしか無い」ことを見る。同じ穴の裏表である。

## 判定

Java基礎編の問題について、模範解答が呼ぶメソッド名と使うクラス名を集め、
**その問題までに学習者が読める文章**に一度も出てこないものを失敗とする。読めるものは4つ。

  それまでの全レッスンの解説・サンプル・確認クイズ
  それまでの問題の課題文・ひな形・表示ケース・模範解答（解いた後に読み返せる）
  その問題自身のレッスンの解説・サンプル・確認クイズ
  その問題自身の課題文・ひな形・表示ケース・観点（rubric）

**ヒントは数えない。** 開くと報酬が減るので、そこにしか無い道具は「無い」のと同じに扱う。

見るのはJava基礎編（`java-se`）だけである。実践編以降は道具を渡したうえで判断を測る作りで、
`comparingLong` や `parseLong` のように既に使っているクラスの別メソッドを自分で探すのは
学習の一部になっている（`check_starter_imports.py` が編を絞っているのと同じ理由）。
`--list` を付けると、その分も参考として出す（失敗にはしない）。

## 直し方

解説へその道具を書く（推奨。書く先は到達目標と噛み合う回を選ぶ）／課題文へ書き方を書く／
その時点までの道具で解ける課題に変える。ヒントは「開くと近道になる」ものに留める。
意図してヒントだけに置くものは ALLOWED へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
# 見る編。ここに無い編は、道具を渡したうえで判断を測る作りなので対象にしない。
PARTS = {'java-se'}
CALL = re.compile(r'\.([a-zA-Z_]\w*)\s*\(')
TYPE = re.compile(r'\b([A-Z][A-Za-z0-9]{2,})\b')
DECLARED = re.compile(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)')
STRING = re.compile(r'"(?:[^"\\]|\\.)*"')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')

# 意図してヒントだけに置く道具。足すときは理由を必ず書く。
#   (問題ID, 道具名): 理由
ALLOWED = {
}


def main():
    listing = '--list' in sys.argv[1:]
    failures, reference = [], []
    seen = ''
    for part, chapter, pid, lesson, task in curriculum_order():
        own = readable(lesson, task)
        known = seen + '\n' + own
        missing = [name for name in tools_used(task.get('solution') or '')
                   if name not in known and not allowed(pid, name)]
        if missing:
            hints = '\n'.join(task.get('hints') or [])
            where = 'ヒントにしかありません' if all(n in hints for n in missing) else 'どこにもありません'
            (failures if part in PARTS else reference).append((chapter, pid, missing, where))
        # 次の問題からは、この問題の模範解答も読める（解いた後に読み返せる）。
        # ヒントは足さない ― 開くと報酬が減るので、読んでいるとは限らない。
        seen = known + '\n' + (task.get('solution') or '')

    print(f'Java基礎編の問題について、模範解答が要求する道具がヒントの外にあるかを見ました'
          f'（意図してヒントだけに置く{len(ALLOWED)}件は対象外）。')
    if listing and reference:
        print(f'\n参考（実践編以降。道具を渡したうえで判断を測る作りなので失敗にしません）:')
        for chapter, pid, missing, where in reference:
            print(f'  {chapter:8} {pid:10} {", ".join(missing)} … {where}')
    if not failures:
        print('  ヒントを開かなくても、使う道具が分かる形になっています。')
        return 0

    print(f'\nヒントを開かないと着手できない問題が{len(failures)}件あります。', file=sys.stderr)
    for chapter, pid, missing, where in failures:
        print(f'  {chapter} {pid}: {", ".join(missing)} … {where}', file=sys.stderr)
    print('\n直し方は3つです。解説へその道具を書く／課題文へ書き方を書く／'
          'その時点までの道具で解ける課題に変える。'
          '意図してヒントだけに置くなら ALLOWED へ理由付きで入れる。', file=sys.stderr)
    return 1


def tools_used(solution):
    """模範解答が使っている道具（呼ぶメソッド名と、外から借りるクラス名）。"""
    code = STRING.sub('""', LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', solution)))
    declared = set(DECLARED.findall(code))          # 解答の中で宣言する型は「借りる道具」ではない
    return sorted(set(CALL.findall(code)) | (set(TYPE.findall(code)) - declared))


def readable(lesson, task):
    """その問題を解く前に学習者が読める文章。ヒントは入れない。"""
    parts = [lesson.get('explanation') or '']
    for sample in lesson.get('samples') or []:
        parts += [sample.get('code') or '', sample.get('caption') or '']
    for quiz in lesson.get('quiz') or []:
        parts += [quiz.get('question') or '', quiz.get('explanation') or ''] \
            + list(quiz.get('choices') or [])
    parts += [task.get('task') or '', task.get('starterCode') or '',
              task.get('starterContent') or '']
    for case in task.get('visibleCases') or []:
        parts += [case.get('label') or '', str(case.get('expected') or '')]
    for item in task.get('rubric') or []:
        parts.append(json.dumps(item, ensure_ascii=False))
    return '\n'.join(parts)


def allowed(pid, name):
    return ('*', name) in ALLOWED or (pid, name) in ALLOWED


def curriculum_order():
    """カリキュラム順に [(編id, 章id, 問題ID, レッスン, 問題)] を並べる。"""
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
                                     f"{lesson['id']}#{number}", lesson, task))
    return problems


if __name__ == '__main__':
    sys.exit(main())
