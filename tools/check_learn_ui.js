/*
 * 学習画面をブラウザで実際に操作して確かめる。check-learn-ui.sh から呼ばれる。
 *
 * 引数: <アプリのポート> <ChromeのCDPポート>
 *
 * 見るのは「1問を解き切るまでの経路」全体である。
 *   誤答 → コンパイルエラー → ヒント → 模範解答 → 正解（★・コイン・通知）
 *   → 1問1枚のパネル（クリア済みの見分け）→ 獲得の履歴（消えた通知の読み返し）→ 自動保存
 *   → カフェへ寄り道して同じ位置で再開 → 復習（途中で抜けて「続きから」戻る）→ クイズのしおり
 * サーバー側の採点は `verify-solutions.sh` が見るので、ここでは
 * **画面がその結果をどう受け取って描くか**（`renderJudgement` / `applyDelta` / 通知 / 復習の出題）
 * だけを確かめる。
 *
 * 対象は `1-1#1` に決め打ちしてある。レッスンIDは進捗ファイルの互換のため変えない方針
 * （`docs/guide.md`「章を分ける手順」）なので、番号が動いて別の問題を検査してしまう心配はない。
 * ただし問題の形（単一ファイル・ヒントあり・模範解答あり）が変わるとこの検査は成り立たないので、
 * 最初に `/api/state` から前提を確かめて、崩れていたら理由の分かる失敗にする。
 *
 * 落とし穴を3つ踏んであるので、真似するときは注意する。
 *   ・同じURLへ Page.navigate してもページは読み直されない（ハッシュ移動と見なされる）。
 *     about:blank を経由してから開く。自動保存の検査は読み直しが要るので特に効く。
 *   ・採点は1件で最大5秒×ケース数かかる。固定待ちにすると遅い環境で必ず落ちるので、
 *     結果カード（`.card-result`）が spinner でなくなるまで待つ。
 *   ・★や報酬の描画は提出の応答（`delta` / `newStar` / `cafeAward`）に頼っている。
 *     応答の形が崩れても、画面を再読み込みすれば `/api/state` から直ってしまうため
 *     見た目だけでは気づけない。画面が受け取った応答そのものも確かめる。
 */
const PORT = process.argv[2];
const CDP = process.argv[3];
const GREEN = '\x1b[32m', RED = '\x1b[31m', RESET = '\x1b[0m';

/** 検査対象の問題。`1-1` は最初のレッスン（Hello, Java!）。 */
const LESSON = '1-1';
const TASK = '1';
/** クイズのしおりを試すレッスン。`1-1` にはクイズが無いので、クイズを持つ最初のレッスンを使う。 */
const QUIZ_LESSON = '1-3';
/**
 * 「試しに実行」の入力欄を見るレッスン。`1-1` は入力を読まないので欄が出ない。
 * 見えているケースに入力がある問題を使う（無くなったら前提の検査で止まる）。
 */
const STDIN_LESSON = '3-2';
const STDIN_TASK = '1';
/**
 * 報酬の通知を「表示しない」に切り替えたあとでクリアする問題。上の節で使う `TASK` は
 * すでにクリア済みで、再提出では報酬が出ない（cash が 0 になる）ので、同じレッスンの
 * 2問目を取っておく。出力は教材の表示ケースから読むので、文面が変わっても追随する。
 */
const PREF_TASK = '2';
/** 自動保存が効いているかを見るための目印。模範解答の末尾へ足す。 */
const SAVE_MARK = '// 自動保存の目印';

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
 * 画面の中で使う道具。読み直しのたびに入れ直す（window は読み直しで消える）。
 *
 * 待ちはすべて「そうなるまで」の形にしてある。採点は環境の速さで数秒ぶれるので、
 * 固定待ちにすると遅い環境だけ落ちる検査になる。
 */
const HELPERS = `window.__t = {
  sleep: ms => new Promise(r => setTimeout(r, ms)),
  editor: () => document.querySelector('#task-${TASK} .editor-input'),
  type: value => {
    const ta = window.__t.editor();
    ta.value = value;
    ta.dispatchEvent(new Event('input', { bubbles: true }));
  },
  until: async (fn, tries = 160) => {
    for (let i = 0; i < tries; i++) {
      const got = fn();
      if (got) { return got; }
      await window.__t.sleep(250);
    }
    return null;
  },
  submit: async () => {
    const host = document.getElementById('result-${TASK}');
    host.innerHTML = '';
    document.getElementById('submitBtn-${TASK}').click();
    const card = await window.__t.until(() => {
      const c = host.querySelector('.card-result');
      return c && !c.querySelector('.spinner') ? c : null;
    });
    if (!card) { return { verdict: 'timeout', text: '' }; }
    return {
      verdict: card.classList.contains('ok') ? 'ok' : (card.classList.contains('ng') ? 'ng' : 'unknown'),
      text: card.textContent.replace(/\\s+/g, ' ').trim().slice(0, 200),
      failedCases: card.querySelectorAll('.case-result.fail').length,
      passedCases: card.querySelectorAll('.case-result.pass').length,
      details: card.querySelectorAll('.case-detail').length,
      diagnostics: card.querySelectorAll('.diag-list .diag-error').length
    };
  },
  stars: () => (document.querySelector('#statStars b') || {}).textContent,
  coins: () => (document.querySelector('#statCafe b') || {}).textContent,
  status: () => ((document.getElementById('taskStatus-${TASK}') || {}).textContent || '').trim()
};`;

