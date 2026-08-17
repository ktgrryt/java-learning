"""`sourceChecks` を1件ずつ3つの目的へ分類する（レビュー08-14の §8.7）。

tools/classify-source-checks.sh から呼ばれる。第1引数は jq.judge.CheckCount を含むクラスパス。

  python3 tools/classify_source_checks.py <classpath>           … 集計を出す
  python3 tools/classify_source_checks.py <classpath> --list    … 全件を分類つきで出す
  python3 tools/classify_source_checks.py <classpath> --review  … 判断が要るものだけ出す

## なぜ必要か

レビュー08-13のH-03が「domain能力ではなく一般Javaの字面を測る検査」を108件外し、H-06が
「ひな形が満たす検査は*足場の見張り*であって空振りではない」と決着させた。ただしどちらも
**章ごとの判断**で、検査1件ずつの分類は残っていた（08-14の §8.7 が求めているのはこれ）。

到達目標（`objectives`）が全69章へ入ったので、H-03が手で判断していた
「その構文は学習目標に明示されているか」を**機械で当てられる**ようになった。ここではその
突き合わせを行い、判断が要るものだけを人へ回す。

## 3つの目的

  A 学習対象   … その構文・APIが、到達目標か問題文で名指しされている。書かせたいので検査する
  B 足場の見張り … ひな形が最初から満たしている。「与えた宣言を壊していないか」を見ている
  C 要判断     … AでもBでもない。偶発的な実装固定（正しい別解を弾く）の疑いがある

Bの判定は `check_source_checks.py` と同じく `jq.judge.CheckCount` を通すので、採点と一致する。
Aの判定は語の一致なので取りこぼしがある。**Cは「外すべき」ではなく「読んで決める」**の意味。

## 禁止系（minimum=0）

「`450` と直接書かない」のような禁止は、ひな形が満たすのが正常で、目的も明確なので
最初から分類の対象にしない（Dとして数えるだけ）。
"""
import glob
import json
import re
import subprocess
import sys

RS = '\x1e'
US = '\x1f'

# 正規表現のうち、語の区切りや繰り返しを表すだけの部分（比較には使えない）
NOISE = re.compile(r'\\[sbSBdDwWAZ]|[*+?]\??|\{\d*,?\d*\}|[\^$]|\s')
# 演算子として書かれる記号。`*=` や `||` のように、語では拾えないものを比較するため
SYMBOL = re.compile(r'[-+*/%<>=!?&|]{1,3}')
WORD = re.compile(r'[A-Za-z_][A-Za-z0-9_.]+|\d+')


def main():
    if len(sys.argv) < 2:
        sys.exit('使い方: python3 tools/classify_source_checks.py <classpath> [--list] [--review]')
    classpath = sys.argv[1]
    args = sys.argv[2:]

    items = load()
    if not items:
        print('sourceChecks はありません。')
        return 0

    payload = RS.join(f"{i['pattern']}{US}{i['starter']}" for i in items)
    result = subprocess.run(
        ['java', '-Dfile.encoding=UTF-8', '-cp', classpath, 'jq.judge.CheckCount'],
        input=payload, capture_output=True, text=True)
    if result.returncode != 0:
        print('CheckCount を実行できません:', result.stderr.strip()[:400], file=sys.stderr)
        return 1
    counts = [int(line) for line in result.stdout.split()]
    if len(counts) != len(items):
        print(f'件数が合いません（検査 {len(items)} 件に対し結果 {len(counts)} 件）', file=sys.stderr)
        return 1

    for item, count in zip(items, counts):
        item['kind'] = classify(item, count)

    groups = {}
    for item in items:
        groups.setdefault(item['kind'], []).append(item)

    labels = {'A': 'A 学習対象（目標か問題文で名指し）',
              'B': 'B 足場の見張り（ひな形が満たす）',
              'C': 'C 要判断（AでもBでもない）',
              'D': 'D 禁止系（minimum=0。分類の対象外）'}
    print(f'sourceChecks {len(items)}件を分類しました。')
    for key in 'ABCD':
        got = groups.get(key, [])
        print(f'  {labels[key]}: {len(got)}件'
              f'（{len({(x["lesson"], x["task"]) for x in got})}問）')

    if '--review' in args:
        show(groups.get('C', []), '判断が要るもの')
    elif '--list' in args:
        for key in 'ABCD':
            show(groups.get(key, []), labels[key])
    return 0


def classify(item, starter_hits):
    """1件を A/B/C/D のどれかへ振る。"""
    if item['minimum'] == 0:
        return 'D'
    satisfied = (starter_hits >= item['minimum']
                 and (item['maximum'] < 0 or starter_hits <= item['maximum']))
    if satisfied:
        return 'B'
    return 'A' if named_in_goal(item) else 'C'


def named_in_goal(item):
    """検査している語や記号が、到達目標か問題文に出てくるか。

    学習者が読むのは到達目標と問題文（とヒント）なので、そこに書いてあれば
    「書くように言われたことを検査している」＝A とみなす。`message` は失敗後にしか
    見えないので手がかりに使わない（使うと、どの検査も自分の文言と一致してAになる）。
    """
    haystack = item['goal'] + '\n' + item['task_text']
    return any(t in haystack for t in tokens(item['pattern']))


def tokens(pattern):
    """正規表現から、比較に使える断片を取り出す。

    語（`Files` `nextInt` `10`）と、演算子の記号（`*=` `||` `<=` `?`）の両方を拾う。
    記号だけの検査（`\\*=` など）は語では拾えないため。
    """
    literal = NOISE.sub(' ', pattern).replace('\\', '')
    words = [w for w in WORD.findall(literal) if len(w) >= 2]
    symbols = [x for x in SYMBOL.findall(literal) if x not in ('=',)]
    return words + symbols


def show(items, title):
    if not items:
        return
    print(f'\n■ {title}')
    for item in items:
        print(f'  {item["lesson"]}#{item["task"] + 1}  {item["pattern"]}')
        print(f'      目標: {item["goal"][:70] or "（なし）"}')
        print(f'      文言: {item["message"][:70]}')


def load():
    """検査と、その問題の到達目標・問題文を集める。"""
    items = []
    for path in sorted(glob.glob('content/ch*.json')):
        data = json.load(open(path, encoding='utf-8'))
        objectives = {o['id']: o['text'] for o in data.get('objectives', [])}
        for lesson in data.get('lessons', []):
            lesson_ids = lesson.get('objectiveIds') or []
            tasks = [lesson] + list(lesson.get('extraTasks', []))
            for index, task in enumerate(tasks):
                ids = task.get('objectiveIds') or lesson_ids
                goal = ' / '.join(objectives.get(i, '') for i in ids)
                task_text = (task.get('task') or '') + '\n' + \
                            '\n'.join(task.get('hints') or [])
                for check in task.get('sourceChecks') or []:
                    items.append({
                        'lesson': lesson['id'],
                        'task': index,
                        'pattern': check['pattern'],
                        'minimum': check.get('minimum', 1),
                        'maximum': check.get('maximum', -1),
                        'message': check.get('message', ''),
                        'starter': task.get('starterCode', ''),
                        'goal': goal,
                        'task_text': task_text,
                    })
    return items


if __name__ == '__main__':
    sys.exit(main())
