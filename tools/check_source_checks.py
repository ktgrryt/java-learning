"""sourceChecks が「ひな形のままで満たされていないか」を、採点と同じ判定で数える。

tools/check-source-checks.sh から呼ばれる。第1引数は jq.judge.CheckCount を含むクラスパス。

  python3 tools/check_source_checks.py build/classes:<CheckCountの出力先>

ガイド「sourceChecks は『その構文が到達目標のとき』だけ書く」の後半、
**ひな形のままでは通らない** を機械で確かめるための道具である。
verify-solutions.sh は「ひな形が全体として合格しないか」を見るが、
検査が複数あると、そのうち1件が空振りしていても他が落ちるので隠れてしまう。
ここでは検査を1件ずつ、ひな形に対して数える。

正規表現はPythonで再実装せず jq.judge.CheckCount を呼ぶ。SourceChecker.codeOnly と
MULTILINE|DOTALL をそろえてあるので、ここで出た回数は提出時の判定と一致する。

出力は2種類に分ける。

  要求（minimum>=1）… ひな形が満たしているなら、学習者は何も書かずに通る。
                       ただし「ひな形が与えた宣言を消していないか」の見張りとして
                       置いている場合もあるため、既存分は一覧として出すだけにする。
  禁止（minimum=0）  … ひな形が満たすのが正常。数えるが指摘しない。

既定では要求系の空振りが BASELINE 件を超えたときだけ失敗する。既存分は据え置き、
増えたときだけ気づける形にするためである（→ guide.md「sourceChecks は…」の3つ目の箇条）。
--baseline N で基準を変えられる。--strict は1件でも失敗、--list は数えるだけ。
"""
import json
import glob
import subprocess
import sys

RS = '\x1e'
US = '\x1f'

# 「足場の見張り」として既にある要求系の空振りの数。ここを下げる作業は未着手なので、
# 現状を基準にして、これ以上増えないことだけを守る。減らしたらこの数も下げる。
#
# 107 → 88: H-03の方針（domain能力ではなく一般Javaの字面を測る検査を外す）を
# ch45〜ch48・ch54〜ch56・ch63へ広げた際、外した49件のうち19件が空振りだった。
# 88 → 87: 38-2の応用問題をtrace相関へ置き換えた際、`new TreeMap`と`computeIfAbsent`が消えた。
# 87 → 84: H-03の方針を ch30・ch37・ch38 へ広げ、字面検査14件を外した（うち3件が空振り）。
BASELINE = 84


def main():
    if len(sys.argv) < 2:
        sys.exit('使い方: python3 tools/check_source_checks.py <classpath> [--strict] [--baseline N]')
    classpath = sys.argv[1]
    args = sys.argv[2:]
    strict = '--strict' in args
    baseline = None if ('--list' in args or strict) else BASELINE
    if '--baseline' in args:
        baseline = int(args[args.index('--baseline') + 1])

    items = []
    for path in sorted(glob.glob('content/ch*.json')):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data.get('lessons', []):
            tasks = [lesson] + list(lesson.get('extraTasks', []))
            for index, task in enumerate(tasks):
                for check in task.get('sourceChecks') or []:
                    items.append({
                        'lesson': lesson['id'],
                        'task': index,
                        'pattern': check['pattern'],
                        'minimum': check.get('minimum', 1),
                        'maximum': check.get('maximum', -1),
                        'message': check.get('message', ''),
                        'starter': task.get('starterCode', ''),
                    })

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

    demands = []
    bans = 0
    for item, count in zip(items, counts):
        satisfied = count >= item['minimum'] and (item['maximum'] < 0 or count <= item['maximum'])
        if not satisfied:
            continue
        if item['minimum'] == 0:
            bans += 1          # 禁止系。ひな形が満たすのが正常
        else:
            item['starterHits'] = count
            demands.append(item)

    problems = len({(i['lesson'], i['task']) for i in demands})
    print(f'sourceChecks {len(items)}件を、ひな形に対して数えました。')
    print(f'  禁止（minimum=0）でひな形が満たすもの: {bans}件（正常）')
    print(f'  要求（minimum>=1）でひな形が満たすもの: {len(demands)}件 / {problems}問')

    # 既存分の据え置き中は107行の一覧を毎回出しても読まれないので、
    # 基準を超えたときと、一覧を求められたときだけ明細を出す。
    over = baseline is not None and len(demands) > baseline
    if demands and (over or baseline is None):
        print()
        print('  要求系の空振り（学習者が何も書かずに通る。ひな形が与えた宣言の見張りなら意図的）:')
        for item in demands:
            print(f"    {item['lesson']}#{item['task']} /{item['pattern']}/"
                  f" ひな形={item['starterHits']} 必要={item['minimum']}")
    elif demands:
        print(f'  （基準の {baseline}件 以内なので明細は出しません。--list で全件出せます）')

    if over:
        print(f'\n要求系の空振りが基準の {baseline}件 を超えました（現在 {len(demands)}件）。', file=sys.stderr)
        return 1
    if strict and demands:
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
