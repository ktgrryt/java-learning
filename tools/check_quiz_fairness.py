"""確認クイズに「Javaを知らなくても解ける手がかり」が無いかを検査する。

tools/check-quiz-fairness.sh から呼ばれる。

  python3 tools/check_quiz_fairness.py           … 検査する
  python3 tools/check_quiz_fairness.py --list    … 基準を外れている問題を全部出す

## なぜ必要か

4択で正解を当てられてしまう手がかりは、Javaの理解とは無関係に正答率を上げる。過去に3つ潰したが、
クイズが391問へ増える過程で2つが戻っていた（`docs/guide.md`「クイズを足すとき」の表）。
人手のレビューでは戻ったことに気づけなかったので、機械で数える。

見張るのは次の2つ。**どちらも「誤答を具体化する」ことで同時に直る**（実際に戻っていた原因は、
誤答が「物理サーバー本体」「一通り確認しました」のような投げやりな一言で、選択肢として
成立していなかったこと。正解だけが具体的な技術的記述になるので長さも断定語も偏る）。

  1. 正解が目に見えて長い … 長いほうを選ぶだけで当たってしまう
     （1文字未満の差は見分けられないので数えない。数えるのは全角2文字ぶん以上の差）
  2. 断定語（必ず・常に・すべて・絶対）が誤答にだけ出る … 断定を避けるだけで当たってしまう

## 正解の位置は「章単位」で見る

**全体の分布は失敗にしない**（544問で 27/28/24/20 のように、足していけば自然に均れる）。
見るのは**章ごとの偏り**である。2026-08-26の精査で、`ch60`（Spring Boot）・`ch61`
（Open Liberty）・`ch62`（Quarkus）の**36問すべてが `answer: 0`** だったのが見つかった ――
先頭を選び続けるだけで36問正解になるのに、全体の集計では 27/28/24/20 で健全に見えていた。
**1章ずつ数えないと出ない**種類なので、ここで数える。

並べ替えるときは `progress.json` の `quizChoices`（学習者が選んだ**番号**）を新しい位置へ
読み替える段を `ProgressStore` の `QUIZ_SWAPS` へ足す。読み替えれば、正解した記録が誤答へ
化けることはない（記録を捨てる必要もない）。

## 基準

`1` は「1〜2文字長い」を数えても意味がない（学習者が見分けられない）。**目に見える差**だけを
数えるため、`LONG_MARGIN`（画面上の幅。全角8文字ぶん）以上長いものを対象にする。長さは
文字数ではなく幅で測る（`Jakarta EE/MicroProfile` のような半角のAPI名を、全角と同じ重みで
数えないため）。`2` は4択なので、断定語を含む選択肢が誤答である割合は 75% が偶然の水準になる。
"""
import json
import pathlib
import sys
import unicodedata

CONTENT = pathlib.Path('content')

# 断定語。仕様上いつも成り立つ場合だけ使い、水増しには使わない。
ASSERTIVE = ('必ず', '常に', 'すべて', '絶対')

# 長さは**画面上の幅**で測る。文字数で数えると `Jakarta EE/MicroProfile` のような半角の
# API名が全角と同じ重みになり、見た目は長くないのに長いと判定されてしまう（逆もある）。
# 全角=2、半角=1として数え、しきい値も同じ単位で持つ（16 ≒ 日本語8文字ぶん）。
LONG_MARGIN = 10       # 「目に見えて長い」と見なす幅の差（全角5文字ぶん）
MAX_LONG = 5           # 目に見えて長い問題を許す数
# 「長いほうを選ぶ」戦略が通るかは、**見分けられる差**で数える。1文字未満の差
# （`void Book(String t) { }` と `Book(int a, int b) { }` の差は半角1つ）は誰も見分けられず、
# それを数えても実態を表さない。全角2文字ぶん以上長いものを「見分けられる」とし、
# その割合が4択の偶然の水準（25%）を大きく超えないことを見る。
PERCEPTIBLE = 4        # 見分けられる差（全角2文字ぶん）
MAX_PERCEPTIBLE_RATE = 0.30
MAX_ASSERTIVE_WRONG = 0.75   # 断定語を含む選択肢が誤答である割合の上限（4択の偶然の水準）
# 章単位の偏り。1章のクイズが少ないと偏って見えるのは当たり前なので、数える下限を置く。
# 4択で5問なら、同じ位置が4問（80%）来る確率は偶然でも十分あるが、7割を超え続ける章は
# 作り方の癖である（ch60〜62は12問すべてが同じ位置だった）。
MIN_CHAPTER_QUIZZES = 5      # これ未満の章は数えない
MAX_CHAPTER_SHARE = 0.7      # 1つの位置に集まっていい割合の上限


