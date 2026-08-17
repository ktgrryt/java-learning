"""content/*.json から省略可能なキーが黙って消えていないかを確かめる。

tools/check-content-inventory.sh から呼ばれる。

  python3 tools/check_content_inventory.py           … スナップショットと比べる
  python3 tools/check_content_inventory.py --update  … スナップショットを書き直す

## なぜ必要か

`ContentLoader` は必須のキーが欠けると例外で止まる。しかし **省略可能なキーは、
消えても何も起きない**。`sourceChecks` を丸ごと落とした章は、模範解答検証も
ひな形検証も通ってしまう（検査が減っただけで、残った検査は正しく動くため）。

実際に34問から68件の `sourceChecks` が消えた。原因は、章のJSONを生成スクリプトで
作り直したときに、出力側が並べていないキーが落ちたことである。`type` のような
必須キーは `ContentLoader` が止めたが、`sourceChecks` は黙って消えた。

## 何を見るか

レッスンと問題ごとに**個数だけ**を記録し、前回より**減っていたら失敗**にする。
増えるのは通常の加筆なので何も言わない。中身は見ないので、書き換えは自由にできる。

  レッスン: quiz の数、preflight の検査数、問題の数
  問題ごと: type、sourceChecks・hints・visibleCases・hiddenCases の数、
            artifact/runtime-lab/project の検査数、solution の有無

意図して減らしたときは `--update` でスナップショットを書き直す。差分に
「どのレッスンの何が減ったか」がそのまま出るので、レビューで確認できる。
"""
import json
import pathlib
import sys

SNAPSHOT = pathlib.Path('tools/content-inventory.json')
CONTENT = pathlib.Path('content')

# 問題ごとに記録する項目。順番はスナップショットの並びと一致させる。
TASK_FIELDS = ['type', 'sourceChecks', 'hints', 'visibleCases', 'hiddenCases',
               'checks', 'solution']


def main():
    update = '--update' in sys.argv[1:]
    current = collect()

    if update:
        SNAPSHOT.write_text(render(current), encoding='utf-8')
        print(f'{SNAPSHOT} を書き直しました（レッスン{len(current)}件）。')
        print('差分を確認して、減った項目が意図したものかを確かめてください。')
        return 0

    if not SNAPSHOT.exists():
        print(f'{SNAPSHOT} がありません。--update で作ってください。', file=sys.stderr)
        return 1

    previous = json.loads(SNAPSHOT.read_text(encoding='utf-8'))
    problems = compare(previous, current)

    print(f'レッスン{len(current)}件を、前回のスナップショットと比べました。')
    if not problems:
        print('  省略可能なキーが消えた箇所はありません。')
        return 0

    print(f'\n減っている項目が{len(problems)}件あります。', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n意図した削除なら `./tools/check-content-inventory.sh --update` で'
          'スナップショットを更新してください。', file=sys.stderr)
    return 1


def collect():
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    files = [c for part in manifest['parts'] for c in part['chapters']]
    inventory = {}
    for name in files:
        data = json.loads((CONTENT / name).read_text(encoding='utf-8'))
        for lesson in data.get('lessons', []):
            entry = {
                'quiz': len(lesson.get('quiz') or []),
                'preflight': len((lesson.get('preflight') or {}).get('checks') or []),
                'tasks': [task_counts(t) for t in tasks_of(lesson)],
            }
            inventory[lesson['id']] = entry
    return inventory


def tasks_of(lesson):
    # 事前確認レッスンと概念レッスンは提出課題を持たない。レッスン本体を1問目として
    # 数えると、実在しない single-file 問題がスナップショットへ入る。
    if lesson.get('lessonType') in ('preflight', 'concept'):
        return []
    if lesson.get('type') == 'preflight' or 'preflight' in lesson:
        return []
    return [lesson] + list(lesson.get('extraTasks') or [])


def task_counts(task):
    kind = task.get('type', 'single-file')
    if kind == 'artifact':
        checks = len((task.get('artifact') or {}).get('checks') or [])
    elif kind == 'runtime-lab':
        spec = task.get('runtimeLab') or task.get('runtime_lab') or {}
        checks = len(spec.get('checks') or [])
    elif kind == 'project':
        checks = len((task.get('project') or {}).get('editableFiles') or [])
    else:
        checks = 0
    return [
        kind,
        len(task.get('sourceChecks') or []),
        len(task.get('hints') or []),
        len(task.get('visibleCases') or []),
        len(task.get('hiddenCases') or []),
        checks,
        1 if (task.get('solution') or task.get('project') or task.get('runtimeLab')) else 0,
    ]


def compare(previous, current):
    problems = []
    for lesson_id, before in previous.items():
        after = current.get(lesson_id)
        if after is None:
            problems.append(f'{lesson_id}: レッスンが無くなっています')
            continue
        for key in ('quiz', 'preflight'):
            if after[key] < before[key]:
                problems.append(
                    f'{lesson_id}: {key} が {before[key]} → {after[key]} に減っています')
        if len(after['tasks']) < len(before['tasks']):
            problems.append(f'{lesson_id}: 問題数が {len(before["tasks"])} → '
                            f'{len(after["tasks"])} に減っています')
        for index, task_before in enumerate(before['tasks']):
            if index >= len(after['tasks']):
                break
            task_after = after['tasks'][index]
            for position, field in enumerate(TASK_FIELDS):
                old, new = task_before[position], task_after[position]
                if field == 'type':
                    if old != new:
                        problems.append(
                            f'{lesson_id}#{index}: type が {old} → {new} に変わっています')
                elif new < old:
                    problems.append(f'{lesson_id}#{index}: {field} が {old} → {new} '
                                    'に減っています')
    return problems


def render(inventory):
    """レッスン1件1行で書く。差分に「どのレッスンの何が減ったか」がそのまま出る。"""
    lines = ['{']
    items = list(inventory.items())
    for position, (lesson_id, entry) in enumerate(items):
        tasks = ', '.join(json.dumps(t, ensure_ascii=False) for t in entry['tasks'])
        comma = '' if position == len(items) - 1 else ','
        lines.append(f'  {json.dumps(lesson_id)}: {{"quiz": {entry["quiz"]}, '
                     f'"preflight": {entry["preflight"]}, "tasks": [{tasks}]}}{comma}')
    lines.append('}')
    return '\n'.join(lines) + '\n'


if __name__ == '__main__':
    sys.exit(main())
