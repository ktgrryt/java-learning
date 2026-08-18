/*
 * コード補完。VS Codeのように、書きかけの語から候補を出す。
 *
 * 候補の出どころは3つある。
 *   1. java-api.js の辞書 … 標準ライブラリのクラスとメソッド
 *   2. 今書いているコードそのもの … 自分で作った変数・メソッド・クラス
 *   3. 定型の短縮（SNIPPETS） … `sout` → `System.out.println();`
 *
 * 「今書いているコード」を読むのに本物の構文解析はしていない。書きかけの
 * コードは文法的に壊れているのが普通で、厳密な解析はすぐ失敗するため。
 * 代わりに、文字列とコメントを空白で塗りつぶしてから正規表現で拾っている。
 * 外れても候補が少し的外れになるだけで、入力の邪魔はしない。
 *
 * 操作:
 *   ↑ ↓        候補を選ぶ（macOSでは Ctrl+P / Ctrl+N でも動く）
 *   Tab        選んでいる候補を入れる
 *   Esc        閉じる
 *   Ctrl+Space 自分で呼び出す
 * Tabは候補が出ていないときは、今までどおりインデントとして働く。
 */
(function (global) {
  'use strict';

  var api = global.JQJavaApi;

  var MAX_ITEMS = 100;   // これ以上は出さない（絞り込めば消える）

  /**
   * 押しただけでは何も起きないキー。窓を閉じる判断から外す。
   *
   * `Control` を押した瞬間にも keydown が飛び、そのとき既に `ctrlKey` は true である。
   * 「修飾キー付きのキーなら窓を閉じる」だけで判断すると、**修飾キーを先に押す組み合わせ
   * （Ctrl+N / Ctrl+P）が成立しない** ― Nを押す前に窓が消えてしまう。
   */
  var BARE_MODIFIERS = {
    Control: true, Meta: true, Shift: true, Alt: true, AltGraph: true, CapsLock: true
  };

  /**
   * macOSか。Emacsキーバインド（Ctrl+P / Ctrl+N）を候補の移動に使うかの判断に用いる。
   *
   * macOSではテキスト欄で Ctrl+P / Ctrl+N が「1行上／下へ」として最初から効くので、
   * 候補の移動に割り当てても手の動きが変わらない。WindowsやLinuxでは Ctrl+N が
   * ブラウザの「新しいウィンドウ」なので、そちらは横取りしない。
   */
  function isMac() {
    var nav = global.navigator || {};
    if (nav.userAgentData && nav.userAgentData.platform) {
      return /mac/i.test(nav.userAgentData.platform);
    }
    return /Mac|iPhone|iPad/.test(nav.platform || nav.userAgent || '');
  }

  // ── 字句の下ごしらえ ───────────────────────────────────────────────

  /**
   * 文字列とコメントの中身を空白に置き換える。
   *
   * こうしておくと `"int x = 1"` のような文字列や、コメントに書いた説明を
   * コードと間違えずに正規表現をかけられる。位置は1文字もずらさないので、
   * 元のテキストの位置と結果の位置はそのまま対応する。
   *
   * 引用符自体は残す。`"abc".length()` のように文字列リテラルからメソッドを
   * 呼ぶとき、受け側が文字列だと見分けるため。
   */
  function tokenize(text) {
    var n = text.length;
    var chars = text.split('');
    var kind = new Array(n);      // 'c' コード / 's' 文字列 / 'm' コメント
    var i = 0;

    while (i < n) {
      var c = text.charAt(i);

      if (c === '/' && text.charAt(i + 1) === '/') {
        while (i < n && text.charAt(i) !== '\n') { kind[i] = 'm'; chars[i] = ' '; i++; }
        continue;
      }
      if (c === '/' && text.charAt(i + 1) === '*') {
        var end = text.indexOf('*/', i + 2);
        end = end === -1 ? n : end + 2;      // 閉じていなくても末尾まで飲む
        while (i < end) {
          kind[i] = 'm';
          if (text.charAt(i) !== '\n') { chars[i] = ' '; }
          i++;
        }
        continue;
      }
      if (c === '"' || c === '\'') {
        kind[i] = 's';
        i++;                                  // 開き記号は残す
        while (i < n) {
          var d = text.charAt(i);
          if (d === '\n') { break; }          // 閉じないまま行が終わった
          kind[i] = 's';
          if (d === '\\') {                   // \" などは2文字で1つ
            chars[i] = ' ';
            i++;
            if (i < n && text.charAt(i) !== '\n') { kind[i] = 's'; chars[i] = ' '; i++; }
            continue;
          }
          if (d === c) { i++; break; }        // 閉じ記号も残す
          chars[i] = ' ';
          i++;
        }
        continue;
      }

      kind[i] = 'c';
      i++;
    }

    return { code: chars.join(''), kind: kind };
  }

  /**
   * 各位置の波かっこの深さ。
   *
   * `{` はそれ自体を外側の深さとして数える（`{` の直後から1段深い）。
   * 変数がフィールドかローカル変数かの判定と、変数が見える範囲の判定に使う。
   */
  function braceDepths(code) {
    return depthsOf(code, '{', '}');
  }

  /** 丸かっこの深さ。引数リストの中の宣言を見分けるのに使う。 */
  function parenDepths(code) {
    return depthsOf(code, '(', ')');
  }

  function depthsOf(code, open, close) {
    var out = new Array(code.length);
    var d = 0;
    for (var i = 0; i < code.length; i++) {
      var c = code.charAt(i);
      if (c === open) { out[i] = d; d++; continue; }
      if (c === close) { d = d > 0 ? d - 1 : 0; out[i] = d; continue; }
      out[i] = d;
    }
    return out;
  }

  /** openIndex の `{` に対応する `}` の位置。見つからなければ末尾。 */
  function matchBrace(code, openIndex) {
    var d = 0;
    for (var i = openIndex; i < code.length; i++) {
      var c = code.charAt(i);
      if (c === '{') { d++; } else if (c === '}') { d--; if (d === 0) { return i; } }
    }
    return code.length;
  }

  // ── 書いているコードを読む ─────────────────────────────────────────

  // 型の書き方。`Map<String, List<Integer>>` や `int[]`、`Map.Entry` まで拾う
  var TYPE = '[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*' +
             '(?:\\s*<[^<>]*(?:<[^<>]*>[^<>]*)*>)?(?:\\s*\\[\\s*\\])*';

  var MODIFIERS = '(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)*';

  // `Type name` の並び。後ろが `= ; , ) :` のいずれかなら宣言と見なす
  var VAR_RE = new RegExp('\\b(' + TYPE + ')\\s+([A-Za-z_$][\\w$]*)\\s*(?==|;|,|\\)|:)', 'g');

  // `修飾子 戻り値 名前(引数)` の並び。後ろが `{`（本体あり）か `;`（抽象）
  var METHOD_RE = new RegExp(MODIFIERS + '(' + TYPE + ')\\s+([A-Za-z_$][\\w$]*)\\s*' +
    '\\(([^()]*)\\)\\s*(?:throws\\s+[A-Za-z_$][\\w$.,\\s]*?)?\\s*[{;]', 'g');

  var CLASS_RE = new RegExp('\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)' +
    '\\s*(?:<[^<>]*>)?\\s*(?:\\(([^()]*)\\))?' +
    '(?:\\s*extends\\s+(' + TYPE + '))?' +
    '(?:\\s*implements\\s+([A-Za-z_$][\\w$.<>,\\s]*?))?\\s*\\{', 'g');

  var IMPORT_RE = /^[ \t]*import[ \t]+(?:static[ \t]+)?([\w.$]+)[ \t]*;/gm;

  var RESERVED = {};
  (function () {
    var i;
    for (i = 0; i < api.keywords.length; i++) { RESERVED[api.keywords[i]] = true; }
    for (i = 0; i < api.literals.length; i++) { RESERVED[api.literals[i]] = true; }
  })();

  /** 型の表記から、基本の名前と型引数を取り出す。`Map<String,Integer>` → Map + [String, Integer] */
  function parseType(text) {
    if (!text) { return null; }
    var raw = String(text).replace(/\s+/g, '');
    var dims = 0;
    while (/\[\]$/.test(raw)) { raw = raw.slice(0, -2); dims++; }

    var args = [];
    var lt = raw.indexOf('<');
    if (lt !== -1 && /> *$/.test(raw)) {
      var inner = raw.slice(lt + 1, raw.lastIndexOf('>'));
      args = splitTopLevel(inner, ',');
      raw = raw.slice(0, lt);
    }

    // `Map.Entry` のような書き方は、辞書に載っている最後の部分だけを使う
    if (raw.indexOf('.') !== -1) {
      var tail = raw.slice(raw.lastIndexOf('.') + 1);
      if (api.has(tail)) { raw = tail; }
    }
    if (!raw) { return null; }
    return { name: raw, args: args, dims: dims };
  }

  /** かっこや山かっこの中を無視して区切る。`String,List<Integer,X>` → 2つ */
  function splitTopLevel(text, sep) {
    var out = [];
    var depth = 0;
    var start = 0;
    for (var i = 0; i < text.length; i++) {
      var c = text.charAt(i);
      if (c === '<' || c === '(' || c === '[') { depth++; continue; }
      if (c === '>' || c === ')' || c === ']') { depth--; continue; }
      if (c === sep && depth === 0) { out.push(text.slice(start, i)); start = i + 1; }
    }
    out.push(text.slice(start));
    var trimmed = [];
    for (var j = 0; j < out.length; j++) {
      var t = out[j].replace(/^\s+|\s+$/g, '');
      if (t) { trimmed.push(t); }
    }
    return trimmed;
  }

  /** `var` の型を、右辺から推し量る。 */
  function inferVarType(code, afterName) {
    var rest = code.slice(afterName, afterName + 120);
    var m = /^\s*=\s*new\s+([A-Za-z_$][\w$]*(?:\s*<[^<>]*>)?)/.exec(rest);
    if (m) { return m[1]; }
    if (/^\s*=\s*"/.test(rest)) { return 'String'; }
    if (/^\s*=\s*'/.test(rest)) { return 'char'; }
    if (/^\s*=\s*\d+\.\d/.test(rest)) { return 'double'; }
    if (/^\s*=\s*\d/.test(rest)) { return 'int'; }
    if (/^\s*=\s*(?:true|false)\b/.test(rest)) { return 'boolean'; }
    // `var list = List.of(...)` のような書き方は、呼び出し先のクラス名を借りる
    m = /^\s*=\s*([A-Z][\w$]*)\s*\./.exec(rest);
    if (m && api.has(m[1])) { return m[1]; }
    return '';
  }

  /**
   * 書いているコードを読んで、補完に使える名前を集める。
   *
   * 同じテキストで何度も呼ばれるので、直近の結果を1つだけ覚えておく
   * （キー入力ごとに全部読み直すが、教材のコードは数十行なので十分速い）。
   */
  var lastScan = { text: null, result: null };

  function scan(text) {
    if (lastScan.text === text) { return lastScan.result; }

    var tok = tokenize(text);
    var code = tok.code;
    var depth = braceDepths(code);
    var parens = parenDepths(code);
    var paramRanges = [];   // メソッド宣言の引数リストの範囲
    var result = {
      code: code,
      kind: tok.kind,
      depth: depth,
      vars: [],        // ローカル変数・引数・フィールド
      methods: [],     // クラスに属さないものも含めた全メソッド
      classes: {},     // クラス名 → 定義
      classList: [],
      imports: {}
    };

    var m;

    // ---- import した名前 --------------------------------------------------
    IMPORT_RE.lastIndex = 0;
    while ((m = IMPORT_RE.exec(code)) !== null) {
      var full = m[1];
      result.imports[full.slice(full.lastIndexOf('.') + 1)] = full;
    }

    // ---- クラス・インタフェース・enum・record -----------------------------
    CLASS_RE.lastIndex = 0;
    while ((m = CLASS_RE.exec(code)) !== null) {
      var open = code.indexOf('{', m.index + m[0].length - 1);
      if (open === -1) { open = m.index + m[0].length - 1; }
      var cls = {
        name: m[2],
        kind: m[1],
        components: m[3] || '',
        ext: m[4] ? m[4].replace(/\s+/g, '') : '',
        impl: m[5] ? m[5].replace(/\s+/g, '') : '',
        declPos: m.index,
        bodyStart: open + 1,
        bodyEnd: matchBrace(code, open),
        innerDepth: depth[open] + 1,
        methods: [],
        fields: [],
        statics: []
      };
      if (!result.classes[cls.name]) {
        result.classes[cls.name] = cls;
        result.classList.push(cls);
      }
    }

    /** その位置を含む、いちばん内側のクラス。 */
    function ownerAt(pos) {
      var best = null;
      for (var i = 0; i < result.classList.length; i++) {
        var c = result.classList[i];
        if (pos >= c.bodyStart && pos <= c.bodyEnd) {
          if (!best || c.bodyStart > best.bodyStart) { best = c; }
        }
      }
      return best;
    }
    result.ownerAt = ownerAt;

    // ---- メソッド ---------------------------------------------------------
    METHOD_RE.lastIndex = 0;
    while ((m = METHOD_RE.exec(code)) !== null) {
      var retRaw = m[1].replace(/\s+/g, '');
      var retBase = retRaw.replace(/[<\[].*$/, '');
      if (RESERVED[retBase] && retBase !== 'var') { continue; }
      if (RESERVED[m[2]]) { continue; }
      // `名前(` の直前に `.` があるものは呼び出し。宣言ではない
      var before = code.slice(Math.max(0, m.index - 1), m.index);
      if (before === '.') { continue; }

      var mods = m[0].slice(0, m[0].indexOf(m[1]));
      var method = {
        name: m[2],
        kind: 'method',
        params: m[3].replace(/\s+/g, ' ').replace(/^ | $/g, ''),
        type: retRaw,
        isStatic: /\bstatic\b/.test(mods),
        pos: m.index,
        doc: ''
      };
      var owner = ownerAt(m.index);
      method.owner = owner ? owner.name : '';
      result.methods.push(method);
      if (owner) { owner.methods.push(method); }

      var parenAt = m.index + m[0].indexOf('(' + m[3]);
      paramRanges.push([parenAt, parenAt + m[3].length + 1]);
    }

    // ---- 変数（宣言のかたち） ---------------------------------------------
    collectVars();

    /**
     * `Type name` の並びを集める。ローカル変数・引数・フィールドをまとめて拾う。
     *
     * フィールドとローカル変数の区別は波かっこの深さで付ける。ただし引数リストは
     * かっこの中なので深さが増えず、そのままではフィールドに見えてしまう。
     * 丸かっこの深さも見て、かっこの中の宣言はフィールドにしない。
     */
    function collectVars() {
      var re = new RegExp(VAR_RE.source, 'g');
      var v;
      while ((v = re.exec(code)) !== null) {
        var typeRaw = v[1].replace(/\s+/g, '');
        var base = typeRaw.replace(/[<\[].*$/, '').replace(/^.*\./, '');
        if (RESERVED[typeRaw] && typeRaw !== 'var') { continue; }
        if (RESERVED[base] && base !== 'var') { continue; }
        if (RESERVED[v[2]]) { continue; }

        var at = v.index;
        var nameAt = v.index + v[0].indexOf(v[2], v[1].length);
        if (typeRaw === 'var') {
          typeRaw = inferVarType(code, nameAt + v[2].length) || '';
        }

        var owner = ownerAt(at);
        var inParens = !!parens[at];
        var kind = 'var';
        if (inParens) {
          kind = inParamList(at) ? 'param' : 'var';
        } else if (owner && depth[at] === owner.innerDepth) {
          kind = 'field';
        }

        var entry = {
          name: v[2],
          kind: kind,
          type: typeRaw,
          pos: at,
          scopePos: inParens ? scopeStartOf(at) : at,
          owner: owner ? owner.name : '',
          isStatic: false
        };
        result.vars.push(entry);
        if (owner && kind === 'field') { owner.fields.push(entry); }
      }
    }

    function inParamList(at) {
      for (var i = 0; i < paramRanges.length; i++) {
        if (at > paramRanges[i][0] && at < paramRanges[i][1]) { return true; }
      }
      return false;
    }

    /**
     * かっこの中で宣言された変数が、どこから見えるようになるか。
     *
     * 引数・`for` の初期化・`catch` の例外・`instanceof` のパターン変数は、
     * どれも「かっこの後ろに続くブロックの中」で使える。だから対応する `{` の
     * 直後を有効範囲の始まりとする。ブロックが無ければかっこの直後にしておく。
     */
    function scopeStartOf(at) {
      var d = parens[at];
      var i = at;
      while (i < code.length && parens[i] >= d) { i++; }   // 閉じかっこまで進む
      for (var j = i; j < code.length; j++) {
        var c = code.charAt(j);
        if (c === '{') { return j + 1; }
        if (c === ';' || c === '}') { break; }
      }
      return i;
    }

    // ---- record の部品はフィールドと同名メソッドになる ---------------------
    for (var ci = 0; ci < result.classList.length; ci++) {
      var rc = result.classList[ci];
      if (rc.kind === 'record' && rc.components) {
        var parts = splitTopLevel(rc.components, ',');
        for (var pi = 0; pi < parts.length; pi++) {
          var pm = /^([\s\S]+?)\s+([A-Za-z_$][\w$]*)$/.exec(parts[pi]);
          if (!pm) { continue; }
          var comp = {
            name: pm[2], kind: 'method', params: '', type: pm[1].replace(/\s+/g, ''),
            isStatic: false, owner: rc.name, pos: rc.declPos, doc: 'recordの部品'
          };
          rc.methods.push(comp);
          result.methods.push(comp);
        }
      }
      // ---- enum の定数 ----------------------------------------------------
      if (rc.kind === 'enum') {
        var seg = code.slice(rc.bodyStart, rc.bodyEnd);
        var semi = seg.indexOf(';');
        if (semi !== -1) { seg = seg.slice(0, semi); }
        var consts = splitTopLevel(seg, ',');
        for (var ei = 0; ei < consts.length; ei++) {
          var cm = /^([A-Za-z_$][\w$]*)/.exec(consts[ei]);
          if (!cm || RESERVED[cm[1]]) { continue; }
          rc.statics.push({
            name: cm[1], kind: 'field', params: '', type: rc.name,
            isStatic: true, owner: rc.name, pos: rc.bodyStart, doc: 'enumの定数'
          });
        }
        rc.statics.push({
          name: 'values', kind: 'method', params: '', type: rc.name + '[]',
          isStatic: true, owner: rc.name, pos: rc.bodyStart, doc: '定数を全部並べた配列'
        });
        rc.methods.push({
          name: 'name', kind: 'method', params: '', type: 'String',
          isStatic: false, owner: rc.name, pos: rc.bodyStart, doc: '定数の名前'
        });
        rc.methods.push({
          name: 'ordinal', kind: 'method', params: '', type: 'int',
          isStatic: false, owner: rc.name, pos: rc.bodyStart, doc: '宣言した順番（0から）'
        });
      }
    }

    lastScan.text = text;
    lastScan.result = result;
    return result;
  }

  // ── 型の解決 ───────────────────────────────────────────────────────

  /**
   * 変数がその位置から見えるか。
   *
   * 宣言の位置から今の位置までの間で、波かっこの深さが宣言時より浅くなったら
   * そのブロックはもう閉じている＝見えない。これがJavaのブロックスコープそのもの。
   */
  function visibleVars(info, pos) {
    var out = [];
    var minDepth = info.depth[Math.max(0, Math.min(pos, info.depth.length - 1))];
    if (minDepth === undefined) { minDepth = 0; }

    // 位置の新しい順に見ていく。カーソルから手前へ戻りながら深さの最小を更新する
    var sorted = info.vars.slice().sort(function (a, b) { return b.scopePos - a.scopePos; });
    var cursor = pos;
    for (var i = 0; i < sorted.length; i++) {
      var v = sorted[i];
      if (v.scopePos >= pos) { continue; }
      for (var j = cursor - 1; j >= v.scopePos; j--) {
        if (info.depth[j] !== undefined && info.depth[j] < minDepth) { minDepth = info.depth[j]; }
      }
      cursor = v.scopePos;
      if (info.depth[v.scopePos] <= minDepth) { out.push(v); }
    }
    return out;
  }

  /** 型引数を当てはめる。`Set<K>` を Map<String,Integer> の文脈で見ると Set<String>。 */
  function substitute(typeText, ownerType) {
    var parsed = parseType(typeText);
    if (!parsed) { return null; }
    if (!ownerType) { return parsed; }

    var names = api.typeParamsOf(ownerType.name);
    var map = {};
    for (var i = 0; i < names.length; i++) {
      map[names[i]] = ownerType.args[i] || '';
    }
    if (map[parsed.name]) {
      var swapped = parseType(map[parsed.name]);
      if (swapped) {
        swapped.dims += parsed.dims;
        return swapped;
      }
    }
    for (var j = 0; j < parsed.args.length; j++) {
      if (map[parsed.args[j]]) { parsed.args[j] = map[parsed.args[j]]; }
    }
    return parsed;
  }

  /** 自分で書いたクラスのメンバ（親クラスの分も辿って集める）。 */
  function localMembers(info, className, wantStatic) {
    var out = [];
    var seen = {};
    var name = className;
    var guard = 0;

    while (name && guard++ < 8) {
      var cls = info.classes[name];
      if (!cls) { break; }
      var lists = wantStatic
        ? [cls.statics, filterStatic(cls.methods, true), filterStatic(cls.fields, true)]
        : [filterStatic(cls.methods, false), filterStatic(cls.fields, false)];
      for (var li = 0; li < lists.length; li++) {
        for (var i = 0; i < lists[li].length; i++) {
          var mem = lists[li][i];
          var key = mem.kind + '/' + mem.name;
          if (seen[key]) { continue; }
          seen[key] = true;
          out.push(mem);
        }
      }
      var parsedExt = parseType(cls.ext);
      name = parsedExt ? parsedExt.name : '';
      // 親が標準ライブラリのクラスなら、そこからは辞書を使う
      if (name && !info.classes[name] && api.has(name)) {
        var inherited = api.membersOf(name, wantStatic);
        for (var k = 0; k < inherited.length; k++) {
          var key2 = inherited[k].kind + '/' + inherited[k].name;
          if (!seen[key2]) { seen[key2] = true; out.push(inherited[k]); }
        }
        name = '';
      }
    }

    if (!wantStatic) {
      var objs = api.membersOf('Object', false);
      for (var o = 0; o < objs.length; o++) {
        var key3 = objs[o].kind + '/' + objs[o].name;
        if (!seen[key3]) { seen[key3] = true; out.push(objs[o]); }
      }
    }
    return out;
  }

  function filterStatic(list, wantStatic) {
    var out = [];
    for (var i = 0; i < list.length; i++) {
      if (!!list[i].isStatic === !!wantStatic) { out.push(list[i]); }
    }
    return out;
  }

  /** クラス名から、そのクラスのメンバ一覧。自分のコード優先、無ければ辞書。 */
  function membersOfType(info, type, wantStatic) {
    if (!type) { return []; }
    if (type.dims > 0) {
      // 配列。使えるのは length だけ
      return [{ name: 'length', kind: 'field', params: '', type: 'int', doc: '配列の要素数', owner: type.name + '[]', depth: 0 }];
    }
    if (info.classes[type.name]) { return localMembers(info, type.name, wantStatic); }
    if (api.has(type.name)) { return api.membersOf(type.name, wantStatic); }
    return [];
  }

  /** メンバ1つを名前で引く（自分のコード → 辞書の順）。 */
  function memberByName(info, type, name, wantStatic) {
    var list = membersOfType(info, type, wantStatic);
    for (var i = 0; i < list.length; i++) {
      if (list[i].name === name) { return list[i]; }
    }
    if (wantStatic) { return memberByName(info, type, name, false); }
    return null;
  }

  /**
   * `list.get(0)` のような並びを左から辿って、最後の型を決める。
   *
   * 分からなくなったら null を返す。呼び出し側はそのとき
   * 「自分で書いたメソッドを全部出す」という控えめな候補に切り替える。
   */
  function resolveReceiver(receiver, info, pos) {
    var segments = splitTopLevel(receiver, '.');
    if (!segments.length) { return null; }

    // `java.util.List.of` のような完全修飾名。パッケージの部分を読み飛ばす
    for (var q = segments.length - 1; q >= 1; q--) {
      var pkg = segments.slice(0, q).join('.');
      if (api.packageNames.indexOf(pkg) !== -1 && api.has(segments[q])) {
        segments = segments.slice(q);
        break;
      }
    }

    var first = segments[0].replace(/^\s+|\s+$/g, '');
    var type = null;
    var isStatic = false;

    if (/^["']/.test(first)) {
      type = { name: 'String', args: [], dims: 0 };
    } else if (first === 'this' || first === 'super') {
      var owner = info.ownerAt(pos);
      if (!owner) { return null; }
      var target = first === 'super' ? parseType(owner.ext) : { name: owner.name, args: [], dims: 0 };
      if (!target) { return null; }
      type = target;
    } else {
      var bare = first.replace(/\s*\[[^\]]*\]\s*$/, '');
      var indexed = bare !== first;
      var byVar = findVar(info, bare, pos);
      if (byVar) {
        type = parseType(byVar.type);
        if (type && indexed && type.dims > 0) { type.dims--; }
      } else if (info.classes[bare] || api.has(bare)) {
        type = { name: bare, args: [], dims: 0 };
        isStatic = true;
      } else {
        return null;
      }
    }
    if (!type) { return null; }

    for (var i = 1; i < segments.length; i++) {
      var seg = segments[i].replace(/^\s+|\s+$/g, '');
      var call = /^([A-Za-z_$][\w$]*)\s*\(/.test(seg);
      var nameMatch = /^([A-Za-z_$][\w$]*)/.exec(seg);
      if (!nameMatch) { return null; }
      var mem = memberByName(info, type, nameMatch[1], isStatic);
      if (!mem) { return null; }
      var next = substitute(mem.type, type);
      if (!next) { return null; }
      // `args[0]` のように添字が付いていれば1次元ぶん減らす
      if (/\]\s*$/.test(seg) && next.dims > 0) { next.dims--; }
      type = next;
      isStatic = false;
      if (!call && mem.kind === 'method') {
        // メソッドを `()` なしで書いている途中。型はそのまま使う
        isStatic = false;
      }
    }

    return { type: type, isStatic: isStatic };
  }

  function findVar(info, name, pos) {
    var list = visibleVars(info, pos);
    for (var i = 0; i < list.length; i++) {
      if (list[i].name === name) { return list[i]; }
    }
    // 見える範囲の判定に失敗したとき（書きかけで波かっこが揃っていないなど）の保険
    for (var j = info.vars.length - 1; j >= 0; j--) {
      if (info.vars[j].name === name) { return info.vars[j]; }
    }
    return null;
  }

  // ── 今どこを書いているか ───────────────────────────────────────────

  var WORD_TAIL = /[A-Za-z_$][\w$]*$/;

  /**
   * カーソル位置の文脈を調べる。
   *   member  … `sc.` の続き。受け側の型のメンバを出す
   *   import  … `import java.u` の続き。パッケージとクラスを出す
   *   new     … `new Sc` の続き。クラスだけを出す
   *   word    … ふつうの語。変数・メソッド・クラス・キーワードを出す
   */
  function contextAt(text, pos) {
    var info = scan(text);
    var code = info.code;

    if (pos > 0 && (info.kind[pos - 1] === 'm' || info.kind[pos - 1] === 's')) {
      return { kind: 'none' };
    }

    var lineStart = code.lastIndexOf('\n', pos - 1) + 1;
    var line = code.slice(lineStart, pos);
    var wordMatch = WORD_TAIL.exec(line);
    var prefix = wordMatch ? wordMatch[0] : '';
    var from = pos - prefix.length;

    // 数字から始まる語の続きは補完しない（`123abc` のような書きかけ）
    if (prefix && /\w/.test(code.charAt(from - 1))) {
      return { kind: 'none' };
    }

    var imp = /^[ \t]*import[ \t]+(?:static[ \t]+)?([\w.$]*)$/.exec(line);
    if (imp) {
      var qualified = imp[1];
      var lastDot = qualified.lastIndexOf('.');
      return {
        kind: 'import',
        head: lastDot === -1 ? '' : qualified.slice(0, lastDot),
        prefix: prefix,
        from: from,
        info: info
      };
    }

    if (/\bnew\s+[A-Za-z_$][\w$]*$/.test(line) || /\bnew\s+$/.test(line)) {
      return { kind: 'new', prefix: prefix, from: from, info: info };
    }

    // 直前が `.` ならメンバ補完。`.` の手前を式として読む
    var dotAt = from - 1;
    while (dotAt >= lineStart && /[ \t]/.test(code.charAt(dotAt))) { dotAt--; }
    if (dotAt >= 0 && code.charAt(dotAt) === '.' && code.charAt(dotAt - 1) !== '.') {
      var receiver = readReceiver(info, dotAt);
      return {
        kind: 'member',
        receiver: receiver,
        prefix: prefix,
        from: from,
        info: info
      };
    }

    return { kind: 'word', prefix: prefix, from: from, info: info };
  }

  /**
   * `.` の手前にある式を後ろ向きに読む。
   *
   * 「セグメント（識別子・呼び出し・添字・文字列）」と「.」が交互に並ぶ形だけを
   * 受け付ける。`return list.` の `return` まで飲み込まないように、
   * 空白は `.` の周りだけ飛ばす。
   */
  function readReceiver(info, dotIndex) {
    var code = info.code;
    var i = dotIndex - 1;
    var end = dotIndex;

    for (var guard = 0; guard < 40; guard++) {
      while (i >= 0 && /[ \t\r\n]/.test(code.charAt(i))) { i--; }
      var segEnd = i;

      // 後ろの `)` `]` はかっこの対応を取って飛ばす
      while (i >= 0 && (code.charAt(i) === ')' || code.charAt(i) === ']')) {
        i = matchOpenBackward(code, i) - 1;
        if (i < -1) { return ''; }
      }
      // 文字列リテラル
      if (i >= 0 && info.kind[i] === 's') {
        while (i >= 0 && info.kind[i] === 's') { i--; }
      } else {
        while (i >= 0 && /[\w$]/.test(code.charAt(i))) { i--; }
      }
      if (i === segEnd) { return ''; }        // セグメントが空。式として読めない

      end = i + 1;
      var j = i;
      while (j >= 0 && /[ \t\r\n]/.test(code.charAt(j))) { j--; }
      if (j >= 0 && code.charAt(j) === '.' && code.charAt(j - 1) !== '.') {
        i = j - 1;
        continue;
      }
      break;
    }

    return code.slice(end, dotIndex).replace(/^\s+|\s+$/g, '');
  }

  /** closeIndex の閉じかっこに対応する開きかっこの位置。 */
  function matchOpenBackward(code, closeIndex) {
    var close = code.charAt(closeIndex);
    var open = close === ')' ? '(' : '[';
    var d = 0;
    for (var i = closeIndex; i >= 0; i--) {
      var c = code.charAt(i);
      if (c === close) { d++; } else if (c === open) { d--; if (d === 0) { return i; } }
    }
    return -1;
  }

  // ── 候補を作る ─────────────────────────────────────────────────────

  // 同じ点数のときの並び順。小さいほうが上に出る。
  // 「今書いているコードに近いもの」から順に見せる並びにしてある
  var RANK = {
    'var': 0, 'param': 0, 'field': 1, 'method': 2,
    'localClass': 3,                          // このファイルで宣言したクラス
    'keyword': 4, 'literal': 4, 'type': 4,    // if / for / int など
    'nearClass': 5, 'package': 5,             // import 済み、または java.lang
    'class': 6                                // それ以外の標準ライブラリ
  };

  var PRIMITIVE = {};
  (function () {
    for (var i = 0; i < api.primitives.length; i++) { PRIMITIVE[api.primitives[i]] = true; }
  })();

  function memberItem(mem, ownLabel) {
    var detail = mem.kind === 'method'
      ? '(' + (mem.params || '') + ')' + (mem.type ? ': ' + mem.type : '')
      : (mem.type ? ': ' + mem.type : '');
    return {
      label: mem.name,
      kind: mem.kind,
      params: mem.params || '',
      detail: detail,
      doc: mem.doc || '',
      origin: ownLabel || mem.owner || '',
      rank: RANK[mem.kind] === undefined ? 3 : RANK[mem.kind],
      depth: mem.depth || 0
    };
  }

  /** 辞書のクラス1つ。near は import 済みや java.lang のもので、少し上に出す。 */
  function classItem(name, near) {
    var pkg = api.packageOf(name);
    return {
      label: name,
      kind: 'class',
      params: '',
      detail: pkg,
      doc: api.docOf(name),
      origin: pkg,
      rank: near ? RANK['nearClass'] : RANK['class'],
      depth: 0
    };
  }

  function keywordItem(word, doc, kind) {
    return {
      label: word, kind: kind || 'keyword', params: '', detail: '',
      doc: doc || '', origin: '', rank: RANK[kind || 'keyword'], depth: 0
    };
  }

  /**
   * 短縮して書ける定型。略記はIntelliJのlive templateと同じものに合わせてある。
   *
   * `System.out.println(` は基礎編で何百回も打つが、打つ手そのものが練習になるのは
   * **出力が到達目標の第1章**だけである。そこから先は計算した値を覗く窓になるので、
   * `sout` で入れられるようにしてある。標準の書き方は入る文字列の側に残るので、
   * `System.out.println` を読んで覚える機会は減らない。
   *
   * 置いておくだけでは気づかれないので、**教材の側で案内する**（`2-1` の解説の最後
   * 「打つ手間を減らす（エディタの補完）」）。一度は画面から案内カードを出す形にしたが、
   * 出す条件（レッスンの1問目・閉じるまで・使えたら引っ込める）が読み手から見て特殊で、
   * 読み返せる場所にないため教材へ移した。
   *
   * `insert` … 入れる文字列。`caret` … その先頭から数えたカーソルの位置
   */
  var SNIPPETS = [
    {
      label: 'sout',
      insert: 'System.out.println();',
      caret: 'System.out.println('.length,
      doc: '1行出力する。かっこの中にカーソルが入る'
    }
  ];

  function snippetItem(snip) {
    return {
      label: snip.label, kind: 'snippet', params: '', detail: snip.insert,
      doc: snip.doc || '', origin: '', rank: RANK['keyword'], depth: 0,
      insert: snip.insert, caret: snip.caret
    };
  }

  function localClassItem(cls) {
    return {
      label: cls.name, kind: 'class', params: '', detail: cls.kind,
      doc: 'このファイルの' + cls.kind, origin: '', rank: RANK['localClass'], depth: 0
    };
  }

  /** import してあるか java.lang のクラスか。すぐ使えるので優先して見せる。 */
  function isNearby(info, name) {
    return !!info.imports[name] || api.packageOf(name) === 'java.lang';
  }

  function varItem(v) {
    var label = v.kind === 'field' ? 'フィールド' : (v.kind === 'param' ? '引数' : 'この中の変数');
    return {
      label: v.name,
      kind: v.kind === 'param' ? 'var' : v.kind,
      params: '',
      detail: v.type ? ': ' + v.type : '',
      doc: label,
      origin: v.owner || '',
      rank: RANK[v.kind] === undefined ? 0 : RANK[v.kind],
      depth: 0
    };
  }

  /** 語の補完（変数・自分のメソッド・クラス・キーワード）。 */
  function wordCandidates(info, pos) {
    var items = [];
    var seen = {};
    var i;

    var vars = visibleVars(info, pos);
    for (i = 0; i < vars.length; i++) {
      if (seen['v' + vars[i].name]) { continue; }
      seen['v' + vars[i].name] = true;
      items.push(varItem(vars[i]));
    }

    // 同じクラスの中のメソッドは、そのまま名前で呼べる
    var owner = info.ownerAt(pos);
    if (owner) {
      var own = localMembers(info, owner.name, false).concat(localMembers(info, owner.name, true));
      for (i = 0; i < own.length; i++) {
        if (own[i].kind !== 'method') { continue; }
        if (seen['m' + own[i].name]) { continue; }
        seen['m' + own[i].name] = true;
        items.push(memberItem(own[i], owner.name));
      }
    }
    // ほかのクラスのstaticメソッドは、クラス名から呼ぶので候補にしない

    for (i = 0; i < info.classList.length; i++) {
      var cls = info.classList[i];
      if (seen['c' + cls.name]) { continue; }
      seen['c' + cls.name] = true;
      items.push(localClassItem(cls));
    }

    var names = typeNames();
    for (i = 0; i < names.length; i++) {
      if (seen['c' + names[i]]) { continue; }
      seen['c' + names[i]] = true;
      items.push(classItem(names[i], isNearby(info, names[i])));
    }

    // 定型の短縮（`sout` など）。キーワードと同じ点数なので、`s` だけ打った状態では
    // 変数や `String` に埋もれる。`sout` まで打てば完全一致で先頭に出る
    for (i = 0; i < SNIPPETS.length; i++) {
      items.push(snippetItem(SNIPPETS[i]));
    }

    for (i = 0; i < api.primitives.length; i++) {
      items.push(keywordItem(api.primitives[i], '基本型', 'type'));
    }
    for (i = 0; i < api.keywords.length; i++) {
      items.push(keywordItem(api.keywords[i], 'Javaのキーワード'));
    }
    for (i = 0; i < api.literals.length; i++) {
      items.push(keywordItem(api.literals[i], '決まった値', 'literal'));
    }

    return items;
  }

  var TYPE_NAMES = null;

  /** 辞書に載っているクラス名（メンバを知らない注釈なども含む）。 */
  function typeNames() {
    if (TYPE_NAMES) { return TYPE_NAMES; }
    var seen = {};
    var out = [];
    var i, j;
    for (i = 0; i < api.classNames.length; i++) {
      seen[api.classNames[i]] = true;
      out.push(api.classNames[i]);
    }
    for (i = 0; i < api.packageNames.length; i++) {
      var list = api.classesInPackage(api.packageNames[i]);
      for (j = 0; j < list.length; j++) {
        if (!seen[list[j]]) { seen[list[j]] = true; out.push(list[j]); }
      }
    }
    TYPE_NAMES = out;
    return out;
  }

  /** メンバ補完。受け側の型が分かればそのメンバ、分からなければ控えめな候補。 */
  function memberCandidates(ctx) {
    var info = ctx.info;
    var items = [];
    var i;

    var resolved = ctx.receiver ? resolveReceiver(ctx.receiver, info, ctx.from) : null;

    if (resolved) {
      // int や double にメソッドは無い。ここで候補なしと返せば窓は出ない
      if (resolved.type.dims === 0 && PRIMITIVE[resolved.type.name]) { return []; }

      var members = membersOfType(info, resolved.type, resolved.isStatic);
      var label = resolved.type.name + (resolved.type.dims > 0 ? '[]' : '');
      for (i = 0; i < members.length; i++) {
        items.push(memberItem(members[i], label));
      }
      if (resolved.isStatic && info.classes[resolved.type.name]) {
        // 自分で書いたクラスは、staticでないメンバも書き間違いとして出さない
        return items;
      }
      if (items.length) { return items; }
    }

    // 型が分からないときは、このファイルで宣言されたメンバを全部出す。
    // 自作クラスの変数（`dog.` など）でここに来ることが多い
    var seen = {};
    for (i = 0; i < info.methods.length; i++) {
      var mem = info.methods[i];
      if (mem.isStatic) { continue; }
      if (seen[mem.name]) { continue; }
      seen[mem.name] = true;
      items.push(memberItem(mem, mem.owner));
    }
    for (i = 0; i < info.vars.length; i++) {
      if (info.vars[i].kind !== 'field') { continue; }
      if (seen[info.vars[i].name]) { continue; }
      seen[info.vars[i].name] = true;
      items.push(memberItem({
        name: info.vars[i].name, kind: 'field', params: '',
        type: info.vars[i].type, doc: '', owner: info.vars[i].owner
      }, info.vars[i].owner));
    }
    var objs = api.membersOf('Object', false);
    for (i = 0; i < objs.length; i++) {
      if (seen[objs[i].name]) { continue; }
      seen[objs[i].name] = true;
      items.push(memberItem(objs[i], 'Object'));
    }
    return items;
  }

  /** import文の補完。パッケージとクラス名を出す。 */
  function importCandidates(ctx) {
    var items = [];
    var i;
    var prefixes = api.packagePrefixes(ctx.head ? ctx.head + '.' : '');
    for (i = 0; i < prefixes.length; i++) {
      var full = prefixes[i];
      var leaf = full.slice(full.lastIndexOf('.') + 1);
      items.push({
        label: leaf, kind: 'package', params: '', detail: full,
        doc: 'パッケージ', origin: '', rank: RANK['package'], depth: 0, isPackage: true
      });
    }
    if (ctx.head) {
      var classes = api.classesInPackage(ctx.head);
      for (i = 0; i < classes.length; i++) {
        items.push({
          label: classes[i], kind: 'class', params: '', detail: ctx.head,
          doc: api.docOf(classes[i]), origin: '', rank: RANK['nearClass'], depth: 0
        });
      }
    }
    return items;
  }

  /** `new` の後。作れるクラスだけを出す。 */
  function newCandidates(ctx) {
    var items = [];
    var i;
    for (i = 0; i < ctx.info.classList.length; i++) {
      var cls = ctx.info.classList[i];
      if (cls.kind === 'interface') { continue; }
      items.push(localClassItem(cls));
    }
    var names = typeNames();
    for (i = 0; i < names.length; i++) {
      if (ctx.info.classes[names[i]]) { continue; }
      items.push(classItem(names[i], isNearby(ctx.info, names[i])));
    }
    return items;
  }

  // ── 絞り込みと並べ替え ─────────────────────────────────────────────

  /** 大文字の頭文字だけを取り出す。`getOrDefault` → `god`、`StringBuilder` → `sb` */
  function initialsOf(label) {
    var out = label.charAt(0);
    for (var i = 1; i < label.length; i++) {
      var c = label.charAt(i);
      if (c >= 'A' && c <= 'Z') { out += c; }
      else if (c === '_' && i + 1 < label.length) { out += label.charAt(i + 1); }
    }
    return out.toLowerCase();
  }

  function isSubsequence(label, prefix) {
    var i = 0;
    for (var j = 0; j < label.length && i < prefix.length; j++) {
      if (label.charAt(j) === prefix.charAt(i)) { i++; }
    }
    return i === prefix.length;
  }

  /** 一致の良さ。小さいほど良い。-2 は完全一致、-1 は不一致 */
  function scoreOf(label, prefix) {
    if (!prefix) { return 0; }
    if (label === prefix) { return -2; }
    if (label.indexOf(prefix) === 0) { return 0; }
    var lower = label.toLowerCase();
    var want = prefix.toLowerCase();
    if (lower === want) { return -2; }
    if (lower.indexOf(want) === 0) { return 1; }
    if (initialsOf(label).indexOf(want) === 0) { return 2; }
    if (lower.indexOf(want) !== -1) { return 3; }
    if (isSubsequence(lower, want)) { return 4; }
    return -1;
  }

  /**
   * 絞り込んで並べ替える。
   *
   * 何か打っているときは「一致の良さ → 近さ → 短さ」の順で並べる。
   * まだ何も打っていないとき（`sc.` の直後など）は辞書に書いた順を保つ。
   * `System.out.` で println が先頭に来るのはこのため。
   */
  function filterAndSort(items, prefix) {
    var out = [];
    for (var i = 0; i < items.length; i++) {
      var s = scoreOf(items[i].label, prefix);
      if (s < -1) { items[i].score = 0; items[i].exact = true; }
      else if (s < 0) { continue; }
      else { items[i].score = s; items[i].exact = false; }
      items[i].order = i;
      out.push(items[i]);
    }
    out.sort(function (a, b) {
      if (a.exact !== b.exact) { return a.exact ? -1 : 1; }
      if (a.score !== b.score) { return a.score - b.score; }
      if (a.depth !== b.depth) { return a.depth - b.depth; }
      if (a.rank !== b.rank) { return a.rank - b.rank; }
      if (prefix) {
        if (a.label.length !== b.label.length) { return a.label.length - b.label.length; }
        if (a.label !== b.label) { return a.label < b.label ? -1 : 1; }
      }
      return a.order - b.order;
    });
    return out.length > MAX_ITEMS ? out.slice(0, MAX_ITEMS) : out;
  }

  /**
   * カーソル位置に出す候補を返す。
   * explicit が true（Ctrl+Space）なら、語が1文字も無くても候補を出す。
   */
  function suggest(text, pos, explicit) {
    var ctx = contextAt(text, pos);
    if (ctx.kind === 'none') { return null; }
    if (!explicit && !ctx.prefix && ctx.kind !== 'member' && ctx.kind !== 'import') { return null; }

    var items;
    if (ctx.kind === 'member') { items = memberCandidates(ctx); }
    else if (ctx.kind === 'import') { items = importCandidates(ctx); }
    else if (ctx.kind === 'new') { items = newCandidates(ctx); }
    else { items = wordCandidates(ctx.info, pos); }

    items = filterAndSort(items, ctx.prefix);
    if (!items.length) { return null; }
    return { items: items, from: ctx.from, to: pos, prefix: ctx.prefix, context: ctx.kind };
  }

  // ── 候補の一覧（ポップアップ） ─────────────────────────────────────

  var ICONS = {
    method: 'M', field: 'F', var: 'V', class: 'C',
    keyword: 'K', literal: 'L', type: 'T', package: 'P',
    snippet: 'S'
  };

  /**
   * 候補の一覧を出す小さな窓。
   *
   * エディタの箱は overflow:hidden なので、その中に置くと切れてしまう。
   * body直下に position:fixed で置き、カーソルの座標に合わせて動かす。
   *
   * 窓はページ全体で1つだけ使う（sharedPopup）。エディタは1レッスンに複数あるが、
   * 入力できるのは1つだけなので足りる。レッスンを開き直すたびに窓と
   * イベント登録が増えていくのを避けるためでもある。
   */
  function Popup() {
    this.id = 'cmp';
    this.el = document.createElement('div');
    this.el.className = 'cmp-popup';
    this.el.hidden = true;
    this.el.innerHTML =
      '<ul class="cmp-list" role="listbox" id="' + this.id + '-list"></ul>' +
      '<div class="cmp-doc" hidden></div>';
    this.list = this.el.querySelector('.cmp-list');
    this.docBox = this.el.querySelector('.cmp-doc');
    document.body.appendChild(this.el);
  }

  Popup.prototype.render = function (items, index, prefix) {
    var html = '';
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      html +=
        '<li class="cmp-item' + (i === index ? ' is-selected' : '') + '"' +
        ' id="' + this.id + '-o' + i + '" role="option" data-index="' + i + '"' +
        ' aria-selected="' + (i === index ? 'true' : 'false') + '">' +
        '<span class="cmp-icon cmp-icon-' + it.kind + '">' + (ICONS[it.kind] || '·') + '</span>' +
        '<span class="cmp-label">' + markMatch(it.label, prefix) + '</span>' +
        '<span class="cmp-detail">' + esc(it.detail || '') + '</span>' +
        '</li>';
    }
    this.list.innerHTML = html;
    this.el.hidden = false;
    this.showDoc(items[index]);
  };

  Popup.prototype.select = function (items, index) {
    var nodes = this.list.children;
    for (var i = 0; i < nodes.length; i++) {
      var on = i === index;
      nodes[i].className = 'cmp-item' + (on ? ' is-selected' : '');
      nodes[i].setAttribute('aria-selected', on ? 'true' : 'false');
    }
    var node = nodes[index];
    if (node) {
      var top = node.offsetTop;
      var bottom = top + node.offsetHeight;
      if (top < this.list.scrollTop) { this.list.scrollTop = top; }
      else if (bottom > this.list.scrollTop + this.list.clientHeight) {
        this.list.scrollTop = bottom - this.list.clientHeight;
      }
    }
    this.showDoc(items[index]);
  };

  Popup.prototype.showDoc = function (item) {
    if (!item || (!item.doc && !item.origin)) {
      this.docBox.hidden = true;
      return;
    }
    var head = item.origin ? '<b>' + esc(item.origin) + '</b>' : '';
    this.docBox.innerHTML = head + (item.doc ? '<span>' + esc(item.doc) + '</span>' : '');
    this.docBox.hidden = false;
  };

  Popup.prototype.hide = function () {
    this.el.hidden = true;
    this.list.innerHTML = '';
  };

  Popup.prototype.place = function (x, y, lineHeight) {
    var el = this.el;
    // 大きさを測るために、いったん左上に置く（同じ描画の中で動かすので見えはしない）
    el.style.left = '0px';
    el.style.top = '0px';

    var w = el.offsetWidth;
    var h = el.offsetHeight;
    var vw = window.innerWidth;
    var vh = window.innerHeight;

    var left = Math.max(6, Math.min(x, vw - w - 8));
    var top = y;
    // 下に入らなければカーソルの上に出す
    if (top + h > vh - 8) {
      var above = y - lineHeight - h;
      top = above > 6 ? above : Math.max(6, vh - h - 8);
    }
    el.style.left = Math.round(left) + 'px';
    el.style.top = Math.round(top) + 'px';
  };

  function esc(text) {
    return global.JQHighlight ? global.JQHighlight.escapeHtml(text) : String(text);
  }

  /** 打った文字と一致した部分を太字にする。 */
  function markMatch(label, prefix) {
    if (!prefix) { return esc(label); }
    var lower = label.toLowerCase();
    var want = prefix.toLowerCase();
    var at = lower.indexOf(want);
    if (at !== -1) {
      return esc(label.slice(0, at)) + '<b>' + esc(label.slice(at, at + want.length)) + '</b>' +
        esc(label.slice(at + want.length));
    }
    // 飛ばし読みで一致した場合は1文字ずつ印を付ける
    var out = '';
    var pi = 0;
    for (var i = 0; i < label.length; i++) {
      var ch = label.charAt(i);
      if (pi < want.length && ch.toLowerCase() === want.charAt(pi)) {
        out += '<b>' + esc(ch) + '</b>';
        pi++;
      } else {
        out += esc(ch);
      }
    }
    return out;
  }

  // ── 文字幅の測定 ───────────────────────────────────────────────────

  var measureCanvas = null;

  /** テキストの表示幅（px）。カーソルの座標を出すために使う。 */
  function textWidth(text, cs) {
    if (!measureCanvas) { measureCanvas = document.createElement('canvas'); }
    var ctx = measureCanvas.getContext('2d');
    ctx.font = (cs.fontStyle || 'normal') + ' ' + (cs.fontWeight || 'normal') + ' ' +
      cs.fontSize + ' ' + cs.fontFamily;
    return ctx.measureText(text).width;
  }

  // ── エディタとつなぐ ───────────────────────────────────────────────

  var sharedPopup = null;   // ページ全体で1つだけ作る窓
  var active = null;        // いま候補を出している Completer

  /**
   * 共有の窓を用意する。
   *
   * 窓そのものへの操作（クリック）と、画面全体の操作（スクロール・リサイズ・
   * 窓の外のクリック）は、ここで一度だけ登録して、いま開いている Completer に配る。
   * エディタごとに登録すると、レッスンを開き直すたびに増えて消えなくなる。
   */
  function ensurePopup() {
    if (sharedPopup) { return sharedPopup; }
    sharedPopup = new Popup();

    // クリックで候補を選べるようにする。mousedownを止めて入力欄からフォーカスを外さない
    sharedPopup.el.addEventListener('mousedown', function (e) { e.preventDefault(); });
    sharedPopup.el.addEventListener('click', function (e) {
      if (!active) { return; }
      var li = e.target;
      while (li && li !== sharedPopup.el && !li.hasAttribute('data-index')) { li = li.parentNode; }
      if (!li || li === sharedPopup.el) { return; }
      active.index = parseInt(li.getAttribute('data-index'), 10) || 0;
      active.accept();
    });
    // マウスを乗せただけでは選び直さない（Tabで入るものが勝手に変わらないように）。
    // 窓が出た場所にたまたまマウスが止まっていることがあるため。
    // 乗せている候補は CSS の :hover で分かるようにしてある。

    var reposition = function () { if (active) { active.place(); } };
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    document.addEventListener('mousedown', function (e) {
      if (active && !sharedPopup.el.contains(e.target)) { active.close(); }
    }, true);

    return sharedPopup;
  }

  /**
   * 1つのエディタに補完機能を足す。
   * editor.js から `new JQComplete.Completer(editor)` で作られる。
   */
  function Completer(editor) {
    this.editor = editor;
    this.input = editor.input;
    this.popup = ensurePopup();
    this.items = [];
    this.index = 0;
    this.from = 0;
    this.to = 0;
    this.prefix = '';
    this.open = false;
    this._accepting = false;

    var self = this;

    this.input.setAttribute('aria-autocomplete', 'list');
    this.input.setAttribute('aria-expanded', 'false');
    this.input.setAttribute('aria-controls', this.popup.id + '-list');

    // 入力欄そのものへの登録は、エディタが消えれば一緒に消えるので放っておいてよい
    this.input.addEventListener('blur', function () { self.close(); });
    this.input.addEventListener('scroll', function () { if (self.open) { self.place(); } });
  }

  Completer.prototype.isOpen = function () { return this.open; };

  /** 入力のたびに呼ばれる。出すべきなら出し、そうでなければ閉じる。 */
  Completer.prototype.onInput = function () {
    if (this._accepting) { return; }
    var el = this.input;
    if (el.selectionStart !== el.selectionEnd) { this.close(); return; }

    var value = el.value;
    var pos = el.selectionStart;
    var prev = value.charAt(pos - 1);

    // `.` を打った直後、または語を書いている途中だけ自動で出す
    if (prev !== '.' && !/[A-Za-z_$]/.test(prev)) { this.close(); return; }

    this.trigger(false);
  };

  /** 候補を作って表示する。explicit は Ctrl+Space から呼ばれたとき。 */
  Completer.prototype.trigger = function (explicit) {
    var el = this.input;
    if (el.selectionStart !== el.selectionEnd) { this.close(); return false; }

    var res = null;
    try {
      res = suggest(el.value, el.selectionStart, explicit);
    } catch (err) {
      // 補完は「あると便利」なだけの機能。壊れても入力の邪魔はしない
      if (global.console && console.warn) { console.warn('補完に失敗しました', err); }
      res = null;
    }
    if (!res) { this.close(); return false; }

    // 別のエディタが出していた候補は先に閉じる（窓は共有なので）
    if (active && active !== this) { active.close(); }

    this.items = res.items;
    this.from = res.from;
    this.to = res.to;
    this.prefix = res.prefix;
    this.index = 0;
    this.open = true;
    active = this;

    this.popup.render(this.items, this.index, this.prefix);
    this.input.setAttribute('aria-expanded', 'true');
    this.input.setAttribute('aria-activedescendant', this.popup.id + '-o0');
    this.place();   // カーソルがスクロールで隠れていれば、ここで閉じる
    return this.open;
  };

  Completer.prototype.close = function () {
    if (!this.open) { return; }
    this.open = false;
    this.items = [];
    if (active === this) { active = null; }
    this.popup.hide();
    this.input.setAttribute('aria-expanded', 'false');
    this.input.removeAttribute('aria-activedescendant');
  };

  Completer.prototype.move = function (delta) {
    if (!this.open || !this.items.length) { return; }
    var n = this.items.length;
    this.index = ((this.index + delta) % n + n) % n;
    this.popup.select(this.items, this.index);
    this.input.setAttribute('aria-activedescendant', this.popup.id + '-o' + this.index);
  };

  /** 選んでいる候補を書き込む。 */
  Completer.prototype.accept = function () {
    if (!this.open || !this.items.length) { return false; }
    var item = this.items[this.index];
    var el = this.input;
    var value = el.value;
    var text = item.label;
    var caret = text.length;
    // 自動で足した `)` の位置。入れた文字列の先頭から数える。-1 は足していない。
    // `sout` は `);` まで入れるので、末尾から数えると `;` を指してしまう
    var closerAt = -1;

    if (item.kind === 'snippet') {
      // 定型はひとかたまりで入れ替える。`sout` → `System.out.println();`
      text = item.insert;
      caret = item.caret;
      closerAt = item.caret;   // かっこの中で `)` を打っても重ならないように
    } else if (item.isPackage) {
      // パッケージは `.` まで入れて、続きの候補をもう一度出す
      text += '.';
      caret = text.length;
    } else if (item.kind === 'method') {
      var next = value.charAt(this.to);
      if (next === '(') {
        caret = text.length;                      // すでに `(` がある
      } else {
        text += '()';
        caret = item.params ? text.length - 1 : text.length;  // 引数があればかっこの中へ
        closerAt = text.length - 1;
      }
    }

    // 書き込みで input イベントが飛び、そこから onInput → trigger と戻ってきて
    // 入れた語でまた候補が出てしまう。その間だけ自動表示を止める。
    // 途中で失敗しても戻せるように finally で下ろす
    this._accepting = true;
    try {
      this.close();
      this.editor.replaceRange(this.from, this.to, text, caret);
      // ここで入れた `)` は自動で足したものなので、引数を書いたあとに `)` を打ったら
      // 通り抜けてよい。覚えさせないと、その `)` が重なって `println("x"))` になる
      if (closerAt >= 0 && this.editor.markAutoClosed) {
        this.editor.markAutoClosed(this.from + closerAt);
      }
    } finally {
      this._accepting = false;
    }

    if (item.isPackage) { this.trigger(true); }
    return true;
  };

  /** カーソルの真下に窓を置く。 */
  Completer.prototype.place = function () {
    var el = this.input;
    var cs = global.getComputedStyle(el);
    var rect = el.getBoundingClientRect();
    var lineHeight = parseFloat(cs.lineHeight) || 20;
    var padLeft = parseFloat(cs.paddingLeft) || 0;
    var padTop = parseFloat(cs.paddingTop) || 0;

    var value = el.value;
    var anchor = this.from;
    var lineStart = value.lastIndexOf('\n', anchor - 1) + 1;
    var lineIndex = 0;
    for (var i = 0; i < lineStart; i++) {
      if (value.charCodeAt(i) === 10) { lineIndex++; }
    }

    var x = rect.left + padLeft + textWidth(value.slice(lineStart, anchor), cs) - el.scrollLeft;
    var yTop = rect.top + padTop + lineIndex * lineHeight - el.scrollTop;

    // カーソルの行がスクロールで隠れているときは出さない
    if (yTop + lineHeight < rect.top || yTop > rect.bottom) { this.close(); return; }

    this.popup.place(x, yTop + lineHeight, lineHeight);
  };

  /**
   * キー操作。処理したら true を返す。
   * 呼び出し側（editor.js）は true のとき、そのキーの通常の動作をやめる。
   */
  Completer.prototype.handleKeyDown = function (e) {
    var mod = e.metaKey || e.ctrlKey;

    // Ctrl+Space で自分から呼び出す
    if (e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey && (e.key === ' ' || e.code === 'Space')) {
      e.preventDefault();
      if (this.open) { this.close(); } else { this.trigger(true); }
      return true;
    }

    if (!this.open) { return false; }

    // 修飾キーそのものを押しただけなら、まだ何も起きていない。窓は開けたままにする
    if (BARE_MODIFIERS[e.key]) { return false; }

    // macOSのEmacsキーバインドでも候補を選べるようにする（Ctrl+P 上 / Ctrl+N 下）。
    // 横取りするのは**窓が出ているあいだだけ**である ― 出ていないときは上の
    // `!this.open` で先に返るので、OS本来の「1行上／下へ」がそのまま残る。
    if (isMac() && e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey) {
      if (e.key === 'n' || e.code === 'KeyN') {
        e.preventDefault();
        this.move(1);
        return true;
      }
      if (e.key === 'p' || e.code === 'KeyP') {
        e.preventDefault();
        this.move(-1);
        return true;
      }
    }

    switch (e.key) {
      case 'Tab':
        if (e.shiftKey) { this.close(); return false; }
        e.preventDefault();
        this.accept();
        return true;
      case 'ArrowDown':
        e.preventDefault();
        this.move(1);
        return true;
      case 'ArrowUp':
        e.preventDefault();
        this.move(-1);
        return true;
      case 'PageDown':
        e.preventDefault();
        this.move(8);
        return true;
      case 'PageUp':
        e.preventDefault();
        this.move(-8);
        return true;
      case 'Escape':
        e.preventDefault();
        this.close();
        return true;
      default:
        break;
    }

    // ここに来たキーは補完のものではない。
    // 移動・確定・ショートカットは候補を閉じてから、本来の動作に任せる
    if (mod || e.key === 'Enter' || e.key === 'ArrowLeft' || e.key === 'ArrowRight' ||
        e.key === 'Home' || e.key === 'End') {
      this.close();
    }
    return false;
  };

  global.JQComplete = {
    Completer: Completer,
    // ショートカットの案内文を場合分けするために app.js が読む
    isMac: isMac,
    // 中身の確認用（テストや動作確認から呼べるようにしておく）
    suggest: suggest,
    scan: scan,
    contextAt: contextAt
  };
})(window);
