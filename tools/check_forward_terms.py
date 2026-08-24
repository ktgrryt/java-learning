"""まだ習っていない用語を、断りなく解説やクイズで使っていないかを検査する。

tools/check-forward-terms.sh から呼ばれる。

  python3 tools/check_forward_terms.py          … 検査する
  python3 tools/check_forward_terms.py --list   … 用語ごとの判定を全部出す

## なぜ必要か

`5-5`（画面の `6-5`。二重ループ）は、内側から外側のループを抜ける3つのやり方の1つ目に
「メソッドへ切り出して `return`」を挙げ、「いちばん読みやすい。まずこれを考える」と
書いていた。**メソッドを学ぶのは2章あと**（`ch07`）なので、読んだ時点では選べない。
同じレッスンの `⚠️` も「3重以上になったらメソッドへ切り出す」と書いていた。
2026-08-24に利用者から指摘があり、ここを含む12章ぶんをそろえた。

`check_hint_dependency.py` が「必要な**道具**がヒントにしか無い」ことを見て、
`check_starter_syntax.py` が「初めて習う**構文**をひな形が先に書く」ことを見るのに対し、
ここは「まだ習っていない**用語**を地の文が断りなく使う」ことを見る。

## 判定

用語ごとに「それを教える章」を下の表で決め、**それより前の章の本文**に出てきたら、
その章のどこかに **その用語を教える章への `第NN章` 参照**があることを求める。

  失敗1  前の章が使っているのに、その章に教える章への案内が無い
  失敗2  表が指す章に、その用語が出てこない（表の紐づけが古い）

見る単位を章にしているのは、学習者が章を順に読むからである。`9-3` が
「表の**サブクラス**は第10章で学ぶ…」と断れば、同じ章の `9-4` の章末クイズが
`サブクラス` を使うのは構わない。逆に案内がどこにも無ければ、初見の学習者は
**その語だけ意味が取れないまま読み進める**ことになる。

案内は `第NN章` と**内部番号**で書く（`web/app.js` の `localizeChapterReferences` が
画面の番号へ読み替える）。`第7〜8章` のような書き方は読み替えを通らないので、
`check_chapter_refs.py` ではなくこの検査で `第7章` と `第8章` に割るきっかけになる。

見るのはJava基礎編（`java-se`）だけである。実践編以降は複数の編を行き来して読む作りで、
前提知識も編の `prerequisite` で示している（`check_hint_dependency.py` が編を絞るのと同じ理由）。

意図して案内なしに使うものは `ALLOWED` へ理由付きで入れる。
"""
import json
import pathlib
import re
import sys

CONTENT = pathlib.Path('content')
CHAPTER_REF = re.compile(r'第(\d+)章')

