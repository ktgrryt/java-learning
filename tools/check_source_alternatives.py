"""sourceChecks が「正しい別解」を弾いていないかを、模範解答の等価変形で検査する。

tools/check-source-alternatives.sh から呼ばれる。第1引数は jq.judge.CheckCount を含む
クラスパス、第2引数は採点させるサーバのポート。

  python3 tools/check_source_alternatives.py build/classes:build/tools 8765
  python3 tools/check_source_alternatives.py <cp> <port> --list   … 全件出す

## なぜ必要か

2026-08-21に、5-3の応用問題（各桁の合計）が `n /= 10;` で落ちることが分かった。検査の
正規表現が `/\\s*10` で、**割り算の字面**を固定していたためである。桁を落とせているかを
見たいだけなのに、`n = n / 10` と書いた人だけが通る形になっていた。

同じ形の取りこぼしは、書き方を1か所ずつ変えて提出してみれば機械で見つかる。

  複合代入      n = n / 10  ⇔  n /= 10
  増減          created++   ⇔  created += 1  ⇔  created = created + 1
  交換法則      150 * 3     ⇔  3 * 150
  this.         width * height  ⇔  width * this.height
  equals の向き x.equals(t)  ⇔  t.equals(x)
  ラムダの引数名 deployment -> ...  ⇔  d -> ...

## 判定

模範解答を変形して提出し、**全テストケースを通るのに sourceChecks で落ちる**ものを挙げる。
出力が変わる変形（＝等価でない）は、ケースが落ちるので自動的に外れる。

## ALLOWED（意図してその字面を求めているもの）

「`++` で1つ増やす」「複合代入の形で書く」のように、**字面そのものが学習目標**の問題では、
別の書き方を弾くのが正しい。その場合だけ ALLOWED へ入れる。足すときは、問題文か検査の
message がその字面を名指ししていることを理由に書く。
"""
import glob
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import source_mutations as mutations

RS, US = '\x1e', '\x1f'

# (問題, 変形の種類): その字面を求めている理由。
# 「message や問題文がその字面を名指ししているか」だけで判断する。
ALLOWED = {
    ('2-3#2', '増減'): '問題文が「`count` を `++` で1つ増やして」と書いている',
    ('3-1#2', '交換法則'): '問題文が「1行目は `a + b * c`」と式そのものを指定している',
    ('3-1#2', 'かっこ'): 'かっこ無しで `*` が先に計算されるのを見る問題なので、かっこを付けると狙いが消える',
    ('3-5#2', '複合代入'): '複合代入を学ぶ問題。message が「`x = x * 3;` ではなく」と明示している',
    ('4-2#2', '比較の向き'): '問題文が「この問題は `<=`（以下）で判定します」と演算子を指定している',
    ('5-1#2', '増減'): '問題文が「サンプルには **降順（`i--`）** が出てきました…両方使います」と書いている',
    # 以下は「左右を入れ替えた比較」。Javaでは書かない並びなので、通す必要はないと判断した。
    ('5-5#2', '比較の向き'): '`i >= j` の並びは書かない（`j <= i` と `j < i + 1` は通る）',
    ('27-3#2', '比較の向き'): '`0 < count` の並びは書かない（`count > 0` `count <= 0` `count < 1` は通る）',
    ('27-4#2', '比較の向き'): '`0 < amount` の並びは書かない（4通りの自然な書き方は通る）',
    ('59-1#1', '比較の向き'): '`severity <= event.severity` の並びは書かない',
    ('59-6#3', '比較の向き'): '`time >= deployment.time()` の並びは書かない',
}


