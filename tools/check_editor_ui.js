/*
 * コードを書く欄（web/editor.js）のキー操作をブラウザで確かめる。check-editor-ui.sh から呼ばれる。
 *
 * 引数: <アプリのポート> <ChromeのCDPポート>
 *
 * いちばん見たいのは**閉じ記号の打ち抜け**である。通り抜けてよいのは「エディタが自動で足した
 * 閉じ記号」の前だけで、ひな形にあった `)` や自分で書いた `)` の前では打った文字が入らなければ
 * ならない。この判定は位置の記憶（`web/editor.js` の `_autoClosed`）に頼っており、記憶が
 * 編集でずれると「打った `)` が消える」か「`)` が重なる」のどちらかになる。どちらも
 * **書いた本人にしか見えない**ので、ここで打鍵そのものを送って確かめる。
 *
 * 引用符も同じ枠組みで見る。`"` は開きと閉じが同じ記号なので、足してよい場面の判断が
 * かっこより難しい ― 文字列の中では足さず、テキストブロック（`"""`）を打つ途中でも
 * 足してはいけない（第18章に、学習者が `"""` を自分で打つ問題がある）。
 *
 * 補完の案内（`sout` を知らせるカード）もここで見る。出す条件が「第2章以降のレッスンの1問目で、
 * まだ閉じていないとき」という画面側の判断なので、教材や採点では確かめられない。
 *
 * 落とし穴を3つ踏んであるので、真似するときは注意する。
 *   ・同じURLへ Page.navigate してもページは読み直されない（ハッシュ移動と見なされる）。
 *     about:blank を経由してから開く。
 *   ・文字入力は `Input.dispatchKeyEvent` で記号の `code` を作るのが面倒なので、
 *     ふつうの文字は `Input.insertText` に任せる。ただし**かっこ類は keydown を見ている**ので、
 *     `(` `)` `{` などは必ずキーイベントで送る（insertText では補助が働かない）。
 *   ・「打鍵で書いた状態」と「貼り付け・ひな形で出来た状態」は別物である。前者だけが
 *     打ち抜けの対象になるので、後者は textarea へ直接代入して作る。
 */
const PORT = process.argv[2];
const CDP = process.argv[3];
const GREEN = '\x1b[32m', RED = '\x1b[31m', RESET = '\x1b[0m';

/** 検査に使うレッスンと問題。単一ファイル問題であればどれでも成り立つ。 */
const LESSON = '1-1';
const TASK = '1';
/**
 * 補完の案内が出始めるレッスン（第2章の最初の練習問題）。
 * 出す位置は `web/app.js` の `COMPLETION_TIP_FROM` が持つので、動かすなら両方直す。
 */
const TIP_LESSON = '2-1';

