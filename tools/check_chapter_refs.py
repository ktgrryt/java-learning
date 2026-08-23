"""章参照（「第NN章」）とレッスン参照（「13-5」）が、学習者に正しい番号で見えるかを確かめる。

tools/check-chapter-refs.sh から呼ばれる。

  python3 tools/check_chapter_refs.py         … 検査する
  python3 tools/check_chapter_refs.py --list  … 全参照を一覧する（終了コードは常に0）

## なぜ必要か

この教材には**3つの章番号**がある。

  内部番号 … `content/ch46-sql-database.json` のファイル名の番号。レッスンID `46-1` の左側
  編内番号 … 画面に出る番号。編ごとに1から振り直す（`ContentLoader` の partNumber）
  旧通し番号 … 編内番号へ移行する前の、全編を1から57まで通した番号。もう画面には出ない

`content/*.json` の本文は**内部番号**で `第NN章` と書く。`web/app.js` の
`localizeChapterReferences` が `chNN` の章を引いて編内番号へ読み替え、別の編なら
編名も前に付けるので、学習者には正しい番号が見える。並び替えにも自動で追従する。

壊れるのは次の3つで、いずれも画面を見ないと気づけない。

  1. 参照先の `chNN` が無い … 読み替えできず、書いた数字がそのまま出る
  2. 変換を通らないフィールドに書く … `esc` で表示される文字列は読み替えられない
  3. `labs/**/*.md` に書く … READMEはファイルとして読まれるので誰も読み替えない

実際に labs のREADMEは30ファイルが旧通し番号と内部番号を混在させていた。

## レッスン番号も同じ形で2つある

レッスンIDは章を分けても変えない（`progress.json` のキーなので変えると進捗が消える）。
そのため `ch67` のように、IDが `41-4` から始まる章がある。画面では**章の中の位置**で
振り直すので、同じレッスンが `41-4`（内部）と `5-4`（画面）の2つの番号を持つ。

本文も `第NN章` と同じく**内部ID**で `41-4` と書く。`web/app.js` の
`localizeLessonReferences` が読み替える。ただしコードブロックの中は読み替えない
（`5-5` の二重ループの出力例が `1-1 1-2 2-1 …` で、これは番号ではないため）。

**数の範囲を `2-3` のように地の文へ書かないこと。** レッスンIDと同じ形なので、
読み替えの対象になってしまう。`--list` で全参照を一覧できる。

## 何を見るか

  content … `第NN章` が manifest の章へ解決できること
  content … 変換を通らないフィールドでは、内部番号と編内番号が一致していること
  content … レッスン参照が実在するレッスンを指していること
  content … 変換を通らないフィールドでは、レッスンの内部番号と画面の番号が一致していること
  content … 事前確認は章の先頭に1つだけであること（画面の `-0` の意味が保てる）
  labs   … `第NN章` と `13-5` のようなレッスンIDを書いていないこと（編名・章タイトル・レッスン名で参照する）

labsはREADMEだけでなく、学習者が画面のファイル一覧で読む `.java` や `server.xml` の
コメントも対象にする（実際にその2箇所へ旧通し番号が残っていた）。`target/` などの
生成物は元ファイルから作り直されるので見ない。

`docs/` は保守者向けで、ファイルを探すために内部番号を `chNN` と書くので対象外。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
LABS = pathlib.Path('labs')

CHAPTER_REF = re.compile(r'第(\d+)章')

# レッスン参照。前後に英数字・ドット・`[` `]` `-` が付くものは外す
# （`postgres:16-alpine` `2024-05-03` 正規表現の `[1-4]` を拾わないため）。
# **`\w` はASCIIだけにする。** Pythonの `\w` は日本語も含むので、そのままだと
# 「画面の60-5では」のような日本語のうしろの参照を見落とす。web/app.js の `\w`
# （JavaScriptはASCIIのみ）と範囲をそろえないと、検査と画面の判定が食い違う。
LESSON_REF = re.compile(r'(?<![A-Za-z0-9_.\[-])(\d{1,2})-(\d{1,2})(#\d+)?(?![A-Za-z0-9_.\]-])')

# ```で囲んだ範囲。`localizeLessonReferences` が読み替えないので、ここも見ない。
CODE_BLOCK = re.compile(r'```.*?```', re.S)

# labsの走査から外すディレクトリ。生成物なので元ファイルを直せば作り直される。
GENERATED_DIRS = {'target', 'build', 'node_modules', '.git', '.mvn'}

# `renderMarkdown` を通るフィールド。ここに書いた「第NN章」は編内番号へ読み替えられる。
# 対応は web/app.js の renderMarkdown 呼び出し箇所（explanation・task・hints・quiz）。
TRANSLATED_FIELDS = {
    'lessons[].explanation',
    'lessons[].task',
    'lessons[].extraTasks[].task',
    'lessons[].hints[]',
    'lessons[].extraTasks[].hints[]',
    'lessons[].quiz[].question',
    'lessons[].quiz[].choices[]',
    'lessons[].quiz[].explanation',
}


# レッスン参照を探すフィールド。**学習者が読む文章だけ**にする。
# `expected` や `stdin`、`solution` にはレッスンIDと同じ形の文字列がふつうに出てくる
# （`COMMIT 1-3`、二重ループの出力 `1-1` など）ので、走査に入れると誤検出になる。
# `rubric` と `objectives[].text` は問題を書く側の基準で画面に出ないため対象外。
SCANNED_FIELDS = TRANSLATED_FIELDS | {
    'title',
    'subtitle',
    'lessons[].title',
    'lessons[].samples[].caption',
    'lessons[].visibleCases[].label',
    'lessons[].hiddenCases[].label',
    'lessons[].extraTasks[].visibleCases[].label',
    'lessons[].extraTasks[].hiddenCases[].label',
    'lessons[].sourceChecks[].message',
    'lessons[].extraTasks[].sourceChecks[].message',
    'lessons[].artifact.checks[].message',
    'lessons[].extraTasks[].artifact.checks[].message',
    'lessons[].runtimeLab.checks[].label',
    'lessons[].extraTasks[].runtimeLab.checks[].label',
    'lessons[].preflight.buttonLabel',
    'lessons[].preflight.checks[].label',
    'lessons[].preflight.checks[].help',
}


def main():
    listing = '--list' in sys.argv[1:]
    chapters = load_chapters()
    lessons = load_lessons()
    refs = collect_content_refs()
    lab_refs = collect_lab_refs()
    lesson_refs = collect_lesson_refs()
    lab_lesson_refs = collect_lab_lesson_refs(lessons)

    if listing:
        show(refs, lab_refs, chapters)
        show_lessons(lesson_refs, lessons)
        return 0

    problems = []
    problems += check_resolvable(refs, chapters)
    problems += check_translated(refs, chapters)
    problems += check_labs(lab_refs)
    problems += check_chapter_ids()
    problems += check_lesson_resolvable(lesson_refs, lessons, chapters)
    problems += check_lesson_translated(lesson_refs, lessons)
    problems += check_lab_lesson_refs(lab_lesson_refs, lessons)
    problems += check_preflight_position()
    problems += check_part_counts()

    print(f'章参照を{len(refs) + len(lab_refs)}件'
          f'（content {len(refs)}件 / labs {len(lab_refs)}件）、'
          f'レッスン参照を{len(lesson_refs) + len(lab_lesson_refs)}件'
          f'（content {len(lesson_refs)}件 / labs {len(lab_lesson_refs)}件）調べました。')
    if not problems:
        print('  学習者に違う番号が見える参照はありません。')
        return 0

    print(f'\n直す参照が{len(problems)}件あります。', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n参照の書き方は docs/guide.md「章を1つ足す」にあります。', file=sys.stderr)
    return 1


# 章の `id` がファイル名の番号と違うもの。`web/app.js` が数字から別名を作って救っているが、
# **新しく増やさない**ためにここへ挙げておく（増やすと画面に内部番号が出る事故に近づく）。
LEGACY_CHAPTER_IDS = {
    'ch30-jvm-memory.json': '30',
    'ch32-threads-safety.json': '32',
    'ch34-tasks-async-io.json': '34',
    'ch37-performance-lab.json': '37',
    'ch38-resilience-observability.json': '38',
}


def check_chapter_ids():
    """章の `id` は `chNN`（ファイル名と同じ番号）にする。

    `web/app.js` の `localizeChapterReferences` は `第NN章` から **`chNN` を組み立てて**
    章を引く。`id` が `34` のように数字だけだと引けず、**内部番号がそのまま画面に出る**
    （実際に `ch40` の「第34章」がこれで読み替えられていなかった）。いまは app 側で
    数字から別名を作って救っているが、`id` を合わせるのが本筋なので新しいものは失敗にする。
    """
    problems = []
    for path in sorted(CONTENT.glob('ch*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        want = 'ch' + re.match(r'ch(\d+)', path.name).group(1)
        if raw.get('id') == want:
            continue
        if LEGACY_CHAPTER_IDS.get(path.name) == raw.get('id'):
            continue                      # 既知の5章。進捗（rewardedChapters）が壊れるので直さない
        problems.append(
            f'{path.name}: 章の id が {raw.get("id")!r} です。`{want}` にしてください'
            '（`第NN章` の読み替えが `chNN` で章を引くため、違うと内部番号が画面に出ます）')
    return problems


def check_resolvable(refs, chapters):
    """参照先の chNN が無いと読み替えできず、書いた数字がそのまま画面に出る。"""
    problems = []
    for ref in refs:
        if ref['number'] not in chapters:
            problems.append(
                f'{ref["file"]} {ref["path"]}: 第{ref["number"]}章 に対応する '
                f'content/ch{ref["number"]:0>2}-*.json がありません。'
                '内部番号（ファイル名の番号）で書いてください')
    return problems


def check_translated(refs, chapters):
    """変換を通らないフィールドは、内部番号と編内番号が一致していないと違う番号が出る。"""
    problems = []
    for ref in refs:
        if ref['path'] in TRANSLATED_FIELDS:
            continue
        chapter = chapters.get(ref['number'])
        if chapter is None:
            continue          # 解決できない件は check_resolvable が報告済み
        if int(ref['number']) != chapter['partNumber']:
            problems.append(
                f'{ref["file"]} {ref["path"]}: 第{ref["number"]}章 は画面では'
                f'「{chapter["part"]} 第{chapter["partNumber"]}章」と出ますが、'
                'このフィールドは読み替えられません。'
                '編名と章タイトルで参照してください')
    return problems


def check_lesson_resolvable(lesson_refs, lessons, chapters):
    """指せないレッスンを書くと、読み替えられず内部IDがそのまま画面に出る。

    章はあるのにレッスンが無いものだけを失敗にする（`3-4割` のような数の書き方を
    誤って失敗にしないため）。ただしその形は読み替えの対象になるので、
    地の文へ書かないこと自体が決まりである。
    """
    problems = []
    for ref in lesson_refs:
        if ref['id'] in lessons:
            continue
        if ref['chapter'] not in chapters:
            continue          # レッスン参照ではなく、ただの数字と判断する
        problems.append(
            f'{ref["file"]} {ref["path"]}: `{ref["id"]}` に対応するレッスンがありません。'
            f'第{ref["chapter"]}章にあるレッスンIDで書いてください  …{ref["context"]}…')
    return problems


def check_lesson_translated(lesson_refs, lessons):
    """変換を通らないフィールドは、内部IDと画面の番号が一致していないと違う番号が出る。"""
    problems = []
    for ref in lesson_refs:
        if ref['path'] in TRANSLATED_FIELDS:
            continue
        lesson = lessons.get(ref['id'])
        if lesson is None:
            continue          # 解決できない件は check_lesson_resolvable が報告済み
        if ref['id'] != lesson['shown']:
            problems.append(
                f'{ref["file"]} {ref["path"]}: `{ref["id"]}` は画面では'
                f'「{lesson["part"]} {lesson["shown"]}」と出ますが、'
                'このフィールドは読み替えられません。レッスン名で参照してください')
    return problems


def check_lab_lesson_refs(lab_lesson_refs, lessons):
    """READMEはファイルとして読まれるので、レッスンIDも読み替えられない。

    章参照と同じ理由で、labs には番号を書かない。`62-3` は画面では `4-3` と出るので、
    学習者が探しても見つからない（誤読はしないが、引けない）。レッスン名で参照する。
    """
    problems = []
    for ref in lab_lesson_refs:
        lesson = lessons.get(ref['id'])
        shown = f'「{lesson["part"]} {lesson["shown"]}『{lesson["title"]}』」' if lesson else ''
        problems.append(
            f'{ref["file"]}: `{ref["id"]}` と書かれています。画面では{shown}と出るので、'
            'labsではレッスン名で参照してください  …' + ref['context'] + '…')
    return problems


def collect_lab_lesson_refs(lessons):
    refs = []
    for path in sorted(LABS.rglob('*')):
        if not path.is_file() or is_generated(path):
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue
        for match in LESSON_REF.finditer(CODE_BLOCK.sub(lambda m: ' ' * len(m.group(0)), text)):
            key = f'{match.group(1)}-{match.group(2)}'
            if key not in lessons:
                continue          # 版番号や正規表現など、レッスンIDでないもの
            refs.append({'file': str(path), 'id': key, 'task': match.group(3) or '',
                         'context': snippet(text, match.start())})
    return refs


def check_preflight_position():
    """事前確認は章の先頭に1つだけ。画面ではこれだけを `-0` として見せる。

    `web/app.js` は事前確認を本編の番号に数えず `-0` を割り当てる。途中に混ざったり
    2つ入ったりすると、本編の番号が飛ぶか、同じ `-0` が並ぶ。
    """
    problems = []
    for path in sorted(CONTENT.glob('ch*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        positions = [index for index, lesson in enumerate(raw.get('lessons', []))
                     if lesson.get('lessonType') == 'preflight']
        if not positions:
            continue
        if positions != [0]:
            problems.append(
                f'{path.name}: 事前確認が {positions} 番目にあります。'
                '章の先頭に1つだけ置いてください（画面の `-0` が重なります）')
    return problems


def check_labs(lab_refs):
    """READMEはファイルとして読まれるので、誰も番号を読み替えない。"""
    return [f'{ref["file"]}: 第{ref["number"]}章 と書かれています。'
            'labsでは番号を使わず、編名と章タイトル（必要ならレッスン名）で'
            '参照してください'
            for ref in lab_refs]


# 「ここまでのN編」「全N編」のように**編の数を名指しした**書き方。編を足すと黙って古くなる
# （2026-08-23に、8編目の業務アプリ総合演習編が「ここまでの6編」と書いていた ―― 正しくは7編）。
PART_COUNT_REFS = [
    (re.compile(r'ここまでの(\d+)編'), 'before'),
    (re.compile(r'全(\d+)編'), 'total'),
]


def check_part_counts():
    """編の数を名指しした文が、いまの編の数と合っているか。

    `ここまでのN編` はその編より前にある編の数、`全N編` は編の総数と一致させる。
    編を足したり並べ替えたりすると数字だけが古くなり、画面（編の見出しの前提知識）に
    そのまま出る ―― 数え直せば分かることなので、ここで見張る。
    """
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    parts = manifest['parts']
    total = len(parts)
    # 章ファイル -> その編が何編目か（本文で名指ししていても拾えるように）
    part_index_of_file = {}
    for index, part in enumerate(parts, start=1):
        for file_name in part['chapters']:
            part_index_of_file[file_name] = index

    problems = []

    def inspect(text, where, part_index):
        for pattern, kind in PART_COUNT_REFS:
            for match in pattern.finditer(text):
                written = int(match.group(1))
                expected = total if kind == 'total' else part_index - 1
                if written != expected:
                    problems.append(
                        f'{where}: 「{match.group(0)}」は数が合いません'
                        f'（いまは{expected}編）')

    for index, part in enumerate(parts, start=1):
        for key, value in part.items():
            if isinstance(value, str):
                inspect(value, f'manifest.json {part["id"]}.{key}', index)

    for file_name, part_index in part_index_of_file.items():
        refs = []
        walk_part_counts(
            json.loads((CONTENT / file_name).read_text(encoding='utf-8')), '', refs)
        for path, text in refs:
            inspect(text, f'{file_name} {path}', part_index)

    return problems


def walk_part_counts(node, path, found):
    """JSONの文字列をすべて拾う（「編」の数え方は本文のどこに書いても腐る）。"""
    if isinstance(node, str):
        found.append((path, node))
    elif isinstance(node, dict):
        for key, value in node.items():
            walk_part_counts(value, f'{path}.{key}' if path else key, found)
    elif isinstance(node, list):
        for value in node:
            walk_part_counts(value, f'{path}[]', found)


def load_chapters():
    """内部番号 -> {編名, 編内番号, 章タイトル}。編内番号は ContentLoader と同じ採番。"""
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    chapters = {}
    for part in manifest['parts']:
        for part_number, file_name in enumerate(part['chapters'], start=1):
            number = str(int(re.match(r'ch(\d+)', file_name).group(1)))
            raw = json.loads((CONTENT / file_name).read_text(encoding='utf-8'))
            chapters[number] = {
                'part': part['title'],
                'partNumber': part_number,
                'title': raw.get('title', ''),
                'file': file_name,
            }
    return chapters


def load_lessons():
    """レッスンID -> {編名, 画面の番号, 章タイトル}。番号は web/app.js と同じ採番。"""
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    lessons = {}
    for part in manifest['parts']:
        for part_number, file_name in enumerate(part['chapters'], start=1):
            raw = json.loads((CONTENT / file_name).read_text(encoding='utf-8'))
            shown_number = 0
            for lesson in raw.get('lessons', []):
                if lesson.get('lessonType') == 'preflight':
                    number = 0
                else:
                    shown_number += 1
                    number = shown_number
                lessons[lesson['id']] = {
                    'part': part['title'],
                    'shown': f'{part_number}-{number}',
                    'title': lesson.get('title', ''),
                    'file': file_name,
                }
    return lessons


def collect_lesson_refs():
    """本文に現れるレッスン参照を、フィールドのパスごとに拾う。コードブロックは見ない。"""
    refs = []
    for path in sorted(CONTENT.glob('*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        walk_lessons(raw, '', path.name, refs)
    return refs


def walk_lessons(node, path, file_name, refs):
    if isinstance(node, str):
        if path not in SCANNED_FIELDS:
            return
        # コードブロックは同じ長さの空白へ置き換える（位置をずらさず、中身だけ外す）
        text = CODE_BLOCK.sub(lambda m: ' ' * len(m.group(0)), node)
        for match in LESSON_REF.finditer(text):
            refs.append({
                'file': file_name,
                'path': path,
                'id': f'{match.group(1)}-{match.group(2)}',
                'chapter': str(int(match.group(1))),
                'task': match.group(3) or '',
                'context': snippet(node, match.start()),
            })
    elif isinstance(node, dict):
        for key, value in node.items():
            walk_lessons(value, f'{path}.{key}' if path else key, file_name, refs)
    elif isinstance(node, list):
        for value in node:
            walk_lessons(value, f'{path}[]', file_name, refs)


def collect_content_refs():
    refs = []
    for path in sorted(CONTENT.glob('*.json')):
        raw = json.loads(path.read_text(encoding='utf-8'))
        walk(raw, '', path.name, refs)
    return refs


def walk(node, path, file_name, refs):
    """JSONを辿り、文字列に現れる「第NN章」をフィールドのパスごとに拾う。"""
    if isinstance(node, str):
        for match in CHAPTER_REF.finditer(node):
            refs.append({
                'file': file_name,
                'path': path,
                'number': str(int(match.group(1))),
                'context': snippet(node, match.start()),
            })
    elif isinstance(node, dict):
        for key, value in node.items():
            walk(value, f'{path}.{key}' if path else key, file_name, refs)
    elif isinstance(node, list):
        for value in node:
            walk(value, f'{path}[]', file_name, refs)


def collect_lab_refs():
    refs = []
    for path in sorted(LABS.rglob('*')):
        if not path.is_file() or is_generated(path):
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue          # 画像やjarなど。章参照は入らない
        for match in CHAPTER_REF.finditer(text):
            refs.append({
                'file': str(path),
                'path': '',
                'number': str(int(match.group(1))),
                'context': snippet(text, match.start()),
            })
    return refs


def is_generated(path):
    """ビルド生成物。元ファイルを直せば作り直されるので、二重に報告しない。"""
    return any(part in GENERATED_DIRS for part in path.parts)


def snippet(text, position):
    return text[max(0, position - 40):position + 40].replace('\n', ' ')


def show(refs, lab_refs, chapters):
    for ref in refs + lab_refs:
        chapter = chapters.get(ref['number'])
        shown = (f'{chapter["part"]} 第{chapter["partNumber"]}章'
                 f'『{chapter["title"]}』' if chapter else '★解決できません')
        where = f'{ref["file"]} {ref["path"]}'.strip()
        print(f'第{ref["number"]}章 → {shown}\n  {where}\n  …{ref["context"]}…')
    print(f'\ncontent {len(refs)}件 / labs {len(lab_refs)}件')


def show_lessons(lesson_refs, lessons):
    print()
    for ref in lesson_refs:
        lesson = lessons.get(ref['id'])
        shown = (f'{lesson["part"]} {lesson["shown"]}{ref["task"]}'
                 f'『{lesson["title"]}』' if lesson else '★解決できません')
        print(f'{ref["id"]}{ref["task"]} → {shown}\n'
              f'  {ref["file"]} {ref["path"]}\n  …{ref["context"]}…')
    print(f'\nレッスン参照 {len(lesson_refs)}件')


if __name__ == '__main__':
    sys.exit(main())
