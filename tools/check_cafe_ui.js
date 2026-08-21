/*
 * カフェ画面をブラウザで実際に操作して確かめる。check-cafe-ui.sh から呼ばれる。
 *
 * 引数: <アプリのポート> <ChromeのCDPポート>
 *
 * 追加パッケージは使わない。node 24 には WebSocket が組み込まれているので、
 * Chrome DevTools Protocol を直接叩ける。
 *
 * 落とし穴を2つ踏んであるので、真似するときは注意する。
 *   ・同じURL（`#cafe` 付き）へ Page.navigate してもページは読み直されない
 *     （ハッシュ移動と見なされる）。about:blank を経由してから開く。
 *   ・タブのidは `cafeTabequipment` のように id をそのまま連結した形。
 *     大文字にすると見つからず、click が静かに失敗する。
 */
const PORT = process.argv[2];
const CDP = process.argv[3];
const GREEN = '[32m', RED = '[31m', RESET = '[0m';

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

(async () => {
  const { send, close } = connect(await pageTarget());
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  // 編集したJSが読み込まれないまま通ってしまわないように
  await send('Network.setCacheDisabled', { cacheDisabled: true });
  // 画面のJSより先に、例外を集める入れ物を用意する
  await send('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.__jqErrors = [];'
      + 'window.addEventListener("error", e => window.__jqErrors.push(String(e.message)));'
      + 'window.addEventListener("unhandledrejection", e => window.__jqErrors.push(String(e.reason)));'
  });

  await send('Page.navigate', { url: 'about:blank' });
  await sleep(300);
  await send('Page.navigate', { url: `http://localhost:${PORT}/#cafe` });
  await sleep(2500);

  const ev = async expression => {
    const r = await send('Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error((d.exception && d.exception.description) || JSON.stringify(d));
    }
    return r.result.value;
  };

  // ── 描画 ────────────────────────────────────────────────
  const head = await ev(`(() => {
    const q = s => document.querySelector(s);
    return { hash: location.hash,
             title: (q('.hero-title') || {}).textContent || '',
             coin: (q('#statCafe b') || {}).textContent || '',
             tabs: document.querySelectorAll('.cafe-workspace-tab').length };
  })()`);
  check(head.hash === '#cafe', 'カフェ画面を開ける', head.hash);
  check(head.title.length > 0, '店構えの称号が出る', head.title);
  check(head.coin.length > 0, 'ヘッダにコインが出る', head.coin);
  check(head.tabs === 3, 'タブが3つある', head.tabs);

  for (const tab of ['equipment', 'network', 'items']) {
    const panel = await ev(`(() => {
      const b = document.getElementById('cafeTab${tab}');
      if (!b) { return 'ボタンが無い'; }
      b.click();
      const p = document.getElementById('cafePanel${tab}');
      if (!p) { return 'パネルが無い'; }
      return { heading: (p.querySelector('h2, h3') || {}).textContent || '', nodes: p.querySelectorAll('*').length };
    })()`);
    await sleep(400);
    check(typeof panel === 'object' && panel.heading.length > 0 && panel.nodes > 10,
      `タブ「${tab}」が中身つきで描ける`, panel);
  }

  // ── 購入（CafeApi 経由で残高が正しく減るか）────────────────
  await ev(`document.getElementById('cafeTabequipment').click()`);
  await sleep(400);
  // 画面を開いている間は自動売上のtick（2.5秒ごと）が同時に加算する。差額はぴったりには
  // ならないので、測っている数秒ぶんの自動売上を許容差として見込む（＝アプリの正しい挙動）。
  const buy = await ev(`(async () => {
    const before = (await (await fetch('/api/state')).json()).progress.cafe;
    const btn = [...document.querySelectorAll('#cafePanelequipment .cafe-buy')].find(b => !b.disabled);
    if (!btn) { return { skipped: '買える設備が無い（進捗の用意が足りない）' }; }
    const id = btn.getAttribute('data-id');
    const target = before.upgrades.find(u => u.id === id);
    btn.click();
    await new Promise(r => setTimeout(r, 1500));
    const after = (await (await fetch('/api/state')).json()).progress.cafe;
    // 画面側が差分を当てているかも見る。再読み込みせずに、買った設備のボタンが
    // 「買える」状態から消えていること（＝delta が届いて描き直されたこと）を確かめる。
    const stillBuyable = [...document.querySelectorAll('#cafePanelequipment .cafe-buy')]
      .some(b => !b.disabled && b.getAttribute('data-id') === id);
    return { id, cost: target && target.cost, paid: before.cash - after.cash,
             passivePerMinute: before.passiveCashPerMinute,
             ownedBefore: before.ownedUpgrades.length, ownedAfter: after.ownedUpgrades.length,
             stillBuyable,
             toast: ((document.querySelector('.toast') || {}).innerText || '').replace(/\s+/g, ' ').trim() };
  })()`);
  if (buy.skipped) {
    check(false, '設備を購入できる', buy.skipped);
  } else {
    // 自動売上は最大でも「1分ぶん」しか入らない幅で測っているので、それを許容差にする
    const allowance = Math.ceil((buy.passivePerMinute || 0)) + 1;
    check(buy.paid <= buy.cost && buy.paid >= buy.cost - allowance,
      '購入で残高が価格ぶんだけ減る',
      { 価格: buy.cost, 減った額: buy.paid, 許容差: allowance + '（同時に入る自動売上のぶん）' });
    check(buy.ownedAfter === buy.ownedBefore + 1, '所持設備が1つ増える', buy);
    check(buy.toast.length > 0, '購入の通知が出る', buy.toast);
    check(buy.stillBuyable === false,
      '購入した設備が、再読み込みせずに買えない状態へ変わる（差分が画面へ届いている）',
      { 買った設備: buy.id, まだ買えるままか: buy.stillBuyable });
  }

  // ── 自動売上（tickが回って加算されるか）────────────────────
  const passive = await ev(`(async () => {
    const a = (await (await fetch('/api/state')).json()).progress.cafe;
    await new Promise(r => setTimeout(r, 3000));
    const b = (await (await fetch('/api/state')).json()).progress.cafe;
    return { perMinute: a.passiveCashPerMinute, gained: b.cash - a.cash };
  })()`);
  check(passive.perMinute > 0 && passive.gained > 0, '画面表示中の自動売上が加算される', passive);

  // ── 応答の形（画面が頼っている delta.progress が入っているか）──────
  //
  // ここは自動売上の加算を見たあとに置く。`cafe/passive/start` はセッションを張り替えるので、
  // 先にやると画面側のtickが止まり、上の検査が誤って落ちる（単一利用者向けの割り切り）。
  //
  // 画面は購入の応答に入る delta で残高と設備を描き直す。ここが落ちても、開いている間は
  // 自動売上のtick（2.5秒ごと）が同じ delta を運んでくるため画面は自力で復帰してしまい、
  // 見た目だけでは気づけない（実際にこの検査を作るとき、delta を落としても通ってしまった）。
  // そのため応答そのものを確かめる。
  const contract = await ev(`(async () => {
    const post = async (path, body) => {
      const res = await fetch('/api/' + path, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
      return { status: res.status, json: await res.json() };
    };
    const out = {};
    const cafe = (await (await fetch('/api/state')).json()).progress.cafe;
    const next = cafe.upgrades.find(u => u.available && !u.owned && u.cost <= cafe.cash);
    if (next) {
      const r = await post('cafe/purchase', { id: next.id });
      out['cafe/purchase'] = r.status === 200
        && typeof (((r.json.delta || {}).progress || {}).cafe || {}).cash === 'number';
    }
    for (const [path, body] of [['cafe/items/seen', {}],
                                ['cafe/passive/start', { sessionId: 'ui-check' }],
                                ['cafe/passive/stop', { sessionId: 'ui-check' }]]) {
      const r = await post(path, body);
      out[path] = r.status === 200
        && typeof (((r.json.delta || {}).progress || {}).cafe || {}).cash === 'number';
    }
    return out;
  })()`);
  for (const [path, ok] of Object.entries(contract)) {
    check(ok, `${path} の応答に delta.progress が入っている`);
  }


  // ── 復習手当スロットが設備タブに出ているか ────────────────
  //
  // 常連サービス系統（今日の1杯目）を差し替えた系統。系統の一覧は
  // `web/app.js` の trackOrder / effectLabel / EQUIPMENT_HELP の3か所に散っているので、
  // どれか1つを直し忘れると「スロットが出ない」「?の説明が販売戦略になる」で気づく。
  await ev(`location.hash = '#cafe'`);
  await sleep(1200);
  const reviewTrack = await ev(`(() => {
    const labels = [...document.querySelectorAll('#cafePanelequipment .upgrade-type')]
      .map(n => n.textContent.trim());
    const help = document.getElementById('equipmentHelp-review');
    const chips = [...document.querySelectorAll('#cafePanelequipment .cafe-effects span')]
      .map(n => n.textContent.trim());
    return {
      labels: labels,
      helpTitle: help ? (help.textContent || '').trim().slice(0, 40) : '',
      chip: chips.find(t => t.indexOf('復習報酬') === 0) || ''
    };
  })()`);
  check(reviewTrack.labels.indexOf('復習手当') >= 0,
    '設備タブに復習手当スロットが出ている', reviewTrack.labels);
  check(reviewTrack.labels.indexOf('常連サービス') < 0,
    '差し替えた常連サービスは残っていない', reviewTrack.labels);
  check(reviewTrack.helpTitle.indexOf('復習手当') >= 0,
    '「?」の説明が復習手当のものになっている', reviewTrack.helpTitle);
  check(reviewTrack.chip.length > 0, '概要に「復習報酬 +N%」が出ている', reviewTrack.chip);

  // ── 期限が来た問題を復習で通すとコインが入り、通知と履歴に残るか ──────
  //
  // この検査の進捗は「2026-08-10 にクリア」なので、40問すべて期限切れである
  // （初回の期限は翌日または3日後）。★は増えないので、**入るのはコインだけ**。
  // `check_learn_ui.js` の復習の節は、その場でクリアした問題＝期限前を見ているので
  // 「通知が出ない」側しか通らない。出る側はここで見る。
  // 自動売上が同時に動いているので、残高の差では測らない。**獲得の履歴に1件増えたか**を見る
  // （履歴の cash は、その回に払われた額そのもの）。
  const solutionCode = await ev(`(async () => {
    const r = await fetch('api/solution', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lessonId: '1-1', taskId: '1' })
    });
    return (await r.json()).solution || '';
  })()`);
  check(solutionCode.length > 0, '模範解答を取れた（この検査の前提）',
    solutionCode.slice(0, 40));

  const paid = await ev(`(async () => {
    const logOf = () => { try { return JSON.parse(localStorage.getItem('jq-coin-log') || '[]'); }
                          catch (e) { return []; } };
    const before = logOf().length;
    location.hash = '#review/1-1/1';
    for (let i = 0; i < 40 && !document.querySelector('#task-1 .editor-input'); i++) {
      await new Promise(r => setTimeout(r, 250));
    }
    const ta = document.querySelector('#task-1 .editor-input');
    if (!ta) { return { missing: true }; }
    ta.value = ${JSON.stringify(solutionCode)};
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    document.getElementById('submitBtn-1').click();
    for (let i = 0; i < 60; i++) {
      const card = document.querySelector('#result-1 .card-result');
      if (card && !card.querySelector('.spinner')) { break; }
      await new Promise(r => setTimeout(r, 250));
    }
    await new Promise(r => setTimeout(r, 900));
    const toast = document.getElementById('toast');
    const log = logOf();
    return {
      added: log.length - before,
      passed: !!document.querySelector('#result-1 .card-result.ok'),
      toastShown: toast.classList.contains('show'),
      toastText: (toast.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60),
      newest: log.length ? { reason: log[0].reason, cash: log[0].cash, star: !!log[0].newStar } : null
    };
  })()`);
  check(!paid.missing && paid.passed, '復習でも模範解答が合格になる（この検査の前提）', paid);
  check(paid.added === 1 && paid.newest && paid.newest.cash > 0,
    '期限が来た問題を復習で通すとコインが入る', paid);
  check(paid.toastShown && paid.toastText.indexOf('復習の注文') >= 0,
    '復習の報酬通知が出る', paid.toastText);
  check(paid.newest && paid.newest.reason === '復習の注文',
    '獲得の履歴に「復習の注文」として残る', paid.newest);
  check(paid.newest && !paid.newest.star, '★は付けない（増えたのはコインだけ）', paid.newest);

  // 同じ問題をもう一度通しても入らない（通した時点で期限が先へ動いている）
  const second = await ev(`(async () => {
    const logOf = () => { try { return JSON.parse(localStorage.getItem('jq-coin-log') || '[]'); }
                          catch (e) { return []; } };
    const before = logOf().length;
    document.getElementById('toast').classList.remove('show');
    document.getElementById('submitBtn-1').click();
    for (let i = 0; i < 60; i++) {
      const card = document.querySelector('#result-1 .card-result');
      if (card && !card.querySelector('.spinner')) { break; }
      await new Promise(r => setTimeout(r, 250));
    }
    await new Promise(r => setTimeout(r, 900));
    return {
      added: logOf().length - before,
      toastShown: document.getElementById('toast').classList.contains('show')
    };
  })()`);
  check(second.added === 0, '同じ日の2回目はコインが入らない', second);
  check(!second.toastShown, '2回目は通知も出ない', second);

  // ── 学習画面へ戻れるか（振り分けが壊れていないか）──────────
  await ev(`location.hash = '#1-1'`);
  await sleep(1500);
  const lesson = await ev(`(() => ({
    hash: location.hash,
    heading: ((document.querySelector('.lesson-h1') || document.querySelector('h1') || {}).textContent || '').trim(),
    editors: document.querySelectorAll('.editor-input').length
  }))()`);
  check(lesson.hash === '#1-1' && lesson.editors > 0, 'レッスン画面とエディタが描ける', lesson);

  const errors = await ev(`window.__jqErrors || []`);
  check(errors.length === 0, '画面のJavaScriptが例外を出していない', errors);

  close();
  if (failures > 0) {
    console.log(`\n${RED}カフェ画面の検査に失敗しました（${failures}件）${RESET}`);
    process.exit(1);
  }
  console.log(`\n${GREEN}CAFE UI OK: 描画・タブ・購入・自動売上・復習手当スロット・期限ぶんの復習報酬（通知と履歴）・学習画面への復帰を確認しました${RESET}`);
})().catch(e => {
  console.error(`${RED}検査を実行できませんでした: ${e.message}${RESET}`);
  process.exit(1);
});
