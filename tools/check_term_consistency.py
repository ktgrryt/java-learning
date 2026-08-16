"""散文に裸の英語が残っていないかを検査する（レビュー08-14の §7-4）。

tools/check-term-consistency.sh から呼ばれる。

  python3 tools/check_term_consistency.py           … 検査する
  python3 tools/check_term_consistency.py --list    … 残っている箇所を全部出す
  python3 tools/check_term_consistency.py --measure … 語ごとの混在率を出す（失敗にしない）

## なぜ必要か

同じものを英語とカタカナで呼び分けると、初学者は**別概念かどうかを毎回考えることになる**。
2026-08-15の実測では `container` 41% / `image` 39% / `timeout` 49% が混在していた。
一度そろえても、章が増えれば戻る（クイズの手がかりで同じことが起きた。F-16）。だから数える。

## 何を見るか

見るのは `explanation` `task` `hints` `title` `message` だけ。**コードブロックと `…` の中は見ない**
（`solution` `starterCode` `samples` も対象外）。そこは英語が正しい。

  裸の一般名詞（`container` `image` `build` …）… 失敗にする。カタカナにするか `…` で囲む
  大文字始まり（`Response` `Thread`）        … 見ない。クラス名・固有名詞である
  KEEP に挙げた語                            … 見ない。仕様・製品の用語（authorization server など）

## 直し方

  一般名詞なら       → カタカナにする（container → コンテナ）
  識別子・製品名なら → `…` で囲む（`timeout`、`build`）。囲めば検査の対象から外れる
  新しい仕様用語なら → 下の KEEP へ足す。**足す理由をコメントに書くこと**

基準は `docs/guide.md`「英語のままにする語と、日本語にする語」にある。
"""
import glob
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
FIELDS = ('explanation', 'task', 'hints', 'title', 'message')

# 一般名詞。散文では日本語（カタカナ）で書く。
GENERAL = {
    'container': 'コンテナ',
    'image': 'イメージ',
    'build': 'ビルド',
    'timeout': 'タイムアウト',
    'download': 'ダウンロード',
    'request': 'リクエスト',
    'response': 'レスポンス',
    'module': 'モジュール',
    'thread': 'スレッド',
    'memory': 'メモリ',
    'server': 'サーバー',
    'compile': 'コンパイル',
    'deploy': 'デプロイ',
}

# 英語のまま書く語。**足すときは理由を書くこと。**
KEEP = re.compile(
    # 製品の機能名（公式が固有名として大文字で書く）
    r'Native\s+Image|Build-time\s+Optimization|Build\s+time|Run\s+time'
    r'|Dev\s+Services|InstantOn|Native\s+Memory\s+Tracking'
    # imageの種別。公式のimage名の一部
    r'|(?:builder|micro|base)\s+image'
    # アノテーションと列挙定数
    r'|RUNTIME/METHOD|Fault\s+Tolerance:\s*Timeout'
    # 仕様の用語（OAuth 2.0 / JPMS / Loom / GitHub）
    r'|authorization\s+server|resource\s+server|unnamed\s+module|named\s+module'
    r'|module\s+path|module-info|carrier\s+thread|pull\s+request'
    # ログの項目名として英語で並べている箇所
    r'|trace/request'
    # 「配備（deploy）」のような語釈。日本語と英語の対応を示している
    r'|（[a-z][a-z\s./-]*）'
)


def main():
    args = sys.argv[1:]
    findings = []
    counts = {word: [0, 0] for word in GENERAL}
    for path in sorted(glob.glob(str(CONTENT / 'ch*.json'))):
        data = json.load(open(path, encoding='utf-8'))
        for where, text in prose_of(data):
            visible = mask(text)
            for word, japanese in GENERAL.items():
                counts[word][1] += len(re.findall(re.escape(japanese), visible))
                for m in re.finditer(rf'(?<![A-Za-z`-]){word}(?![A-Za-z`-])', visible):
                    counts[word][0] += 1
                    findings.append((pathlib.Path(path).name, where, word, japanese,
                                     text[max(0, m.start() - 40):m.end() + 24]))

    if '--measure' in args:
        print(f'{"英語":11}{"件":>5}  {"日本語":11}{"件":>5}  混在率')
        for word, (en, ja) in sorted(counts.items(), key=lambda kv: -kv[1][0]):
            total = en + ja
            rate = min(en, ja) / total * 100 if total else 0
            print(f'{word:11}{en:5}  {GENERAL[word]:11}{ja:5}  {rate:4.0f}%')
        return 0

    print(f'散文（解説・問題文・ヒント・見出し）に裸の英語が{len(findings)}件あります'
          f'（コードと `…` の中は見ていません）。')
    if not findings:
        print('  英語とカタカナの呼び分けはありません。')
        return 0

    if '--list' in args:
        for name, where, word, japanese, snippet in findings:
            print(f'  {name} {where}: {word} → {japanese}')
            print(f'      …{snippet.strip()}…')
    else:
        seen = {}
        for name, _, word, _, _ in findings:
            seen.setdefault(word, set()).add(name)
        for word, files in sorted(seen.items(), key=lambda kv: -len(kv[1])):
            print(f'  {word} → {GENERAL[word]}: {len(files)}ファイル'
                  f'（{" ".join(sorted(files)[:4])}{" …" if len(files) > 4 else ""}）')

    print('\n直す箇所があります。一般名詞ならカタカナにし、識別子・製品名なら `…` で囲みます'
          '（囲めば対象から外れます）。新しい仕様用語なら理由を添えて KEEP へ足します。'
          '基準は docs/guide.md「英語のままにする語と、日本語にする語」。--list で全部出せます。',
          file=sys.stderr)
    return 1


def prose_of(data):
    """対象フィールドの文字列を、どのレッスンかつきで返す。"""
    out = []

    def walk(node, key, where):
        if isinstance(node, dict):
            here = node.get('id', where) if 'id' in node else where
            for k, v in node.items():
                walk(v, k, here)
        elif isinstance(node, list):
            for v in node:
                walk(v, key, where)
        elif isinstance(node, str) and key in FIELDS:
            out.append((where, node))

    walk(data, None, '（章）')
    return out


def mask(text):
    """コードブロック・`…`・KEEPの語を伏せる。長さは変えない（位置をずらさないため）。"""
    text = re.sub(r'```.*?```', lambda m: ' ' * len(m.group()), text, flags=re.S)
    text = re.sub(r'`[^`\n]*`', lambda m: ' ' * len(m.group()), text)
    return KEEP.sub(lambda m: ' ' * len(m.group()), text)


if __name__ == '__main__':
    sys.exit(main())
