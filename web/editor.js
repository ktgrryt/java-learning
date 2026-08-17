/*
 * 軽量コードエディタ。
 *
 * 仕組みは「textarea を透明にして、その裏に色付きの <pre> を重ねる」という定番の方法。
 * CDNから重いエディタを読み込まずに、行番号・色付け・自動インデントだけを実現する。
 *
 * 初心者がつまずきやすいところを補助する:
 *   - Tab で4スペース（Javaの慣習）。Shift+Tab で戻す
 *   - Enter で前の行のインデントを引き継ぐ。`{` の後ろなら1段深くする
 *   - `{` `(` `[` を自動で閉じる。自分で閉じ記号を打ったときは重複させず通り抜ける
 *   - 引用符は入力・削除とも1文字ずつ扱う（勝手に2個入ったように見せない）
 *   - 書きかけの語から補完候補を出す（complete.js）。Tab で選んだ候補を入れる
 */
(function (global) {
  'use strict';

  var INDENT = '    '; // 4スペース

  var PAIRS = { '(': ')', '{': '}', '[': ']' };
  var CLOSERS = { ')': true, '}': true, ']': true };

  function Editor(host, options) {
    this.host = host;
    this.language = options && options.language ? options.language : 'java';
    this.ariaLabel = options && options.ariaLabel ? options.ariaLabel : 'コードを書く欄';
    this.onSubmit = null;
    this._isComposing = false;
    this._build();
  }

  Editor.prototype._build = function () {
    this.host.classList.add('editor');
    this.host.innerHTML =
      '<div class="editor-gutter"></div>' +
      '<div class="editor-scroll">' +
      '  <pre class="editor-highlight" aria-hidden="true"><code></code></pre>' +
      '  <textarea class="editor-input" spellcheck="false" autocapitalize="off"' +
      '            autocomplete="off" wrap="off" aria-label="'
                   + global.JQHighlight.escapeHtml(this.ariaLabel) + '"></textarea>' +
      '</div>';

    this.gutter = this.host.querySelector('.editor-gutter');
    this.scroll = this.host.querySelector('.editor-scroll');
    this.pre = this.host.querySelector('.editor-highlight');
    this.code = this.host.querySelector('.editor-highlight code');
    this.input = this.host.querySelector('.editor-input');

    // コード補完。complete.js が読み込まれていなければ、無しで動く
    this.complete = this.language === 'java' && global.JQComplete
      ? new global.JQComplete.Completer(this) : null;

    var self = this;
    this.input.addEventListener('input', function () {
      self._refresh();
      if (self.complete && !self._isComposing) { self.complete.onInput(); }
    });
    this.input.addEventListener('scroll', function () { self._syncScroll(); });
    this.input.addEventListener('keydown', function (e) { self._onKeyDown(e); });
    // 日本語IMEの変換確定Enterを、自動改行やショートカットとして扱わない。
    // Safariなどでは KeyboardEvent.isComposing が安定しない場合があるため、
    // compositionstart/end の状態も別に保持する。
    this.input.addEventListener('compositionstart', function () {
      self._isComposing = true;
      if (self.complete) { self.complete.close(); }   // 変換中に候補を重ねない
    });
    this.input.addEventListener('compositionend', function () {
      self._isComposing = false;
      self._refresh();
    });
  };

  Editor.prototype.getValue = function () {
    return this.input.value;
  };

  Editor.prototype.setValue = function (text) {
    if (this.complete) { this.complete.close(); }
    this.input.value = text == null ? '' : text;
    this._refresh();
    this.input.scrollTop = 0;
    this._syncScroll();
  };

  Editor.prototype.focus = function () {
    this.input.focus();
  };

  /** 色付けと行番号を書き直す。 */
  Editor.prototype._refresh = function () {
    var text = this.input.value;
    // 末尾が改行だと <pre> の最終行が潰れるので、見えない1文字を足して高さを保つ
    var highlighter = this.language === 'java'
      ? global.JQHighlight.java
      : global.JQHighlight.plain;
    this.code.innerHTML = highlighter(text + '\n');

    var lineCount = text.split('\n').length;
    if (this._lineCount !== lineCount) {
      var nums = new Array(lineCount);
      for (var i = 0; i < lineCount; i++) { nums[i] = i + 1; }
      this.gutter.textContent = nums.join('\n');
      this._lineCount = lineCount;
    }
    this._syncScroll();
  };

  /**
   * 下敷きの色付け層と行番号を、textarea のスクロール位置に合わせる。
   *
   * transform で層ごと動かすと、箱の右端・下端も一緒に動いてしまい、
   * スクロールした分だけ何も描かれない帯ができる（文字が消えて見える）。
   * 箱は動かさず、中身だけをスクロールさせるのが正しい。
   */
  Editor.prototype._syncScroll = function () {
    this.pre.scrollTop = this.input.scrollTop;
    this.pre.scrollLeft = this.input.scrollLeft;
    this.gutter.scrollTop = this.input.scrollTop;
  };

  /** カーソル位置を書き換える。undo履歴を残すため execCommand を優先する。 */
  Editor.prototype._insert = function (text, selectStart, selectEnd) {
    var el = this.input;
    var start = el.selectionStart;
    if (document.execCommand && document.queryCommandSupported
        && document.queryCommandSupported('insertText')) {
      document.execCommand('insertText', false, text);
    } else {
      var value = el.value;
      el.value = value.slice(0, el.selectionStart) + text + value.slice(el.selectionEnd);
      el.selectionStart = el.selectionEnd = start + text.length;
    }
    if (selectStart !== undefined) {
      el.selectionStart = start + selectStart;
      el.selectionEnd = start + (selectEnd === undefined ? selectStart : selectEnd);
    }
    this._refresh();
  };

  /**
   * 範囲を書き換える。補完（complete.js）が選ばれた候補を差し込むのに使う。
   * caretOffset は、入れた文字列の先頭から数えたカーソルの位置。
   */
  Editor.prototype.replaceRange = function (from, to, text, caretOffset) {
    var el = this.input;
    // execCommand は focus が当たっていないと何もしないので、先に戻す
    if (document.activeElement !== el) { el.focus(); }
    el.selectionStart = from;
    el.selectionEnd = to;
    this._insert(text, caretOffset === undefined ? text.length : caretOffset);
  };

  Editor.prototype._onKeyDown = function (e) {
    var el = this.input;
    var mod = e.metaKey || e.ctrlKey;

    // IME変換中のキーはブラウザとIMEに任せる。特に変換確定のEnterへ
    // preventDefault()やexecCommand()を行うと、確定文字が二重に入ることがある。
    // keyCode 229 は isComposing がfalseになる一部ブラウザ向けのフォールバック。
    if (e.isComposing || this._isComposing || e.keyCode === 229) {
      return;
    }

    // ---- 補完の操作 --------------------------------------------------------
    // 候補が出ているときだけ ↑↓ Tab Esc を横取りする。
    // 出ていなければ false が返るので、この下のTabやEnterの処理がそのまま働く。
    if (this.complete && this.complete.handleKeyDown(e)) {
      return;
    }

    // ---- ショートカット ----------------------------------------------------
    // 実行＝採点なので、⌘/Ctrl + Enter の1つだけ。Shiftの有無では区別しない
    if (mod && e.key === 'Enter') {
      e.preventDefault();
      if (this.onSubmit) { this.onSubmit(); }
      return;
    }
    if (mod && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();   // ブラウザの「ページを保存」を止める（自動保存しているので不要）
      return;
    }

    // ---- Tab / Shift+Tab --------------------------------------------------
    if (e.key === 'Tab') {
      e.preventDefault();
      if (e.shiftKey) {
        this._outdent();
      } else if (el.selectionStart !== el.selectionEnd) {
        this._indentSelection();
      } else {
        this._insert(INDENT);
      }
      return;
    }

    // ---- Enter で自動インデント -------------------------------------------
    if (e.key === 'Enter' && !mod) {
      var value = el.value;
      var pos = el.selectionStart;
      if (pos !== el.selectionEnd) { return; }   // 範囲選択中は既定の動作に任せる

      var lineStart = value.lastIndexOf('\n', pos - 1) + 1;
      var currentLine = value.slice(lineStart, pos);
      var indent = (/^[ \t]*/.exec(currentLine) || [''])[0];
      var before = currentLine.replace(/\s+$/, '');
      var after = value.slice(pos);
      var opensBlock = /[{(\[]$/.test(before);

      e.preventDefault();
      if (opensBlock && /^\s*[}\)\]]/.test(after)) {
        // {  ここでEnter  } → 間に1行空けて、閉じ記号を元のインデントに戻す
        this._insert('\n' + indent + INDENT + '\n' + indent,
          1 + indent.length + INDENT.length);
      } else if (opensBlock) {
        this._insert('\n' + indent + INDENT);
      } else {
        this._insert('\n' + indent);
      }
      return;
    }

    // ---- 閉じ記号の打ち抜け（重複を防ぐ） ----------------------------------
    if (CLOSERS[e.key] && el.selectionStart === el.selectionEnd
        && el.value.charAt(el.selectionStart) === e.key) {
      e.preventDefault();
      el.selectionStart = el.selectionEnd = el.selectionStart + 1;
      return;
    }

    // ---- かっこ・クォートの自動閉じ ----------------------------------------
    if (PAIRS[e.key]) {
      var selStart = el.selectionStart;
      var selEnd = el.selectionEnd;
      if (selStart !== selEnd) {
        // 選択範囲を囲む
        e.preventDefault();
        var picked = el.value.slice(selStart, selEnd);
        this._insert(e.key + picked + PAIRS[e.key], 1, 1 + picked.length);
        return;
      }
      var next = el.value.charAt(selStart);
      // 直後が英数字なら閉じ記号を足さない（既存の語を壊さないため）
      if (next === '' || /[\s)\]};,.]/.test(next)) {
        e.preventDefault();
        this._insert(e.key + PAIRS[e.key], 1);
      }
      return;
    }

    // ---- Backspace でペアをまとめて消す ------------------------------------
    if (e.key === 'Backspace' && el.selectionStart === el.selectionEnd) {
      var p = el.selectionStart;
      var prev = el.value.charAt(p - 1);
      if (PAIRS[prev] && el.value.charAt(p) === PAIRS[prev]) {
        e.preventDefault();
        el.selectionStart = p - 1;
        el.selectionEnd = p + 1;
        this._insert('');
        return;
      }
      // 行頭のインデントは4つまとめて消す
      var ls = el.value.lastIndexOf('\n', p - 1) + 1;
      var head = el.value.slice(ls, p);
      if (head.length >= INDENT.length && /^ +$/.test(head)) {
        e.preventDefault();
        el.selectionStart = p - INDENT.length;
        el.selectionEnd = p;
        this._insert('');
      }
    }
  };

  /** 選択した行すべてを1段深くする。 */
  Editor.prototype._indentSelection = function () {
    var el = this.input;
    var value = el.value;
    var start = value.lastIndexOf('\n', el.selectionStart - 1) + 1;
    var end = el.selectionEnd;
    var block = value.slice(start, end);
    var replaced = block.replace(/^/gm, INDENT);
    el.selectionStart = start;
    el.selectionEnd = end;
    this._insert(replaced);
    el.selectionStart = start;
    el.selectionEnd = start + replaced.length;
    this._refresh();
  };

  /** 選択した行すべてを1段浅くする。 */
  Editor.prototype._outdent = function () {
    var el = this.input;
    var value = el.value;
    var start = value.lastIndexOf('\n', el.selectionStart - 1) + 1;
    var end = Math.max(el.selectionEnd, el.selectionStart);
    var lineEnd = value.indexOf('\n', end);
    if (lineEnd === -1) { lineEnd = value.length; }
    var block = value.slice(start, lineEnd);
    var replaced = block.replace(new RegExp('^(?: {1,' + INDENT.length + '}|\\t)', 'gm'), '');
    if (replaced === block) { return; }
    el.selectionStart = start;
    el.selectionEnd = lineEnd;
    this._insert(replaced);
    el.selectionStart = start;
    el.selectionEnd = start + replaced.length;
    this._refresh();
  };

  global.JQEditor = Editor;
})(window);
