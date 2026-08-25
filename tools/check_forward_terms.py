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

見る単位は章である。学習者が章を順に読むからで、`9-3` が「表の**サブクラス**は
第10章で学ぶ…」と断れば、同じ章の `9-4` の章末クイズが `サブクラス` を使うのは構わない。
逆に案内がどこにも無ければ、初見の学習者は**その語だけ意味が取れないまま読み進める**
ことになる。

**表の「教える章」にはレッスンidも書ける**（`void` の `7-2` のように）。そのときは章の
判定に加えて、**同じ章のそれより前のレッスン**も見て、そのレッスン自身に案内があることを
求める。章単位だけでは、`7-1`（画面 `8-1`）の ⚠️ が `void` を使い `void` を教えるのは
`7-2`、という**同じ章の中の先出し**を通してしまう（2026-08-25に利用者から指摘。
「voidの説明がなくわかりにくい」）。レッスン単位の案内は `第NN章` のほかに
`7-2` のようなレッスン参照と「次のレッスン」の言い回しも認める。
書くのは**その語だけのレッスンが章の後ろにある**ときだけでよい。

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
# 教材が書くレッスン参照（`14-4` など）。前後に英数字が付くものは番号ではない
# （`web/app.js` の LESSON_REF と同じ考え方）。
LESSON_REF = re.compile(r'(?<![\w.\[-])(\d{1,2}-\d{1,2})(?![\w.\]-])')
FENCE = re.compile(r'```[\s\S]*?```')
# 番号を書かない案内。`13-3` の「次のレッスンで `throws` を扱います」のような形。
NEXT_LESSON_PHRASES = ('次のレッスン', '次の回', 'このあとのレッスン', '章の最後')

# 用語名, 正規表現, その用語を教える章id（レッスンidを書くと同じ章の前のレッスンも見る）
TERMS = [
    ('配列',          r'配列',                                    'ch06'),
    ('拡張for',       r'拡張\s?for',                              'ch06'),
    ('メソッド',       r'メソッド',                                 'ch07'),
    ('void',         r'(?<!\w)void(?!\w)',                       '7-2'),
    ('戻り値',        r'戻り値',                                   'ch07'),
    ('オーバーロード',  r'オーバーロード',                            'ch07'),
    ('再帰',          r'再帰',                                    'ch07'),
    ('参照型',        r'参照型|参照のコピー',                        'ch07'),
    ('クラス',        r'クラス',                                   'ch08'),
    ('インスタンス',    r'インスタンス',                              'ch08'),
    ('オブジェクト',    r'オブジェクト',                              'ch08'),
    ('フィールド',      r'フィールド',                               '8-1'),
    ('コンストラクタ',   r'コンストラクタ',                            'ch08'),
    ('null',         r'null',                                    '8-4'),
    ('カプセル化',      r'カプセル化|アクセサ',                        'ch09'),
    ('パッケージ',      r'パッケージ',                                'ch09'),
    ('継承',          r'継承|サブクラス|スーパークラス|親クラス|子クラス',    'ch10'),
    ('オーバーライド',   r'オーバーライド|@Override',                   'ch10'),
    ('toString',     r'(?<![.\w])toString',                       '10-4'),
    ('ポリモーフィズム', r'ポリモーフィズム',                            'ch11'),
    ('instanceof',   r'instanceof',                               '11-2'),
    ('抽象クラス',      r'抽象クラス|abstract',                        'ch11'),
    ('インターフェース', r'インターフェース',                            'ch12'),
    ('検査例外',       r'検査例外|throws',                           '13-4'),
    ('StringBuilder', r'StringBuilder',                           '14-4'),
    ('ラッパークラス',   r'ラッパークラス|オートボクシング',               'ch15'),
    ('コレクション',    r'ArrayList|コレクション',                      'ch16'),
    ('ジェネリクス',    r'ジェネリクス',                                '16-2'),
    ('HashMap',      r'HashMap|HashSet',                          'ch16'),
    ('ラムダ',        r'ラムダ',                                     'ch17'),
    ('record',       r'\brecord\b',                               'ch18'),
    ('enum',         r'\benum\b',                                 '18-5'),
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
    ('ZoneId',       r'ZoneId|夏時間|ZonedDateTime',                 '44-1'),
    ('JUnit',        r'JUnit|@Test\b',                              'ch45'),
    ('テストダブル',    r'テストダブル|モック|スタブ',                    'ch45'),
    ('分離レベル',     r'分離レベル|実行計画',                          '46-3'),
    ('冪等',          r'冪等',                                      'ch47'),
    ('synchronized', r'synchronized|\bvolatile\b',                  '32'),
    ('Virtual Threads', r'ExecutorService|Virtual\s?Thread|CompletableFuture', '34-1'),
    ('JFR',          r'\bJFR\b|\bJMH\b|スレッドダンプ',              '37-0'),
    ('Servlet',      r'Servlet|サーブレット',                         'ch22'),
    ('CDI',          r'\bCDI\b|@Inject',                            'ch25'),
    ('JPA',          r'\bJPA\b|EntityManager|JPQL',                  '26-1'),
    ('@Transactional', r'@Transactional',                           'ch27'),
    ('Bean Validation', r'Bean Validation|@NotBlank|@Valid',        'ch29'),
    ('Jakarta Data', r'Jakarta Data',                               'ch48'),
    ('SLI/SLO',      r'\bSLO\b|\bSLI\b',                            '38'),
    ('outbox',       r'outbox',                                     '54-4'),
]

