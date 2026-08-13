/*
 * 画面の明るさ（ライト / ダーク / システムに合わせる）。
 *
 * このファイルだけは <head> で、style.css の後・本文が描かれる前に読み込む。
 * <html data-theme> を先に決めておかないと、保存した設定が効くまでの一瞬だけ
 * 既定のダークが見えてしまう。ライトを選んでいる人には毎回チカッと光るので、
 * app.js（本文の末尾で読む）とは分けて先に走らせている。
 *
 * 覚える値は 'light' / 'dark' / 'system' の3つ。
 * CSSに渡すのは解決後の 'light' / 'dark' だけで、'system' は OS の設定に読み替える。
 * こうしておくと、CSS側はライトとダークの2枚を持つだけで済む。
 */
(function () {
  'use strict';

  var KEY = 'jq-theme';
  var CHOICES = ['light', 'dark', 'system'];
  var FALLBACK = 'system';

  // ライト側を見る。ダーク側を見ると、どちらでもない環境（判定できない古い
  // ブラウザ）で「ライト扱い」になってしまう。既定はダークに寄せたいので逆にする。
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
  var listeners = [];

  /** 保存されている設定を読む。壊れた値や未設定なら 'system'。 */
  function load() {
    var v = null;
    try { v = localStorage.getItem(KEY); } catch (e) { /* 使えなくても既定で動く */ }
    return CHOICES.indexOf(v) >= 0 ? v : FALLBACK;
  }

  // 今の設定はこの変数が持ち、localStorage は保存先としてしか使わない。
  // 読むたびに localStorage を見に行く形にすると、プライベートモードなどで
  // 保存できないときに、選んだ設定がその場で捨てられてしまう
  // （書き込みが失敗 → 読み直すと元の値のまま → 画面が変わらない）。
  var current = load();

  function read() { return current; }

  /** 設定を、CSSが解釈できる 'light' / 'dark' に解決する。 */
  function resolve(pref) {
    if (pref === 'light' || pref === 'dark') { return pref; }
    return media && media.matches ? 'light' : 'dark';
  }

  function apply() {
    document.documentElement.setAttribute('data-theme', resolve(read()));
  }

  function notify() {
    var pref = read();
    var eff = resolve(pref);
    for (var i = 0; i < listeners.length; i++) {
      listeners[i](pref, eff);
    }
  }

  function set(pref) {
    if (CHOICES.indexOf(pref) < 0) { return; }
    current = pref;
    apply();       // 保存できなくても、今開いている画面には必ず効かせる
    notify();
    try { localStorage.setItem(KEY, pref); } catch (e) { /* 次回に持ち越せないだけ */ }
  }

  // 「システムに合わせる」を選んでいる間は、OS側の切り替えにその場で追従する。
  // 他の2つを選んでいるときは、OSが変わっても動かさない（明示的に選んだ方を尊重する）。
  if (media) {
    var onSystemChange = function () {
      if (read() !== 'system') { return; }
      apply();
      notify();
    };
    if (media.addEventListener) { media.addEventListener('change', onSystemChange); }
    else if (media.addListener) { media.addListener(onSystemChange); }   // 古いSafari
  }

  apply();   // 本文が描かれる前に確定させる

  window.JQTheme = {
    CHOICES: CHOICES,
    /** 保存されている設定（'light' / 'dark' / 'system'）。 */
    get: read,
    /** いま実際に出ている配色（'light' / 'dark'）。 */
    effective: function () { return resolve(read()); },
    set: set,
    /** 設定が変わったとき、または system 追従でOS側が変わったときに呼ばれる。 */
    onChange: function (fn) { listeners.push(fn); }
  };
})();
