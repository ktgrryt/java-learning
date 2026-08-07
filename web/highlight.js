/*
 * Javaコードに色を付ける小さなハイライタ。
 *
 * エディタの下敷き（textarea の裏に重ねる <pre>）と、解説中のコードブロックの
 * 両方で同じものを使う。書きかけのコード（閉じていない文字列など）でも
 * 壊れないことを重視していて、正確な構文解析はしていない。
 */
(function (global) {
  'use strict';

  var KEYWORDS = [
    'abstract', 'assert', 'break', 'case', 'catch', 'class', 'const', 'continue',
    'default', 'do', 'else', 'enum', 'extends', 'final', 'finally', 'for', 'goto',
    'if', 'implements', 'import', 'instanceof', 'interface', 'native', 'new',
    'package', 'private', 'protected', 'public', 'record', 'return', 'sealed',
    'static', 'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw',
    'throws', 'transient', 'try', 'var', 'volatile', 'while', 'yield'
  ];

  // 基本型と、初心者がよく触る標準クラス
  var TYPES = [
    'boolean', 'byte', 'char', 'double', 'float', 'int', 'long', 'short', 'void',
    'String', 'Integer', 'Double', 'Boolean', 'Character', 'Long', 'Object',
    'Math', 'System', 'Scanner', 'Arrays', 'List', 'ArrayList', 'Map', 'HashMap',
    'Set', 'HashSet', 'StringBuilder', 'Exception', 'RuntimeException'
  ];

  var LITERALS = ['true', 'false', 'null'];

  var word = function (list) { return '\\b(?:' + list.join('|') + ')\\b'; };

  // 先に書いたものが優先される。コメントと文字列を最初に食わせるのが肝心
  var TOKEN = new RegExp([
    '(\\/\\*[\\s\\S]*?(?:\\*\\/|$))',        // 1: ブロックコメント（閉じてなくてもOK）
    '(\\/\\/[^\\n]*)',                       // 2: 行コメント
    '("(?:\\\\.|[^"\\\\\\n])*(?:"|$))',      // 3: 文字列（閉じてなくてもOK）
    "('(?:\\\\.|[^'\\\\\\n])*(?:'|$))",      // 4: 文字リテラル
    '(@\\w+)',                               // 5: アノテーション
    '(' + word(LITERALS) + ')',              // 6: true / false / null
    '(' + word(KEYWORDS) + ')',              // 7: キーワード
    '(' + word(TYPES) + ')',                 // 8: 型・よく使うクラス
    '(\\b\\d[\\d_]*(?:\\.[\\d_]+)?[dDfFlL]?\\b)', // 9: 数値
    '(\\b[a-zA-Z_]\\w*(?=\\s*\\())'          // 10: メソッド呼び出しの名前
  ].join('|'), 'g');

  var CLASS_OF_GROUP = {
    1: 'tok-comment',
    2: 'tok-comment',
    3: 'tok-string',
    4: 'tok-string',
    5: 'tok-annotation',
    6: 'tok-literal',
    7: 'tok-keyword',
    8: 'tok-type',
    9: 'tok-number',
    10: 'tok-fn'
  };

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  /** Javaコードを、色付け用の <span> を含むHTMLにする。 */
  function highlightJava(code) {
    var out = '';
    var last = 0;
    var m;
    TOKEN.lastIndex = 0;
    while ((m = TOKEN.exec(code)) !== null) {
      // 空マッチで無限ループにならないように保険をかける
      if (m[0] === '') {
        TOKEN.lastIndex++;
        continue;
      }
      out += escapeHtml(code.slice(last, m.index));
      var cls = null;
      for (var g = 1; g <= 10; g++) {
        if (m[g] !== undefined) { cls = CLASS_OF_GROUP[g]; break; }
      }
      out += cls
        ? '<span class="' + cls + '">' + escapeHtml(m[0]) + '</span>'
        : escapeHtml(m[0]);
      last = m.index + m[0].length;
    }
    out += escapeHtml(code.slice(last));
    return out;
  }

  /** 出力例などJava以外のテキスト。色は付けずエスケープだけする。 */
  function highlightPlain(text) {
    return escapeHtml(text);
  }

  global.JQHighlight = {
    java: highlightJava,
    plain: highlightPlain,
    escapeHtml: escapeHtml
  };
})(window);