let failures = 0;
function check(ok, message, detail) {
  if (ok) {
    console.log(`${GREEN}OK${RESET}  ${message}`);
  } else {
    failures++;
    console.log(`${RED}FAIL${RESET} ${message}${detail === undefined ? '' : ' → ' + JSON.stringify(detail)}`);
  }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function pageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${CDP}/json/list`)).json();
  const page = list.find(t => t.type === 'page');
  if (!page) { throw new Error('Chromeのpageターゲットが見つかりません'); }
  return page.webSocketDebuggerUrl;
}

function connect(url) {
  const ws = new WebSocket(url);
  const waiting = new Map();
  let id = 0;
  const ready = new Promise(res => ws.addEventListener('open', res));
  ws.addEventListener('message', ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && waiting.has(msg.id)) {
      const { resolve, reject } = waiting.get(msg.id);
      waiting.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    }
  });
  const send = async (method, params = {}) => {
    await ready;
    const mid = ++id;
    return new Promise((resolve, reject) => {
      waiting.set(mid, { resolve, reject });
      ws.send(JSON.stringify({ id: mid, method, params }));
    });
  };
  return { send, close: () => ws.close() };
}

/**
 * キーイベントに必要な `code` と仮想キーコード。US配列で送る。
 * `modifiers: 8` は Shift（`)` は Shift+0、`{` は Shift+[ など）。
 */
const KEYS = {
  '(': { code: 'Digit9', vk: 57, modifiers: 8 },
  ')': { code: 'Digit0', vk: 48, modifiers: 8 },
  '{': { code: 'BracketLeft', vk: 219, modifiers: 8 },
  '}': { code: 'BracketRight', vk: 221, modifiers: 8 },
  '[': { code: 'BracketLeft', vk: 219, modifiers: 0 },
  ']': { code: 'BracketRight', vk: 221, modifiers: 0 },
  ';': { code: 'Semicolon', vk: 186, modifiers: 0 },
  '"': { code: 'Quote', vk: 222, modifiers: 8 }
};

(async () => {
  const { send, close } = connect(await pageTarget());
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  // 編集したJSが読み込まれないまま通ってしまわないように
  await send('Network.setCacheDisabled', { cacheDisabled: true });
  await send('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.__jqErrors = [];'
      + 'window.addEventListener("error", e => window.__jqErrors.push(String(e.message)));'
      + 'window.addEventListener("unhandledrejection", e => window.__jqErrors.push(String(e.reason)));'
  });

  const ev = async expression => {
    const r = await send('Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error((d.exception && d.exception.description) || JSON.stringify(d));
    }
    return r.result.value;
  };

  /** かっこ類・記号を打鍵として送る（keydown を見ている補助を働かせるため）。 */
  const key = async ch => {
    const k = KEYS[ch];
    if (!k) { throw new Error('KEYS に無い文字です: ' + ch); }
    const common = { key: ch, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk,
                     modifiers: k.modifiers };
    await send('Input.dispatchKeyEvent',
      Object.assign({ type: 'keyDown', text: ch, unmodifiedText: ch }, common));
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyUp' }, common));
    await sleep(90);
  };
  /** Tab / Backspace / Delete のような文字を持たないキー。 */
  const special = async (name, code, vk) => {
    const common = { key: name, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk };
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyDown' }, common));
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyUp' }, common));
    await sleep(140);
  };
  const tab = () => special('Tab', 'Tab', 9);
  const backspace = () => special('Backspace', 'Backspace', 8);
  const del = () => special('Delete', 'Delete', 46);
  /**
   * Ctrl + 英字。人が打つのと同じ順序で送る ― **Control の keydown を先に出す**。
   * これを省くと、`Control` を押した瞬間に窓が閉じる不具合を見逃す
   * （そのkeydownでも `ctrlKey` は true なので、窓を閉じる判断に落ちてしまう）。
   *
   * `text` を付けないのも要点で、付けると制御文字が入ってしまう。
   * CDP の modifiers は Alt=1 / Ctrl=2 / Meta=4 / Shift=8。
   */
  const ctrlLetter = async ch => {
    const vk = ch.toUpperCase().charCodeAt(0);
    const ctrlDown = { key: 'Control', code: 'ControlLeft', modifiers: 2,
                       windowsVirtualKeyCode: 17, nativeVirtualKeyCode: 17 };
    const letter = { key: ch, code: 'Key' + ch.toUpperCase(), modifiers: 2,
                     windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk };
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyDown' }, ctrlDown));
    await sleep(60);
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyDown' }, letter));
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyUp' }, letter));
    await send('Input.dispatchKeyEvent',
      Object.assign({ type: 'keyUp' }, ctrlDown, { modifiers: 0 }));
    await sleep(150);
  };
  /** Control を押して離すだけ（何も起きてはいけない）。 */
  const ctrlAlone = async () => {
    const common = { key: 'Control', code: 'ControlLeft',
                     windowsVirtualKeyCode: 17, nativeVirtualKeyCode: 17 };
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyDown', modifiers: 2 }, common));
    await sleep(120);
    await send('Input.dispatchKeyEvent', Object.assign({ type: 'keyUp', modifiers: 0 }, common));
    await sleep(120);
  };
  /** ふつうの文字。記号の code を作らずに済ませる。 */
  const write = async s => { await send('Input.insertText', { text: s }); await sleep(90); };

  /** 補完の窓の状態（開いているか・何番目を選んでいるか）。 */
  const candidate = () => ev(`(() => {
    const pop = document.querySelector('.cmp-popup');
    if (!pop || pop.hidden) { return { open: false }; }
    const items = Array.prototype.slice.call(pop.querySelectorAll('.cmp-item'));
    const at = items.findIndex(n => n.classList.contains('is-selected'));
    const labels = pop.querySelectorAll('.cmp-item .cmp-label');
    return { open: true, count: items.length, index: at,
             selected: at >= 0 && labels[at] ? labels[at].textContent : null };
  })()`);

  const focusEditor = async value => ev(`(() => {
    const ta = document.querySelector('#task-${TASK} .editor-input');
    ta.focus();
    ta.value = ${JSON.stringify(value)};
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    ta.selectionStart = ta.selectionEnd = ta.value.length;
    return true;
  })()`);

  /** 打鍵で書いたのではない状態（ひな形・貼り付け相当）を作る。`|` がカーソル。 */
  const preset = async marked => {
    const caret = marked.indexOf('|');
    await focusEditor(marked.replace('|', ''));
    await ev(`(() => {
      const ta = document.querySelector('#task-${TASK} .editor-input');
      ta.selectionStart = ta.selectionEnd = ${caret};
      return true;
    })()`);
    await sleep(120);
  };
  const clear = async () => { await focusEditor(''); await sleep(120); };
  /** いまの中身とカーソル位置を `|` 入りの1行で返す。 */
  const read = () => ev(`(() => {
    const ta = document.querySelector('#task-${TASK} .editor-input');
    return ta.value.slice(0, ta.selectionStart) + '|' + ta.value.slice(ta.selectionStart);
  })()`);

  // ── 前提 ────────────────────────────────────────────────
  await send('Page.navigate', { url: 'about:blank' });
  await sleep(300);
  await send('Page.navigate', { url: `http://localhost:${PORT}/#${LESSON}` });
  await sleep(2000);
  const ready = await ev(`(() => ({
    editors: document.querySelectorAll('#task-${TASK} .editor-input').length,
    hash: location.hash
  }))()`);
  check(ready.editors === 1 && ready.hash === `#${LESSON}`,
    `${LESSON}#${TASK} のコード欄が1つ描けている`, ready);
  if (ready.editors !== 1) {
    console.log(`${RED}この検査は ${LESSON}#${TASK} を単一ファイル問題として触ります。`
      + `教材を変えたなら、対象を差し替えてください（tools/check_editor_ui.js の LESSON / TASK）${RESET}`);
    close();
    process.exit(1);
  }

  // ── 自動で閉じる ────────────────────────────────────────
  await clear();
  await write('println');
  await key('(');
  check(await read() === 'println(|)', '`(` を打つと `)` が自動で入り、カーソルは間に残る', await read());
  await clear();
  await key('{');
  check(await read() === '{|}', '`{` も自動で閉じる', await read());
  await clear();
  await key('[');
  check(await read() === '[|]', '`[` も自動で閉じる', await read());
  await preset('println(|abc)');
  await key('(');
  check(await read() === 'println((|abc)',
    '直後が英数字なら閉じ記号を足さない（既存の語を壊さない）', await read());

  // ── 打ち抜けは「自動で足した閉じ記号」の前だけ ──────────────────
  await clear();
  await write('println');
  await key('(');
  await write('a');
  await key(')');
  check(await read() === 'println(a)|',
    '自動で入った `)` の前で `)` を打つと通り抜ける（`))` にならない）', await read());
  await key(';');
  check(await read() === 'println(a);|', '続けて `;` を打つとかっこの外へ入る', await read());

  // ここが今回の要点。ひな形や貼り付けで出来た `)` は自動で足したものではないので、
  // 打った `)` は入らなければならない（以前はカーソルだけ動いて文字が消えていた）
  await preset('println(a)|)');
  await key(')');
  check(await read() === 'println(a))|)',
    '余分な `)` の前では、打った `)` がそのまま入る', await read());
  await preset('System.out.println(x + " = " (x + y|));');
  await key(')');
  check(await read() === 'System.out.println(x + " = " (x + y)|));',
    'ひな形の `))` の前でも、打った `)` がそのまま入る', await read());
  await preset('int[] a = {1, 2|};');
  await key('}');
  check(await read() === 'int[] a = {1, 2}|};',
    '`}` も同じ（ひな形の `}` の前では打った文字が入る）', await read());

  // ── 引用符（開きと閉じが同じ記号なので、かっことは別の枝）──────────────
  await clear();
  await write('String s = ');
  await key('"');
  check(await read() === 'String s = "|"',
    '`"` を打つと `""` が入り、カーソルは間に残る', await read());
  await write('Hello');
  await key('"');
  check(await read() === 'String s = "Hello"|',
    '自動で入った `"` の前で `"` を打つと通り抜ける', await read());

  await clear();
  await write('println');
  await key('(');
  await key('"');
  check(await read() === 'println("|")',
    'かっこの中でも `"` は自動で閉じる', await read());

  // 文字列の中で打つ `"` は「閉じるつもりの1つ」なので足さない
  await preset('String s = "ab |cd";');
  await key('"');
  check(await read() === 'String s = "ab "|cd";',
    '文字列の中では `""` を足さず、打った1文字だけ入る', await read());

  // ひな形の `"` の前では、打った文字がそのまま入る（かっこと同じ考え方）
  await preset('String s = "abc|";');
  await key('"');
  check(await read() === 'String s = "abc"|";',
    'ひな形の `"` の前では、打った `"` がそのまま入る', await read());

  // 第18章のテキストブロック。`"""` を打つとき `""""` になってはいけない
  await clear();
  await write('String m = ');
  await key('"');
  await key('"');
  await key('"');
  check(await read() === 'String m = """|',
    '`"""` を3回打つと `"""` になる（`""""` にならない）', await read());
  // テキストブロックの中では、内容の `"` を1つだけ書ける
  await preset('String m = """\n  25|\n  """;');
  await key('"');
  check(await read() === 'String m = """\n  25"|\n  """;',
    'テキストブロックの中では `"` を1つだけ入れる', await read());

  await clear();
  await key('"');
  await backspace();
  check(await read() === '|', 'Backspace で `""` をまとめて消せる', await read());

  // ── 覚えた位置が編集で動いても追従する ──────────────────────────
  await clear();
  await write('println');
  await key('(');
  await write('abcdef');
  await key(')');
  check(await read() === 'println(abcdef)|',
    '間に文字を書いたあとでも通り抜ける（位置が後ろへずれる）', await read());

  await clear();
  await write('println');
  await key('(');
  await write('abc');
  await backspace();
  await key(')');
  check(await read() === 'println(ab)|',
    'Backspaceで縮めたあとでも通り抜ける（位置が前へずれる）', await read());

  await clear();
  await write('f');
  await key('(');
  await key('(');
  await write('a');
  await key(')');
  await key(')');
  check(await read() === 'f((a))|', '入れ子でも内側→外側の順に通り抜ける', await read());

  await clear();
  await key('(');
  await del();                    // 自動で入った `)` を消す
  check(await read() === '(|', '自動で入った `)` を消せる', await read());
  await write('a');
  await key(')');
  check(await read() === '(a)|',
    '覚えていた `)` を消したあとは、打った `)` が入る', await read());

  // ── 補完が入れたかっこも「自動で足したもの」として扱う ──────────────
  await clear();
  await write('System.out.pri');
  await sleep(500);
  const popup = await ev(`(() => {
    const el = document.querySelector('.cmp-popup');
    return el ? { shown: !el.hidden, empty: !el.textContent } : null;
  })()`);
  check(popup && popup.shown && !popup.empty, '補完の窓が候補つきで出る', popup);
  await tab();
  const accepted = await read();
  // 第1候補が print か println かは辞書の並び順で決まるので、形だけを見る
  check(/^System\.out\.[A-Za-z]+\(\|\)$/.test(accepted),
    '補完がメソッドを `()` ごと入れ、カーソルを間に置く', accepted);
  await key('"');
  await write('x');
  await key('"');
  await key(')');
  const closed = await read();
  check(/^System\.out\.[A-Za-z]+\("x"\)\|$/.test(closed),
    '補完が入れた `)` も通り抜ける（`))` にならない）', closed);

  // ── 定型の短縮（`sout`）──────────────────────────────────────────
  // `);` まで入れるので、通り抜けさせる `)` は**末尾ではなく caret の位置**である。
  // ここを末尾から数えると `;` を覚えてしまい、打った `)` が消える。
  await clear();
  await write('sout');
  await sleep(500);
  const snip = await candidate();
  check(snip.open && snip.selected === 'sout',
    '`sout` を打つと同名の候補が先頭に来る', snip);
  await tab();
  const soutInserted = await read();
  check(soutInserted === 'System.out.println(|);',
    '`sout` + Tab で `System.out.println();` が入り、カーソルがかっこの中に来る', soutInserted);
  await key('"');
  await write('x');
  await key('"');
  await key(')');
  check(await read() === 'System.out.println("x")|;',
    '`sout` が入れた `)` も通り抜ける（`);` の `;` を巻き込まない）', await read());

  // ── 候補の移動（↑↓ と、macOSのEmacsキーバインド Ctrl+P / Ctrl+N）──────────
  // 窓が開いているあいだだけ横取りする。閉じているときの Ctrl+N / Ctrl+P は
  // macOSの「1行下／上へ」なので、そちらを塞いでいないことも見る。
  // 合成キーイベントではOS側の行移動が起きないため、カーソル位置ではなく
  // **こちらが横取りしたか（defaultPrevented）** で判定する。
  const mac = await ev(`!!(window.JQComplete && window.JQComplete.isMac && window.JQComplete.isMac())`);
  const note = await ev(`((document.querySelector('#task-${TASK} .shortcut-note') || {}).textContent || '')`);
  check(mac ? note.indexOf('Ctrl+P / Ctrl+N') >= 0 : note.indexOf('Ctrl+P') < 0,
    mac ? 'ショートカットの案内に Ctrl+P / Ctrl+N が出ている'
        : 'macOS以外では案内に Ctrl+P / Ctrl+N を出さない', note);

  if (!mac) {
    console.log('    （macOS以外なので Ctrl+P / Ctrl+N の割り当ては無効。移動の検査は飛ばす）');
  } else {
    await clear();
    // 横取りされたかを記録する。textarea のハンドラより後で走る window で見る
    await ev(`(() => {
      window.__pd = [];
      window.addEventListener('keydown', function (e) {
        if (e.ctrlKey && (e.key === 'n' || e.key === 'p')) {
          window.__pd.push({ key: e.key, prevented: e.defaultPrevented });
        }
      });
      return true;
    })()`);
    await write('S');
    await sleep(600);
    const first = await candidate();
    check(first.open && first.count > 2, '`S` を打つと候補が複数出る', first);

    // Ctrl+N は「Control を押す → N を押す」の2段である。1段目で閉じてはいけない
    await ctrlAlone();
    const afterCtrl = await candidate();
    check(afterCtrl.open && afterCtrl.index === first.index,
      'Control を押しただけでは窓が閉じず、選択も動かない', afterCtrl);

    await ctrlLetter('n');
    const down1 = await candidate();
    await ctrlLetter('n');
    const down2 = await candidate();
    await ctrlLetter('p');
    const up1 = await candidate();
    check(down1.index === first.index + 1 && down2.index === first.index + 2,
      'Ctrl+N で候補が1つずつ下へ動く',
      { first: first.index, down1: down1.index, down2: down2.index });
    check(up1.index === down2.index - 1, 'Ctrl+P で候補が1つ上へ戻る',
      { down2: down2.index, up1: up1.index });
    check(down1.selected && down1.selected !== first.selected,
      '選ばれている候補の見た目も変わる', { first: first.selected, next: down1.selected });

    const whileOpen = await ev(`window.__pd`);
    check(whileOpen.length === 3 && whileOpen.every(r => r.prevented),
      '窓が開いているあいだは Ctrl+N / Ctrl+P を横取りする', whileOpen);

    // ↑↓ と同じ結果になるか（片方だけ動くと、案内文が嘘になる）
    await ctrlLetter('n');
    const byCtrl = await candidate();
    await special('ArrowUp', 'ArrowUp', 38);
    await special('ArrowDown', 'ArrowDown', 40);
    const byArrow = await candidate();
    check(byCtrl.index === byArrow.index, 'Ctrl+N と ↓ は同じ動き',
      { ctrl: byCtrl.index, arrow: byArrow.index });

    // 選んだ候補は Tab で入る（移動の割り当てが確定を壊していないか）
    await tab();
    const inserted = await read();
    check(inserted.indexOf(byArrow.selected) === 0,
      'Ctrl+N で選んだ候補が Tab で入る', { selected: byArrow.selected, inserted: inserted });

    // 窓が閉じているときは横取りしない（OSの「1行下／上へ」を残す）
    await ev(`(() => { window.__pd = []; return true; })()`);
    await special('Escape', 'Escape', 27);
    await ctrlLetter('n');
    await ctrlLetter('p');
    const whileClosed = await ev(`window.__pd`);
    check(whileClosed.length === 2 && whileClosed.every(r => !r.prevented),
      '窓が閉じているときの Ctrl+N / Ctrl+P は横取りしない（OS本来の行移動を残す）',
      whileClosed);
  }

  // ── 中身を入れ替えたら覚えを捨てる ────────────────────────────
  await clear();
  await write('println');
  await key('(');
  const restored = await ev(`(async () => {
    // 「ひな形に戻す」は confirm を挟むので、そのあいだだけ差し替えて押す
    const orig = window.confirm;
    window.confirm = () => true;
    document.querySelector('#task-${TASK} [data-role="restore"]').click();
    window.confirm = orig;
    await new Promise(r => setTimeout(r, 300));
    return document.querySelector('#task-${TASK} .editor-input').value.length;
  })()`);
  check(restored > 0, '「ひな形に戻す」でひな形が入る', restored);
  await preset('println(a)|)');
  await key(')');
  check(await read() === 'println(a))|)',
    '入れ替えたあとも、余分な `)` の前では打った `)` が入る', await read());

  // ── 第1章の1行を通しで打つ（補助どうしが噛み合っているか）──────────────
  await clear();
  await write('System.out.println');
  await key('(');
  await key('"');
  await write('Hello, Java!');
  await key('"');
  await key(')');
  await key(';');
  check(await read() === 'System.out.println("Hello, Java!");|',
    '最初のレッスンの1行を、余計な記号なしで打ち切れる', await read());

  // ── これまでの補助が壊れていないか ──────────────────────────────
  await clear();
  await key('(');
  await backspace();
  check(await read() === '|', 'Backspace で `()` をまとめて消せる', await read());
  await clear();
  await write('x');
  // 書きかけの語では候補が出ていて、その Tab は候補の確定になる（[[code-completion-design]]）。
  // ここで見たいのは候補が無いときの Tab なので、Escapeで窓を畳んでから打つ
  await special('Escape', 'Escape', 27);
  await tab();
  check(await read() === 'x    |', '候補が出ていないときの Tab は4スペース', await read());

  const errors = await ev(`window.__jqErrors || []`);
  check(errors.length === 0, '画面のJavaScriptが例外を出していない', errors);

  // ── 補完の案内（第2章のはじめから、閉じるまで）──────────────────────────
  // 「閉じた」印は localStorage に残る。ここまでの検査で `sout` を使っており
  // （使えた人には出し続けない）印が既に付いているので、まず消してから見る。
  // ここから先はページを読み直すので、上の例外チェックはこの前に置いてある。
  const openLesson = async id => {
    await send('Page.navigate', { url: 'about:blank' });   // 同じURLでは読み直されない
    await sleep(300);
    await send('Page.navigate', { url: `http://localhost:${PORT}/#${id}` });
    await sleep(1600);
  };
  const tipState = () => ev(`(() => {
    const card = document.querySelector('.card-tip');
    return { shown: !!card, text: card ? card.textContent : '' };
  })()`);
  const forgetTip = () => ev(`(localStorage.removeItem('jq-completion-tip-done'), true)`);

  await forgetTip();
  await openLesson(LESSON);
  const atFirst = await tipState();
  check(!atFirst.shown,
    `第1章（${LESSON}）では案内を出さない（書き写す手そのものが練習のため）`, atFirst);

  await openLesson(TIP_LESSON);
  const atTip = await tipState();
  check(atTip.shown && atTip.text.indexOf('sout') >= 0 && atTip.text.indexOf('Tab') >= 0,
    `${TIP_LESSON} で補完と \`sout\` の案内が出る`, atTip.text.slice(0, 80));

  const tipClosed = await ev(`(async () => {
    document.querySelector('.card-tip [data-role="tip-close"]').click();
    await new Promise(r => setTimeout(r, 150));
    return { card: !!document.querySelector('.card-tip'),
             saved: localStorage.getItem('jq-completion-tip-done') };
  })()`);
  check(!tipClosed.card && tipClosed.saved === '1',
    '「閉じる」で消え、閉じたことが残る', tipClosed);

  await openLesson(TIP_LESSON);
  check(!(await tipState()).shown, '閉じたあとは開き直しても出ない');

  // `sout` を自分で使えた人にも出し続けない（complete.js が app.js へ知らせる）
  await forgetTip();
  await openLesson(TIP_LESSON);
  check((await tipState()).shown, '印を消せば案内はまた出る（次の確認の前提）');
  await clear();
  await write('sout');
  await sleep(500);
  await tab();
  const afterSnippet = await ev(`(() => ({
    card: !!document.querySelector('.card-tip'),
    saved: localStorage.getItem('jq-completion-tip-done'),
    code: document.querySelector('#task-${TASK} .editor-input').value
  }))()`);
  check(afterSnippet.code.indexOf('System.out.println') >= 0
      && !afterSnippet.card && afterSnippet.saved === '1',
    '`sout` を使うと案内は引っ込む（もう知っている人に出し続けない）', afterSnippet);

  const tipErrors = await ev(`window.__jqErrors || []`);
  check(tipErrors.length === 0, '案内を出した画面でも例外が出ていない', tipErrors);

  close();
  if (failures > 0) {
    console.log(`\n${RED}エディタの検査に失敗しました（${failures}件）${RESET}`);
    process.exit(1);
  }
  console.log(`\n${GREEN}EDITOR UI OK: 自動で閉じるかっこと引用符・打ち抜けの条件・`
    + `テキストブロック・位置の追従・候補の移動（↑↓ と Ctrl+P/N）・定型の短縮（sout）・`
    + `Tabの字下げ・補完の案内（${TIP_LESSON} から、閉じるまで）を確認しました${RESET}`);
})().catch(e => {
  console.error(`${RED}検査を実行できませんでした: ${e.message}${RESET}`);
  process.exit(1);
});