# 用語名, 正規表現, その用語を教える章id
TERMS = [
    ('配列',          r'配列',                                    'ch06'),
    ('拡張for',       r'拡張\s?for',                              'ch06'),
    ('メソッド',       r'メソッド',                                 'ch07'),
    ('戻り値',        r'戻り値',                                   'ch07'),
    ('オーバーロード',  r'オーバーロード',                            'ch07'),
    ('再帰',          r'再帰',                                    'ch07'),
    ('参照型',        r'参照型|参照のコピー',                        'ch07'),
    ('クラス',        r'クラス',                                   'ch08'),
    ('インスタンス',    r'インスタンス',                              'ch08'),
    ('オブジェクト',    r'オブジェクト',                              'ch08'),
    ('フィールド',      r'フィールド',                               'ch08'),
    ('コンストラクタ',   r'コンストラクタ',                            'ch08'),
    ('null',         r'null',                                    'ch08'),
    ('カプセル化',      r'カプセル化|アクセサ',                        'ch09'),
    ('パッケージ',      r'パッケージ',                                'ch09'),
    ('継承',          r'継承|サブクラス|スーパークラス|親クラス|子クラス',    'ch10'),
    ('オーバーライド',   r'オーバーライド|@Override',                   'ch10'),
    ('toString',     r'(?<![.\w])toString',                       'ch10'),
    ('ポリモーフィズム', r'ポリモーフィズム',                            'ch11'),
    ('instanceof',   r'instanceof',                               'ch11'),
    ('抽象クラス',      r'抽象クラス|abstract',                        'ch11'),
    ('インターフェース', r'インターフェース',                            'ch12'),
    ('検査例外',       r'検査例外|throws',                           'ch13'),
    ('StringBuilder', r'StringBuilder',                           'ch14'),
    ('ラッパークラス',   r'ラッパークラス|オートボクシング',               'ch15'),
    ('コレクション',    r'ArrayList|コレクション',                      'ch16'),
    ('ジェネリクス',    r'ジェネリクス',                                'ch16'),
    ('HashMap',      r'HashMap|HashSet',                          'ch16'),
    ('ラムダ',        r'ラムダ',                                     'ch17'),
    ('record',       r'\brecord\b',                               'ch18'),
    ('enum',         r'\benum\b',                                 'ch18'),
    ('テキストブロック', r'テキストブロック',                            'ch18'),
    ('LocalDate',    r'LocalDate',                                'ch19'),
    ('例外',          r'例外',                                      'ch64'),
    # ── 実践編以降 ──
    ('Optional',      r'Optional',                                 'ch40'),
    ('Collectors',    r'Collectors',                               'ch40'),
    ('ワイルドカード',   r'ワイルドカード|型消去',                       'ch41'),
    ('Queue',        r'\bQueue\b|\bDeque\b|Sequenced Collection',  'ch67'),
    ('ネストしたクラス', r'ネストしたクラス|inner class|nested class',    'ch67'),
    ('BigDecimal',   r'BigDecimal|BigInteger',                     'ch42'),
    ('正規表現',       r'正規表現|\bPattern\b|\bMatcher\b',           'ch66'),
    ('sealed',       r'\bsealed\b',                                'ch43'),
    ('リフレクション',  r'リフレクション',                              'ch43'),
    ('ZoneId',       r'ZoneId|夏時間|ZonedDateTime',                 'ch44'),
    ('JUnit',        r'JUnit|@Test\b',                              'ch45'),
    ('テストダブル',    r'テストダブル|モック|スタブ',                    'ch45'),
    ('分離レベル',     r'分離レベル|実行計画',                          'ch46'),
    ('冪等',          r'冪等',                                      'ch47'),
    ('synchronized', r'synchronized|\bvolatile\b',                  '32'),
    ('Virtual Threads', r'ExecutorService|Virtual\s?Thread|CompletableFuture', '34'),
    ('JFR',          r'\bJFR\b|\bJMH\b|スレッドダンプ',              '37'),
    ('Servlet',      r'Servlet|サーブレット',                         'ch22'),
    ('CDI',          r'\bCDI\b|@Inject',                            'ch25'),
    ('JPA',          r'\bJPA\b|EntityManager|JPQL',                  'ch26'),
    ('@Transactional', r'@Transactional',                           'ch27'),
    ('Bean Validation', r'Bean Validation|@NotBlank|@Valid',        'ch29'),
    ('Jakarta Data', r'Jakarta Data',                               'ch48'),
    ('SLI/SLO',      r'\bSLO\b|\bSLI\b',                            '38'),
    ('outbox',       r'outbox',                                     'ch54'),
]

# 意図して案内なしに使うもの。足すときは理由を必ず書く。
#   (章id, 用語名): 理由
ALLOWED = {
    ('ch06', '配列'):
        'ch06 が配列の章。同じ章の中の前後は対象にしない',
    ('ch07', '参照型'):
        '7-4「引数はコピーで渡される」が参照の話を初めて説明する回。ch08 の 8-4 が深める',
    ('ch43', '@Transactional'):
        '43-4 は「アノテーション自身は何もしない」ことの例として名前だけ挙げている。'
        '意味を知る必要が無いので、案内を足すとかえって「学ぶべきもの」に見える',
}


