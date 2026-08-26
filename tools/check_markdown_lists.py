"""リスト項目が2行以上に分かれていないかを検査する。

tools/check-markdown-lists.sh から呼ばれる。

  python3 tools/check_markdown_lists.py          … 検査する
  python3 tools/check_markdown_lists.py --list    … 見た項目の数を場所ごとに出す

## なぜ必要か

`web/markdown.js` の箇条書き・番号付きリストは、**同じ形の行が続くあいだ集める**だけで、
1行が1項目である。続き行（2スペース字下げ）は項目に入らず、リストを抜けた**別の段落**として
左端に描かれる ―― 文の途中から始まる断片が本文へ紛れて見える。

2026-08-26に `7-4`（画面 `8-4`）の番号付きリストで見つかった。`2.` の続き行
「`a` は参照のコピーなので…」が独立した段落になっていた。同じ形が教材に59件あった。
**ソースだけ見ると正しく見える**ので、機械で見張る。

直し方は続き行を項目の行へつなぐだけである（全角どうしなら詰め、片方が半角なら空白を1つ）。
行が長くなるのは構わない。分けたいなら、続き行も `-` で始めて独立した項目にする。

## 見る場所

画面が `renderMarkdown` に渡すフィールドだけを見る（`web/app.js`）――
解説・課題文・ヒント・クイズの問と選択肢と解説・サンプルの見出し。
`starterCode` や `solution` は**コードなので見ない** ―― Javadocの `* ` や YAML の `- ` が
リストの記号と同じ形をしているため、混ぜると「続き行」を誤検出する。
```で囲んだ範囲も同じ理由で素通しする。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
# 画面がmarkdownとして描くフィールド（web/app.js の renderMarkdown 呼び出しと対）
MD_KEYS = {'explanation', 'task', 'hints', 'question', 'choices', 'caption'}
LIST = re.compile(r'^(?:>\s?)*\s*(?:[-*]\s+|\d+\.\s+)')


def main():
    listing = '--list' in sys.argv[1:]
    items, failures = 0, []
    for path in sorted(CONTENT.glob('ch*.json')):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data.get('lessons', []):
            for where, text in fields(lesson):
                counted, bad = inspect(text)
                items += counted
                for item, follow in bad:
                    failures.append((f'{lesson["id"]} {where}', item, follow))
            if listing:
                print(f'  {lesson["id"]}: リスト項目 '
                      f'{sum(inspect(t)[0] for _, t in fields(lesson))}件')

    print(f'リスト項目{items}件について、続き行が無いかを見ました。')
    if not failures:
        print('  すべての項目が1行で書かれています。')
        return 0

    print(f'\n2行以上に分かれたリスト項目が{len(failures)}件あります。', file=sys.stderr)
    print('（続き行は項目に入らず、左端の別の段落として描かれます）', file=sys.stderr)
    for where, item, follow in failures:
        print(f'  {where}: {item[:60]}', file=sys.stderr)
        print(f'      続き行: {follow[:60]}', file=sys.stderr)
    print('\n続き行を項目の行へつないでください（全角どうしなら詰め、片方が半角なら空白を1つ）。'
          '分けて見せたいなら、続き行も `-` で始めて独立した項目にします。', file=sys.stderr)
    return 1


def inspect(text):
    """(リスト項目の数, [(項目, 続き行)]) を返す。```で囲んだ範囲は見ない。"""
    lines = text.split('\n')
    count, bad, fence = 0, [], False
    for i, line in enumerate(lines):
        if line.strip().startswith('```'):
            fence = not fence
            continue
        if fence or not LIST.match(line):
            continue
        count += 1
        follow = lines[i + 1] if i + 1 < len(lines) else ''
        if (follow.strip() and not follow.strip().startswith('```')
                and not LIST.match(follow) and follow[:1].isspace()):
            bad.append((line.strip(), follow.strip()))
    return count, bad


def fields(lesson):
    """学習者が読むmarkdownを [(場所, 本文)] で並べる。"""
    out = [('解説', lesson.get('explanation') or '')]
    for sample in lesson.get('samples') or []:
        out.append(('サンプル見出し', sample.get('caption') or ''))
    for number, quiz in enumerate(lesson.get('quiz') or [], start=1):
        out.append((f'クイズ{number}', quiz.get('question') or ''))
        out.append((f'クイズ{number}の解説', quiz.get('explanation') or ''))
        for choice in quiz.get('choices') or []:
            if isinstance(choice, str):
                out.append((f'クイズ{number}の選択肢', choice))
    tasks = ([lesson] if lesson.get('task') else []) + list(lesson.get('extraTasks') or [])
    for number, task in enumerate(tasks, start=1):
        out.append((f'#{number} 課題文', task.get('task') or ''))
        for hint in task.get('hints') or []:
            out.append((f'#{number} ヒント', hint))
    return out


if __name__ == '__main__':
    sys.exit(main())
