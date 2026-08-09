/*
 * 解説文用の最小マークダウン変換。
 *
 * 対応しているのは、教材を書くのに必要なものだけ:
 *   見出し(## ###) / 段落 / 箇条書き(- *) / 番号付き(1.) / 引用(>) / 水平線(---)
 *   コードブロック(```lang) / インラインコード(`x`) / 太字(**x**) / 表(| a | b |)
 *
 * 外部ライブラリを持ち込まないのが方針なので自前で書いている。
 * HTMLは必ずエスケープしてから組み立てる。
 */
(function (global) {
  'use strict';

  var esc = global.JQHighlight.escapeHtml;

  /** 行内の記法（太字・インラインコード）を適用する。入力はエスケープ済みであること。 */
  function inline(escaped) {
    return escaped
      // `code` を先に処理する。そうしないと `**` のようなコード内の記号が太字と誤解される
      .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
      // 中身に `**` を含まない、という条件で囲みを探す。
      // これで **`*`**（中身が単独のアスタリスク）も拾えて、
      // かつ **あ** と **い** が1つにつながってしまうこともない
      .replace(/\*\*((?:(?!\*\*)[\s\S])+)\*\*/g, '<strong>$1</strong>');
  }

  /**
   * 表の1行をセルに分ける。`\|` は「セルの中の縦棒」として扱う。
   * 行頭と行末の `|` は区切りではないので落とす。
   */
  function splitRow(line) {
    var cells = [];
    var current = '';
    for (var i = 0; i < line.length; i++) {
      var c = line.charAt(i);
      if (c === '\\' && line.charAt(i + 1) === '|') {
        current += '|';
        i++;
      } else if (c === '|') {
        cells.push(current);
        current = '';
      } else {
        current += c;
      }
    }
    cells.push(current);
    // 行頭・行末の `|` が作る空セルを取り除く
    if (cells.length && cells[0].trim() === '') { cells.shift(); }
    if (cells.length && cells[cells.length - 1].trim() === '') { cells.pop(); }
    return cells.map(function (s) { return s.trim(); });
  }

  /** `|---|:--:|` のような区切り行か。 */
  function isTableDivider(line) {
    return /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(line) && line.indexOf('-') !== -1;
  }

  function table(headerLine, bodyLines) {
    var head = splitRow(headerLine).map(function (c) {
      return '<th>' + inline(esc(c)) + '</th>';
    }).join('');
    var body = bodyLines.map(function (line) {
      return '<tr>' + splitRow(line).map(function (c) {
        return '<td>' + inline(esc(c)) + '</td>';
      }).join('') + '</tr>';
    }).join('');
    return '<div class="md-table-wrap"><table class="md-table">'
      + '<thead><tr>' + head + '</tr></thead>'
      + '<tbody>' + body + '</tbody></table></div>';
  }

  /**
   * コードブロック。```java だけ色付けし、言語指定なしのブロック（出力例や図）は
   * そのまま出す。出力例に色が付くと「これもコードなのか」と誤解させてしまうため。
   */
  function codeBlock(lang, lines) {
    var code = lines.join('\n');
    var isJava = lang === 'java';
    var html = isJava ? global.JQHighlight.java(code) : global.JQHighlight.plain(code);
    var label = lang === '' ? '' : '<div class="code-lang">' + esc(lang) + '</div>';
    return '<div class="code-block">' + label
      + '<pre class="code' + (isJava ? '' : ' code-plain') + '"><code>' + html
      + '</code></pre></div>';
  }

  /** マークダウン文字列をHTMLに変換する。 */
  function render(markdown) {
    var lines = String(markdown == null ? '' : markdown).replace(/\r\n/g, '\n').split('\n');
    var out = [];
    var i = 0;

    while (i < lines.length) {
      var line = lines[i];

      // ---- コードブロック --------------------------------------------------
      var fence = /^```(\w*)\s*$/.exec(line);
      if (fence) {
        var lang = fence[1] || '';
        var body = [];
        i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i])) {
          body.push(lines[i]);
          i++;
        }
        i++; // 閉じフェンスを飛ばす
        out.push(codeBlock(lang, body));
        continue;
      }

      // ---- 表（ヘッダ行のすぐ下が区切り行なら表とみなす） -------------------
      if (/^\s*\|/.test(line) && i + 1 < lines.length && isTableDivider(lines[i + 1])) {
        var headerLine = line;
        i += 2; // ヘッダ行と区切り行を消費
        var rows = [];
        while (i < lines.length && /^\s*\|/.test(lines[i])) {
          rows.push(lines[i]);
          i++;
        }
        out.push(table(headerLine, rows));
        continue;
      }

      // ---- 見出し ----------------------------------------------------------
      var heading = /^(#{1,4})\s+(.*)$/.exec(line);
      if (heading) {
        var level = Math.min(heading[1].length + 1, 5); // ## → h3 相当の見え方にする
        out.push('<h' + level + ' class="md-h' + heading[1].length + '">'
          + inline(esc(heading[2])) + '</h' + level + '>');
        i++;
        continue;
      }

      // ---- 水平線 ----------------------------------------------------------
      if (/^(-{3,}|\*{3,})\s*$/.test(line)) {
        out.push('<hr class="md-hr">');
        i++;
        continue;
      }

      // ---- 引用（連続する > をまとめる） ------------------------------------
      // 中身をもう一度 render にかける。こうすると引用の中で段落を分けたり、
      // 箇条書きやコードブロックを置いたりできる（`⚠️ 間違えやすい点` を長めに書きたいとき用）。
      if (/^>\s?/.test(line) || /^>$/.test(line)) {
        var quote = [];
        while (i < lines.length && (/^>\s?/.test(lines[i]) || /^>$/.test(lines[i]))) {
          quote.push(lines[i].replace(/^>\s?/, ''));
          i++;
        }
        out.push('<blockquote class="md-quote">' + render(quote.join('\n')) + '</blockquote>');
        continue;
      }

      // ---- 箇条書き --------------------------------------------------------
      if (/^\s*[-*]\s+/.test(line)) {
        var items = [];
        while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
          items.push('<li>' + inline(esc(lines[i].replace(/^\s*[-*]\s+/, ''))) + '</li>');
          i++;
        }
        out.push('<ul class="md-list">' + items.join('') + '</ul>');
        continue;
      }

      // ---- 番号付きリスト --------------------------------------------------
      if (/^\s*\d+\.\s+/.test(line)) {
        // 「3. から始める」と書いたらその番号から始める（手順の続きを書けるように）
        var firstNo = parseInt(/^\s*(\d+)\./.exec(line)[1], 10);
        var ordered = [];
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          ordered.push('<li>' + inline(esc(lines[i].replace(/^\s*\d+\.\s+/, ''))) + '</li>');
          i++;
        }
        var startAttr = firstNo > 1 ? ' start="' + firstNo + '"' : '';
        out.push('<ol class="md-list"' + startAttr + '>' + ordered.join('') + '</ol>');
        continue;
      }

      // ---- 空行 ------------------------------------------------------------
      if (line.trim() === '') {
        i++;
        continue;
      }

      // ---- 段落（空行か特殊行が来るまで続ける） -----------------------------
      var para = [];
      while (i < lines.length
        && lines[i].trim() !== ''
        && !/^```/.test(lines[i])
        && !/^#{1,4}\s/.test(lines[i])
        && !/^>\s?/.test(lines[i])
        && !/^\s*[-*]\s+/.test(lines[i])
        && !/^\s*\d+\.\s+/.test(lines[i])
        && !/^(-{3,}|\*{3,})\s*$/.test(lines[i])
        && !(/^\s*\|/.test(lines[i]) && i + 1 < lines.length && isTableDivider(lines[i + 1]))) {
        para.push(lines[i]);
        i++;
      }
      out.push('<p class="md-p">' + inline(esc(para.join(' '))) + '</p>');
    }

    return out.join('\n');
  }

  global.JQMarkdown = { render: render };
})(window);