def main():
    listing = '--list' in sys.argv[1:]
    chapters, partname = curriculum_order()
    index = {chapter['id']: number for number, chapter in enumerate(chapters)}

    failures = []
    for name, pattern, taught in TERMS:
        regex = re.compile(pattern)
        if taught not in index:
            failures.append((taught, name, '表が指す章がありません'))
            continue
        if not regex.search(prose(chapters[index[taught]])):
            failures.append((taught, name, f'{taught} に出てこません（表の紐づけが古い）'))
        for chapter in chapters[:index[taught]]:
            if (chapter['id'], name) in ALLOWED:
                continue
            hits = [where for where, text in fields(chapter) if regex.search(text)]
            if not hits:
                continue
            if points_at(chapter, taught, index, partname.get(taught)):
                if listing:
                    print(f'  OK  {chapter["id"]} が {name} を使い、{taught} への案内あり')
                continue
            failures.append((chapter['id'], name,
                             f'案内なしに使っています（{taught} で教える）… {hits[0]}'))

    print(f'用語{len(TERMS)}件について、教える章より前の本文を見ました'
          f'（意図して案内なしに使う{len(ALLOWED)}件は対象外）。')
    if not failures:
        print('  まだ習っていない用語は、教える章への案内とともに出ています。')
        return 0

    print(f'\n案内なしに先の章の用語を使っている箇所が{len(failures)}件あります。', file=sys.stderr)
    for where, name, verdict in failures:
        print(f'  {where}: {name} … {verdict}', file=sys.stderr)
    print('\n直し方は4つです。その語を使わずに書き直す／同じ章のどこかへ「第NN章で学ぶ」と'
          '案内を書く／その時点の道具で足りる説明に変える／表の「教える章」を実態へ合わせる。'
          '意図して案内なしに使うなら ALLOWED へ理由付きで入れる。', file=sys.stderr)
    return 1


def points_at(chapter, taught, index, partname):
    """その章のどこかに、`taught` 以降へ学習者を送る案内があるか。

    `第NN章` のほか、**編の名前**も案内として認める（`17-5` は Stream API の
    続きを「後続のJava実践・開発基盤編で学びます」と、章ではなく編で案内している）。
    """
    for _, text in fields(chapter):
        if partname and partname in text:
            return True
        for number in CHAPTER_REF.findall(text):
            for target in ('ch' + (number if len(number) > 1 else '0' + number), number):
                if index.get(target, -1) >= index[taught]:
                    return True
    return False


def fields(chapter):
    """学習者が読む文章を [(場所, 本文)] で並べる。"""
    out = []
    for lesson in chapter['lessons']:
        head = f'{lesson["id"]}'
        out.append((f'{head} 解説', lesson.get('explanation') or ''))
        for sample in lesson.get('samples') or []:
            out.append((f'{head} サンプル', (sample.get('caption') or '')
                        + '\n' + (sample.get('code') or '')))
        for quiz in lesson.get('quiz') or []:
            out.append((f'{head} クイズ', '\n'.join(
                [quiz.get('question') or '', quiz.get('explanation') or '']
                + [c for c in (quiz.get('choices') or []) if isinstance(c, str)])))
        tasks = ([lesson] if lesson.get('task') else []) + list(lesson.get('extraTasks') or [])
        for number, task in enumerate(tasks, start=1):
            out.append((f'{head}#{number} 課題文', task.get('task') or ''))
            out.append((f'{head}#{number} ひな形', task.get('starterCode') or ''))
            for hint in task.get('hints') or []:
                out.append((f'{head}#{number} ヒント', hint))
    return out


def prose(chapter):
    return '\n'.join(text for _, text in fields(chapter))


def curriculum_order():
    """全編の章をカリキュラム順に並べ、章idごとの編名も返す。"""
    manifest = json.load(open(CONTENT / 'manifest.json', encoding='utf-8'))
    chapters, partname = [], {}
    for part in manifest['parts']:
        for filename in part['chapters']:
            data = json.load(open(CONTENT / filename, encoding='utf-8'))
            chapters.append(data)
            partname[data['id']] = part['title']
    return chapters, partname


if __name__ == '__main__':
    sys.exit(main())