def main():
    listing = '--list' in sys.argv[1:]
    quizzes = load()
    total = len(quizzes)

    long_ones = [q for q in quizzes if margin(q) >= LONG_MARGIN]
    perceptible = [q for q in quizzes if margin(q) >= PERCEPTIBLE]
    perceptible_rate = len(perceptible) / total
    assertive = [(q, index) for q in quizzes
                 for index, choice in enumerate(q['choices'])
                 if any(word in choice for word in ASSERTIVE)]
    assertive_wrong = [(q, i) for q, i in assertive if i != q['answer']]
    ratio = len(assertive_wrong) / len(assertive) if assertive else 0.0

    print(f'確認クイズ{total}問を調べました。')
    print(f'  正解が全角{LONG_MARGIN // 2}文字ぶん以上長い: {len(long_ones)}問'
          f'（{len(long_ones)/total*100:.1f}% / 上限{MAX_LONG}問）')
    print(f'  正解が見分けられるほど長い（全角{PERCEPTIBLE // 2}文字ぶん以上）: {len(perceptible)}問'
          f'（{perceptible_rate*100:.1f}% / 上限{MAX_PERCEPTIBLE_RATE*100:.0f}%、偶然の水準25%）')
    print(f'  断定語を含む選択肢: {len(assertive)}件、うち誤答 {len(assertive_wrong)}件'
          f'（{ratio*100:.1f}% / 上限{MAX_ASSERTIVE_WRONG*100:.0f}%）')
    print(f'  正解の位置（全体）: {position_summary(quizzes)}')
    skewed = skewed_chapters(quizzes)
    if skewed:
        print(f'  正解の位置が1か所へ集まっている章: {len(skewed)}章')
        for file_name, counts, share, position in skewed:
            print(f'      {file_name} {counts} … 位置{position}が{share*100:.0f}%')
    else:
        print(f'  正解の位置が1か所へ集まっている章: なし'
              f'（{MIN_CHAPTER_QUIZZES}問以上の章で上限{MAX_CHAPTER_SHARE*100:.0f}%）')

    if listing:
        show(long_ones, assertive_wrong)
        return 0

    problems = []
    if len(long_ones) > MAX_LONG:
        problems.append(f'正解が全角{LONG_MARGIN // 2}文字ぶん以上長い問題が{len(long_ones)}問あります'
                        f'（上限{MAX_LONG}問）。長いほうを選ぶだけで当たってしまいます')
    if perceptible_rate > MAX_PERCEPTIBLE_RATE:
        problems.append(f'正解が見分けられるほど長い問題が{perceptible_rate*100:.1f}%あります'
                        f'（上限{MAX_PERCEPTIBLE_RATE*100:.0f}%）。'
                        '長いほうを選ぶ戦略が、偶然の水準（25%）より有利になっています')
    if ratio > MAX_ASSERTIVE_WRONG:
        problems.append(f'断定語を含む選択肢のうち誤答が{ratio*100:.1f}%です'
                        f'（上限{MAX_ASSERTIVE_WRONG*100:.0f}%）。'
                        '断定を避けるだけで当たってしまいます')
    for file_name, counts, share, position in skewed:
        problems.append(f'{file_name} は正解の位置が{position}へ{share*100:.0f}%集まっています'
                        f'（{sum(counts)}問中{counts[position]}問 / 上限'
                        f'{MAX_CHAPTER_SHARE*100:.0f}%）。同じ番号を選び続けるだけで通ります')

    if not problems:
        print('  Javaを知らなくても使える手がかりは基準内です。')
        return 0

    print(f'\n直す箇所が{len(problems)}件あります。', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n該当する問題は --list で出せます。'
          '直し方は docs/guide.md「クイズを足すとき」にあります。', file=sys.stderr)
    return 1


def margin(quiz):
    """正解が、いちばん長い誤答より画面上でどれだけ長いか（全角=2 / 半角=1）。"""
    correct = width(quiz['choices'][quiz['answer']])
    others = [width(c) for i, c in enumerate(quiz['choices']) if i != quiz['answer']]
    return correct - max(others) if others else 0


def width(text):
    """画面に出したときのおおよその幅。全角・全角相当を2、それ以外を1として数える。"""
    return sum(2 if unicodedata.east_asian_width(ch) in 'WF' else 1 for ch in text)


def skewed_chapters(quizzes):
    """正解の位置が1か所へ集まっている章。(ファイル名, 位置ごとの件数, 割合, 位置) を返す。"""
    per_file = {}
    for quiz in quizzes:
        counts = per_file.setdefault(quiz['file'], [0, 0, 0, 0])
        if quiz['answer'] < 4:
            counts[quiz['answer']] += 1
    skewed = []
    for file_name, counts in per_file.items():
        total = sum(counts)
        if total < MIN_CHAPTER_QUIZZES:
            continue
        position = counts.index(max(counts))
        share = counts[position] / total
        if share > MAX_CHAPTER_SHARE:
            skewed.append((file_name, counts, share, position))
    return sorted(skewed, key=lambda row: -row[2])


def position_summary(quizzes):
    counts = [0, 0, 0, 0]
    for quiz in quizzes:
        if quiz['answer'] < 4:
            counts[quiz['answer']] += 1
    total = len(quizzes)
    return ' / '.join(f'{n}({n/total*100:.0f}%)' for n in counts)


def show(long_ones, assertive_wrong):
    if long_ones:
        print(f'\n■ 正解が全角{LONG_MARGIN // 2}文字ぶん以上長い問題（差が大きい順）')
        for quiz in sorted(long_ones, key=lambda q: -margin(q)):
            print(f'  {quiz["where"]} (+{margin(quiz)}幅)')
            print(f'      正解: {quiz["choices"][quiz["answer"]][:70]}')
            for index, choice in enumerate(quiz['choices']):
                if index != quiz['answer']:
                    print(f'      誤答{index}: {choice[:70]}')
    if assertive_wrong:
        print('\n■ 断定語を含む誤答')
        for quiz, index in assertive_wrong:
            print(f'  {quiz["where"]} 誤答{index}: {quiz["choices"][index][:76]}')


def load():
    manifest = json.loads((CONTENT / 'manifest.json').read_text(encoding='utf-8'))
    quizzes = []
    for part in manifest['parts']:
        for file_name in part['chapters']:
            raw = json.loads((CONTENT / file_name).read_text(encoding='utf-8'))
            for lesson in raw.get('lessons', []):
                for index, quiz in enumerate(lesson.get('quiz', [])):
                    quizzes.append({
                        'where': f'{lesson["id"]}#{index + 1}',
                        'file': file_name,
                        'choices': quiz['choices'],
                        'answer': quiz['answer'],
                    })
    return quizzes


if __name__ == '__main__':
    sys.exit(main())