# コードには最初から出ている語。**地の文だけ**を見る（サンプルとひな形は数えない）。
# `void` は `public static void main` として `1-1` から画面に出ているので、コードまで
# 数えると基礎編の全章が当たり、案内を6つ書くことになる。学習者が読めないのは
# 地の文が説明なしに `void` と書く場合だけである。
PROSE_ONLY = {'void'}

# 意図して案内なしに使うもの。足すときは理由を必ず書く。
#   (章id または レッスンid, 用語名): 理由
ALLOWED = {
    ('ch06', '配列'):
        'ch06 が配列の章。同じ章の中の前後は対象にしない',
    ('ch07', '参照型'):
        '7-4「引数はコピーで渡される」が参照の話を初めて説明する回。ch08 の 8-4 が深める',
    ('46-0', '分離レベル'):
        '46-0 は事前確認の回で、章末のSQL labが何を確かめるかを予告している。'
        '`実行計画` の意味を知る必要が無いので、案内を足すと予告が二重になる',
    ('ch43', '@Transactional'):
        '43-4 は「アノテーション自身は何もしない」ことの例として名前だけ挙げている。'
        '意味を知る必要が無いので、案内を足すとかえって「学ぶべきもの」に見える',
}


def main():
    listing = '--list' in sys.argv[1:]
    chapters, partname = curriculum_order()
    index = {chapter['id']: number for number, chapter in enumerate(chapters)}
    lessons = lesson_order(chapters)

    failures = []
    for name, pattern, taught in TERMS:
        regex = re.compile(pattern)
        only_prose = name in PROSE_ONLY
        if '-' in taught:                      # レッスン単位で教える語
            if taught not in lessons:
                failures.append((taught, name, '表が指すレッスンがありません'))
                continue
            number, position, chapter, lesson = lessons[taught]
            place, earlier = (number, position), chapter['lessons'][:position]
            here = joined(lesson_fields(lesson, only_prose))
        else:                                  # 章単位で教える語
            if taught not in index:
                failures.append((taught, name, '表が指す章がありません'))
                continue
            number = index[taught]
            chapter = chapters[number]
            place, earlier = (number, 0), []
            here = joined(fields(chapter, only_prose))
        if not regex.search(here):
            failures.append((taught, name, f'{taught} に出てこません（表の紐づけが古い）'))
        part = partname.get(chapter['id'])

        for before in chapters[:number]:
            if (before['id'], name) in ALLOWED:
                continue
            hits = [where for where, text in fields(before, only_prose) if regex.search(text)]
            if not hits:
                continue
            if points_at(before, number, index, part):
                if listing:
                    print(f'  OK  {before["id"]} が {name} を使い、{taught} への案内あり')
                continue
            failures.append((before['id'], name,
                             f'案内なしに使っています（{taught} で教える）… {hits[0]}'))

        for before in earlier:                 # 同じ章の、教えるレッスンより前
            if (before['id'], name) in ALLOWED or (chapter['id'], name) in ALLOWED:
                continue
            hits = [where for where, text in lesson_fields(before, only_prose)
                    if regex.search(text)]
            if not hits:
                continue
            if points_at_lesson(before, place, index, lessons):
                if listing:
                    print(f'  OK  {before["id"]} が {name} を使い、{taught} への案内あり')
                continue
            failures.append((before['id'], name,
                             f'案内なしに使っています（同じ章の {taught} で教える）… {hits[0]}'))

    print(f'用語{len(TERMS)}件について、教えるところより前の本文を見ました'
          f'（レッスンidで教える場所を決めた語は同じ章の前のレッスンも、'
          f'意図して案内なしに使う{len(ALLOWED)}件は対象外）。')
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


