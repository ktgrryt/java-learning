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

var maps = [1, 8, 55, 512].map(function (count) {
  return scene.networkMap({ storeCount: count, locked: count === 1, maximum: count === 512 });
});
ok('主要な店舗数でネットワーク図を生成', maps.every(function (svg) {
  return svg.indexOf('<svg class="csn-svg"') === 0 && !/(NaN|undefined|Infinity)/.test(svg);
}));

console.log('\nCAFE SCENE OK: 店舗グラフィックの成長差分と描画条件を確認しました');
