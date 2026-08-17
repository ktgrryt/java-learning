/*
 * 学習画面をブラウザで実際に操作して確かめる。check-learn-ui.sh から呼ばれる。
 *
 * 引数: <アプリのポート> <ChromeのCDPポート>
 *
 * 見るのは「1問を解き切るまでの経路」全体である。
 *   誤答 → コンパイルエラー → ヒント → 模範解答 → 正解（★・コイン・通知）→ 自動保存 → 復習
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
    starter: (window.__t.editor() || {}).value || '',
    hintLabel: ((document.querySelector('#task-${TASK} .hint-btn') || {}).textContent || '').trim()
  }))()`);
  check(head.hash === `#${LESSON}` && head.editors === 1 && head.title.length > 0,
    'レッスン画面（見出しとエディタ）が描ける', { hash: head.hash, title: head.title, editors: head.editors });
  check(head.submitLabel === '▶ 実行して採点', '提出ボタンが「実行して採点」になっている', head.submitLabel);
  check(head.starter.length > 0, 'エディタにひな形が入っている', head.starter.slice(0, 60));
  check(/残り\d+/.test(head.hintLabel), 'ヒントの残り件数が出ている', head.hintLabel);

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
             sameAsStarter: window.__t.editor().value === ${JSON.stringify(head.starter)} };
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

  const errors = await ev(`window.__jqErrors || []`);
  check(errors.length === 0, '画面のJavaScriptが例外を出していない', errors);

  close();
  if (failures > 0) {
    console.log(`\n${RED}学習画面の検査に失敗しました（${failures}件）${RESET}`);
    process.exit(1);
  }
  console.log(`\n${GREEN}LEARN UI OK: 誤答・コンパイルエラー・ヒント・模範解答・`
    + `★と報酬・自動保存・復習の出題を確認しました${RESET}`);
})().catch(e => {
  console.error(`${RED}検査を実行できませんでした: ${e.message}${RESET}`);
  process.exit(1);
});
