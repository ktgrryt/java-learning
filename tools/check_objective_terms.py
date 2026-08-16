"""到達目標が名指しした構文・APIが、その目標を測る問題に出てくるかを検査する。

tools/check-objective-terms.sh から呼ばれる。

  python3 tools/check_objective_terms.py          … 検査する
  python3 tools/check_objective_terms.py --list   … 名指しした語と出現状況を全部出す

## なぜ必要か

`check-objectives.sh` が見るのは「目標が問題から参照されているか」だけで、その問題が目標を
**どこまで測っているか**は見ない。2026-08-16の点検で、目標がバッククォートで名指しした道具を
**一度も書かせないまま章クリアできる**箇所が6件見つかった。

  ch03-o4  「`printf` と `String.format` で…」  `String.format` を書く問題が無かった
  ch04-o5  「`null` や0除算を避ける…」          `null` を扱う問題が無かった
  ch09-o3  「`public` `protected` `private`…」  `protected` は1ファイルの問題では測れない
  ch20-o1  「…モジュールの `exports`…」          `exports` は1ファイルの問題では書けない
  ch44-o1  「`Instant` と `LocalDateTime`…」    使う問題が別の目標へしか紐づいていなかった
  ch47-o2  「`HttpClient` で…」                  同上（実HTTPのlabが別の目標だった）

直し方は3つ。①その語を書かせる問題を足す ②紐づけを直す ③目標文を実態へ合わせる。
どれも「目標のほうが問題より広い」状態を解消する。

## 判定

目標文の `…` で囲まれた語を取り出し、その目標に紐づく**必須問題**の
問題文・ひな形・模範解答・`sourceChecks`・lab仕様のどこかに現れるかを見る。
現れなければ失敗。表記の都合で一致しないものは `ALLOWED` へ理由付きで入れる。
"""
import glob
import json
import pathlib
import sys

CONTENT = pathlib.Path('content')
# バッククォートで区切った奇数番目が code span。`%` のような短いものを飛ばすと
# 対の位置がずれるので、正規表現ではなく分割で取り出す。
SKIP = {'0', '1', '-1'}

# 語そのものは出てこないが、目標は測れているもの。足すときは理由を必ず書く。
ALLOWED = {
    ('ch01-o3', '"3" + 4'): '同じ形の `"10" + 5` を 1-3#2 で書かせている（表記だけの違い）',
    ('ch04-o3', '=='): '文字列を `==` で比べるのは誤りなので書かせない。'
                       '`equals` を使う問題と、`14-3` の `==` の比較で測る',
    ('ch16-o3', 'remove(int)'): '`remove(0)` と `remove(Object)` の呼び分けを 16-3#2 で測っている'
                                '（目標文の表記が引数の型なので一致しない）',
    ('ch16-o3', 'remove(Object)'): '同上',
    ('ch40-o2', 'Optional'): '`findFirst().map().orElse()` の形で `Optional` を扱わせている'
                             '（型名を書かずに使うのが普通の書き方）',
    ('ch47-o2', 'HttpClient'): '実HTTPのlab（`labs/http-client/exercise/ApiClient.java`）が'
                               '`HttpClient` を使う。labのファイルはこの検査の対象外',
    ('32-o4', 'sleep'): '「`sleep` で待たない」という**禁止**の名指しなので、出てこないのが正しい',
}


def main():
    listing = '--list' in sys.argv[1:]
    missing, checked = [], 0
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        corpus = collect(data)
        for objective in data.get('objectives', []):
            oid, text = objective['id'], objective['text']
            body = '\n'.join(corpus.get(oid) or [])
            for term in code_spans(text):
                if term in SKIP or not 2 <= len(term) <= 40:
                    continue
                checked += 1
                found = term in body
                if listing:
                    print(f'  {"OK " if found else "NG "} {oid}: {term!r}')
                if not found and (oid, term) not in ALLOWED:
                    missing.append((oid, term, text))

    print(f'目標が名指しした語を{checked}件、それを測る問題の中で探しました'
          f'（表記の都合で一致しない{len(ALLOWED)}件は理由付きで除外）。')
    if not missing:
        print('  名指しした構文・APIは、すべて問題側に現れています。')
        return 0

    print(f'\n問題に出てこない語が{len(missing)}件あります。', file=sys.stderr)
    for oid, term, text in missing:
        print(f'  {oid}: {term!r} が問題に出てきません\n      {text}', file=sys.stderr)
    print('\n直し方は3つです。その語を書かせる問題を足す／紐づけ（objectiveIds）を直す／'
          '目標文を実態へ合わせる。', file=sys.stderr)
    return 1


def code_spans(text):
    """`…` で囲まれた部分だけを順に返す。"""
    parts = text.split('`')
    return [parts[i] for i in range(1, len(parts), 2)]


def collect(data):
    """目標id -> その目標を測る必須問題のテキスト。"""
    corpus = {o['id']: [] for o in data.get('objectives', [])}
    for lesson in data['lessons']:
        if lesson.get('type') == 'preflight':
            continue
        base = lesson.get('objectiveIds') or []
        for task in [lesson] + list(lesson.get('extraTasks') or []):
            if not task.get('task') or task.get('required') is False:
                continue
            body = '\n'.join([
                task.get('task') or '', task.get('solution') or '',
                task.get('starterCode') or '',
                '\n'.join(s.get('pattern', '') for s in (task.get('sourceChecks') or [])),
                json.dumps(task.get('runtimeLab') or task.get('project')
                           or task.get('artifact') or {}, ensure_ascii=False),
            ])
            for oid in (task.get('objectiveIds') or base):
                if oid in corpus:
                    corpus[oid].append(body)
    return corpus


if __name__ == '__main__':
    sys.exit(main())
