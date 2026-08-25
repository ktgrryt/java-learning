#!/usr/bin/env node
/* カフェSVGの成長差分・再描画条件・文字列の健全性を外部ライブラリなしで検査する。 */
'use strict';

var fs = require('fs');
var path = require('path');
var vm = require('vm');

var root = path.resolve(__dirname, '..');
var context = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(root, 'web/cafe-scene.js'), 'utf8'), context);
var scene = context.window.CafeScene;

function ok(label, condition) {
  if (!condition) { throw new Error('NG ' + label); }
  console.log('OK  ' + label);
}

var interiors = [];
for (var interior = 0; interior <= 8; interior++) {
  interiors.push(scene.markup({
    level: 12,
    structure: 7,
    interior: interior,
    storeCount: 1,
    equippedIds: []
  }));
}
for (var i = 1; i < interiors.length; i++) {
  ok('内装' + (i - 1) + ' → ' + i + 'でグラフィックが変化', interiors[i - 1] !== interiors[i]);
}

var allScenes = [];
for (var level = 1; level <= scene.maxEra; level++) {
  allScenes.push(scene.markup({
    level: level,
    structure: Math.min(level, scene.maxStructure),
    interior: Math.min(8, level - 1),
    storeCount: 1,
    equippedIds: []
  }));
}
ok('全ランクがSVGを生成', allScenes.every(function (svg) {
  return svg.indexOf('<svg class="cs-svg"') === 0 && svg.endsWith('</svg>');
}));
ok('不正な数値や未定義値を含まない', !/(NaN|undefined|Infinity)/.test(allScenes.join('')));

var fake = {
  attrs: {},
  innerHTML: '',
  getAttribute: function (key) { return this.attrs[key] == null ? null : this.attrs[key]; },
  setAttribute: function (key, value) { this.attrs[key] = String(value); }
};
var baseView = { level: 8, structure: 7, interior: 5, storeCount: 1, equippedIds: [] };
ok('初回は店舗シーンを描画', scene.render(fake, baseView));
var firstMarkup = fake.innerHTML;
ok('店舗数だけの変化では同じ絵を再描画しない', !scene.render(fake, {
  level: 8,
  structure: 7,
  interior: 5,
  storeCount: 55,
  equippedIds: []
}) && fake.innerHTML === firstMarkup);

ok('花火の配置とアニメーション変形を別要素に保持',
  /transform="translate\(388,128\) scale\(1\)"><g class="cs-spark"/.test(interiors[8]));
ok('音符の配置とアニメーション変形を別要素に保持',
  /transform="translate\([^\"]+\)"><g class="cs-music"/.test(scene.markup({
    level: 12,
    structure: 7,
    interior: 8,
    equippedIds: ['lifelong_academy']
  })));

// 電飾の紐の両端は、屋根の軒（rx 〜 rx+rw、下端 ry+rh）の内側で終えること。
// 端が軒より外／下に出ると、紐が空で切れて店構えから浮いて見える。
[2, 3, 4, 5, 6, 7].forEach(function (structure) {
  var svg = scene.markup({
    level: Math.min(structure, scene.maxEra),
    structure: structure,
    interior: 5,
    storeCount: 1,
    equippedIds: []
  });
  // 屋根は rx="…" y="…" width="…" height="13" rx="2" の1枚だけ
  var roof = /<rect x="(-?[\d.]+)" y="(-?[\d.]+)" width="([\d.]+)" height="13" rx="2"/.exec(svg);
  var wire = /<path d="M(-?[\d.]+) (-?[\d.]+) Q[\d.]+ [\d.]+ (-?[\d.]+) ([\d.]+)" fill="none"/.exec(svg);
  ok('ランク' + structure + 'の電飾の両端が軒の内側に収まる', !!roof && !!wire
    && Number(wire[1]) >= Number(roof[1])
    && Number(wire[3]) <= Number(roof[1]) + Number(roof[3])
    && Number(wire[2]) <= Number(roof[2]) + 13
    && Number(wire[2]) === Number(wire[4]));
});

// 看板は店名を「Java Café」と書き、文字は板の内側に収めること。
// 縦位置は墨（インク）の高さで積んでいるので、その比率で当たりを見る。
// 実測（700 の等幅、大きさに比例）… Java Café は上に 0.80em・下に 0.014em、
// COFFEE & CODE は上に 0.742em・下に 0.015em。
var INK = { 'Java Café': [0.8, 0.014], 'COFFEE & CODE': [0.742, 0.015] };
[2, 3, 4, 5, 6, 7].forEach(function (structure) {
  [[], ['morning_playlist']].forEach(function (equipped) {
    var svg = scene.markup({
      level: Math.min(structure, scene.maxEra),
      structure: structure,
      interior: 5,
      storeCount: 1,
      equippedIds: equipped
    });
    var label = 'ランク' + structure + (equipped.length ? '（音符あり）' : '');
    // 店名は Java Café。絵の中の文字はどこも総大文字にしない（マシンの Java バッジも）
    ok(label + 'の看板が「Java Café」', svg.indexOf('>Java Café</text>') > 0
      && svg.indexOf('JAVA') < 0);

    // 看板の板は fill="#241b18" の rect 1枚
    var board = /<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" rx="3" fill="#241b18"/
      .exec(svg);
    var top = Number(board[2]);
    var bottom = top + Number(board[4]);
    var inside = true;
    Object.keys(INK).forEach(function (text) {
      var re = new RegExp('<text x="[\\d.]+" y="([\\d.]+)"[^>]*font-size="([\\d.]+)"[^>]*>' + text + '<');
      var hit = re.exec(svg);
      if (!hit) { return; }
      var base = Number(hit[1]);
      var size = Number(hit[2]);
      // 縁（stroke-width 2）が板の内へ1入るので、その内側に収まっていること
      if (base - size * INK[text][0] < top + 1) { inside = false; }
      if (base + size * INK[text][1] > bottom - 1) { inside = false; }
    });
    ok(label + 'の看板の文字が板の内側に収まる', !!board && inside);
  });
});

/* 店名の表記は「Java Café」で統一する。絵だけでなく画面の小さなラベルも総大文字に
   しない（店舗網の見出し・報酬通知の小見出し・獲得履歴の小見出しが該当）。
   これらは絵ではないので、markup() ではなく取り込み元の字面で見る。 */
ok('画面のどこにも「JAVA CAFÉ」と書かない', ['web/app.js', 'web/cafe-scene.js']
  .every(function (file) {
    return fs.readFileSync(path.join(root, file), 'utf8').indexOf('JAVA CAF') < 0;
  }));

// マシン本体のバッジも Java（総大文字にしない）。設備を積まないと絵に出ない
var withMachine = scene.markup({
  level: 6,
  structure: 5,
  interior: 5,
  storeCount: 1,
  equippedIds: ['espresso']
});
ok('マシンのバッジが「Java」', withMachine.indexOf('>Java</text>') > 0
  && withMachine.indexOf('JAVA') < 0);

var maps = [1, 8, 55, 512].map(function (count) {
  return scene.networkMap({ storeCount: count, locked: count === 1, maximum: count === 512 });
});
ok('主要な店舗数でネットワーク図を生成', maps.every(function (svg) {
  return svg.indexOf('<svg class="csn-svg"') === 0 && !/(NaN|undefined|Infinity)/.test(svg);
}));

console.log('\nCAFE SCENE OK: 店舗グラフィックの成長差分と描画条件を確認しました');
