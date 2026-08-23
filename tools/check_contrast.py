#!/usr/bin/env python3
"""配色（web/style.css の2枚のパレット）の読みやすさを数字で確かめる。

tools/check-contrast.sh から呼ばれる。`--list` で全組み合わせの実測値を出す。

## なぜ必要か

配色は「暗い」「明るい」の2枚あり、同じ変数名で色だけが入れ替わる（`--accent` など）。
**片方だけを見て決めると、もう片方で字が沈む。** 実際に、明るい配色の主ボタンは
濃い茶（`#b5761a`）に黒に近い字を乗せていて 4.92:1 しかなく、「茶色が濃くて黒い
テキストと合わせると見づらい」という指摘を受けた（2026-08-23）。同じとき、
`--accent` を**字の色**として使う箇所は白地で 3.77:1、カードの地では 3.28:1 で、
本文の基準（4.5:1）を下回っていた。

どちらも目で見て気づくのは難しく、色を1つ動かすたびに関わる組み合わせを数え直す必要が
ある。ここに置いておけば、パレットを触った回に必ず数字が出る。

## 基準

本文と同じ大きさの字は **4.5:1**（WCAG 2.1 の AA）。字ではない形（スイッチの玉など）は
**3:1** で見る。塗り面いっぱいに字が乗る主ボタンだけは **6:1** で見る ―― 4.92:1 でも
濁って見えるという指摘を受けた組み合わせなので、基準そのものを上げてある。

`--text-faint` はこの基準を満たしていない（明るい配色で 2.88:1）が、意味を持たない添え書き
（拡張子・秒数・注記）にだけ使う色なので、いまは対象から外してある ―― 使い所を広げるなら
先に色を濃くすること。
"""
import re
import sys
from pathlib import Path

GREEN, RED, DIM, RESET = "\033[32m", "\033[31m", "\033[2m", "\033[0m"

CSS = Path(__file__).resolve().parent.parent / 'web' / 'style.css'

# (字の色, 地の色, 最低比, 何のことか)
#
# 「字」は本文と同じ大きさで出るものを 4.5:1 で見る。組み合わせは、実際に style.css で
# その2つが重なる場所があるものだけを挙げている（机上の組み合わせを増やしても意味がない）。
PAIRS = [
    ('text', 'bg', 4.5, '本文（ページの地）'),
    ('text', 'bg-elev', 4.5, '本文（カードの地）'),
    ('text', 'bg-elev-2', 4.5, '本文（一段沈んだ地）'),
    ('text-dim', 'bg', 4.5, '添え書き（ページの地）'),
    ('text-dim', 'bg-elev', 4.5, '添え書き（カードの地）'),
    ('text-dim', 'bg-elev-2', 4.5, '添え書き（一段沈んだ地）'),
    ('accent', 'bg', 4.5, 'アクセントの字（ページの地）'),
    ('accent', 'bg-elev', 4.5, 'アクセントの字（カードの地）'),
    ('accent', 'bg-elev-2', 4.5, 'アクセントの字（小さな札の地）'),
    # ここだけ 6.0 で見る。**4.5 では足りなかった**組み合わせである ―― 濃い茶の面
    # （#b5761a）に黒に近い字で 4.92:1 は基準を満たしていたが、「茶色が濃くて黒いテキストと
    # 合わせると見づらい」と指摘された（2026-08-23）。塗り面いっぱいに字が乗るので、
    # 明るさの差が小さいと基準を満たしていても濁って見える。
    ('on-accent', 'accent-surface', 6.0, '主ボタン・スイッチ・開いているレッスンの字'),
    ('ok', 'bg-elev', 4.5, '通った知らせの字'),
    ('ok', 'ok-bg', 4.5, '通った知らせの字（薄い緑の地）'),
    ('ng', 'bg-elev', 4.5, '失敗の知らせの字'),
    ('ng', 'ng-bg', 4.5, '失敗の知らせの字（薄い赤の地）'),
    ('info', 'bg-elev', 4.5, '案内の字'),
]

# 字ではない形。境目が分かることだけを見るので 3:1（WCAG 1.4.11）。
SHAPES = [
    ('on-accent', 'accent-surface', 3.0, 'スイッチの玉（入のとき）'),
    ('accent-surface', 'bg', 2.0, '主ボタンの縁（地との差）'),
]


def channel(value):
    value = value / 255
    return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4


def luminance(color):
    color = color.lstrip('#')
    r, g, b = (int(color[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def palettes():
    """暗い配色と明るい配色を読む。

    明るい配色は<b>差分だけ</b>書いてあるので、暗い配色に重ねて読む
    （`--accent-surface` のように片方にしか無い変数があると、そこで取り違える）。
    """
    css = CSS.read_text(encoding='utf-8')
    light_at = css.index(':root[data-theme="light"]')
    dark = dict(re.findall(r'--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})', css[:light_at]))
    light = dict(re.findall(r'--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})', css[light_at:]))
    return {'暗い配色': dark, '明るい配色': {**dark, **light}}


def main():
    listing = '--list' in sys.argv[1:]
    problems = []
    counted = 0
    for name, palette in palettes().items():
        if listing:
            print(f'\n{name}')
        for group, kind in ((PAIRS, '字'), (SHAPES, '形')):
            for fg, bg, minimum, label in group:
                if fg not in palette or bg not in palette:
                    problems.append(f'{name}: --{fg} か --{bg} が見つかりません（変数を消した？）')
                    continue
                ratio = contrast(palette[fg], palette[bg])
                counted += 1
                if listing:
                    print(f'  {ratio:5.2f}:1 （最低 {minimum}）{kind}: {label}'
                          f'{DIM}  --{fg} {palette[fg]} / --{bg} {palette[bg]}{RESET}')
                elif ratio < minimum:
                    problems.append(
                        f'{name}: {label} が {ratio:.2f}:1 です'
                        f'（最低 {minimum}:1 ／ --{fg} {palette[fg]} on --{bg} {palette[bg]}）')
    if listing:
        return 0

    print(f'配色2枚の組み合わせを{counted}件、明るさの比で確かめました。')
    if not problems:
        print(f'  {GREEN}どちらの配色でも字が沈んでいる組み合わせはありません。{RESET}')
        return 0

    print(f'\n{RED}読みにくい組み合わせが{len(problems)}件あります。{RESET}', file=sys.stderr)
    for line in problems:
        print(f'  {line}', file=sys.stderr)
    print('\n色の持ち場は web/style.css の先頭（2枚のパレット）にあります。'
          '塗り面と字の色は別の変数です（--accent-surface / --accent）。', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
