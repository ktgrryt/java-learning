/*
 * 解説文用の最小マークダウン変換。
 *
 * 対応しているのは、教材を書くのに必要なものだけ:
 *   見出し(## ###) / 段落 / 箇条書き(- *) / 番号付き(1.) / 引用(>) / 水平線(---)
 *   コードブロック(```lang) / インラインコード(`x`) / 太字(**x**) / 表(| a | b |)
 *   図(```svg キャプション)
 *
 * 外部ライブラリを持ち込まないのが方針なので自前で書いている。
 * HTMLは必ずエスケープしてから組み立てる。図だけは例外的にタグを通すが、
 * 文字列としては扱わず、ホワイトリストでDOMを組み直してから出す（figure()を参照）。
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

  // ── 図（```svg キャプション） ────────────────────────────────────────
  //
  // 解説の中に図を置けるようにするための、最小のSVG取り込み。決めていることは3つ。
  //
  //   ・色は書かせない。図にはクラスだけを付けてもらい、色は style.css のCSS変数から取る。
  //     こうしておけばライト／ダークの切り替えに図も自動で追従する。
  //   ・id は書かせない。同じページに図が何枚も並ぶので、矢印の <marker> に必要な id は
  //     ここで図ごとに一意に振る。コンテンツ側は `marker-end="arrow"` とだけ書く。
  //   ・タグを文字列として組み立てない。許可した要素・属性だけでDOMを組み直してから出す。
  //     読み込むのは手元の content/*.json だけだが、この変換の「HTMLは必ずエスケープする」
  //     という前提を図のために崩さないでおく。

  var SVG_NS = 'http://www.w3.org/2000/svg';

  /** 図の中で使える要素。<script> <style> <image> <foreignObject> は入れない。 */
  var FIG_TAGS = {
    svg: 1, g: 1, title: 1, desc: 1,
    rect: 1, circle: 1, ellipse: 1, line: 1, polyline: 1, polygon: 1, path: 1,
    text: 1, tspan: 1
  };

  var NUM = /^[-+0-9.,%\s]*$/;        // 座標・長さ・破線パターンなど数の並び
  var WORD = /^[a-zA-Z-]*$/;          // text-anchor などの決まった語

  /**
   * 使える属性と、その値の形。
   * DOM経由で組み立てるので値から別のタグが生えることはないが、
   * 書き間違い（色を直接書いた、など）をその場で落とすために形も見る。
   */
  var FIG_ATTRS = {
    'class': /^[a-zA-Z0-9 _-]*$/,
    'transform': /^[a-zA-Z0-9 (),.+-]*$/,
    'opacity': NUM,
    'x': NUM, 'y': NUM, 'width': NUM, 'height': NUM,
    'rx': NUM, 'ry': NUM, 'cx': NUM, 'cy': NUM, 'r': NUM,
    'x1': NUM, 'y1': NUM, 'x2': NUM, 'y2': NUM, 'dx': NUM, 'dy': NUM,
    'points': NUM,
    'd': /^[-+0-9.,\sMmLlHhVvCcSsQqTtAaZz]*$/,
    'viewBox': NUM,
    'preserveAspectRatio': /^[a-zA-Z0-9 ]*$/,
    'text-anchor': WORD, 'dominant-baseline': WORD,
    'font-size': NUM, 'font-weight': /^[a-z0-9]*$/, 'letter-spacing': NUM,
    'stroke-width': NUM, 'stroke-dasharray': NUM,
    'stroke-linecap': WORD, 'stroke-linejoin': WORD,
    // 色はクラスで当てる決まりなので、ここで許すのは「塗らない」と「線の色に合わせる」だけ
    'fill': /^(none|currentColor)$/, 'stroke': /^(none|currentColor)$/
  };

  /** 矢印の頭。コンテンツが書くトークン → <marker> の中身に付けるクラス。 */
  var ARROWS = {
    'arrow': 'fig-arrowhead',
    'arrow-accent': 'fig-arrowhead-accent',
    'arrow-ok': 'fig-arrowhead-ok',
    'arrow-ng': 'fig-arrowhead-ng'
  };

  var figureSeq = 0;

  /** 矢印の頭を1つ作る。線の太さに合わせて拡大されるよう markerUnits は strokeWidth。 */
  function arrowMarker(id, token) {
    var marker = document.createElementNS(SVG_NS, 'marker');
    marker.setAttribute('id', id);
    marker.setAttribute('viewBox', '0 0 10 10');
    marker.setAttribute('refX', '9');
    marker.setAttribute('refY', '5');
    marker.setAttribute('markerWidth', '6');
    marker.setAttribute('markerHeight', '6');
    marker.setAttribute('markerUnits', 'strokeWidth');
    // marker-start にも同じ形を使えるよう、始点側では自動で向きを反転させる
    marker.setAttribute('orient', 'auto-start-reverse');
    var head = document.createElementNS(SVG_NS, 'path');
    head.setAttribute('d', 'M 0 1 L 10 5 L 0 9 z');
    head.setAttribute('class', ARROWS[token]);
    marker.appendChild(head);
    return marker;
  }

  /**
   * 許可した要素・属性だけで木を組み直す。許可外の要素は中身ごと落とす。
   *
   * @param arrows 使われた矢印トークンの置き場（あとで <marker> を注入するため）
   * @param uid    図ごとの接頭辞。矢印の id をこれで一意にする
   */
  function copyFigureElement(src, arrows, uid, depth) {
    // depth は入れ子の暴走止め。図に必要な深さは <svg><g><text><tspan> 程度
    if (depth > 8 || !FIG_TAGS[src.localName]) { return null; }

    var out = document.createElementNS(SVG_NS, src.localName);
    var attrs = src.attributes;
    for (var i = 0; i < attrs.length; i++) {
      var name = attrs[i].name;
      var value = attrs[i].value;
      if (name === 'marker-start' || name === 'marker-end') {
        if (ARROWS[value]) {
          arrows[value] = true;
          out.setAttribute(name, 'url(#' + uid + '-' + value + ')');
        }
        continue;
      }
      var shape = FIG_ATTRS[name];
      if (shape && shape.test(value)) {
        out.setAttribute(name, value);
      }
    }

    var children = src.childNodes;
    for (var j = 0; j < children.length; j++) {
      var child = children[j];
      if (child.nodeType === 3) {
        out.appendChild(document.createTextNode(child.nodeValue));   // <text> の中身
      } else if (child.nodeType === 1) {
        var copied = copyFigureElement(child, arrows, uid, depth + 1);
        if (copied) { out.appendChild(copied); }
      }
    }
    return out;
  }

  /**
   * ```svg ブロックを <figure> にする。
   * 解析に失敗したもの（閉じタグ抜けなど）は、黙って消さずコードブロックとして出す。
   * 書いた本人が画面を見て気づけるようにするため。
   */
  function figure(lines, caption) {
    var doc = null;
    try {
      doc = new DOMParser().parseFromString(lines.join('\n'), 'image/svg+xml');
    } catch (e) {
      doc = null;
    }
    var root = doc && doc.documentElement;
    if (!root
      || root.localName !== 'svg'
      || !root.getAttribute('viewBox')
      || doc.getElementsByTagName('parsererror').length > 0) {
      return codeBlock('', lines);
    }

    var uid = 'fig' + (++figureSeq);
    var arrows = {};
    var svg = copyFigureElement(root, arrows, uid, 0);
    if (!svg) { return codeBlock('', lines); }

    var used = Object.keys(arrows);
    if (used.length) {
      var defs = document.createElementNS(SVG_NS, 'defs');
      for (var i = 0; i < used.length; i++) {
        defs.appendChild(arrowMarker(uid + '-' + used[i], used[i]));
      }
      svg.insertBefore(defs, svg.firstChild);
    }

    var own = svg.getAttribute('class');
    svg.setAttribute('class', own ? 'md-figure-svg ' + own : 'md-figure-svg');
    if (!svg.getAttribute('preserveAspectRatio')) {
      svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    }
    // viewBox の大きさをそのまま表示寸法にする。図を縮小すると中の文字も一緒に縮んで
    // 読めなくなるので、入りきらない場合は縮めずに横スクロールさせる（表と同じ扱い）。
    var box = svg.getAttribute('viewBox').split(/[\s,]+/);
    if (box.length === 4 && !svg.getAttribute('width')) {
      svg.setAttribute('width', box[2]);
      svg.setAttribute('height', box[3]);
    }
    svg.setAttribute('role', 'img');
    if (caption) {
      // 読み上げ用なので、キャプションの装飾記号は落とした素のテキストを渡す
      svg.setAttribute('aria-label', caption.replace(/[`*]/g, ''));
    }

    return '<figure class="md-figure">'
      + '<div class="md-figure-scroll">' + new XMLSerializer().serializeToString(svg) + '</div>'
      + (caption ? '<figcaption class="md-figure-caption">' + inline(esc(caption)) + '</figcaption>' : '')
      + '</figure>';
  }

  /** マークダウン文字列をHTMLに変換する。 */
  function render(markdown) {
    var lines = String(markdown == null ? '' : markdown).replace(/\r\n/g, '\n').split('\n');
    var out = [];
    var i = 0;

    while (i < lines.length) {
      var line = lines[i];

      // ---- コードブロックと図 ----------------------------------------------
      // 開始行は「```lang」。```svg のときだけ、同じ行の残りをキャプションとして使う
      var fence = /^```(\w*)[ \t]*(.*)$/.exec(line);
      if (fence) {
        var lang = fence[1] || '';
        var info = (fence[2] || '').trim();
        var body = [];
        i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i])) {
          body.push(lines[i]);
          i++;
        }
        i++; // 閉じフェンスを飛ばす
        out.push(lang === 'svg' ? figure(body, info) : codeBlock(lang, body));
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
