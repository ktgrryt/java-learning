/*
 * 学習画面をブラウザで実際に操作して確かめる。check-learn-ui.sh から呼ばれる。
 *
 * 引数: <アプリのポート> <ChromeのCDPポート>
 *
 * 見るのは「1問を解き切るまでの経路」全体である。
 *   誤答 → コンパイルエラー → ヒント → 模範解答 → 正解（★・コイン・通知）
 *   → 1問1枚のパネル（クリア済みの見分け）→ 獲得の履歴（消えた通知の読み返し）→ 自動保存
 *   → カフェへ寄り道して同じ位置で再開 → 復習 → クイズのしおり
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

  const errors = await ev(`window.__jqErrors || []`);
  check(errors.length === 0, '画面のJavaScriptが例外を出していない', errors);

  close();
  if (failures > 0) {
    console.log(`\n${RED}学習画面の検査に失敗しました（${failures}件）${RESET}`);
    process.exit(1);
  }
  console.log(`\n${GREEN}LEARN UI OK: 誤答・コンパイルエラー・ヒント・模範解答・`
    + `★と報酬・1問1枚のパネル・獲得の履歴・自動保存・カフェへの寄り道と位置の復元・復習の出題・`
    + `クイズのしおり・サイドバーの検索を確認しました${RESET}`);
})().catch(e => {
  console.error(`${RED}検査を実行できませんでした: ${e.message}${RESET}`);
  process.exit(1);
});