def points_at(chapter, number, index, part):
    """その章のどこかに、`number` 番目以降の章へ学習者を送る案内があるか。

    `第NN章` のほか、**編の名前**も案内として認める（`17-5` は Stream API の
    続きを「後続のJava実践・開発基盤編で学びます」と、章ではなく編で案内している）。
    """
    for _, text in fields(chapter):
        if part and part in text:
            return True
        if chapter_ref_reaches(text, number, index):
            return True
    return False


def points_at_lesson(lesson, place, index, lessons):
    """そのレッスン自身に、同じ章の後ろへ学習者を送る案内があるか。

    認めるのは3つ。`14-4` のようなレッスン参照（教えるレッスン以降）、
    「次のレッスン」の言い回し、`10-1` の「第10章の最後で扱います」のような
    **その章自身**への参照である。

    <b>先の章への `第NN章` は数えない。</b> 章単位の判定では「その章のどこかに
    案内があるか」で足りるが、レッスン単位で同じ扱いにすると `7-1` の
    「`static` の意味は第8章で」が `void` の案内として通ってしまう。
    別の語のための案内なので、同じ章の中の先出しは見逃される。

    番号を探すのはコードブロックの外だけにする（二重ループの出力例が
    `1-1 1-2 …` とレッスンidと同じ形をしている）。
    """
    for _, text in lesson_fields(lesson, only_prose=True):
        text = FENCE.sub('', text)
        if any(phrase in text for phrase in NEXT_LESSON_PHRASES):
            return True
        for raw in CHAPTER_REF.findall(text):
            for target in ('ch' + (raw if len(raw) > 1 else '0' + raw), raw):
                if index.get(target, -1) == place[0]:
                    return True
        for ref in LESSON_REF.findall(text):
            if ref in lessons and lessons[ref][:2] >= place:
                return True
    return False


def chapter_ref_reaches(text, number, index):
    """`第NN章` の参照が、`number` 番目以降の章を指しているか。"""
    for raw in CHAPTER_REF.findall(text):
        for target in ('ch' + (raw if len(raw) > 1 else '0' + raw), raw):
            if index.get(target, -1) >= number:
                return True
    return False


def fields(chapter, only_prose=False):
    """章の中で学習者が読む文章を [(場所, 本文)] で並べる。"""
    out = []
    for lesson in chapter['lessons']:
        out.extend(lesson_fields(lesson, only_prose))
    return out


def lesson_fields(lesson, only_prose=False):
    """1レッスンぶんを [(場所, 本文)] で並べる。

    `only_prose` では地の文だけにする（解説はコードブロックを外し、サンプルは見出しだけ、
    ひな形は数えない）。コードに最初から出ている語（`PROSE_ONLY`）を見るときに使う。
    """
    head = f'{lesson["id"]}'
    explanation = lesson.get('explanation') or ''
    out = [(f'{head} 解説', FENCE.sub('', explanation) if only_prose else explanation)]
    for sample in lesson.get('samples') or []:
        caption = sample.get('caption') or ''
        out.append((f'{head} サンプル',
                    caption if only_prose else caption + '\n' + (sample.get('code') or '')))
    for quiz in lesson.get('quiz') or []:
        out.append((f'{head} クイズ', '\n'.join(
            [quiz.get('question') or '', quiz.get('explanation') or '']
            + [c for c in (quiz.get('choices') or []) if isinstance(c, str)])))
    tasks = ([lesson] if lesson.get('task') else []) + list(lesson.get('extraTasks') or [])
    for number, task in enumerate(tasks, start=1):
        out.append((f'{head}#{number} 課題文', task.get('task') or ''))
        if not only_prose:
            out.append((f'{head}#{number} ひな形', task.get('starterCode') or ''))
        for hint in task.get('hints') or []:
            out.append((f'{head}#{number} ヒント', hint))
    return out


def joined(entries):
    return '\n'.join(text for _, text in entries)


def lesson_order(chapters):
    """レッスンidから (章の順番, 章の中の順番, 章, レッスン) を引く表。"""
    out = {}
    for number, chapter in enumerate(chapters):
        for position, lesson in enumerate(chapter['lessons']):
            out[lesson['id']] = (number, position, chapter, lesson)
    return out


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
