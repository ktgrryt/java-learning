"""章の到達目標（objectives）と、問題・クイズへの紐づけを検査する。

tools/check-objectives.sh から呼ばれる。

  python3 tools/check_objectives.py                … 検査する（全58章に到達目標が必要）
  python3 tools/check_objectives.py --list         … 章ごとの記述状況を一覧する
  python3 tools/check_objectives.py --baseline 3   … 未記述を許す章数を一時的に緩める

## なぜ必要か

章クリアは「必須問題を全部解いたか」を、`layers` は「概念・コード・実践のどこまで到達したか」を
答える。どちらも**終わったか**の話で、**何ができるようになるか**は教材のどこにも構造化されて
いなかった（導入文に目標らしい記述があるのは321レッスン中11件）。

`objectives` はそこを埋める。**学習者の画面には出さない**（`rubric` と同じく、問題を書く側の
基準として持つ）。ただし宣言しただけでは実態とずれるので、次を機械で見張る。

  宣言した目標は、必ずその章の問題かクイズで測られていること
  問題とクイズは、必ずどれかの目標へ紐づいていること
  目標は「〜できる」の形で、観察できる状態を書いていること

`objectiveIds` はレッスンに書き、問題は必要なときだけ上書きする。レッスン内の問題は同じ目標を
測ることが多いので、全問へ書かせると重複が増えて実態と合わなくなるほうが早い。

## 何を見るか

  1. `objectives` の形（id が「章のid + -oM」、重複なし、章あたり2〜5個）
  2. `objectiveIds` がその章の目標へ解決できること（`ContentLoader` も起動時に止める）
  3. どの問題・クイズからも参照されていない目標、任意問題しか測っていない目標が無いこと
  4. `objectiveIds` を持たないレッスンが無いこと（事前確認レッスンは対象外）
  5. 目標の文が可能形（「〜できる」「〜書ける」）で終わっていること

全58章へ書き終えたので、既定では未記述を1章も許さない。章を足すときは目標も書く。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
# 観察できる状態は「〜できる」の形、つまり日本語の可能形で書く。語尾を一覧で持つと
# 書くたびに一覧を足すことになり検査の意味が薄れるので、可能形そのものを規則にする。
#
# 可能形は「-eru / -rareru」なので、`る`（丁寧形は`ます`）の直前がエ段のかなになる
# … 書ける・選べる・読める・直せる・決められる・使える。
# `できる`（出来る）だけはエ段にならないので別に許す。
#
# 逆に「〜を理解する」「〜がわかる」「〜を学ぶ」はこの形にならないので落ちる。
# 可能形に見えるが到達を測れない動詞（覚える・慣れる・考えるなど）は名指しで落とす。
OBSERVABLE = re.compile(r'(?:[えけげせぜてでねへべめれ]る|[えけげせぜてでねへべめれ]ます'
                        r'|できる|できます)$')
UNOBSERVABLE = ('覚える', '覚えます', '考える', '考えます', '思える', '思えます',
                '慣れる', '慣れます', '触れる', '触れます', '見える', '見えます',
                '増える', '増えます')
MIN_PER_CHAPTER = 2
MAX_PER_CHAPTER = 5
BASELINE = 0           # 未記述を許す章数。全58章へ書き終えたので0


def main():
    args = sys.argv[1:]
    listing = '--list' in args
    baseline = BASELINE
    if '--baseline' in args:
        baseline = int(args[args.index('--baseline') + 1])

    chapters = load()
    if listing:
        show(chapters)
        return 0

    problems = []
    missing = []
    for chapter in chapters:
        if not chapter['objectives']:
            missing.append(chapter['id'])
            continue
        problems += check_shape(chapter)
        problems += check_links(chapter)

    written = len(chapters) - len(missing)
    print(f'章{len(chapters)}件のうち{written}件へ到達目標を書いてあります'
          f'（未記述{len(missing)}件 / 基準{baseline}件）。')

    if len(missing) > baseline:
        over = len(missing) - baseline
        problems.append(f'到達目標が無い章が基準より{over}件多いです: '
                        + ' '.join(missing[:8]) + ('…' if len(missing) > 8 else ''))

    if not problems:
        print('  宣言した目標と、問題・クイズの紐づけは一致しています。')
        return 0

    print(f'\n直す箇所が{len(problems)}件あります。', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n書き方は docs/guide.md「到達目標を書く」にあります。', file=sys.stderr)
    return 1


def check_shape(chapter):
    """1. objectives の形を見る。5. 観察できる書き方かを見る。"""
    problems = []
    # 章のidは `ch03` のほか、旧来の `30` のような形も残っているので、
    # 形を決め打ちせずその章のid＋`-oM` を要求する（ContentLoader も同じ規則）。
    shape = re.compile(r'^' + re.escape(chapter['id']) + r'-o\d+$')
    seen = set()
    for objective in chapter['objectives']:
        oid = objective.get('id', '')
        text = objective.get('text', '')
        if not shape.match(oid):
            problems.append(f'{chapter["id"]}: 目標のidは「章のid + -oM」にしてください'
                            f'（例: {chapter["id"]}-o1）: {oid!r}')
        if oid in seen:
            problems.append(f'{chapter["id"]}: 目標のidが重複しています: {oid}')
        seen.add(oid)
        if not text.strip():
            problems.append(f'{chapter["id"]}: 目標 {oid} の text が空です')
        else:
            tail = main_clause(text)
            if tail.endswith(UNOBSERVABLE):
                problems.append(
                    f'{chapter["id"]}: 目標 {oid} の語尾では到達を測れません'
                    f'（何ができるかを書いてください）: {text[:38]!r}')
            elif not OBSERVABLE.search(tail):
                problems.append(
                    f'{chapter["id"]}: 目標 {oid} が可能形で終わっていません'
                    f'（「〜できる」「〜書ける」「〜決められる」など）: {text[:38]!r}')
    count = len(chapter['objectives'])
    if not MIN_PER_CHAPTER <= count <= MAX_PER_CHAPTER:
        problems.append(f'{chapter["id"]}: 目標が{count}個です'
                        f'（{MIN_PER_CHAPTER}〜{MAX_PER_CHAPTER}個にしてください）')
    return problems


def main_clause(text):
    """語尾を見るための本文を取り出す。

    末尾の句点と、`（sleep で待たない）` のような補足の括弧は落とす。補足は注記なので、
    可能形かどうかは本文の動詞で決める。
    """
    tail = text.strip().rstrip('。')
    while True:
        stripped = re.sub(r'[（(][^（()）]*[)）]$', '', tail).rstrip('。').rstrip()
        if stripped == tail:
            return tail
        tail = stripped


def check_links(chapter):
    """2. 解決できるか。3. 参照されない目標。4. 紐づけの無いレッスン。"""
    problems = []
    known = {o.get('id') for o in chapter['objectives']}
    used = set()
    # 任意発展問題（`required: false`）は章クリアの分母に入らないので、それだけが測っている
    # 目標は「全員が通る問題では測られていない」ことになる。宣言と実態が合わないので落とす。
    required_used = set()
    for lesson in chapter['lessons']:
        if lesson['preflight']:
            continue
        lesson_ids = lesson['objectiveIds']
        if not lesson_ids:
            problems.append(f'{lesson["id"]}: objectiveIds がありません'
                            '（このレッスンがどの目標を測るのかを書いてください）')
        for oid in lesson_ids:
            if oid not in known:
                problems.append(f'{lesson["id"]}: 解決できない objectiveIds: {oid}')
            else:
                used.add(oid)
                # 1問目はレッスンと同じ指定で、必須問題である
                required_used.add(oid)
        for index, (task_ids, required) in enumerate(lesson['tasks']):
            for oid in task_ids:
                if oid not in known:
                    problems.append(f'{lesson["id"]}#{index + 2}: 解決できない objectiveIds: {oid}')
                    continue
                used.add(oid)
                if required:
                    required_used.add(oid)
    for oid in sorted(known):
        if oid not in used:
            problems.append(f'{chapter["id"]}: 目標 {oid} を測る問題・クイズがありません'
                            '（測らない目標は書かないでください）')
        elif oid not in required_used:
            problems.append(f'{chapter["id"]}: 目標 {oid} を測るのが任意発展問題だけです'
                            '（章クリアの分母に入らないので、全員が通る問題で測ってください）')
    return problems


def load():
    """manifest の掲載順に、検査に必要な形だけを集める。"""
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    chapters = []
    for part in manifest['parts']:
        for file_name in part['chapters']:
            raw = json.loads((CONTENT / file_name).read_text(encoding='utf-8'))
            lessons = []
            for lesson in raw.get('lessons', []):
                # 1問目はレッスンと同じJSONオブジェクトなので、レッスンの objectiveIds が
                # そのまま1問目の指定になる（ContentLoader も同じ読み方をする）。
                # 2問目以降だけが自分の objectiveIds を持ちうる。
                tasks = [(t.get('objectiveIds', []), t.get('required', True) is not False)
                         for t in lesson.get('extraTasks', [])]
                lessons.append({
                    'id': lesson['id'],
                    'preflight': lesson.get('lessonType') == 'preflight',
                    'objectiveIds': lesson.get('objectiveIds', []),
                    'tasks': tasks,
                })
            chapters.append({
                'id': raw['id'],
                'title': raw.get('title', ''),
                'file': file_name,
                'objectives': raw.get('objectives', []),
                'lessons': lessons,
            })
    return chapters


def show(chapters):
    for chapter in chapters:
        mark = f'{len(chapter["objectives"])}個' if chapter['objectives'] else '★未記述'
        print(f'{chapter["id"]:6} {mark:8} {chapter["title"][:26]}')
        for objective in chapter['objectives']:
            print(f'         {objective.get("id")}: {objective.get("text", "")}')
    written = sum(1 for c in chapters if c['objectives'])
    print(f'\n記述済み {written}章 / 未記述 {len(chapters) - written}章')


if __name__ == '__main__':
    sys.exit(main())