def main():
    if len(sys.argv) < 3:
        sys.exit('使い方: python3 tools/check_source_alternatives.py <classpath> <port> [--list]')
    classpath, port = sys.argv[1], sys.argv[2]
    listing = '--list' in sys.argv[3:]

    tasks = load()
    variants, flips = plan(tasks, classpath)
    rejected = []
    for (task, index), checks in sorted(flips.items()):
        label, code = variants[(task, index)]
        result = submit(port, task, code)
        cases = result.get('cases') or []
        if not (result.get('compiled') and cases
                and result.get('passedCount', 0) == len(cases)):
            continue                       # 出力が変わる＝等価な変形ではない
        rejected.append((task, label, checks))

    kept = [r for r in rejected if (r[0], mutations.kind(r[1])) not in ALLOWED]
    print(f'模範解答の等価変形 {len(variants)}件を検査しました'
          f'（検査に当たらなくなるのは {len(flips)}件 / '
          f'出力は通るのに落ちるのは {len(rejected)}件 / '
          f'うち意図した字面 {len(rejected) - len(kept)}件）。')

    for task, label, checks in (rejected if listing else kept):
        mark = '  ' if (task, mutations.kind(label)) not in ALLOWED else '（意図）'
        print(f'{mark}{task}: {label} で落ちます')
        for check in checks:
            print(f'      pattern: {check["pattern"]}')
            print(f'      message: {check["message"]}')

    if kept:
        print()
        print('検査が書き方を固定しています。その字面が学習目標なら ALLOWED へ理由つきで、')
        print('そうでなければ正規表現を別解も通る形へ広げてください。')
        return 1
    print('  正しい別解を弾いている検査はありません。')
    return 0


def load():
    tasks = []
    for path in sorted(glob.glob('content/ch*.json')):
        data = json.load(open(path, encoding='utf-8'))
        for lesson in data.get('lessons', []):
            for index, task in enumerate([lesson] + list(lesson.get('extraTasks') or []), 1):
                if not task.get('sourceChecks') or not task.get('solution'):
                    continue
                if task.get('type', 'single-file') != 'single-file':
                    continue
                tasks.append({'name': f"{lesson['id']}#{index}", 'lesson': lesson['id'],
                              'taskId': str(index), 'checks': task['sourceChecks'],
                              'solution': task['solution']})
    return tasks


def plan(tasks, classpath):
    """どの変形がどの検査を落とすかを、提出せずに先に絞る（数え方は採点と同じ）。"""
    variants, pairs, index = {}, [], []
    for task in tasks:
        for position, check in enumerate(task['checks']):
            pairs.append((check['pattern'], task['solution']))
            index.append((task, position, None))
        for number, (label, code) in enumerate(mutations.variants(task['solution'])):
            variants[(task['name'], number)] = (label, code)
            for position, check in enumerate(task['checks']):
                pairs.append((check['pattern'], code))
                index.append((task, position, number))

    counts = count(pairs, classpath)
    base, flips = {}, {}
    for (task, position, number), got in zip(index, counts):
        check = task['checks'][position]
        if number is None:
            base[(task['name'], position)] = got
            continue
        if satisfied(base[(task['name'], position)], check) and not satisfied(got, check):
            flips.setdefault((task['name'], number), []).append(check)
    lookup = {task['name']: task for task in tasks}
    return variants, {k: v for k, v in flips.items() if k[0] in lookup}


def satisfied(count_, check):
    maximum = check.get('maximum', -1)
    return count_ >= check.get('minimum', 1) and (maximum < 0 or count_ <= maximum)


def count(pairs, classpath):
    """正規表現はPythonで再実装せず jq.judge.CheckCount を呼ぶ（採点と一致させる）。"""
    payload = RS.join(f'{pattern}{US}{source}' for pattern, source in pairs)
    result = subprocess.run(
        ['java', '-Dfile.encoding=UTF-8', '-cp', classpath, 'jq.judge.CheckCount'],
        input=payload, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit('CheckCount を実行できません: ' + result.stderr.strip()[:400])
    counts = [int(line) for line in result.stdout.split()]
    if len(counts) != len(pairs):
        sys.exit(f'件数が合いません（{len(pairs)}件に対し結果 {len(counts)}件）')
    return counts


def submit(port, name, code):
    lesson, task_id = name.split('#')
    body = json.dumps({'lessonId': lesson, 'taskId': task_id,
                       'code': code, 'review': True}).encode('utf-8')
    request = urllib.request.Request(f'http://localhost:{port}/api/submit', data=body,
                                     headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        return json.load(e)


if __name__ == '__main__':
    sys.exit(main())
