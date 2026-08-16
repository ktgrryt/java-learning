"""章参照（「第NN章」）が、学習者に正しい番号で見えるかを確かめる。

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

## 何を見るか

  content … `第NN章` が manifest の章へ解決できること
  content … 変換を通らないフィールドでは、内部番号と編内番号が一致していること
  labs   … `第NN章` を書いていないこと（編名と章タイトルで参照する）

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


def main():
    listing = '--list' in sys.argv[1:]
    chapters = load_chapters()
    refs = collect_content_refs()
    lab_refs = collect_lab_refs()

    if listing:
        show(refs, lab_refs, chapters)
        return 0

    problems = []
    problems += check_resolvable(refs, chapters)
    problems += check_translated(refs, chapters)
    problems += check_labs(lab_refs)
    problems += check_chapter_ids()

    print(f'章参照を{len(refs) + len(lab_refs)}件'
          f'（content {len(refs)}件 / labs {len(lab_refs)}件）調べました。')
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


def check_labs(lab_refs):
    """READMEはファイルとして読まれるので、誰も番号を読み替えない。"""
    return [f'{ref["file"]}: 第{ref["number"]}章 と書かれています。'
            'labsでは番号を使わず、編名と章タイトル（必要ならレッスン名）で'
            '参照してください'
            for ref in lab_refs]


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


if __name__ == '__main__':
    sys.exit(main())