(async () => {
  const { send, close } = connect(await pageTarget());
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  // 編集したJSが読み込まれないまま通ってしまわないように
  await send('Network.setCacheDisabled', { cacheDisabled: true });
  // 画面のJSより先に、例外と提出の応答を集める入れ物を用意する
  await send('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.__jqErrors = [];'
      + 'window.addEventListener("error", e => window.__jqErrors.push(String(e.message)));'
      + 'window.addEventListener("unhandledrejection", e => window.__jqErrors.push(String(e.reason)));'
      + 'window.__jqSubmits = [];'
      + 'const __origFetch = window.fetch;'
      + 'window.fetch = function (input, init) {'
      + '  const url = typeof input === "string" ? input : ((input && input.url) || "");'
      + '  return __origFetch.apply(this, arguments).then(res => {'
      + '    if (url.indexOf("/api/submit") >= 0) {'
      + '      res.clone().json().then(j => window.__jqSubmits.push(j), () => {});'
      + '    }'
      + '    return res;'
      + '  });'
      + '};'
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

  /** 画面を開き直す。about:blank を挟まないと同じURLでは読み直されない。 */
  const open = async hash => {
    await send('Page.navigate', { url: 'about:blank' });
    await sleep(300);
    await send('Page.navigate', { url: `http://localhost:${PORT}/${hash}` });
    await sleep(2000);
    await ev(HELPERS);
  };

  // ── 前提（教材が変わってこの検査が成り立たなくなったら、ここで理由を出す）────
  await open(`#${LESSON}`);
  const target = await ev(`(async () => {
    const state = await (await fetch('/api/state')).json();
    let found = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${LESSON}') { found = l; }
    }));
    if (!found) { return { missing: 'レッスン' }; }
    const task = (found.tasks || []).find(t => t.id === '${TASK}');
    if (!task) { return { missing: '問題' }; }
    return { type: task.type || 'single-file', hintCount: task.hintCount,
             hasSolution: !!task.hasSolution, cleared: !!task.cleared };
  })()`);
  check(!target.missing && target.type === 'single-file' && target.hintCount > 0
    && target.hasSolution && !target.cleared,
    `${LESSON}#${TASK} はヒントと模範解答のある単一ファイル問題で、まだ未クリア`, target);
  if (target.missing || target.type !== 'single-file' || !target.hasSolution) {
    console.log(`${RED}この検査は ${LESSON}#${TASK} を単一ファイル問題として触ります。`
      + `教材を変えたなら、対象を差し替えてください（tools/check_learn_ui.js の LESSON / TASK）${RESET}`);
    close();
    process.exit(1);
  }

  // ── 描画 ────────────────────────────────────────────────
  const head = await ev(`(() => ({
    hash: location.hash,
    title: ((document.querySelector('h1') || {}).textContent || '').replace(/\\s+/g, ' ').trim(),
    editors: document.querySelectorAll('#task-${TASK} .editor-input').length,
    submitLabel: ((document.getElementById('submitBtn-${TASK}') || {}).textContent || '').trim(),
    tryLabel: ((document.getElementById('tryBtn-${TASK}') || {}).textContent || '').trim(),
    tryInputs: document.querySelectorAll('#task-${TASK} .try-stdin').length,
    starter: (window.__t.editor() || {}).value || '',
    hintLabel: ((document.querySelector('#task-${TASK} .hint-btn') || {}).textContent || '').trim()
  }))()`);
  check(head.hash === `#${LESSON}` && head.editors === 1 && head.title.length > 0,
    'レッスン画面（見出しとエディタ）が描ける', { hash: head.hash, title: head.title, editors: head.editors });
  check(head.submitLabel === '✓ 提出して採点', '提出ボタンが「提出して採点」になっている', head.submitLabel);
  check(head.tryLabel === '▶ 試しに実行', '採点しないで走らせるボタンが並んでいる', head.tryLabel);
  check(head.tryInputs === 0, '入力を読まない問題では入力欄を出さない', head.tryInputs);
  check(head.starter.length > 0, 'エディタにひな形が入っている', head.starter.slice(0, 60));
  check(/残り\d+/.test(head.hintLabel), 'ヒントの残り件数が出ている', head.hintLabel);

  // ── 試しに実行（採点も記録もしないこと）────────────────────────
  // ここが壊れると「採点なしのはずが★や苦手度を動かす」ので、提出より先に見る。
  const tried = await ev(`(async () => {
    window.__t.type('public class Main {\\n  public static void main(String[] args) {\\n'
      + '    System.out.println("ためし");\\n  }\\n}');
    const host = document.getElementById('result-${TASK}');
    host.innerHTML = '';
    document.getElementById('tryBtn-${TASK}').click();
    const card = await window.__t.until(() => {
      const c = host.querySelector('.card-result');
      return c && !c.querySelector('.spinner') ? c : null;
    });
    return {
      isTry: !!card && card.classList.contains('card-try'),
      graded: !!card && card.querySelectorAll('.case-result').length,
      head: card ? (card.querySelector('.try-result-head') || {}).textContent || '' : '',
      out: card ? ((card.querySelector('.out-pre') || {}).textContent || '').trim() : '',
      submits: window.__jqSubmits.length,
      stars: window.__t.stars(),
      status: window.__t.status()
    };
  })()`);
  check(tried.isTry && tried.out === 'ためし',
    '試しに実行すると、書いたコードの出力がそのまま出る', tried);
  check(tried.graded === 0 && /採点はしていません/.test(tried.head),
    '採点はしない（ケースの合否を出さず、採点でないと書いてある）', tried);
  check(tried.submits === 0 && tried.stars === '0' && tried.status === '',
    '/api/submit を呼ばず、★もクリア済みの印も動かない', tried);

  // ── 誤答（採点結果の描画と、★が増えないこと）──────────────────
  const wrong = await ev(`(async () => {
    window.__t.type('public class Main {\\n  public static void main(String[] args) {\\n'
      + '    System.out.println("これは期待と違う出力");\\n  }\\n}');
    const r = await window.__t.submit();
    return Object.assign(r, { stars: window.__t.stars(), status: window.__t.status() });
  })()`);
  check(wrong.verdict === 'ng', '誤答が不合格として描かれる', wrong);
  check(wrong.failedCases >= 1 && wrong.details >= 1,
    '通らなかったケースと、その中身（差分）が出る', wrong);
  check(wrong.stars === '0' && wrong.status === '',
    '誤答では★が増えず、クリア済みの印も付かない', { stars: wrong.stars, status: wrong.status });
  // 試しに実行を先に1回押している。それが数えられていれば、ここは2回目になってしまう
  const firstAttempts = await ev(`(window.__jqSubmits[0] || {}).attempts`);
  check(firstAttempts === 1,
    '試しに実行は提出回数に入らない（最初の提出が1回目になる）', firstAttempts);

  // ── コンパイルエラー（診断の翻訳が画面に出るか）──────────────────
  const broken = await ev(`(async () => {
    window.__t.type('public class Main { oops }');
    return window.__t.submit();
  })()`);
  check(broken.verdict === 'ng' && broken.diagnostics >= 1,
    'コンパイルエラーが行つきの診断として出る', broken);

  // ── ヒントと模範解答（開示の段取り）────────────────────────
  const hints = await ev(`(async () => {
    const btn = () => document.querySelector('#task-${TASK} .hint-btn');
    for (let i = 0; i < ${target.hintCount}; i++) {
      if (!btn() || btn().disabled) { break; }
      btn().click();
      await window.__t.until(() =>
        document.querySelectorAll('#hints-${TASK} [data-hint]').length > i);
    }
    return {
      shown: document.querySelectorAll('#hints-${TASK} [data-hint]').length,
      texts: [...document.querySelectorAll('#hints-${TASK} .hint-text')].every(n => n.textContent.trim().length > 0),
      exhausted: !!(btn() && btn().disabled),
      solutionButton: !!(await window.__t.until(() =>
        document.querySelector('#solution-${TASK} [data-role="solution"]')))
    };
  })()`);
  check(hints.shown === target.hintCount && hints.texts,
    `ヒントを${target.hintCount}件とも本文つきで開ける`, hints);
  check(hints.exhausted, '全部開くとヒントのボタンが止まる', hints.exhausted);
  check(hints.solutionButton, '全部開くと模範解答のボタンが出る', hints.solutionButton);

  const solution = await ev(`(async () => {
    document.querySelector('#solution-${TASK} [data-role="solution"]').click();
    const copy = await window.__t.until(() =>
      document.querySelector('#solution-${TASK} [data-role="copy"]'));
    if (!copy) { return { shown: false }; }
    const body = document.querySelector('#solution-${TASK} .card-solution').textContent;
    copy.click();
    await window.__t.sleep(300);
    return { shown: body.length > 0, copiedIntoEditor: (window.__t.editor().value || '').length > 0,
             sameAsStarter: window.__t.editor().value === ${JSON.stringify(head.starter)},
             // 復習の節でひな形から解き直すときに使い回す（模範解答を2回開かない）
             code: window.__t.editor().value };
  })()`);
  check(solution.shown && solution.copiedIntoEditor && !solution.sameAsStarter,
    '模範解答を開いてエディタへ入れられる', solution);

  // ── 正解（★・コイン・通知・応答の形）────────────────────────
  const coinsBefore = await ev(`window.__t.coins()`);
  const pass = await ev(`(async () => {
    const r = await window.__t.submit();
    // 通知は数秒で消えるので、消える前に拾う
    const toast = document.querySelector('#toast');
    return Object.assign(r, {
      stars: window.__t.stars(),
      coins: window.__t.coins(),
      status: window.__t.status(),
      toast: (toast ? toast.textContent : '').replace(/\\s+/g, ' ').trim().slice(0, 120),
      toastStats: !!document.querySelector('#toast .toast-stats'),
      nextButton: !!document.getElementById('lessonNextBtn'),
      submitResponse: window.__jqSubmits[window.__jqSubmits.length - 1] || null
    });
  })()`);
  check(pass.verdict === 'ok' && pass.passedCases >= 1, '模範解答が合格として描かれる', {
    verdict: pass.verdict, passed: pass.passedCases, text: pass.text
  });
  check(pass.stars === '1' && pass.status.indexOf('★') >= 0,
    '★がヘッダと問題の見出しに増える', { stars: pass.stars, status: pass.status });
  check(pass.coins !== coinsBefore, 'カフェのコインが増える', { before: coinsBefore, after: pass.coins });
  check(pass.toastStats && pass.toast.length > 0, '報酬の通知が出る', pass.toast);
  // 次への導線はレッスンを開いた時点から出ている（クリアで現れるものではない）。
  // 消えていないことだけを見る
  check(pass.nextButton, 'レッスンの末尾に次へ進む導線がある', pass.nextButton);

  const res = pass.submitResponse || {};
  check(res.newStar === true, '提出の応答に newStar が入っている', res.newStar);
  check(res.next && res.next.lessonId, '提出の応答に次の問題が入っている', res.next);
  check(res.cafeAward && res.cafeAward.cash > 0, '提出の応答に cafeAward が入っている', res.cafeAward);
  check(res.delta && res.delta.progress && typeof res.delta.progress.starCount === 'number',
    '提出の応答に delta.progress が入っている（画面はこれで描き直す）',
    res.delta && Object.keys(res.delta));

  // ── 1問1枚のパネルと、クリア済みの見分け ──────────────────────────
  // クリアの瞬間に緑へ変わることは、**描き直さずに**起きなければならない
  // （`refreshTaskStatus`）。レッスンを描き直すと画面の先頭へ戻り、いま出た採点結果が
  // 視界から消えるため、ここは差分更新にしてある。読み直したあとの姿では確かめられない。
  const panel = await ev(`(() => {
    const block = document.getElementById('task-${TASK}');
    const head = block.querySelector('.task-block-head');
    const task = block.querySelector('.card-task');
    const rows = [...block.querySelectorAll('.task-block-body > *')];
    const style = getComputedStyle(block);
    return {
      cleared: block.classList.contains('is-cleared'),
      chip: !!block.querySelector('.task-clear-chip'),
      mark: (block.querySelector('.task-mark') || {}).textContent,
      headPosition: getComputedStyle(head).position,
      headBottomBorder: parseFloat(getComputedStyle(head).borderBottomWidth),
      panelBorder: parseFloat(style.borderTopWidth),
      panelRadius: parseFloat(style.borderTopLeftRadius),
      taskSideBorder: parseFloat(getComputedStyle(task).borderRightWidth),
      taskRadius: parseFloat(getComputedStyle(task).borderTopLeftRadius),
      dividers: rows.filter(n => n.offsetHeight > 0 && parseFloat(getComputedStyle(n).borderTopWidth) > 0).length,
      // 段の順番。採点結果はヒントより先に来る（下の check の理由を見ること）
      order: rows.map(n => n.classList.contains('card-task') ? '課題'
        : n.classList.contains('card-code') ? 'コード'
        : n.classList.contains('result') ? '結果'
        : n.classList.contains('hints') ? 'ヒント'
        : n.classList.contains('solution-area') ? '模範解答' : '?').join(' '),
      filled: rows.filter(n => n.textContent.trim().length > 0).length,
      // 手つかずの2問目で「出ていない段」を見る（1問目はもう結果もヒントも出ている）
      untouched: (() => {
        const other = document.getElementById('task-2');
        if (!other) { return null; }
        return {
          cleared: other.classList.contains('is-cleared'),
          emptyRowsTakeSpace: [...other.querySelectorAll('.task-block-body > *')]
            .filter(n => !n.textContent.trim() && n.offsetHeight > 0).length
        };
      })()
    };
  })()`);
  check(panel.cleared && panel.chip && panel.mark === '✓',
    'クリアした瞬間に、描き直さずに問題が「クリア済み」の姿になる', panel);
  check(panel.panelBorder >= 1 && panel.panelRadius >= 10
    && panel.taskSideBorder === 0 && panel.taskRadius === 0,
    '1問が1枚のパネルで、中の課題文は枠を持たない（段になっている）', panel);
  // 帯は貼り付けない（試したが、スクロール中ずっと画面の上に残るのが見た目に良くない）。
  // 一度入れて外した指定なので、戻っていないことをここで見る。
  check(panel.headPosition === 'static' && panel.headBottomBorder >= 1,
    '問題の帯はパネルの上辺にあり、スクロールしても付いてこない', panel);
  check(panel.dividers >= 1 && panel.untouched
    && panel.untouched.emptyRowsTakeSpace === 0 && panel.untouched.cleared === false,
    '出ている段は細い線で区切られ、まだ出ていない段（未クリアの問題）は場所を取らない', panel);
  // ヒントを開いていると、コード欄（「試しに実行」の入力欄を含む）と出力のあいだに
  // ヒントが挟まって、書いたものと結果を見比べられなくなる（2026-08-21・利用者の指摘）。
  // ここは5段すべてに中身がある状態なので、順番を入れ替えれば必ず落ちる。
  check(panel.order === '課題 コード 結果 ヒント 模範解答' && panel.filled === 5,
    '採点結果はヒントより先の段に出る（コード欄と出力が離れない）', panel);

  // ── 獲得の履歴（消えた通知をあとから読み返せるか）──────────────────
  // 通知は数秒で消えるので、消えたあとに残っているかはここでしか見られない。
  // 積むのは通知を出すのと同じ場所（`showCafeRewardNotification`）なので、
  // 通知が出ていても履歴が空なら、控えを取る側が外れている。
  const coinLog = await ev(`(async () => {
    document.getElementById('statCafe').click();
    const pop = await window.__t.until(() => {
      const p = document.getElementById('coinLog');
      return p && !p.hidden ? p : null;
    }, 20);
    if (!pop) { return { opened: false }; }
    const first = pop.querySelector('.coin-log-item');
    const out = {
      opened: true,
      items: pop.querySelectorAll('.coin-log-item').length,
      first: (first ? first.textContent : '').replace(/\s+/g, ' ').trim().slice(0, 120),
      today: ((pop.querySelector('.coin-log-sum-cell.today') || {}).textContent || '').trim(),
      expanded: document.getElementById('statCafe').getAttribute('aria-expanded')
    };
    // 開いたままだと後の操作を覆うので、外側を押して閉じるところまで見る
    document.body.click();
    out.closed = !!document.getElementById('coinLog').hidden;
    return out;
  })()`);
  check(coinLog.opened && coinLog.items === 1 && coinLog.expanded === 'true',
    'ヘッダのコインを押すと獲得の履歴が開く', coinLog);
  check(/\+[\d,]+コイン/.test(coinLog.first) && coinLog.first.indexOf('★') >= 0,
    '履歴の1件目に金額と★が残っている', coinLog.first);
  check(/\+[1-9][\d,]*コイン/.test(coinLog.today), '今日の獲得が合計されている', coinLog.today);
  check(coinLog.closed, '外側を押すと履歴が閉じる', coinLog.closed);

  // ── 1日の区切りは午前4時（2026-08-22・利用者の要望）─────────────────────
  //
  // 0時で切ると、0:30 に解いた1問が翌日ぶんになる（連続日数が切れる・履歴の「今日」が
  // 寝る前と寝たあとで分かれる）。区切りは `LearningDay.START_HOUR` が持ち、画面へは
  // `/api/state` の `dayStartHour` で渡す ― 画面に数字を書くと片方だけ動いて食い違う。
  //
  // **いま何時でも同じ判定になるように書く。** 区切りの前後1分の時刻を今の時刻から
  // 計算して履歴へ差し込む（固定の時刻を置くと、走らせた時刻で期待値が変わる）。
  // **差し込んだら読み直す。** 履歴は一度読むと画面の中に残るので（`loadCoinLog` の控え）、
  // localStorage を書くだけでは描き替わらない。
  const injected = await ev(`(async () => {
    const state = await (await fetch('/api/state')).json();
    const hour = Number(state.progress.dayStartHour);
    // いまの学習日の始まり（区切りを引いた日付の、その日の hour 時）
    const shifted = new Date(Date.now() - hour * 3600000);
    const start = new Date(shifted.getFullYear(), shifted.getMonth(), shifted.getDate(), hour, 0, 0);
    const log = JSON.parse(localStorage.getItem('jq-coin-log') || '[]');
    const keptJson = JSON.stringify(log);
    log.unshift({ at: start.getTime() + 60000, cash: 7, reason: '境目のあと' });
    log.unshift({ at: start.getTime() - 60000, cash: 999999, reason: '境目のまえ' });
    localStorage.setItem('jq-coin-log', JSON.stringify(log));
    // 元の履歴は node 側で預かる（このあと読み直すので、画面の変数には残せない）
    return { hour: hour, keptJson: keptJson, kept: log.length - 2 };
  })()`);
  check(injected.hour === 4, '1日の区切り（午前4時）をサーバから受け取る', injected.hour);

  await open(`#${LESSON}`);
  const boundary = await ev(`(async () => {
    document.getElementById('statCafe').click();
    const pop = await window.__t.until(() => {
      const p = document.getElementById('coinLog');
      return p && !p.hidden ? p : null;
    }, 20);
    if (!pop) { return { opened: false }; }
    const whens = [...pop.querySelectorAll('.coin-log-when')].map(n => n.textContent.trim());
    const today = ((pop.querySelector('.coin-log-sum-cell.today') || {}).textContent || '').trim();
    const out = {
      opened: true,
      items: pop.querySelectorAll('.coin-log-item').length,
      today: today,
      todayCash: Number(today.replace(/[^0-9]/g, '')),
      before: whens[0],
      after: whens[1]
    };
    document.body.click();
    // 差し込んだぶんは戻す（この先の履歴の件数を見る節が数え違えないように）
    localStorage.setItem('jq-coin-log', ${JSON.stringify(injected.keptJson)});
    return out;
  })()`);
  check(boundary.opened && boundary.items === injected.kept + 2,
    '差し込んだ2件が履歴に並ぶ（この検査の前提）', boundary);
  check(boundary.todayCash >= 7 && boundary.todayCash < 999999,
    '区切りより前の獲得は「今日ぶん」に入らない', boundary);
  check(/^昨日/.test(boundary.before || ''), '区切りより前は「昨日」と出る', boundary.before);
  check(/^今日/.test(boundary.after || ''), '区切りより後は「今日」と出る', boundary.after);

  // ── 自動保存（読み直しても書いたコードが残るか）──────────────────
  await ev(`(async () => {
    window.__t.type(window.__t.editor().value + '\\n${SAVE_MARK}\\n');
    // 打鍵の0.8秒後にまとめて保存する（web/app.js の scheduleSave）
    await window.__t.sleep(1800);
  })()`);
  await open(`#${LESSON}`);
  const saved = await ev(`(() => ({
    code: (window.__t.editor() || {}).value || '',
    status: window.__t.status(),
    stars: window.__t.stars()
  }))()`);
  check(saved.code.indexOf(SAVE_MARK) >= 0,
    '書いたコードが読み直しても残っている', saved.code.slice(-40));
  check(saved.status.indexOf('★') >= 0 && saved.stars === '1',
    '★も読み直しても残っている', saved);

  // 履歴は localStorage に置いてあるので、読み直しても残る（サーバは1件ずつの内訳を持たない）
  const coinLogAgain = await ev(`(async () => {
    document.getElementById('statCafe').click();
    const pop = await window.__t.until(() => {
      const p = document.getElementById('coinLog');
      return p && !p.hidden ? p : null;
    }, 20);
    if (!pop) { return { opened: false }; }
    const out = { opened: true, items: pop.querySelectorAll('.coin-log-item').length };
    document.body.click();
    return out;
  })()`);
  check(coinLogAgain.items === 1, '獲得の履歴も読み直しても残っている', coinLogAgain);

  // ── 寄り道して戻る（カフェで買って帰り、解いていた位置で再開できるか）────────
  // 位置を控えているのは画面だけ（`web/app.js` の `rememberLessonScroll`）なので、
  // ここでしか確かめられない。壊れても見た目は普通のレッスン画面（先頭が出る）で、
  // 「さっきの続きが出ない」と気づくのは解いている人だけになる。
  // 購入まで通すのは、買ったあとの描き直しが `render` を経由しないためである
  // （`renderCafe(true)`）。ここが `render` に変わると帰り先の記憶が消えるので、
  // その取り違えを捕まえる。
  const detour = await ev(`(async () => {
    const main = document.getElementById('content');
    const state = await (await fetch('/api/state')).json();
    let other = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (!other && l.id !== '${LESSON}') { other = l.id; }
    }));
    document.getElementById('task-${TASK}').scrollIntoView({ block: 'start' });
    await window.__t.sleep(300);
    const leaving = main.scrollTop;
    const coinsBefore = window.__t.coins();
    document.getElementById('cafeBtn').click();
    if (!await window.__t.until(() => document.querySelector('.cafe-page'), 40)) {
      return { error: 'カフェ画面が描かれない' };
    }
    const title = document.getElementById('learningBtn').title;
    const buyable = [...document.querySelectorAll(
      '.cafe-item-buy, .equipment-upgrade-btn, .cafe-automation-buy')].filter(b => !b.disabled);
    let bought = null;
    if (buyable.length) {
      bought = buyable[0].textContent.replace(/\\s+/g, ' ').trim();
      buyable[0].click();
      await window.__t.until(() => window.__t.coins() !== coinsBefore, 40);
    }
    const coinsAfter = window.__t.coins();
    document.getElementById('learningBtn').click();
    if (!await window.__t.until(() => document.querySelector('.lesson-view'), 40)) {
      return { error: '学習画面へ帰れない' };
    }
    await window.__t.sleep(300);
    const back = main.scrollTop;
    // 帰り先はここで読む。このあと別レッスンへ移るので、最後にまとめて読むと取り違える
    const backHash = location.hash;
    const code = (window.__t.editor() || {}).value || '';
    // 別のレッスンへ移ると先頭から始まる（覚えているのは直前に離れた1件だけ）
    if (!other) { return { error: '比較用の別レッスンが見つからない' }; }
    main.scrollTop = leaving;
    await window.__t.sleep(150);
    location.hash = '#' + other;
    if (!await window.__t.until(
        () => location.hash === '#' + other && document.querySelector('.lesson-view'), 40)) {
      return { error: other + ' が開けない' };
    }
    await window.__t.sleep(300);
    return {
      leaving, back, backHash, title, bought, coinsBefore, coinsAfter, code,
      other, otherTop: main.scrollTop
    };
  })()`);
  check(!detour.error, 'カフェへ寄り道して学習画面へ帰れる', detour.error);
  if (!detour.error) {
    check(detour.leaving > 0, '問題までスクロールした状態から寄り道した', detour.leaving);
    check(detour.bought !== null && detour.coinsAfter !== detour.coinsBefore,
      'カフェで1つ購入できた（残高が動く）',
      { bought: detour.bought, before: detour.coinsBefore, after: detour.coinsAfter });
    check(detour.backHash === `#${LESSON}`, '「📚 学習」が解いていたレッスンへ帰す', detour.backHash);
    check(detour.title === '解いていた問題に戻る',
      'カフェでの「📚 学習」の説明が行き先に合っている', detour.title);
    check(detour.back === detour.leaving, '読んでいた位置で再開する',
      { leaving: detour.leaving, back: detour.back });
    check(detour.code.indexOf(SAVE_MARK) >= 0, '書いたコードも残っている', detour.code.slice(-40));
    check(detour.otherTop === 0, '別のレッスンは先頭から始まる（覚えているのは直前の1件だけ）',
      { lesson: detour.other, top: detour.otherTop });
  }

  // ── 復習（クリアした問題が出題に回るか）──────────────────────
  await open('#review');
  const review = await ev(`(() => ({
    rows: document.querySelectorAll('.review-row').length,
    ids: [...document.querySelectorAll('.review-row-id')].map(n => n.textContent.trim()),
    startButton: !!document.getElementById('reviewStartBtn')
  }))()`);
  check(review.rows === 1 && review.startButton,
    'クリアした問題が復習の一覧に出る', review);

  const session = await ev(`(async () => {
    document.getElementById('reviewStartBtn').click();
    await window.__t.until(() => location.hash.indexOf('#review/') === 0);
    await window.__t.sleep(800);
    const editor = document.querySelector('#task-${TASK} .editor-input');
    return {
      hash: location.hash,
      code: (editor || {}).value || '',
      weightBadge: ((document.querySelector('#reviewWeight-${TASK}') || {}).textContent || '').trim(),
      bar: !!document.querySelector('#reviewSkipBtn') || !!document.querySelector('#reviewExitBtn')
    };
  })()`);
  check(session.hash === `#review/${LESSON}/${TASK}`, '復習セッションが始まる', session.hash);
  check(session.code.length > 0 && session.code.indexOf(SAVE_MARK) < 0,
    '復習はひな形から始まる（前に書いた解答が入っていない）', session.code.slice(0, 60));
  check(session.weightBadge.length > 0, '苦手度のバッジが出る', session.weightBadge);
  check(session.bar, '復習を抜ける／飛ばす操作が出ている', session.bar);

  // ── 復習の途中でカフェへ寄り道して帰る（2026-08-22・利用者の指摘）──────────
  //
  // 普通のレッスンは上の節で見ているが、復習は目印（`paintedReview`）を置いていなかったので
  // カフェの「📚 学習」が学習ホームへ出てしまい、解いていたセットを自分で辿り直すことに
  // なっていた。**帰り先はセットごと**でなければならない ―― カフェへ移った時点で
  // `render` が `reviewSession` を捨てるため、控え（`jq-review-run`）から戻す
  // （`resumeReviewFromCafe`）。帯が出ていることで「セットとして戻った」ことを見る。
  const reviewDetour = await ev(`(async () => {
    const barText = () => ((document.querySelector('.review-bar-progress') || {}).textContent
      || '').replace(/\s+/g, ' ').trim();
    const before = barText();
    document.getElementById('cafeBtn').click();
    if (!await window.__t.until(() => document.querySelector('.cafe-page'), 40)) {
      return { error: 'カフェ画面が描かれない' };
    }
    const title = document.getElementById('learningBtn').title;
    document.getElementById('learningBtn').click();
    if (!await window.__t.until(() => document.querySelector('.review-view'), 40)) {
      return { error: '復習画面へ帰れない' };
    }
    await window.__t.sleep(400);
    return {
      title, before, after: barText(), hash: location.hash,
      editorEmpty: !((window.__t.editor() || {}).value || '').includes('${SAVE_MARK}')
    };
  })()`);
  check(!reviewDetour.error, 'カフェへ寄り道して復習へ帰れる', reviewDetour.error);
  if (!reviewDetour.error) {
    check(reviewDetour.hash === `#review/${LESSON}/${TASK}`,
      '「📚 学習」が解いていた復習の問題へ帰す', reviewDetour.hash);
    check(reviewDetour.title === '解いていた問題に戻る',
      'カフェでの「📚 学習」の説明が行き先に合っている', reviewDetour.title);
    check(reviewDetour.after === reviewDetour.before && reviewDetour.after.length > 0,
      'セットとして戻る（帯の「N / M問」が寄り道の前と同じ）',
      { before: reviewDetour.before, after: reviewDetour.after });
    check(reviewDetour.editorEmpty,
      '帰ってきてもひな形のまま（復習の解答は保存しない）', reviewDetour.editorEmpty);
  }

  // ── 前に開いたヒントは畳んで出す（2026-08-21・利用者の指摘）──────────────
  //
  // レッスンで開いたヒントは記録に残る（`revealedHints`）。それが開いたまま復習に
  // 出てくると、答えが見えている状態で解くことになり「いま解けるか」を測れない。
  // ここまでの節でこの問題のヒントは全部開いているので、そのまま材料になる。
  //
  // **本文が見えていないことまで見る。** 属性（`open`）だけでは足りない ― CSSが崩れて
  // 中身が出ていても属性は同じである。ただし**高さでは測れない**: 閉じた <details> の
  // 中身は `content-visibility: hidden` なので、描かれていなくても `offsetHeight` は
  // 値を返す（Chrome 151で実測24px）。`checkVisibility` で見る。
  const reviewHints = await ev(`(async () => {
    const cards = [...document.querySelectorAll('#hints-${TASK} [data-hint]')];
    const head = (n) => n.querySelector('summary');
    const seen = (n) => n.querySelector('.hint-text')
      .checkVisibility({ contentVisibilityAuto: true, visibilityProperty: true });
    const folded = {
      shown: cards.length,
      details: cards.every(n => n.tagName === 'DETAILS' && !n.open && !!head(n)),
      hidden: cards.every(n => !seen(n))
    };
    if (!cards.length) { return folded; }
    head(cards[0]).click();
    await window.__t.until(() => seen(cards[0]));
    return Object.assign(folded, { opened: cards[0].open && seen(cards[0]),
                                   others: !seen(cards[cards.length - 1]) });
  })()`);
  check(reviewHints.shown === target.hintCount,
    '復習でも開示済みのヒントは全部そこにある', reviewHints);
  check(reviewHints.details && reviewHints.hidden,
    '前に開いたヒントは畳まれて出る（本文は見えていない）', reviewHints);
  check(reviewHints.opened, '見出しを押せばその場で開ける', reviewHints);
  check(target.hintCount < 2 || reviewHints.others,
    '1枚開いても他のヒントは畳まれたまま', reviewHints);

  // ── 解いている途中で抜けても「続きから」戻れる（2026-08-21）───────────────
  //
  // セットの枠（何問目か・ここまでの正解数・積み上げ）は画面側にしか無い。控えが
  // 無かったころは、抜け方によらず次は**新しい1セット目**から始まっていた。
  //
  // ここではクリア済みが1問なので**1セット＝問題1問**である（クイズはまだ1問も答えて
  // いないので付かない）。それでも「途中で抜ける → 復習ホーム → 続きから」の経路は同じ。
  //
  // **3つめの再読み込みがこの節の主役。** 控えを localStorage に置いた意味は、本物の
  // 読み直し（`open` が about:blank を挟む）を通さないと測れない ―― 画面の変数に
  // 残っているだけでも1つめと2つめは通ってしまう。
  const leave = await ev(`(async () => {
    document.getElementById('reviewExitBtn').click();
    await window.__t.until(() => location.hash === '#review');
    await window.__t.sleep(300);
    const card = document.querySelector('.review-resume');
    return {
      shown: !!card,
      text: (card ? card.textContent : '').replace(/\\s+/g, ' ').trim().slice(0, 120),
      resumeBtn: !!document.getElementById('reviewResumeBtn'),
      restartBtn: !!document.getElementById('reviewResumeRestartBtn'),
      startBtn: ((document.getElementById('reviewStartBtn') || {}).textContent || '')
        .replace(/\\s+/g, ' ').trim()
    };
  })()`);
  check(leave.shown && leave.resumeBtn && leave.restartBtn,
    '「復習を終える」で抜けても、復習ホームに続きのカードが出る', leave);
  check(leave.text.indexOf('1セット目の途中です') >= 0 && leave.text.indexOf('1 / 1問目') >= 0,
    '何問目まで進んでいたかを先に出す', leave.text);
  check(leave.startBtn.indexOf('はじめから') >= 0,
    '続きがあるあいだは、ヒーローのボタンが「はじめから」の顔になる', leave.startBtn);

  const resumed = await ev(`(async () => {
    document.getElementById('reviewResumeBtn').click();
    await window.__t.until(() => location.hash.indexOf('#review/') === 0);
    await window.__t.sleep(600);
    return {
      hash: location.hash,
      bar: ((document.querySelector('.review-bar-progress') || {}).textContent || '').trim(),
      footer: (document.getElementById('reviewFooter') || {}).textContent || ''
    };
  })()`);
  check(resumed.hash === `#review/${LESSON}/${TASK}` && resumed.bar === '1 / 1問',
    '「続きから」で同じ問題・同じ番号に戻る（1問だけ復習に化けない）', resumed);

  await open('#review');
  const afterReload = await ev(`(() => ({
    shown: !!document.querySelector('.review-resume'),
    text: ((document.querySelector('.review-resume') || {}).textContent || '')
      .replace(/\\s+/g, ' ').trim().slice(0, 60),
    saved: !!localStorage.getItem('jq-review-run')
  }))()`);
  check(afterReload.shown && afterReload.saved,
    '再読み込みしても続きが残る（控えは localStorage の jq-review-run）', afterReload);

  // 続きへ戻ってから下の節へ渡す（以降はセットの中で提出したときの見た目を見る）
  const backIn = await ev(`(async () => {
    document.getElementById('reviewResumeBtn').click();
    await window.__t.until(() => location.hash.indexOf('#review/') === 0);
    await window.__t.sleep(600);
    return { hash: location.hash,
             bar: ((document.querySelector('.review-bar-progress') || {}).textContent || '').trim() };
  })()`);
  check(backIn.bar === '1 / 1問', '読み直したあとの「続きから」でもセットとして戻る', backIn);

  // ── 復習で通したときの知らせ（🔁復習クリアのトーストは戻っていない）──────────
  //
  // 「通った」ことを言うトーストは2026-08-21に外した（利用者の判断）。同じ瞬間に採点結果・
  // 苦手度バッジ・フッタの3つが示すので言い直しだった。ここで見るのは、そのトーストが
  // 戻っていないこと（文面に `復習クリア` が出ない）である。
  //
  // **コインの通知は別で、出るのが正しい。** この問題はこの検査の中でたったいま
  // クリアしたので期限前だが、2026-08-22から**期限前の「早めの復習」にも小額を払う**
  // （1日6問まで）。つまりここは「期限前でも入る」側の経路で、cafeAward が0でないこと・
  // 満額ではないことを一緒に確かめる ― 額を見ずに通知だけを見ると、
  // 期限ぶんと早めぶんが入れ替わっても通ってしまう。
  // **満額の側（期限が来た問題）は `check_cafe_ui.js` が見ている**
  // （あちらの進捗は日付が過去に固定されているので、常に期限切れ）。
  // 額は `window.__t.submit()` の戻りに入っていないので（採点結果だけを返す）、
  // 獲得の履歴（localStorage の jq-coin-log）の最新の1件から読む ― check_cafe_ui.js と同じ手
  const reviewPass = await ev(`(async () => {
    const logOf = () => { try { return JSON.parse(localStorage.getItem('jq-coin-log') || '[]'); }
                          catch (e) { return []; } };
    const before = logOf().length;
    document.getElementById('toast').classList.remove('show');   // 前の節の残りを消す
    window.__t.type(${JSON.stringify(solution.code || '')});
    const r = await window.__t.submit();
    await window.__t.sleep(700);                                 // 出るなら、この間に出ている
    const el = document.getElementById('toast');
    const foot = document.getElementById('reviewFooter');
    const s = await (await fetch('/api/state')).json();
    const cafe = s.progress.cafe || {};
    const log = logOf();
    const newest = log.length > before ? log[0] : null;
    return Object.assign(r, {
      dueDays: (((s.progress.lessons || {})['${LESSON}'] || {}).tasks || [])
        .reduce((acc, t) => (t.id === '${TASK}' ? t.reviewDueDays : acc), null),
      logAdded: log.length - before,
      reason: newest ? newest.reason : '',
      cash: newest ? newest.cash : 0,
      nextOrderCash: cafe.nextOrderCash || 0,
      earlyLeft: cafe.reviewEarlyRewardLeft,
      earlyPerDay: cafe.reviewEarlyRewardPerDay,
      toastShown: el.classList.contains('show'),
      toastText: (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 80),
      footer: (foot ? foot.textContent : '').replace(/\\s+/g, ' ').trim().slice(0, 60),
      badge: ((document.querySelector('#reviewWeight-${TASK}') || {}).textContent || '').trim()
    });
  })()`);
  check(reviewPass.verdict === 'ok', '復習でも模範解答が合格になる（この検査の前提）', reviewPass);
  check(reviewPass.toastText.indexOf('復習クリア') < 0,
    '「🔁 復習クリア」のトーストは戻っていない', reviewPass.toastText);
  check(reviewPass.dueDays === null || reviewPass.dueDays > 0,
    'たったいまクリアした問題なので期限前（＝「早めの復習」の側）', reviewPass.dueDays);
  check(reviewPass.logAdded === 1 && reviewPass.reason === '復習の注文',
    '期限前でもコインは入る（獲得の履歴に「復習の注文」が1件増える）', reviewPass);
  check(reviewPass.cash > 0, '入った額が0ではない（早めの復習ぶん）', reviewPass.cash);
  check(reviewPass.cash < reviewPass.nextOrderCash / 2,
    '早めのぶんは期限ぶん（初クリアの50%）より小さい',
    `${reviewPass.cash} < ${reviewPass.nextOrderCash / 2}`);
  check(reviewPass.earlyLeft < reviewPass.earlyPerDay,
    '1日に払う本数が減っている（早めのぶんは本数で止まる）',
    `${reviewPass.earlyLeft} / ${reviewPass.earlyPerDay}`);
  check(reviewPass.footer.indexOf('復習クリア') >= 0,
    '通ったことは問題の下のフッタが示す', reviewPass.footer);
  check(reviewPass.badge.length > 0, '苦手度のバッジも残っている', reviewPass.badge);

  // ── 結果まで進んだセットは「続き」として残さない（2026-08-21）─────────────
  //
  // 控えるのは**途中のセット**だけである。続けたい人は結果カードの「もう1セット」で進むので、
  // 出し終えた回まで控えると、次に開いた1セット目から「もう出した」ぶんが抜けたままになる
  // （`servedTaskKeys` が残るため）。結果カードと続きのカードが2枚並ぶことも起きない。
  const finished = await ev(`(async () => {
    document.getElementById('reviewFooterBtn').click();   // 「セットの結果へ →」
    await window.__t.until(() => location.hash === '#review');
    await window.__t.sleep(300);
    return {
      summary: !!document.querySelector('.review-summary'),
      resume: !!document.querySelector('.review-resume'),
      saved: !!localStorage.getItem('jq-review-run')
    };
  })()`);
  check(finished.summary && !finished.resume && !finished.saved,
    'セットを終えると結果だけが出て、続きの控えは消えている', finished);

  // ── 1問だけ復習したときの「通った」のひとこと（苦手度で出し分ける）──────
  //
  // `#review/1-1/1` のようにセット外で1問だけ復習すると、フッタのこの1行が唯一の知らせに
  // なる（右上の通知は出さないため）。苦手度0の問題は通しても0のままなので、そこへ
  // 「出題頻度が下がりました」と書くと起きていないことを言うことになる（2026-08-21）。
  //
  // **両方の分岐を通す。** わざと5回失敗して苦手度を1点より上へ上げてから2回通すと、
  // 1回目は 5-4=1単位 で残るので「下がりました」、2回目は0になるので「安定」。
  // 失敗1回が0.25点・正解が1点ぶん下げ、という目盛りが変わればここで気づける。
  await open(`#review/${LESSON}/${TASK}`);
  const single = await ev(`(async () => {
    const foot = () => (document.getElementById('reviewFooter').textContent || '')
      .replace(/\\s+/g, ' ').trim();
    const badge = () => ((document.querySelector('#reviewWeight-${TASK}') || {}).textContent || '').trim();
    const before = { footer: foot(), badge: badge(),
                     bar: ((document.querySelector('.review-bar-progress') || {}).textContent || '').trim() };
    for (let i = 0; i < 5; i++) {
      window.__t.type('public class Main {\\n  public static void main(String[] args) {\\n'
        + '    System.out.println("ちがう' + i + '");\\n  }\\n}');
      await window.__t.submit();
    }
    // 失敗では苦手度バッジを描き直さない（描き直すのは通ったときだけ）。
    // 上がったかどうかはサーバの値で見る
    const weightNow = async () => {
      const state = await (await fetch('/api/state')).json();
      let w = null;
      state.chapters.forEach(ch => ch.lessons.forEach(l => {
        if (l.id === '${LESSON}') {
          (l.tasks || []).forEach(t => { if (t.id === '${TASK}') { w = t.reviewWeight; } });
        }
      }));
      return w;
    };
    const failed = { badge: badge(), weight: await weightNow() };
    // 一覧のバッジと「🔥 苦手」の件数は描き直すたびに作られるので、復習ホームを開いて読む
    // （問題の見出しのバッジは失敗では描き直さないため、1.25点の見え方はここでしか見られない）
    location.hash = '#review';
    await window.__t.until(() => !!document.querySelector('.review-row'), 40);
    await window.__t.sleep(300);
    const listed = {
      badge: ((document.querySelector('.review-row .review-weight') || {}).textContent || '').trim(),
      weakChip: [...document.querySelectorAll('.review-filter-btn, .review-filter')]
        .map(b => b.textContent.replace(/\s+/g, ' ').trim())
        .find(t => t.indexOf('苦手') >= 0) || ''
    };
    location.hash = '#review/${LESSON}/${TASK}';
    await window.__t.until(() => !!document.querySelector('#task-${TASK}'), 40);
    await window.__t.sleep(300);
    window.__t.type(${JSON.stringify(solution.code || '')});
    const first = await window.__t.submit();
    const lowered = { verdict: first.verdict, footer: foot(), badge: badge() };
    // ここは 5-4=1単位（0.25点）。しきい値の下側（安定へ戻る境目）を見るために取っておく
    const stableBadge = badge();
    const second = await window.__t.submit();
    const stable = { verdict: second.verdict, footer: foot(), badge: badge() };
    return { before: before, failed: failed, lowered: lowered, stable: stable,
             stableBadge: stableBadge, listed: listed };
  })()`);
  check(single.before.bar === '1問だけ復習中'
    && single.before.footer.indexOf('解き直せたら提出しましょう') >= 0,
    'セット外の1問として開き、提出前はそう案内する', single.before);
  check(single.failed.weight === 5,
    '失敗5回で苦手度が5単位（1.25点）まで上がる（この検査の前提）', single.failed);
  check(single.lowered.verdict === 'ok'
    && single.lowered.footer.indexOf('出題頻度が下がりました') >= 0,
    '苦手度が残る問題では「出題頻度が下がりました」と出る', single.lowered);
  // ── バッジのしきい値が目盛りとずれていないか（2026-08-22・利用者の指摘）──────
  //
  // 失敗1回=0.25点に対し、しきい値だけが「1回1点」のころの 1 / 3 / 5点 で残っていたため、
  // **失敗3回まで「安定」**と出ていた（実際の記録では全問が「安定」だった）。
  // いまは 0.5 / 1.5 / 3点 = 失敗 2 / 6 / 12 回。ここは**両側**を見る ―
  // 1.25点（失敗5回）が `もう一度`、1回通して0.25点まで下がったら `安定`。
  // 片側だけだと、しきい値をまとめて上げ下げしても気づけない。
  check(single.listed.badge === 'もう一度',
    '1.25点（失敗5回）の行は「もう一度」と出る（以前は「安定」だった）', single.listed.badge);
  check(single.listed.weakChip.indexOf('1') >= 0,
    '同じしきい値で「🔥 苦手」に数える（バッジと絞り込みが食い違わない）',
    single.listed.weakChip);
  check(single.stableBadge === '安定',
    '0.25点（失敗1回ぶん）まで下がれば「安定」に戻る', single.stableBadge);
  check(single.stable.verdict === 'ok' && single.stable.badge === '安定'
    && single.stable.footer.indexOf('しっかり身についています') >= 0,
    '苦手度0になった問題には「下がりました」と言わない', single.stable);

  // ── クイズのしおり（付ける → 復習ホームの一覧 → そのクイズへ戻る）──────
  //
  // クイズは復習で出題しないので、この経路（印を付けて、一覧から見に戻る）は
  // ここでしか通らない。しおりの状態は描き直さずボタンだけ変えるので、
  // サーバへ届いたかは /api/state を読んで確かめる。
  await open(`#${QUIZ_LESSON}`);
  const quizPage = await ev(`(() => ({
    items: document.querySelectorAll('.quiz-item').length,
    marks: document.querySelectorAll('.quiz-item-head .bookmark-btn').length,
    on: document.querySelectorAll('.quiz-item-head .bookmark-btn.on').length
  }))()`);
  if (!quizPage.items) {
    console.log(`${RED}この検査は ${QUIZ_LESSON} に確認クイズがある前提です。`
      + `教材を変えたなら、対象を差し替えてください（tools/check_learn_ui.js の QUIZ_LESSON）${RESET}`);
    close();
    process.exit(1);
  }
  check(quizPage.marks === quizPage.items,
    `${QUIZ_LESSON} のクイズすべてにしおりのボタンがある`, quizPage);
  check(quizPage.on === 0, '最初はどのクイズにも付いていない', quizPage.on);

  const marked = await ev(`(async () => {
    const btn = document.querySelector('.quiz-item-head .bookmark-btn');
    btn.click();
    const on = await window.__t.until(() => btn.classList.contains('on'), 40);
    const state = await (await fetch('/api/state')).json();
    let lesson = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${QUIZ_LESSON}') { lesson = l; }
    }));
    return { on: !!on, pressed: btn.getAttribute('aria-pressed'),
             saved: (lesson && lesson.quizBookmarks) || [] };
  })()`);
  check(marked.on && marked.pressed === 'true', 'クイズにしおりを付けられる', marked);
  check(marked.saved[0] === true && marked.saved[1] === false,
    '付けた1問だけがサーバに残る（/api/state の quizBookmarks）', marked.saved);

  await open('#review');
  const marks = await ev(`(() => {
    const rows = [...document.querySelectorAll('.review-row-main[data-quiz]')];
    const first = rows[0];
    return {
      count: rows.length,
      lesson: first ? first.dataset.lesson : '',
      index: first ? first.dataset.quiz : '',
      state: first ? (first.querySelector('.quiz-bookmark-state') || {}).textContent || '' : '',
      text: first ? ((first.querySelector('.review-row-copy strong') || {}).textContent || '').trim() : ''
    };
  })()`);
  check(marks.count === 1 && marks.lesson === QUIZ_LESSON && marks.index === '0',
    '復習ホームの一覧にしおりを付けたクイズが出る', marks);
  check(marks.text.length > 0 && marks.text.indexOf('`') < 0,
    '一覧の問い文はMarkdownの記法を落として出す', marks.text.slice(0, 40));
  check(marks.state.indexOf('未回答') >= 0, '答える前は「未回答」と出る', marks.state);

  const jumped = await ev(`(async () => {
    document.querySelector('.review-row-main[data-quiz]').click();
    await window.__t.until(() => document.getElementById('quiz-item-0'), 40);
    const item = document.getElementById('quiz-item-0');
    const view = document.getElementById('content').getBoundingClientRect();
    const box = item ? item.getBoundingClientRect() : null;
    return {
      hash: location.hash,
      highlighted: !!(item && item.classList.contains('is-target')),
      inView: !!box && box.top < view.bottom && box.bottom > view.top
    };
  })()`);
  check(jumped.hash === `#${QUIZ_LESSON}`, 'しおりの行からそのレッスンへ移動する', jumped.hash);
  check(jumped.inView, '飛んだ先のクイズが画面に入っている', jumped);
  check(jumped.highlighted, '飛んだ先のクイズが光る（どこへ着いたか分かる）', jumped);

  // ── 復習の最後に続くクイズ（📣の取り返し）─────────────────────────────
  //
  // 📣ひらめきメガホンの条件は「1度目の回答で20問連続正解」だけだったので、初回答を
  // 使い切ると**二度と取れなかった**。復習として出し直したクイズの連続正解でも解放する
  // ようにしたのがこの経路（`/api/quiz` の `review`）。ここで見るのは3つ ―
  // 問題のあとに続けて出ること、答える前に正解を見せないこと、そして
  // **チップも★も動かさないこと**（動かすと「復習では払わない」原則の例外になる）。
  const answered = await ev(`(async () => {
    document.querySelector('.quiz-item .quiz-choice').click();
    await window.__t.until(() => document.querySelector('.quiz-feedback'), 40);
    const state = await (await fetch('/api/state')).json();
    let lesson = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${QUIZ_LESSON}') { lesson = l; }
    }));
    const result = (lesson.quizResults || [])[0] || {};
    return { answer: result.answer, choice: result.choice,
             run: state.progress.cafe.quizReviewRun };
  })()`);
  check(answered.answer != null && answered.run === 0,
    'クイズへ1度目の回答をした（復習へ回る前提）', answered);

  await open('#review');
  const quizNote = await ev(
    `((document.querySelector('.review-quiz-note') || {}).textContent || '')`);
  check(quizNote.indexOf('クイズ') >= 0,
    '復習ホームに「問題のあとにクイズが続く」案内が出る', quizNote);

  const quizPhase = await ev(`(async () => {
    document.getElementById('reviewStartBtn').click();
    await window.__t.until(() => location.hash.indexOf('#review/') === 0, 40);
    await window.__t.sleep(600);
    for (let i = 0; i < 12 && !document.querySelector('.review-quiz-view'); i++) {
      const skip = document.getElementById('reviewSkipBtn');
      if (!skip) { break; }
      skip.click();
      await window.__t.sleep(600);
    }
    const view = document.querySelector('.review-quiz-view');
    return {
      shown: !!view,
      feedback: !!(view && view.querySelector('.quiz-feedback')),
      next: !!document.getElementById('reviewFooterBtn'),
      score: view ? (view.querySelector('.quiz-score') || {}).textContent : '',
      bar: (document.querySelector('.review-bar-progress') || {}).textContent
    };
  })()`);
  check(quizPhase.shown, '問題を出し切るとクイズが続けて出る', quizPhase);
  check(!quizPhase.feedback && !quizPhase.next,
    '答える前は正解も解説も「次へ」も出さない', quizPhase);
  check(String(quizPhase.score).indexOf('/ 20問') >= 0
      && String(quizPhase.bar).indexOf('クイズ') >= 0,
    '📣までの連続と、クイズの段であることが出ている', quizPhase);

  const graded = await ev(`(async () => {
    const before = await (await fetch('/api/state')).json();
    document.querySelectorAll('.review-quiz-view .quiz-choice')[${answered.answer}].click();
    await window.__t.until(() => document.querySelector('.quiz-feedback'), 40);
    const after = await (await fetch('/api/state')).json();
    let lesson = null;
    after.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${QUIZ_LESSON}') { lesson = l; }
    }));
    const view = document.querySelector('.review-quiz-view');
    return {
      verdict: (view.querySelector('.quiz-verdict') || {}).textContent || '',
      explain: !!view.querySelector('.quiz-explain'),
      disabled: [...view.querySelectorAll('.quiz-choice')].every(b => b.disabled),
      runBefore: before.progress.cafe.quizReviewRun,
      runAfter: after.progress.cafe.quizReviewRun,
      cashBefore: before.progress.cafe.cash,
      cashAfter: after.progress.cafe.cash,
      choice: ((lesson.quizResults || [])[0] || {}).choice,
      next: ((document.getElementById('reviewFooterBtn') || {}).textContent || '')
    };
  })()`);
  check(graded.verdict.indexOf('正解') >= 0 && graded.explain && graded.disabled,
    '答えるとその回の結果と解説が出て、押し直せなくなる', graded);
  check(graded.runAfter === graded.runBefore + 1,
    '復習での連続正解が1つ進む', graded);
  check(graded.cashAfter === graded.cashBefore,
    'チップは出ない（残高が動かない）', graded);
  check(graded.choice === answered.choice,
    '★と正解数の根拠（選んだ答え）は書き換えない', graded);
  check(graded.next.length > 0, '答えたあとに次へ進める', graded.next);

  // ── サイドバーの検索（打つ → 絞る → 開く → 章の一覧へ戻る）────────────
  //
  // 索引も照合も画面の中だけにあるので、サーバ側の検査には出ない。
  // 探す語は教材から取る（決め打ちの単語は教材を書き換えたときに黙って腐る）。
  await open(`#${LESSON}`);
  const searchTarget = await ev(`(async () => {
    const state = await (await fetch('/api/state')).json();
    let lesson = null, chapter = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${LESSON}') { lesson = l; chapter = ch; }
    }));
    // 本文だけに出る語。全レッスンの解説から探して、無ければ理由の分かる失敗にする
    const deepTerm = 'System.out.println';
    let deepCount = 0;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if ((l.explanation || '').indexOf(deepTerm) >= 0) { deepCount++; }
    }));
    return {
      title: lesson && lesson.title,
      chapterTitle: chapter && chapter.title,
      shownNumber: chapter && (chapter.partNumber || chapter.number),
      deepTerm: deepTerm,
      deepCount: deepCount,
      box: !!document.getElementById('sidebarSearch'),
      tree: !!document.getElementById('sidebarTree')
    };
  })()`);
  check(searchTarget.box && searchTarget.tree && searchTarget.title && searchTarget.deepCount > 0,
    '検索欄と章の一覧がサイドバーにあり、本文に出る語が教材にある', searchTarget);
  if (!searchTarget.box || !searchTarget.deepCount) {
    console.log(`${RED}検索の検査は #sidebarSearch と、解説に `
      + `${searchTarget.deepTerm} を含むレッスンがある前提です${RESET}`);
    close();
    process.exit(1);
  }

  const searched = await ev(`(async () => {
    if (document.body.classList.contains('sidebar-hidden')) {
      document.getElementById('sidebarToggle').click();
    }
    const input = document.getElementById('sidebarSearch');
    const type = async value => {
      input.focus();
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await window.__t.sleep(150);
    };
    const rows = () => [...document.querySelectorAll('#sidebarTree .side-hit')];
    // 抜粋は「解説「…」」の形。本文から当たった行だけを数えたいので、
    // 到達目標の抜粋は数に入れない
    const bodyRows = () => rows().filter(r => /^(解説|課題|サンプル)「/.test(
      (r.querySelector('.side-hit-snippet') || {}).textContent || ''));

    await type(${JSON.stringify(String(searchTarget.title || ''))});
    const byName = {
      rows: rows().length,
      hit: rows().some(r => r.dataset.lesson === '${LESSON}'),
      marks: document.querySelectorAll('#sidebarTree .side-hit-mark').length,
      where: rows().length
        ? (rows()[0].querySelector('.side-hit-where') || {}).textContent || '' : '',
      count: (document.getElementById('sidebarSearchCount') || {}).textContent || ''
    };

    // 1文字では本文まで広げない（解説がほぼ全章に当たってしまうため）
    await type(${JSON.stringify(String(searchTarget.title || 'a').slice(0, 1))});
    const shallow = {
      rows: rows().length,
      bodyLabels: bodyRows().length
    };

    // 2文字以上なら本文からも探す
    await type(${JSON.stringify(searchTarget.deepTerm)});
    const deep = {
      rows: rows().length,
      bodyLabels: bodyRows().length
    };

    // 一致しない語では、探せる範囲を案内する
    await type('該当しない語ｘｙｚ');
    const empty = {
      rows: rows().length,
      note: !!document.querySelector('#sidebarTree .side-hits-empty')
    };

    // ↑↓ で選んで Enter で開く。語は残る
    await type(${JSON.stringify(String(searchTarget.title || ''))});
    input.dispatchEvent(new KeyboardEvent('keydown',
      { key: 'ArrowDown', bubbles: true, cancelable: true }));
    const active = (document.querySelector('#sidebarTree .side-hit.active') || {}).dataset;
    input.dispatchEvent(new KeyboardEvent('keydown',
      { key: 'Enter', bubbles: true, cancelable: true }));
    await window.__t.until(() => document.querySelector('.lesson-view'), 40);
    const opened = {
      hash: location.hash,
      activeLesson: active && active.lesson,
      queryKept: input.value,
      currentRow: !!document.querySelector('#sidebarTree .side-hit-current')
    };

    // Esc で検索をやめると章の一覧に戻り、現在地の印も戻る
    input.focus();
    input.dispatchEvent(new KeyboardEvent('keydown',
      { key: 'Escape', bubbles: true, cancelable: true }));
    await window.__t.sleep(150);
    const restored = {
      value: input.value,
      hits: document.querySelectorAll('#sidebarTree .side-hit').length,
      chapters: document.querySelectorAll('#sidebarTree .ch').length,
      current: document.querySelectorAll('#sidebarTree .lesson-current').length
    };
    return { byName, shallow, deep, empty, opened, restored };
  })()`);

  check(searched.byName.rows > 0 && searched.byName.hit,
    'レッスン名で打つと、そのレッスンが結果に出る', searched.byName);
  check(searched.byName.marks > 0 && /第\d+章/.test(searched.byName.where),
    '一致した語が強調され、行に編と章名が付く', searched.byName);
  check(/一致 \d+件/.test(searched.byName.count), '件数を出す', searched.byName.count);
  check(searched.shallow.rows > 0 && searched.shallow.bodyLabels === 0,
    '1文字では名前と章名までしか探さない', searched.shallow);
  check(searched.deep.rows > 0 && searched.deep.bodyLabels > 0,
    '2文字以上なら解説や課題文からも探し、当たった箇所を添える', searched.deep);
  check(searched.empty.rows === 0 && searched.empty.note,
    '一致しない語では探せる範囲を案内する', searched.empty);
  check(searched.opened.hash === '#' + searched.opened.activeLesson
    && searched.opened.currentRow,
    '↑↓ と Enter で結果のレッスンを開き、その行に現在地の印が付く', searched.opened);
  check(searched.opened.queryKept.length > 0,
    '開いたあとも検索語は残る（続けて別の行へ移れる）', searched.opened.queryKept);
  check(searched.restored.value === '' && searched.restored.hits === 0
    && searched.restored.chapters > 0 && searched.restored.current === 1,
    'Esc で章の一覧へ戻り、開いているレッスンの印も戻る', searched.restored);

  // ⌘K / Ctrl+K。macOSの入力欄では Ctrl+K を奪わない（本来「行末まで削除」なので、
  // 奪うとコードを書いている最中の打鍵が壊れる）
  //
  // 結果から別のレッスンへ飛んでいることがあるので、コード欄がある前提の
  // レッスン（前提を確かめた ${LESSON}）へ戻してから触る。
  await open(`#${LESSON}`);
  const shortcut = await ev(`(() => {
    const mac = !!(window.JQComplete && window.JQComplete.isMac && window.JQComplete.isMac());
    const fire = (target, init) => {
      const e = new KeyboardEvent('keydown',
        Object.assign({ key: 'k', code: 'KeyK', bubbles: true, cancelable: true }, init));
      target.dispatchEvent(e);
      return e.defaultPrevented;
    };
    // 閉じた状態から開いて検索欄へ入るか
    if (!document.body.classList.contains('sidebar-hidden')) {
      document.getElementById('sidebarToggle').click();
    }
    const closed = document.body.classList.contains('sidebar-hidden');
    const opened = fire(document.body, { metaKey: mac, ctrlKey: !mac });
    const focused = document.activeElement && document.activeElement.id;
    const editor = window.__t.editor();
    editor.focus();
    return {
      mac: mac,
      closedBefore: closed,
      handled: opened,
      hiddenAfter: document.body.classList.contains('sidebar-hidden'),
      focused: focused,
      ctrlInEditor: fire(editor, { ctrlKey: true }),
      metaInEditor: fire(editor, { metaKey: true })
    };
  })()`);
  check(shortcut.closedBefore && shortcut.handled && !shortcut.hiddenAfter
    && shortcut.focused === 'sidebarSearch',
    '⌘K / Ctrl+K で閉じているサイドバーを開いて検索欄へ入る', shortcut);
  check(shortcut.mac ? shortcut.ctrlInEditor === false : shortcut.ctrlInEditor === true,
    shortcut.mac
      ? 'macOSではコード欄の Ctrl+K を奪わない（行末まで削除を残す）'
      : 'macOS以外ではコード欄でも Ctrl+K で検索へ入れる',
    shortcut);
  check(shortcut.metaInEditor === true, 'コード欄からでも ⌘K は効く', shortcut);

  // ── ☰ はどの画面でも出す ────────────────────────────────────────
  //
  // ホームとカフェでは以前隠していた。隠すと、章とレッスンの一覧を開く手立ても、
  // 開いたまま移ってきたときに閉じる手立ても無くなる。CSS1行で消える性質のものなので、
  // 画面ごとに実際の display を見る。
  const toggles = {};
  for (const [name, hash] of [['ホーム', '#menu'], ['カフェ', '#cafe'], ['復習ホーム', '#review']]) {
    await open(hash);
    toggles[name] = await ev(`(() => {
      const btn = document.getElementById('sidebarToggle');
      const shown = () => getComputedStyle(btn).display !== 'none';
      const hidden = () => document.body.classList.contains('sidebar-hidden');
      const before = { shown: shown(), hidden: hidden() };
      btn.click();
      return { before: before, shown: shown(), hidden: hidden() };
    })()`);
  }
  const toggleNames = Object.keys(toggles);
  check(toggleNames.every(k => toggles[k].before.shown && toggles[k].shown),
    'ホーム・カフェ・復習ホームでも☰が出ている', toggles);
  check(toggleNames.every(k => toggles[k].hidden !== toggles[k].before.hidden),
    'どの画面でも☰でサイドバーを開閉できる', toggles);

  // ── 貼り付いた編見出しの上に隙間ができていないか ────────────────────
  //
  // 貼り付く位置は**容器のパディングの内側**なので、`.side-tree` に padding-top を
  // 足すとその分だけ見出しの上にスクロール領域が残り、章やレッスンが覗く。
  // 編の切り替わりでも同じことが起きる（見出しが margin を持つと早く出ていく）。
  // どちらも数pxの隙間なので、実際にその点に何が描かれているかを見るしかない。
  await open(`#${LESSON}`);
  const sticky = await ev(`(async () => {
    if (document.body.classList.contains('sidebar-hidden')) {
      document.getElementById('sidebarToggle').click();
    }
    const tree = document.getElementById('sidebarTree');
    const partTop = () => {
      const box = tree.getBoundingClientRect();
      return [...tree.querySelectorAll('.side-part-head')]
        .map(h => h.getBoundingClientRect())
        .filter(b => b.top >= box.top - 1 && b.top < box.top + 40)
        .map(b => Math.round(b.top - box.top))[0];
    };
    // 上端の数点に何が描かれているか。見出し以外が出たらそこが隙間
    const atTop = () => {
      const box = tree.getBoundingClientRect();
      return [1, 5, 10].map(y => {
        const el = document.elementFromPoint(box.left + 40, box.top + y);
        if (!el) { return 'なし'; }
        return el.closest('.side-part-head') ? 'head' : (el.className || el.tagName);
      });
    };
    const look = async top => {
      tree.scrollTop = top;
      await window.__t.sleep(150);
      return { top: top, pinned: partTop(), atTop: atTop() };
    };
    // 2つめの編の見出しの位置（編の切り替わりを跨いで見るため）
    tree.scrollTop = 0;
    await window.__t.sleep(150);
    const box = tree.getBoundingClientRect();
    const heads = [...tree.querySelectorAll('.side-part-head')];
    const secondPart = heads.length > 1
      ? Math.round(heads[1].getBoundingClientRect().top - box.top + tree.scrollTop) : null;
    const spots = [await look(400)];
    if (secondPart) {
      for (const d of [-40, -10, 0]) { spots.push(await look(secondPart + d)); }
    }
    return { paddingTop: getComputedStyle(tree).paddingTop, parts: heads.length, spots: spots };
  })()`);
  check(sticky.parts > 1 && sticky.spots.length === 4,
    '編が複数あり、切り替わりを跨いで測れた', { parts: sticky.parts, spots: sticky.spots.length });
  // 落ち着いた位置（編の途中）では、見出しが上端そのものに貼り付いている。
  // 切り替わりの最中は、出ていく見出しと入ってくる見出しが動いている途中なので
  // 「0pxにある」ことは成り立たない ― そこで見るのは下の「覗かない」だけである。
  check(sticky.spots[0].pinned === 0 && sticky.paddingTop === '0px',
    '編の途中では見出しが一覧の上端（0px）に貼り付く', sticky.spots[0]);
  check(sticky.spots.every(s => s.atTop.every(what => what === 'head')),
    '貼り付いた見出しの上に章やレッスンが覗かない（編の切り替わりでも）', sticky);

  // ── 「試しに実行」の入力欄（入力を読む問題だけに出る）──────────────
  // ここは最後に見る。別のレッスンを開くので、先にやると「続ける」の行き先や
  // カフェから戻る位置の検査が、この移動のせいで変わってしまう。
  await open(`#${STDIN_LESSON}`);
  const stdinBox = await ev(`(async () => {
    const state = await (await fetch('/api/state')).json();
    let task = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${STDIN_LESSON}') {
        task = (l.tasks || []).find(t => t.id === '${STDIN_TASK}') || null;
      }
    }));
    const caseStdin = task && (task.visibleCases || []).map(c => c.stdin).filter(Boolean)[0];
    const box = document.getElementById('tryStdin-${STDIN_TASK}');
    return {
      caseStdin: caseStdin || '',
      value: box ? box.value : null,
      hasClass: !!document.querySelector('#task-${STDIN_TASK} .card-code.has-try-input'),
      label: ((document.querySelector('#task-${STDIN_TASK} .try-input-label') || {}).textContent || '').trim()
    };
  })()`);
  check(!!stdinBox.caseStdin,
    `${STDIN_LESSON}#${STDIN_TASK} は入力を読む問題（この検査の前提）`, stdinBox.caseStdin);
  check(stdinBox.value === stdinBox.caseStdin && stdinBox.hasClass,
    '入力を読む問題では、見えているケースの入力が入った欄が出る', stdinBox);
  check(/試しに実行/.test(stdinBox.label) && /提出/.test(stdinBox.label),
    '入力欄が「どちらで使う入力か」を書いている', stdinBox.label);

  // 書き換えた入力がそのまま渡る（採点はしない）
  const echoed = await ev(`(async () => {
    const box = document.getElementById('tryStdin-${STDIN_TASK}');
    box.value = '99';
    const ta = document.querySelector('#task-${STDIN_TASK} .editor-input');
    ta.value = 'import java.util.Scanner;\\npublic class Main {\\n'
      + '  public static void main(String[] args) {\\n'
      + '    Scanner sc = new Scanner(System.in);\\n'
      + '    System.out.println("read:" + sc.nextInt());\\n  }\\n}';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    const host = document.getElementById('result-${STDIN_TASK}');
    host.innerHTML = '';
    document.getElementById('tryBtn-${STDIN_TASK}').click();
    const card = await window.__t.until(() => {
      const c = host.querySelector('.card-result');
      return c && !c.querySelector('.spinner') ? c : null;
    });
    return card ? ((card.querySelector('.out-pre') || {}).textContent || '').trim() : null;
  })()`);
  check(echoed === 'read:99', '書き換えた入力がそのまま標準入力へ渡る', echoed);

  // ショートカット。⇧を足したときだけ採点なしで走る（素の⌘/Ctrl+Enterは提出のまま）
  const keys = await ev(`(async () => {
    const ta = document.querySelector('#task-${STDIN_TASK} .editor-input');
    const host = document.getElementById('result-${STDIN_TASK}');
    const press = shift => ta.dispatchEvent(new KeyboardEvent('keydown',
      { key: 'Enter', metaKey: true, shiftKey: shift, bubbles: true, cancelable: true }));
    host.innerHTML = '';
    press(true);
    const tryCard = await window.__t.until(() => {
      const c = host.querySelector('.card-result');
      return c && !c.querySelector('.spinner') ? c : null;
    });
    const shifted = !!tryCard && tryCard.classList.contains('card-try');
    const before = window.__jqSubmits.length;
    press(false);
    const submitted = await window.__t.until(() => window.__jqSubmits.length > before);
    return { shifted: shifted, submitted: !!submitted };
  })()`);
  check(keys.shifted, '⇧ + ⌘/Ctrl + Enter で採点なしに走る', keys);
  check(keys.submitted, '素の ⌘/Ctrl + Enter は提出のまま', keys);

  // ── 報酬の通知の表示/非表示（設定パネルの「報酬の通知」）──────────────
  // 既定で通知が出ることは上の「正解」の節で見ている。ここでは「表示しない」へ切り替えると
  // **通知だけ** が消えて、コインと獲得の履歴は残ることを見る。設定が効いていなければ
  // 上と同じように通知が出るので、この節は必ず落ちる（空振りしない検査になっている）。
  await open(`#${LESSON}`);
  const prefTask = await ev(`(async () => {
    const state = await (await fetch('/api/state')).json();
    let task = null;
    state.chapters.forEach(ch => ch.lessons.forEach(l => {
      if (l.id === '${LESSON}') { task = (l.tasks || []).find(t => t.id === '${PREF_TASK}') || null; }
    }));
    if (!task) { return { missing: true }; }
    const expected = (task.visibleCases || []).map(c => c.expected).filter(Boolean)[0] || '';
    return { cleared: !!task.cleared, expected: expected };
  })()`);
  check(!prefTask.missing && !prefTask.cleared && prefTask.expected.length > 0
    && !/["\\]/.test(prefTask.expected),
    `${LESSON}#${PREF_TASK} は未クリアで、出力が決まっている（この検査の前提）`, prefTask);
  // 期待する出力から、そのまま出すだけのコードを組む（教材の文面が変わっても追随する）
  const prefCode = 'public class Main {\n  public static void main(String[] args) {\n'
    + prefTask.expected.split('\n').map(line => `    System.out.println("${line}");\n`).join('')
    + '  }\n}';

  const toastPref = await ev(`(async () => {
    document.getElementById('settingsBtn').click();
    const pop = await window.__t.until(() => {
      const p = document.getElementById('settingsPop');
      return p && !p.hidden ? p : null;
    }, 20);
    if (!pop) { return { opened: false }; }
    const read = () => Array.prototype.slice.call(pop.querySelectorAll('[data-toast-choice]'))
      .map(b => b.dataset.toastChoice + ':' + b.getAttribute('aria-checked')).join(' ');
    const before = read();
    const off = pop.querySelector('[data-toast-choice="0"]');
    if (off) { off.click(); }
    return {
      opened: true,
      count: pop.querySelectorAll('[data-toast-choice]').length,
      before: before,
      after: read(),
      stillOpen: !document.getElementById('settingsPop').hidden,
      saved: localStorage.getItem('jq-reward-toast')
    };
  })()`);
  check(toastPref.opened && toastPref.count === 2, '設定に「報酬の通知」の2択がある', toastPref);
  check(toastPref.before === '1:true 0:false', '既定は「表示する」', toastPref.before);
  check(toastPref.after === '1:false 0:true' && toastPref.saved === '0',
    '「表示しない」を選ぶと印が移り、保存される', toastPref);
  check(toastPref.stillOpen, '選んでもパネルは閉じない（明るさと同じ）', toastPref.stillOpen);

  // 矢印キーの行き来は区画の中だけ（明るさの最後から報酬の通知へ飛び移らない）
  const arrows = await ev(`(() => {
    const pop = document.getElementById('settingsPop');
    const themeOpts = pop.querySelectorAll('[data-theme-choice]');
    const last = themeOpts[themeOpts.length - 1];
    last.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    const at = document.activeElement;
    return { movedTo: at ? (at.dataset.themeChoice || at.dataset.toastChoice || '') : '',
             kind: at && at.dataset.themeChoice ? 'theme' : 'toast' };
  })()`);
  check(arrows.kind === 'theme', '矢印キーは選んでいる区画の中だけを回る', arrows);

  const quiet = await ev(`(async () => {
    document.body.click();                     // 設定を閉じる
    const ta = document.querySelector('#task-${PREF_TASK} .editor-input');
    if (!ta) { return { editor: false }; }
    ta.value = ${JSON.stringify(prefCode)};
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    const logBefore = JSON.parse(localStorage.getItem('jq-coin-log') || '[]').length;
    const host = document.getElementById('result-${PREF_TASK}');
    host.innerHTML = '';
    const sent = window.__jqSubmits.length;
    document.getElementById('submitBtn-${PREF_TASK}').click();
    const card = await window.__t.until(() => {
      const c = host.querySelector('.card-result');
      return c && !c.querySelector('.spinner') ? c : null;
    });
    await window.__t.until(() => window.__jqSubmits.length > sent);
    const res = window.__jqSubmits[window.__jqSubmits.length - 1] || {};
    await window.__t.sleep(500);               // 出るなら、この間に出ている
    const el = document.getElementById('toast');
    return {
      editor: true,
      verdict: card && card.classList.contains('ok') ? 'ok' : 'ng',
      awarded: !!(res.cafeAward && res.cafeAward.cash > 0),
      newStar: res.newStar === true,
      stats: !!el.querySelector('.toast-stats'),
      shown: el.classList.contains('show'),
      text: (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 60),
      logBefore: logBefore,
      logAfter: JSON.parse(localStorage.getItem('jq-coin-log') || '[]').length
    };
  })()`);
  check(quiet.verdict === 'ok' && quiet.awarded && quiet.newStar,
    '「表示しない」でもクリアと報酬そのものは起きる（この検査の前提）', quiet);
  check(!quiet.stats, '「表示しない」のときは報酬の通知が出ない', quiet);
  check(quiet.logAfter === quiet.logBefore + 1,
    '通知を出さなくても獲得の履歴には残る（あとから読み返せる）', quiet);

  // 読み直しても選んだ設定が残り、「表示する」へ戻せる
  await open(`#${LESSON}`);
  const back = await ev(`(async () => {
    document.getElementById('settingsBtn').click();
    const pop = await window.__t.until(() => {
      const p = document.getElementById('settingsPop');
      return p && !p.hidden ? p : null;
    }, 20);
    if (!pop) { return { opened: false }; }
    const kept = pop.querySelector('[data-toast-choice="0"]').getAttribute('aria-checked');
    pop.querySelector('[data-toast-choice="1"]').click();
    const on = pop.querySelector('[data-toast-choice="1"]').getAttribute('aria-checked');
    document.body.click();
    return { opened: true, kept: kept, on: on, saved: localStorage.getItem('jq-reward-toast') };
  })()`);
  check(back.kept === 'true', '読み直しても「表示しない」が残っている', back);
  check(back.on === 'true' && back.saved === '1', '「表示する」へ戻せる', back);

  // ── 1つ前の問題へ戻る（2026-08-22・利用者の要望）───────────────────────────
  //
  // ここまでで ${LESSON}#${TASK} と ${LESSON}#${PREF_TASK} の2問がクリア済みなので、
  // **1セット＝2問**になる（クリア済みの問題は期限前でも補充に入る → buildReviewQueue）。
  // 2問ないと「戻る」を押せないので、この節はここに置いてある。
  //
  // 見るのは4つ ― 1問目では出さない（押せないボタンを残さない）・2問目には出る・
  // 押すと1問目に戻って何問目かの表示も戻る・戻った位置が控えにも残る
  // （途中で抜けても「続きから」戻る先が今いる問題になる → jq-review-run）。
  await open('#review');
  const stepBack = await ev(`(async () => {
    const bar = () => ((document.querySelector('.review-bar-progress') || {}).textContent || '').trim();
    const backBtn = () => document.getElementById('reviewBackBtn');
    const saved = () => (JSON.parse(localStorage.getItem('jq-review-run') || 'null') || {}).set || {};
    document.getElementById('reviewStartBtn').click();
    await window.__t.until(() => location.hash.indexOf('#review/') === 0, 40);
    await window.__t.sleep(700);
    const first = { bar: bar(), back: !!backBtn(), hash: location.hash, index: saved().index };
    document.getElementById('reviewSkipBtn').click();
    await window.__t.until(() => location.hash !== first.hash, 40);
    await window.__t.sleep(700);
    const second = { bar: bar(), back: !!backBtn(), hash: location.hash, index: saved().index };
    if (!backBtn()) { return { first: first, second: second }; }
    backBtn().click();
    await window.__t.until(() => location.hash === first.hash, 40);
    await window.__t.sleep(700);
    const third = { bar: bar(), back: !!backBtn(), hash: location.hash, index: saved().index,
                    forward: ((document.getElementById('reviewFooterBtn') || {}).textContent || '').trim() };
    return { first: first, second: second, third: third };
  })()`);
  check(stepBack.first.bar === '1 / 2問' && stepBack.second.bar === '2 / 2問',
    '2問のセットが組まれた（この検査の前提）', stepBack);
  check(!stepBack.first.back, '1問目には「前の問題へ」を出さない', stepBack.first);
  check(stepBack.second.back, '2問目には「前の問題へ」が出る', stepBack.second);
  check(!!stepBack.third && stepBack.third.hash === stepBack.first.hash
      && stepBack.third.bar === '1 / 2問' && !stepBack.third.back,
    '押すと1つ前の問題へ戻り、何問目かの表示も戻る', stepBack.third);
  check(!!stepBack.third && stepBack.third.index === 0 && stepBack.second.index === 1,
    '戻った位置は控えにも残る（抜けても戻る先が今の問題になる）', stepBack);
  check(!!stepBack.third && stepBack.third.forward.indexOf('次の問題へ') >= 0,
    '戻ったあとも前へ進める（行き止まりにならない）', stepBack.third);

  const errors = await ev(`window.__jqErrors || []`);
  check(errors.length === 0, '画面のJavaScriptが例外を出していない', errors);

  close();
  if (failures > 0) {
    console.log(`\n${RED}学習画面の検査に失敗しました（${failures}件）${RESET}`);
    process.exit(1);
  }
  console.log(`\n${GREEN}LEARN UI OK: 誤答・コンパイルエラー・ヒント・模範解答・`
    + `★と報酬・1問1枚のパネル・獲得の履歴・自動保存・カフェへの寄り道と位置の復元`
    + `（レッスンと復習の両方）・復習の出題・`
    + `途中で抜けたセットの「続きから」・1つ前の問題へ戻る・復習の最後に続くクイズ・`
    + `クイズのしおり・サイドバーの検索・試しに実行と入力欄・`
    + `報酬の通知の表示/非表示を確認しました${RESET}`);
})().catch(e => {
  console.error(`${RED}検査を実行できませんでした: ${e.message}${RESET}`);
  process.exit(1);
});
