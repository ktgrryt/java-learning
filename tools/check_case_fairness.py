"""テストケースが「問題文だけを読んだ人」に解けるかを検査する。

tools/check-case-fairness.sh から呼ばれる。

  python3 tools/check_case_fairness.py          … 検査する
  python3 tools/check_case_fairness.py --list   … 該当を全部出す

## なぜ必要か

2026-08-16のレビューで、**隠しケースだけが完全一致で求めている固定文言**が10問見つかった。

  23-4 は「別roleは403です」としか書いておらず、隠しケースが `! 権限がありません` を求めていた
  28-4#1 は不正入力の分岐そのものを書いておらず、隠しケースが `400 Bad Request` を求めていた

学習者が読めるのは 問題文・ひな形・表示ケース だけで、隠しケースの中身は提出するまで見えない。
そこにしか無い文言は当てられないので、正しく実装しても必ず落ちる。ヒントは開くと報酬が減るので、
必須の文言をヒントへ置くのも同じ扱いにする（`--list` では区別して出す）。

同時に、**ケースのラベル**も見る。ラベルは落ちたケースを知らせるときに出るので、
`隠しケース1` のような番号だけでは「どの場面で落ちたか」が伝わらない。

## 判定

  失敗 … 模範解答の中の日本語を含む固定文字列が、隠しケースの期待出力にだけ現れる
  失敗 … ケースのラベルが `表示ケース1` `隠しケース2` のような番号だけ

## 直し方

問題文へ文言を書く（推奨）。文言そのものを考えさせたいなら、比べる対象から外す（出力へ含めない）。
"""
import glob
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
LITERAL = re.compile(r'"((?:[^"\\]|\\.){3,})"')
JAPANESE = re.compile(r'[぀-ヿ一-鿿]')
NUMBERED_LABEL = re.compile(r'^(?:表示|隠し)?ケース\s*\d+$')

# 問題文が「並び」や「語の一覧」として書いているので、1語ずつは載っていなくても当てられるもの。
# 足すときは、問題文のどの記述から導けるのかを必ず書く。
ALLOWED = {
    ('18-2#1', '水曜日'): '問題文が「`1`〜`7` を `月曜日`〜`日曜日` に対応させ」と書いている',
    ('18-2#1', '金曜日'): '問題文が「`1`〜`7` を `月曜日`〜`日曜日` に対応させ」と書いている',
    ('18-2#2', '操作 ひく'): '問題文が `たす` / `ひく` / `かける` / `わる` を並べている',
    ('18-2#2', '操作 かける'): '問題文が `たす` / `ひく` / `かける` / `わる` を並べている',
}


def main():
    listing = '--list' in sys.argv[1:]
    unguessable, hinted, labels = [], [], []
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data['lessons']:
            tasks = [lesson] + list(lesson.get('extraTasks') or [])
            for index, task in enumerate(tasks, start=1):
                name = f"{lesson['id']}#{index}"
                labels += [(name, c.get('label'))
                           for c in visible(task) + hidden(task)
                           if NUMBERED_LABEL.match((c.get('label') or '').strip())]
                if task.get('type') not in (None, 'single-file') or not task.get('solution'):
                    continue
                for text in required_only_in_hidden(task):
                    if (name, text) in ALLOWED:
                        continue
                    where = hinted if text in '\n'.join(task.get('hints') or []) \
                        else unguessable
                    where.append((name, text))

    print(f'ケースの読み取りやすさを検査しました'
          f'（問題文に無い文言 {len(unguessable)}件 / ヒントにだけある文言 {len(hinted)}件 / '
          f'番号だけのラベル {len(labels)}件）。')
    if listing:
        for name, text in unguessable + hinted:
            print(f'  {name}: {text!r}')
        for name, label in labels:
            print(f'  {name}: ラベル {label!r}')

    problems = len(unguessable) + len(hinted) + len(labels)
    if not problems:
        print('  隠しケースだけが求める文言と、番号だけのラベルはありません。')
        return 0

    print(f'\n学習者が読めない情報を求めている箇所が{problems}件あります。', file=sys.stderr)
    for name, text in unguessable:
        print(f'  {name}: {text!r} が問題文・ひな形・表示ケースのどこにも無い', file=sys.stderr)
    for name, text in hinted:
        print(f'  {name}: {text!r} がヒントにしか無い（開くと報酬が減る）', file=sys.stderr)
    for name, label in labels:
        print(f'  {name}: ラベル {label!r} が番号だけ。場面を書いてください', file=sys.stderr)
    print('\n必要な文言は問題文へ書いてください。文言を考えさせたいなら、'
          '出力の比較対象から外します。', file=sys.stderr)
    return 1


def visible(task):
    return list(task.get('visibleCases') or [])


def hidden(task):
    return list(task.get('hiddenCases') or [])


def required_only_in_hidden(task):
    """模範解答の固定文字列のうち、隠しケースだけが求めているものを返す。"""
    readable = '\n'.join([task.get('task') or '', task.get('starterCode') or '']
                         + [(c.get('expected') or '') + '\n' + (c.get('stdin') or '')
                            for c in visible(task)])
    found = []
    for literal in sorted(set(LITERAL.findall(task['solution']))):
        text = literal.replace('\\"', '"')
        if text in readable or not JAPANESE.search(text):
            continue
        if any(text in (c.get('expected') or '') for c in hidden(task)):
            found.append(text)
    return found


if __name__ == '__main__':
    sys.exit(main())
