/*
 * 軽量コードエディタ。
 *
 * 仕組みは「textarea を透明にして、その裏に色付きの <pre> を重ねる」という定番の方法。
 * CDNから重いエディタを読み込まずに、行番号・色付け・自動インデントだけを実現する。
 *
 * 初心者がつまずきやすいところを補助する:
 *   - Tab で4スペース（Javaの慣習）。Shift+Tab で戻す
 *   - Enter で前の行のインデントを引き継ぐ。`{` の後ろなら1段深くする
 *   - `{` `(` `[` を自動で閉じる。**自動で足した**閉じ記号の前でだけ、打った閉じ記号は
 *     重複させず通り抜ける（ひな形や自分で書いた閉じ記号の前では、打った文字がそのまま入る）
 *   - `"` も自動で閉じる。ただし文字列やテキストブロック（`"""`）の中では足さない
 *   - 書きかけの語から補完候補を出す（complete.js）。Tab で選んだ候補を入れる
 */
(function (global) {
  'use strict';

  var INDENT = '    '; // 4スペース

  var PAIRS = { '(': ')', '{': '}', '[': ']' };
  var CLOSERS = { ')': true, '}': true, ']': true };
  // 同じ記号で開いて閉じるので、かっことは別に扱う。`'` は入れていない ―
  // Javaの `char` にしか使わないのに、コメントの `don't` のような書き方で
  // 勝手に2個入るほうが煩わしいため
  var QUOTES = { '"': true };

  /**
   * その位置は文字列（`"..."`）かテキストブロック（`"""..."""`）の中か。
   *
   * 構文解析はしない。エスケープを飛ばしながら `"""` と `"` を数えるだけである。
   * 通常の文字列は行をまたげないので改行で閉じ扱いにする（書きかけを追いたいため）。
   * テキストブロックは行をまたぐので、そちらは改行で閉じない。
   *
   * これを見るのは `"` を自動で閉じるかの判断だけで、色付け（highlight.js）とは別系統である。
   * 中にいるときに足さないのは、そこで打つ `"` は「閉じるつもりの1つ」だからである。
   */
  function insideQuotes(text, pos) {
    var block = false;   // `"""` の中
    var str = false;     // `"` の中
    var i = 0;
    while (i < pos) {
      var c = text.charAt(i);
      if ((str || block) && c === '\\') { i += 2; continue; }   // `\"` は数えない
      if (!str && text.substr(i, 3) === '"""') { block = !block; i += 3; continue; }
      if (!block && c === '"') { str = !str; i++; continue; }
      if (!block && c === '\n') { str = false; i++; continue; }
      i++;
    }
    return block || str;
  }

  function Editor(host, options) {
    this.host = host;
    this.language = options && options.language ? options.language : 'java';
    this.ariaLabel = options && options.ariaLabel ? options.ariaLabel : 'コードを書く欄';
    this.onSubmit = null;
    this.onTryRun = null;   // 採点なしで走らせる（⇧⌘/Ctrl + Enter）。無ければ提出に落ちる
    this._isComposing = false;
    // 自動で足した閉じ記号がいまどこにあるか。打ち抜けを許すのはこの位置だけ
    this._autoClosed = [];
    this._lastValue = null;  // 位置をずらすために、1つ前のテキストを控える
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

    // 高さの計算に使う寸法は :root の変数から読む（style.css と二重に持たない）。
    // textarea 自身の computed style は使えない ― この欄は document へ挿す前に
    // 組み立てるので、そのときは空の値が返る。
    var root = global.getComputedStyle(document.documentElement);
    this._lineHeightPx = parseFloat(root.getPropertyValue('--ed-line-height')) || 25;
    this._padYPx = (parseFloat(root.getPropertyValue('--ed-pad-y')) || 14) * 2;
    // プロジェクト型の編集欄は高さを列に合わせる（.project-file-pane .editor-input）。
    // inline の height はその指定に勝ってしまうので、ここでは高さに触らない。
    this._noAutoHeight = !!(this.host.closest && this.host.closest('.project-file-pane'));

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
    // 中身を丸ごと入れ替えるので、覚えている閉じ記号の位置は全部無効になる
    // （ひな形に戻す・模範解答を入れる・保存した続きを開く、のどれもここを通る）
    this._autoClosed = [];
    this.input.value = text == null ? '' : text;
    this._refresh();
    this.input.scrollTop = 0;
    this._syncScroll();
  };

  Editor.prototype.focus = function () {
    this.input.focus();
  };

  /**
   * 自動で足した閉じ記号の位置を覚える。
   *
   * 打った閉じ記号を通り抜けさせてよいのは、ここに覚えのある位置だけである。
   * ひな形にあった `)` や自分で書いた `)` の前でも通り抜けると、打った文字が
   * 消えたように見える（カーソルだけ右へ動く）。
   *
   * 補完（complete.js）が `println()` のように閉じかっこ込みで入れたときも、
   * その閉じかっこは自動で足したものなので、あちらから呼んで覚えさせる。
   */
  Editor.prototype.markAutoClosed = function (pos) {
    if (this._autoClosed.indexOf(pos) < 0) { this._autoClosed.push(pos); }
  };

  /** その位置の閉じ記号は、自動で足したものか。 */
  Editor.prototype._isAutoClosed = function (pos) {
    return this._autoClosed.indexOf(pos) >= 0;
  };

  /** 通り抜けた閉じ記号は役目を終えたので、覚えから外す。 */
  Editor.prototype._forgetAutoClosed = function (pos) {
    var at = this._autoClosed.indexOf(pos);
    if (at >= 0) { this._autoClosed.splice(at, 1); }
  };

  /**
   * テキストが変わったぶんだけ、覚えている位置をずらす。
   *
   * 変わったところを1区間として求め（前後の一致部分を削る）、手前は動かさず、
   * 後ろは伸縮ぶんずらし、区間の中にあったものは捨てる ― 書き換えられた閉じ記号は
   * もう「自動で足したもの」とは言えないため。
   *
   * テキストが変わる道はすべて {@link Editor#_refresh} を通る（打鍵・貼り付け・取り消し・
   * 補完・ひな形に戻す）ので、ずらす場所はここ1つで足りる。
   */
  Editor.prototype._shiftAutoClosed = function (before, after) {
    if (!this._autoClosed.length || before === after) { return; }
    var limit = Math.min(before.length, after.length);
    var head = 0;
    while (head < limit && before.charAt(head) === after.charAt(head)) { head++; }
    var tail = 0;
    while (tail < limit - head
        && before.charAt(before.length - 1 - tail) === after.charAt(after.length - 1 - tail)) {
      tail++;
    }
    var removedEnd = before.length - tail;   // before の [head, removedEnd) が置き換わった
    var shift = after.length - before.length;
    this._autoClosed = this._autoClosed
      .filter(function (pos) { return pos < head || pos >= removedEnd; })
      .map(function (pos) { return pos < head ? pos : pos + shift; });
  };

  /**
   * その位置で `"` を自動で閉じてよいか。
   *
   * 足さないのは3つの場面である。
   *   ・文字列・テキストブロックの中 ― そこで打つ `"` は閉じるつもりの1つだから
   *   ・直前が `""` ― テキストブロックの `"""` を打っている途中で、
   *     ここで足すと `""""` になる（第18章のテキストブロックの問題で必ず踏む）
   *   ・直後が英数字 ― 既存の語を壊さないため（かっこと同じ考え方）
   */
  Editor.prototype._shouldCloseQuote = function (pos) {
    var value = this.input.value;
    if (insideQuotes(value, pos)) { return false; }
    if (value.charAt(pos - 1) === '"' && value.charAt(pos - 2) === '"') { return false; }
    var next = value.charAt(pos);
    return next === '' || /[\s)\]};,.]/.test(next);
  };

  /** 色付けと行番号を書き直す。 */
  Editor.prototype._refresh = function () {
    var text = this.input.value;
    this._shiftAutoClosed(this._lastValue === null ? text : this._lastValue, text);
    this._lastValue = text;
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
      this._fitHeight(lineCount);   // 行数が変わったときだけ測る（打鍵ごとには測らない）
    }
    this._syncScroll();
  };

  /**
   * 書いてあるコードの行数に高さを合わせる。
   *
   * 以前は11行の固定だったが、**ひな形の81%（613問中497問）が11行より長い**
   * （行数の中央値はひな形16行・模範解答23行）。つまりほとんどの問題で、開いた時点から
   * 中身が隠れていて、書きながら狭い窓をスクロールすることになっていた。
   *
   * 下限（11行）と上限（窓の3分の2）は style.css の min-height / max-height が持つ。
   * ここは「必要な高さ」を入れるだけにして、窓の大きさの追随はCSSへ任せる。
   *
   * 1行ぶん余らせているのは、末尾にカーソルを置く場所を残すためと、長い行で出る
   * 横スクロールバー（wrap="off"）が最後の行に重ならないようにするため。
   */
  Editor.prototype._fitHeight = function (lineCount) {
    if (this._noAutoHeight) { return; }
    // つまみ（resize: vertical）で高さを変えた跡があれば、そのあとは自分から動かさない。
    // 見分けは「自分が最後に入れた値と違うか」だけでよい（つまみは inline の height を書く）。
    if (this._autoHeightCss && this.input.style.height !== this._autoHeightCss) {
      this._noAutoHeight = true;
      return;
    }
    this._autoHeightCss = ((lineCount + 1) * this._lineHeightPx + this._padYPx) + 'px';
    this.input.style.height = this._autoHeightCss;
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
    // ⌘/Ctrl + Enter で提出、Shift を足すと採点なしで走らせる（2026-08-19）。
    // 素の ⌘/Ctrl + Enter を提出のままにしてあるのは、それまで唯一の実行操作で
    // 指が覚えているため。onTryRun が無い問題（artifact・複数ファイル）では
    // Shift 付きでも提出に落ちる ―― 押して何も起きないほうが分かりにくい。
    if (mod && e.key === 'Enter') {
      e.preventDefault();
      if (e.shiftKey && this.onTryRun) { this.onTryRun(); }
      else if (this.onSubmit) { this.onSubmit(); }
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
    // 通り抜けるのは、自分が自動で足した閉じ記号の前だけにする。以前は右隣が同じ記号なら
    // 必ず通り抜けていたため、ひな形の `(x + y));` の中や、自分で書いた `)` の前で
    // `)` を打つと、その1文字が入らずカーソルだけ動いていた（打った文字が消えたように見える）。
    if (CLOSERS[e.key] && el.selectionStart === el.selectionEnd
        && el.value.charAt(el.selectionStart) === e.key
        && this._isAutoClosed(el.selectionStart)) {
      e.preventDefault();
      this._forgetAutoClosed(el.selectionStart);
      el.selectionStart = el.selectionEnd = el.selectionStart + 1;
      // ここは文字が増えないので input が飛ばず、補完の窓は自分では畳まれない。
      // 開いたまま置くと、窓が指している場所とカーソルがずれて、Tab が別の場所へ入る
      if (this.complete) { this.complete.close(); }
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
        this.markAutoClosed(selStart + 1 + picked.length);
        return;
      }
      var next = el.value.charAt(selStart);
      // 直後が英数字なら閉じ記号を足さない（既存の語を壊さないため）
      if (next === '' || /[\s)\]};,.]/.test(next)) {
        e.preventDefault();
        this._insert(e.key + PAIRS[e.key], 1);
        // 覚えるのは入れたあと。_insert の中で _refresh が走り、そこで位置がずれるため
        this.markAutoClosed(selStart + 1);
      }
      return;
    }

    // ---- 引用符の自動閉じ --------------------------------------------------
    // 開き記号と閉じ記号が同じなので、かっこと同じ枝には入れられない。
    // 「通り抜け → 囲む → 自動で閉じる」の順に見て、どれでもなければ1文字だけ入れる。
    if (QUOTES[e.key]) {
      var qStart = el.selectionStart;
      var qEnd = el.selectionEnd;
      // 自分が足した閉じ引用符の前なら通り抜ける（かっこと同じ扱い）
      if (qStart === qEnd && el.value.charAt(qStart) === e.key && this._isAutoClosed(qStart)) {
        e.preventDefault();
        this._forgetAutoClosed(qStart);
        el.selectionStart = el.selectionEnd = qStart + 1;
        if (this.complete) { this.complete.close(); }
        return;
      }
      if (qStart !== qEnd) {
        // 選択範囲を引用符で囲む
        e.preventDefault();
        var quoted = el.value.slice(qStart, qEnd);
        this._insert(e.key + quoted + e.key, 1, 1 + quoted.length);
        this.markAutoClosed(qStart + 1 + quoted.length);
        return;
      }
      if (this._shouldCloseQuote(qStart)) {
        e.preventDefault();
        this._insert(e.key + e.key, 1);
        this.markAutoClosed(qStart + 1);
      }
      return;
    }

    // ---- Backspace でペアをまとめて消す ------------------------------------
    if (e.key === 'Backspace' && el.selectionStart === el.selectionEnd) {
      var p = el.selectionStart;
      var prev = el.value.charAt(p - 1);
      // 引用符も同じ扱い。`"` だけ残ると文字列が閉じないコードになってしまう
      var pairOf = PAIRS[prev] || (QUOTES[prev] ? prev : '');
      if (pairOf && el.value.charAt(p) === pairOf) {
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
