"""期待出力が1種類しかない問題を数える（レビュー08-16で見つけた欠陥の再発防止）。

tools/check-constant-output.sh から呼ばれる。

  python3 tools/check_constant_output.py          … 検査する
  python3 tools/check_constant_output.py --list   … 該当する問題を全部出す

## なぜ必要か

テストケースの期待出力が**全部同じ**なら、その文字列を `System.out.println` で出すだけで合格する。
表示ケースには期待出力が見えているので、写せば通る。**採点が何も測っていない状態**になる。

実際に `27-2#2`（トランザクションの内と外を分ける問題）が、入力なし・ケース1件・`sourceChecks`なしで、
模範解答が `println` 4行だった。学習者はトランザクション境界を1度も考えずに★を取れていた。

## 判定

  失敗 … 出力が1種類 かつ `sourceChecks` が無い（`ALLOWED_CHAPTERS` の章を除く）
  参考 … 出力が1種類だが `sourceChecks` がある（検査だけが防波堤なので件数を表示する）

## `ALLOWED_CHAPTERS`

`ch01`〜`ch03` は「`System.out.println` を書く」こと自体が目標で、標準入力もまだ教えていない。
期待出力を写して通ることは避けられず、**写すために `println` を書くことが練習になっている**。
ここだけは許す。それ以降の章では、入力で出力が変わる形にするか、書かせたい構文を
`sourceChecks` で見張ること。

## 直し方

  入力を読ませて出力を変える  … いちばん強い。`27-2#2` はこれで直した
  ケースを増やして出力を変える … 入力がある問題ならこれで足りる
  `sourceChecks` を足す      … 書かせたい構文が目標そのもののときだけ（`docs/guide.md`）
"""
import glob
import json
import pathlib
import sys

CONTENT = pathlib.Path('content')

# 「期待出力を写して通る」ことを許す章。理由は上の docstring にある。
ALLOWED_CHAPTERS = {'ch01', 'ch02', 'ch03'}


def main():
    listing = '--list' in sys.argv[1:]
    unguarded, guarded = [], []
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data['lessons']:
            if lesson.get('type') == 'preflight':
                continue
            tasks = [lesson] + list(lesson.get('extraTasks') or [])
            for index, task in enumerate(tasks, start=1):
                if task.get('type') not in (None, 'single-file'):
                    continue
                cases = (task.get('visibleCases') or []) + (task.get('hiddenCases') or [])
                if not cases:
                    continue
                if len({c.get('expected') for c in cases}) != 1:
                    continue
                where = f'{lesson["id"]}#{index}'
                checks = len(task.get('sourceChecks') or [])
                row = (where, data['id'], len(cases), (task.get('task') or '')[:56])
                if checks:
                    guarded.append(row + (checks,))
                elif data['id'] not in ALLOWED_CHAPTERS:
                    unguarded.append(row)

    print(f'期待出力が1種類しかない問題を調べました。')
    print(f'  検査も無いもの: {len(unguarded)}件'
          f'（{"、".join(sorted(ALLOWED_CHAPTERS))} は目標上やむを得ないので除く）')
    print(f'  `sourceChecks` だけが防波堤のもの: {len(guarded)}件')

    if listing:
        if unguarded:
            print('\n■ 固定出力で通る（検査も無い）')
            for where, chapter, n, task in unguarded:
                print(f'  {where:9} {chapter:6} ケース{n}  {task}')
        if guarded:
            print('\n■ 検査だけが防波堤')
            for where, chapter, n, task, checks in guarded:
                print(f'  {where:9} {chapter:6} ケース{n} 検査{checks}件  {task}')

    if not unguarded:
        print('  期待出力を写すだけで通る問題はありません。')
        return 0

    print(f'\n直す箇所が{len(unguarded)}件あります。', file=sys.stderr)
    for where, chapter, n, task in unguarded:
        print(f'  {where} ({chapter}): {task}', file=sys.stderr)
    print('\n入力で出力が変わる形にするのがいちばん強い直し方です。'
          '書かせたい構文が目標そのものなら `sourceChecks` を足します'
          '（docs/guide.md「sourceChecks は…」）。--list で全部出せます。', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
