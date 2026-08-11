/*
 * 画面の組み立てとサーバとのやり取り。
 *
 * 状態はサーバの /api/state が唯一の情報源。提出のたびにサーバが最新の state を
 * 返してくるので、画面はそれを受け取って描き直すだけにしている（画面側で★を
 * 数え直さないことで、リロードしてもズレない）。
 *
 * 画面はURLのハッシュで切り替える。
 *   #menu（または空） … 学習ホーム（章メニュー）
 *   #cafe              … Java Café（設備から段階的に経営要素を解放）
 *   #3-2 のようなID    … そのレッスン
 * レッスンの順番にロックはかけていない。どこからでも自由に開ける。
 */
(function () {
  'use strict';

  var md = window.JQMarkdown.render;
  var esc = window.JQHighlight.escapeHtml;
  var hlJava = window.JQHighlight.java;

  var state = null;        // サーバから受け取った最新の state
  var lessonIndex = {};    // レッスンID -> レッスン（setState で作る）
  var chapterOfLesson = {};// レッスンID -> 章
  var chapterIndex = {};   // 章ID -> 章
  var lessonList = [];     // 全レッスンを出題順に並べたもの
  var currentId = null;    // いま開いているレッスンID（ホーム／カフェ表示中は null）
  var currentView = 'menu'; // menu / cafe / lesson
  var editors = {};        // 問題ID -> エディタ（1レッスンに複数問あるので複数持つ）
  var saveTimers = {};     // 問題ID -> 自動保存のタイマー
  var busyTask = null;     // 実行・採点中の問題ID（同時に走らせない）
  var sideExpanded = {};   // サイドバーで開いている章のID（既定は全部たたむ）
  var activePartId = null; // メニューで表示中の大区分（Java基礎編 / Web・Jakarta EE編など）
  var selectedChapterByPart = {}; // ホームの編ごとに、最後に見ていた章を覚える
  var activeCafeSection = 'equipment'; // equipment / network / items
  var cafePassiveTimer = null;
  var cafePassiveSessionId = null;
  var cafePassiveBusy = false;
  var cafeItemsSeenBusy = false;
  var CAFE_PASSIVE_INTERVAL_MS = 2500;

  // 店舗シーンのSVGを載せる要素。カフェ画面を描き直すたびに作り直すのではなく、
  // 一度作ったものを使い回して新しい置き場所へ移し替える。
  // 設備を1つ買うたびに絵が作り直されると、湯気や電飾のアニメーションが
  // そのたびに頭へ戻ってしまい、画面がちらついて見えるため。
  var cafeSceneNode = null;
  var pendingCafeScene = null;

  // ---------------------------------------------------------------- 通信

  function api(path, payload) {
    var options = payload === undefined
      ? { method: 'GET' }
      : {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        };
    return fetch('/api/' + path, options).then(function (res) {
      // 応答がJSONでないこともある（サーバが落ちた、間にプロキシが挟まった、など）。
      // res.json() をそのまま信じると、そこで出た構文エラーがそのまま画面へ出て
      // 「Unexpected token」のような、利用者に何もできないメッセージになる。
      // 本文を文字列で受けてから解釈し、読めなければ状態コードで案内する。
      return res.text().then(function (body) {
        var data = null;
        try { data = body ? JSON.parse(body) : null; } catch (e) { data = null; }
        if (!res.ok) {
          throw new Error((data && data.error)
            || ('通信に失敗しました (' + res.status + ')'));
        }
        if (data === null) {
          throw new Error('サーバの応答を読めませんでした (' + res.status + ')');
        }
        return data;
      });
    });
  }

  // ------------------------------------------------------- state のとり回し

  /**
   * state を差し替え、レッスンIDから引くための索引を作り直す。
   *
   * 索引を持たないと、レッスン1件を引くたびに全章・全レッスンを走査することになる。
   * サイドバーは253レッスンぶんの行を毎回作り、その各行が `displayLessonId` から
   * `chapterOf` を呼ぶので、1回の描画で数万回の走査になっていた。
   * state が変わるのは読み込み時とリセット時だけなので、そのときに1回作れば足りる。
   */
  function setState(data) {
    state = data;
    lessonIndex = {};
    chapterOfLesson = {};
    chapterIndex = {};
    lessonList = [];
    state.chapters.forEach(function (ch) {
      chapterIndex[ch.id] = ch;
      ch.lessons.forEach(function (l) {
        lessonIndex[l.id] = l;
        chapterOfLesson[l.id] = ch;
        lessonList.push(l);
      });
    });
  }

  /**
   * 全レッスンを出題順に並べたもの。
   *
   * 毎回作り直さず {@code setState} で作った配列をそのまま返す。
   * <b>戻り値を書き換えないこと</b>（索引と同じ配列なので、並べ替えたり
   * 要素を足すと他の参照も一緒に狂う）。並べ替えたいときは `slice()` で写す。
   */
  function allLessons() {
    return lessonList;
  }

  function findLesson(id) {
    return lessonIndex[id] || null;
  }

  function chapterOf(id) {
    return chapterOfLesson[id] || null;
  }

  /** manifestで定義した大区分。古いstateに対しては全章を1区分として扱う。 */
  function curriculumParts() {
    if (state.parts && state.parts.length) { return state.parts; }
    return [{
      id: 'main', title: 'カリキュラム', subtitle: '', emoji: '📚',
      chapterIds: state.chapters.map(function (ch) { return ch.id; })
    }];
  }

  function partOfChapter(chapter) {
    var found = null;
    curriculumParts().forEach(function (part) {
      if (part.chapterIds.indexOf(chapter.id) >= 0) { found = part; }
    });
    return found;
  }

  function chaptersOfPart(part) {
    return state.chapters.filter(function (ch) { return part.chapterIds.indexOf(ch.id) >= 0; });
  }

  /** 章番号は編ごとに1から振る。古いstateでは従来の通し番号へ戻す。 */
  function displayChapterNumber(chapter) {
    return chapter.partNumber || chapter.number;
  }

  /** 保存用ID（21-1など）は変えず、画面では編内の番号（1-1など）を見せる。 */
  function displayLessonId(lesson) {
    var chapter = chapterOf(lesson.id);
    var dash = lesson.id.indexOf('-');
    if (!chapter || dash < 0) { return lesson.id; }
    return displayChapterNumber(chapter) + lesson.id.substring(dash);
  }

  function chapterById(id) {
    return chapterIndex[id] || null;
  }

  /**
   * 教材に書かれた章番号を、画面の表示番号へ読み替える。
   *
   * 教材は `第43章` のようにファイル名の番号（content/ch43-*.json）で参照を書く。
   * 一方で画面の章番号は編ごとに1から振り直すので、そのまま出すと存在しない番号に
   * なってしまう（基礎編に「第43章」は無い）。ここでファイル名から実際の章を引き、
   * その章の表示番号へ直す。別の編を指しているときだけ編名を前に付ける
   * （同じ編の中では番号だけで足りるし、毎回編名が付くと読みにくい）。
   *
   * 対応する章が無い番号は、読み替えずそのまま残す（誤った番号を作らないため）。
   */
  function localizeChapterReferences(text) {
    var here = currentId && chapterOf(currentId);
    if (!here) { return text; }
    return String(text || '').replace(/第(\d+)章/g, function (whole, rawNumber) {
      var target = chapterById('ch' + (rawNumber.length < 2 ? '0' + rawNumber : rawNumber));
      if (!target) { return whole; }
      var shown = '第' + displayChapterNumber(target) + '章';
      if (target.partId === here.partId) { return shown; }
      var part = partOfChapter(target);
      return part ? part.title + ' ' + shown : shown;
    });
  }

  function renderMarkdown(text) {
    return md(localizeChapterReferences(text));
  }

  function partProgress(part) {
    var chapters = chaptersOfPart(part);
    return chapters.reduce(function (result, ch) {
      result.cleared += ch.clearedCount;
      result.total += ch.taskCount;
      return result;
    }, { cleared: 0, total: 0 });
  }

  /** 全レッスンの全問題を平らに並べたもの（★の分母や集計に使う）。 */
  function allTasks() {
    var list = [];
    allLessons().forEach(function (l) {
      l.tasks.forEach(function (t) { list.push(t); });
    });
    return list;
  }

  function findTask(lesson, taskId) {
    var found = null;
    lesson.tasks.forEach(function (t) { if (t.id === taskId) { found = t; } });
    return found;
  }

  function nextLesson(id) {
    var lessons = allLessons();
    for (var i = 0; i < lessons.length; i++) {
      if (lessons[i].id === id) {
        return i + 1 < lessons.length ? lessons[i + 1] : null;
      }
    }
    return null;
  }

  /** まだクリアしていない先頭のレッスン。全部終わっていれば最後のレッスン。 */
  function firstTodo() {
    var lessons = allLessons();
    for (var i = 0; i < lessons.length; i++) {
      if (!lessons[i].cleared) { return lessons[i].id; }
    }
    return lessons.length ? lessons[lessons.length - 1].id : null;
  }

  /**
   * 「続ける」で開くレッスン。
   * 前回いた場所がまだ未クリアならそこへ戻す（中断したところから再開できるように）。
   * すでにクリア済みなら、次にやるべきレッスンへ進める。
   */
  function resumeTarget() {
    var saved = null;
    try { saved = localStorage.getItem('jq-last-lesson'); } catch (e) { /* 使えなくても困らない */ }
    var lesson = saved && findLesson(saved);
    if (lesson && !lesson.cleared) { return saved; }
    return firstTodo();
  }

  /**
   * 提出・クイズ回答の応答に入っている差分を、手元の state に上書きする。
   *
   * サーバはカリキュラム全体（解説やサンプル込みで約500KB）を返さず、
   * 変わったところ＝進捗まわりだけを返す。解説文などは最初の /api/state で
   * 受け取ったものをそのまま使い続ける。
   */
  function applyDelta(delta) {
    if (!delta) { return; }
    if (delta.progress) { state.progress = delta.progress; }
    if (typeof delta.quizCorrect === 'number') { state.quizCorrect = delta.quizCorrect; }

    var chapterUpdates = {};
    (delta.chapters || []).forEach(function (c) { chapterUpdates[c.id] = c; });
    state.chapters.forEach(function (ch) {
      var u = chapterUpdates[ch.id];
      if (u) {
        ch.cleared = u.cleared;
        ch.clearedCount = u.clearedCount;
      }
    });

    var lessonUpdates = {};
    (delta.lessons || []).forEach(function (l) { lessonUpdates[l.id] = l; });
    allLessons().forEach(function (l) {
      var u = lessonUpdates[l.id];
      if (u) {
        l.cleared = u.cleared;
        l.clearedCount = u.clearedCount;
      }
    });

    // ★・通過ケース数・ヒント開示数は問題ごとに持っている
    var taskUpdates = {};
    (delta.tasks || []).forEach(function (t) { taskUpdates[t.lessonId + '#' + t.taskId] = t; });
    allLessons().forEach(function (l) {
      l.tasks.forEach(function (t) {
        var u = taskUpdates[l.id + '#' + t.id];
        if (u) {
          t.cleared = u.cleared;
          t.passedCount = u.passedCount;
          t.hintsRevealed = u.hintsRevealed;
          t.solutionUnlocked = u.solutionUnlocked;
        }
      });
    });

    // 差分の対象になったレッスンだけ、クイズの回答状況も更新する
    if (delta.lessonId) {
      var target = findLesson(delta.lessonId);
      if (target && delta.quizResults) { target.quizResults = delta.quizResults; }
    }
  }

  function sumValues(obj) {
    var total = 0;
    Object.keys(obj || {}).forEach(function (k) { total += obj[k]; });
    return total;
  }

  function cafeState() {
    return state.progress.cafe || {
      cash: 0, cups: 0, cupPrice: 500, bonusPercent: 0, salesBonusPercent: 0,
      streakBonusPercent: 0, extraCups: 0, chapterBonusPercent: 0,
      quizTipPercent: 0, clearedChapters: 0, brandMultiplierBasisPoints: 10000,
      storeCount: 1, maxStores: 512, nextStoreGain: 1, nextStoreCount: 2,
      storeLimit: 1, nextStoreUnlockStars: 4,
      expansionCost: null, lifetimeCash: 0, ownedItems: [], items: [],
      unseenItemCount: 0,
      ownedUpgrades: [], upgrades: [], ownedAutomation: [], automation: [],
      passiveCashPerMinute: 0, passiveRateBasisPoints: 0,
      passiveCashCap: 0, passiveCashRemaining: 0
    };
  }

  /** 最初の出店枠（現行は★4）が解放されるまで、店舗経営は画面に出さない。 */
  function cafeNetworkUnlocked() {
    var cafe = cafeState();
    return Number(cafe.storeCount || 1) > 1 || Number(cafe.storeLimit || 1) > 1;
  }

  function numberText(value) {
    return Number(value || 0).toLocaleString('ja-JP');
  }

  /** 億・兆まで膨らむカフェ数値を、カードやボタンからはみ出さず読める形にする。 */
  function cafeNumberText(value) {
    var number = Number(value || 0);
    if (Math.abs(number) < 100000000) { return numberText(number); }
    return number.toLocaleString('ja-JP', {
      notation: 'compact',
      maximumFractionDigits: 2
    });
  }

  /**
   * 「12,345コイン / 分」のような単位表記。
   * 単位は途中で改行させず、幅が足りないときだけ数値との間で折り返す。
   */
  function perMinuteText(amount, unit) {
    return amount + '<span class="per-minute-unit">' + (unit || '') + ' / 分</span>';
  }

  function multiplierText(basisPoints) {
    return (Number(basisPoints || 10000) / 10000).toLocaleString('ja-JP', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  /** ★の進行から決まる店の外観。最後の段階だけ上限なし。 */
  function cafeLevel() {
    var cafe = cafeState();
    return {
      level: cafe.level || 1,
      title: cafe.levelTitle || '屋台カフェ',
      threshold: cafe.levelThreshold || 0,
      next: cafe.nextLevelStars == null ? null : cafe.nextLevelStars,
      cupsPerOrder: cafe.cupsPerOrder || 1
    };
  }

  // ------------------------------------------------------------ ヘッダ描画

  function renderHeader() {
    var stars = state.progress.starCount;
    document.querySelector('#statStars b').textContent = stars;
    document.querySelector('#statStreak b').textContent = state.progress.streak;
    document.querySelector('#statCafe b').textContent = cafeNumberText(cafeState().cash);

    var learningBtn = document.getElementById('learningBtn');
    var cafeBtn = document.getElementById('cafeBtn');
    var learningActive = currentView !== 'cafe';
    learningBtn.classList.toggle('active', learningActive);
    cafeBtn.classList.toggle('active', !learningActive);
    cafeBtn.classList.toggle('has-notification', Number(cafeState().unseenItemCount || 0) > 0);
    learningBtn.setAttribute('aria-current', learningActive ? 'page' : 'false');
    cafeBtn.setAttribute('aria-current', learningActive ? 'false' : 'page');
    cafeBtn.setAttribute('aria-label', Number(cafeState().unseenItemCount || 0) > 0
      ? 'カフェ（新しいアイテムがあります）' : 'カフェ');
  }

  // -------------------------------------------------------- サイドバー描画

  function renderSidebar() {
    var nav = document.getElementById('sidebar');
    nav.innerHTML = '';

    var lastPartId = null;
    state.chapters.forEach(function (ch) {
      var part = partOfChapter(ch);
      if (part && part.id !== lastPartId) {
        var currentPart = chaptersOfPart(part).some(function (partChapter) {
          return partChapter.lessons.some(function (l) { return l.id === currentId; });
        });
        var progress = partProgress(part);
        var partStatus = progress.cleared === progress.total && progress.total > 0
          ? '✓' : (progress.cleared > 0 ? '学習中' : '');
        var partHead = document.createElement('div');
        partHead.className = 'side-part-head' + (currentPart ? ' current' : '');
        partHead.innerHTML =
          '<span class="side-part-emoji">' + esc(part.emoji) + '</span>' +
          '<span class="side-part-title">' + esc(part.title) + '</span>' +
          '<span class="side-part-count">' + partStatus + '</span>';
        nav.appendChild(partHead);
        lastPartId = part.id;
      }
      var isCurrentChapter = ch.lessons.some(function (l) { return l.id === currentId; });

      // 章は既定でたたんでおく。ただし自分で開閉していない章のうち、
      // いま開いているレッスンの章だけは現在地が分かるように開いておく。
      if (sideExpanded[ch.id] === undefined && isCurrentChapter) {
        sideExpanded[ch.id] = true;
      }
      var open = !!sideExpanded[ch.id];

      var section = document.createElement('section');
      section.className = 'ch'
        + (ch.cleared ? ' ch-cleared' : '')
        + (isCurrentChapter ? ' ch-current' : '');

      var status = ch.cleared
        ? '<span class="ch-done">✅</span>'
        : (ch.clearedCount ? '<span class="ch-count">学習中</span>' : '');

      section.innerHTML =
        '<button type="button" class="ch-head" aria-expanded="' + open + '">' +
        '  <span class="ch-emoji">' + esc(ch.emoji) + '</span>' +
        '  <span class="ch-titles">' +
        '    <span class="ch-title">第' + displayChapterNumber(ch) + '章　' + esc(ch.title) + '</span>' +
        '    <span class="ch-sub">' + esc(ch.subtitle) + '</span>' +
        '  </span>' +
        '  <span class="ch-status">' + status + '</span>' +
        '  <span class="ch-caret">' + (open ? '▲' : '▼') + '</span>' +
        '</button>';

      var ul = document.createElement('ul');
      ul.className = 'lessons';
      if (!open) { ul.hidden = true; }
      ch.lessons.forEach(function (l) {
        var li = document.createElement('li');
        li.className = 'lesson'
          + (l.cleared ? ' lesson-cleared' : '')
          + (l.id === currentId ? ' lesson-current' : '');
        li.innerHTML =
          '<span class="lesson-mark">' + (l.cleared ? '★' : '○') + '</span>' +
          '<span class="lesson-id">' + esc(displayLessonId(l)) + '</span>' +
          '<span class="lesson-title">' + esc(l.title) + '</span>' +
          lessonTaskProgress(l);
        li.title = lessonTooltip(l);
        li.addEventListener('click', function () { selectLesson(l.id); });
        ul.appendChild(li);
      });
      section.appendChild(ul);

      section.querySelector('.ch-head').addEventListener('click', function () {
        sideExpanded[ch.id] = !open;
        renderSidebar();
      });

      nav.appendChild(section);
    });
  }

  /**
   * 複数問あるレッスンも分母は見せず、途中なら現在の状態だけを示す。
   */
  function lessonTaskProgress(lesson) {
    if (lesson.taskCount < 2 || lesson.cleared) { return ''; }
    return lesson.clearedCount ? '<span class="lesson-frac">学習中</span>' : '';
  }

  function lessonTooltip(lesson) {
    if (lesson.cleared) { return 'クリア済み: ' + lesson.title; }
    return lesson.clearedCount ? lesson.title + '（学習中）' : lesson.title;
  }

  // -------------------------------------------------------- 学習ホーム描画

  function renderMenu() {
    var total = state.totalTasks;
    var stars = state.progress.starCount;
    var done = stars === total;

    var main = document.getElementById('content');
    main.innerHTML =
      '<div class="menu home-page">' +
      renderLearningHero(stars, done) +
      renderMenuStats() +
      '  <section class="menu-section curriculum-section">' +
      '    <header class="section-heading">' +
      '      <div><span class="screen-eyebrow">CURRICULUM</span>' +
      '      <h2 class="menu-h2">章を選ぶ</h2></div>' +
      '      <p class="menu-note">左で章を選び、右からレッスンへ進めます。</p>' +
      '    </header>' +
      '    <div class="part-tabs" id="partTabs" role="tablist" aria-label="カリキュラムの編"></div>' +
      '    <div class="chapter-browser">' +
      '      <nav class="chapter-list" id="chGrid" aria-label="章一覧"></nav>' +
      '      <section class="chapter-detail" id="chapterDetail"></section>' +
      '    </div>' +
      '  </section>' +
      renderMenuGuide(stars) +
      '  <footer class="home-utilities"><span>学習データはこの端末に保存されています。</span>' +
      '  <button class="ghost-btn" id="resetBtn" type="button">進捗をリセット</button></footer>' +
      '</div>';

    renderChapterCards();

    var goBtn = document.getElementById('continueBtn');
    if (goBtn) {
      goBtn.addEventListener('click', function () { selectLesson(goBtn.dataset.target); });
    }
    document.getElementById('resetBtn').addEventListener('click', resetProgress);
    main.scrollTop = 0;
  }

  /** 学習状況と「続ける」ボタン。章メニューの入口として簡潔に見せる。 */
  function renderLearningHero(stars, done) {
    var target = resumeTarget();
    var lesson = findLesson(target);
    var label = done ? '🏆 もう一度見なおす' : (stars === 0 ? '▶ はじめる' : '▶ 続ける');
    var lead = done ? '全レッスン制覇！ おつかれさまでした'
      : (stars === 0 ? 'まずはここから' : '前回の続きはここから');

    return '' +
      '<section class="menu-hero learning-hero">' +
      '  <div class="hero-milestone" aria-label="これまでに' + stars + '問クリア">' +
      '    <span>★</span><b>' + stars + '</b><small>問クリア</small>' +
      '  </div>' +
      '  <div class="hero-body">' +
      '    <span class="screen-eyebrow">TODAY\'S LEARNING</span>' +
      '    <h1 class="hero-title">Javaを学ぶ</h1>' +
      '    <p class="hero-sub">手を動かして身につける · 自分のペースで一問ずつ</p>' +
      '    <div class="hero-action">' +
      '      <div class="hero-next"><div class="cta-lead">' + esc(lead) + '</div>' +
      (lesson ? '      <div class="cta-target"><span class="cta-id">' + esc(displayLessonId(lesson)) + '</span>'
        + esc(lesson.title) + '</div>' : '') + '</div>' +
      '      <button class="primary-btn big" id="continueBtn" data-target="' + esc(target) + '">' +
             esc(label) +
      '      </button>' +
      '    </div>' +
      '  </div>' +
      '</section>';
  }

  // ---------------------------------------------------------- カフェ描画

  function renderCafe(preserveScroll) {
    var stars = state.progress.starCount;
    var main = document.getElementById('content');
    var previousScroll = preserveScroll ? main.scrollTop : 0;
    main.innerHTML =
      '<div class="menu cafe-page">' +
      '  <header class="screen-heading">' +
      '    <div><span class="screen-eyebrow">LEARNING REWARDS</span>' +
      '    <h1>☕ Java Café</h1>' +
      '    <p>問題を解いて得たコインで、あなただけのカフェを育てましょう。</p></div>' +
      '    <button class="ghost-btn screen-back" id="backToLearningBtn">📚 章を選ぶ</button>' +
      '  </header>' +
      renderCafeHero(stars) +
      renderCafeWorkspace() +
      '</div>';

    document.getElementById('backToLearningBtn').addEventListener('click', goHome);
    var expandBtn = document.getElementById('cafeExpandBtn');
    if (expandBtn) { expandBtn.addEventListener('click', expandCafeNetwork); }
    Array.prototype.forEach.call(document.getElementsByClassName('cafe-item-buy'), function (btn) {
      btn.addEventListener('click', function () { purchaseCafeItem(btn.dataset.id); });
    });
    Array.prototype.forEach.call(document.getElementsByClassName('equipment-upgrade-btn'), function (btn) {
      btn.addEventListener('click', function () { purchaseCafeUpgrade(btn.dataset.id); });
    });
    Array.prototype.forEach.call(document.getElementsByClassName('cafe-automation-buy'), function (btn) {
      btn.addEventListener('click', function () { purchaseCafeAutomation(btn.dataset.id); });
    });
    var cafeTabs = Array.prototype.slice.call(document.getElementsByClassName('cafe-workspace-tab'));
    cafeTabs.forEach(function (button, index) {
      button.addEventListener('click', function () { selectCafeSection(button.dataset.section); });
      button.addEventListener('keydown', function (event) {
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') { return; }
        event.preventDefault();
        var direction = event.key === 'ArrowRight' ? 1 : -1;
        var next = cafeTabs[(index + direction + cafeTabs.length) % cafeTabs.length];
        selectCafeSection(next.dataset.section);
        next.focus();
      });
    });
    mountCafeScene();
    if (activeCafeSection === 'items') { acknowledgeCafeItems(); }
    main.scrollTop = previousScroll;
  }

  /**
   * 店舗シーンを置き場所へ移し、必要なら描き直す。
   * すでにDOMにある要素を insertBefore すると「移動」になるので、
   * 絵とアニメーションは切れずにそのまま新しい親へ移る。
   */
  function mountCafeScene() {
    var slot = document.getElementById('cafeSceneSlot');
    if (!slot || !pendingCafeScene) { return; }

    if (!cafeSceneNode) {
      cafeSceneNode = document.createElement('div');
      cafeSceneNode.className = 'cafe-scene';
      cafeSceneNode.setAttribute('role', 'img');
    }
    // 導入設備の帯より下に入れる（絵に覆われないように）
    slot.insertBefore(cafeSceneNode, slot.firstChild);
    cafeSceneNode.setAttribute('aria-label', pendingCafeScene.label);
    CafeScene.render(cafeSceneNode, pendingCafeScene);
  }

  function selectCafeSection(section) {
    if (section === 'network' && !cafeNetworkUnlocked()) { section = 'equipment'; }
    if (section === 'items' && !(cafeState().items || []).length) { section = 'equipment'; }
    activeCafeSection = section;
    Array.prototype.forEach.call(document.getElementsByClassName('cafe-workspace-tab'), function (button) {
      var active = button.dataset.section === section;
      button.classList.toggle('active', active);
      button.setAttribute('aria-selected', active ? 'true' : 'false');
      button.tabIndex = active ? 0 : -1;
    });
    Array.prototype.forEach.call(document.getElementsByClassName('cafe-tab-panel'), function (panel) {
      panel.hidden = panel.dataset.section !== section;
    });
    if (section === 'items') { acknowledgeCafeItems(); }
  }

  /** アイテムタブを開いた時点で通知を既読にする。カードのNEW表示は現在の描画中だけ残す。 */
  function acknowledgeCafeItems() {
    if (cafeItemsSeenBusy || !(cafeState().unseenItemCount > 0)) { return; }
    cafeItemsSeenBusy = true;
    api('cafe/items/seen', {})
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        var tab = document.getElementById('cafeTabitems');
        if (tab) { tab.classList.remove('has-notification'); }
      })
      .catch(function () { /* 次にアイテム画面を開いたとき再試行する */ })
      .finally(function () { cafeItemsSeenBusy = false; });
  }

  function renderCafeWorkspace() {
    var cafe = cafeState();
    var items = cafe.items || [];
    var upgrades = cafe.upgrades || [];
    var networkUnlocked = cafeNetworkUnlocked();
    if (!networkUnlocked && activeCafeSection === 'network') { activeCafeSection = 'equipment'; }
    if (!items.length && activeCafeSection === 'items') { activeCafeSection = 'equipment'; }
    var ownedItems = items.filter(function (item) { return item.owned; }).length;
    var equipped = upgrades.filter(function (upgrade) { return upgrade.equipped; }).length;
    var activeAutomation = (cafe.automation || []).some(function (item) { return item.equipped; });

    function tab(id, icon, label, value, notify) {
      var active = activeCafeSection === id;
      return '<button type="button" class="cafe-workspace-tab' + (active ? ' active' : '')
        + (notify ? ' has-notification' : '')
        + '" id="cafeTab' + id + '" role="tab" aria-selected="' + active + '" aria-controls="cafePanel'
        + id + '" tabindex="' + (active ? '0' : '-1') + '" data-section="' + id + '">' +
        '<span class="cafe-workspace-icon">' + icon + '</span><span><strong>' + label + '</strong><small>'
        + value + '</small></span></button>';
    }

    return '<section class="menu-section cafe-workspace" aria-label="カフェ経営メニュー">' +
      '<nav class="cafe-workspace-nav' + (!networkUnlocked ? ' only-equipment' : '')
        + (items.length ? ' has-items' : '')
        + '" role="tablist" aria-label="経営メニュー">' +
      tab('equipment', '⚙️', '設備', (equipped + (activeAutomation ? 1 : 0))
        ? (equipped + (activeAutomation ? 1 : 0)) + '系統が稼働中' : 'まだ未導入') +
      (networkUnlocked ? tab('network', '🏪', '店舗', (cafe.storeCount || 1) > 1
        ? numberText(cafe.storeCount) + '店舗を営業中' : '新しく解放') : '') +
      (items.length ? tab('items', '🎁', 'アイテム', ownedItems
        ? numberText(ownedItems) + '個を所持' : '新しく解放', cafe.unseenItemCount > 0) : '') +
      '</nav>' +
      '<div class="cafe-workspace-body">' + (networkUnlocked ? renderCafeExpansion() : '')
        + (items.length ? renderCafeItems() : '') + renderCafeShop() + '</div>' +
      '</section>';
  }

  /** 問題を解くほど育つ、現在の店舗の様子。 */
  function renderCafeHero(stars) {

    var cafe = cafeState();
    var networkUnlocked = cafeNetworkUnlocked();
    var level = cafeLevel();
    var owned = cafe.ownedUpgrades || [];
    var equippedUpgrades = (cafe.upgrades || []).filter(function (item) {
      return item.equipped;
    });
    var equippedIds = equippedUpgrades.map(function (item) { return item.id; });
    // 全60設備に合わせ、終盤のRank 11〜12まで内装の成長を残す。
    var furnishing = owned.length >= 58 ? 8
      : owned.length >= 48 ? 7
      : owned.length >= 38 ? 6
      : owned.length >= 28 ? 5
      : owned.length >= 16 ? 4
      : owned.length >= 9 ? 3
      : owned.length >= 4 ? 2
      : owned.length >= 1 ? 1 : 0;
    // Lv.8以降もLv.7の完成した建物を土台に、配色と終盤演出だけを変える。
    var structure = Math.min(level.level, CafeScene.maxStructure);
    var levelPct = level.next
      ? Math.round((stars - level.threshold) / (level.next - level.threshold) * 100)
      : 100;
    levelPct = Math.max(0, Math.min(100, levelPct));
    var equipment = equippedUpgrades
      .map(function (item) {
        return '<span title="' + esc(item.name) + '">' + esc(item.emoji) + '</span>';
      }).join('');
    var orderCups = cafe.orderCups == null
      ? (level.cupsPerOrder + (cafe.extraCups || 0)) * (cafe.storeCount || 1)
      : cafe.orderCups;
    var nextOrderCash = cafe.nextOrderCash == null
      ? Math.floor(orderCups * cafe.cupPrice * (100 + cafe.bonusPercent) / 100)
      : cafe.nextOrderCash;
    var passiveLabel = cafe.passiveCashPerMinute > 0
      ? '表示中の自動売上 · 次の★まで残り '
        + cafeNumberText(cafe.passiveCashRemaining) + 'コイン'
      : '表示中の自動売上';
    var finalLevelMessage = Number(cafe.storeCount || 1) >= Number(cafe.maxStores || 512)
      ? 'Java学習と店舗ネットワークを制覇しました'
      : '店構えは最高ランクです。次は店舗網を広げましょう';
    var orderMetricLabel = stars >= Number(state.totalTasks || 0) ? '最高注文' : '次の注文';

    // 絵そのものは cafe-scene.js が1枚のSVGとして描く。ここでは置き場所だけ用意して、
    // 描画は mountCafeScene() が担当する（毎回作り直すとアニメーションが頭に戻るため）。
    pendingCafeScene = {
      level: level.level,
      structure: structure,
      interior: furnishing,
      storeCount: cafe.storeCount || 1,
      equippedIds: equippedIds,
      // setAttribute で渡すので、ここはHTMLエスケープしない（するとそのまま読まれる）
      label: '現在のJava Café。' + level.title + '（店構えLv.' + level.level
        + '・内装' + furnishing + '）。装備中の設備' + equippedUpgrades.length + '点'
    };

    return '' +
      '<section class="menu-hero cafe-hero">' +
      '  <div class="cafe-scene-slot" id="cafeSceneSlot">' +
      (equipment ? '<div class="cafe-equipment"><small>導入設備</small>' + equipment + '</div>' : '') +
      '  </div>' +
      '  <div class="hero-body">' +
      '    <div class="cafe-level-label">SHOP Lv.' + level.level + ' · INTERIOR ' + furnishing + '</div>' +
      '    <h1 class="hero-title">' + esc(level.title) + '</h1>' +
      '    <div class="cafe-balance" title="正確な残高 ' + numberText(cafe.cash) + 'コイン">' +
      '      <small>現在使えるコイン</small><b>' + cafeNumberText(cafe.cash) + '<em>コイン</em></b>' +
      '    </div>' +
      '    <div class="cafe-key-metrics' + (networkUnlocked ? '' : ' equipment-only') + '">' +
      (networkUnlocked
        ? '      <span><small>営業中</small><b>🏪 ' + numberText(cafe.storeCount || 1) + '店舗</b></span>'
        : '') +
      '      <span><small>' + orderMetricLabel + '</small><b>' + numberText(orderCups) + '杯 · 約'
               + cafeNumberText(nextOrderCash) + 'コイン</b></span>' +
      '      <span class="cafe-passive-metric"><small>' + passiveLabel + '</small><b id="cafePassiveLive">'
               + (cafe.passiveCashPerMinute > 0
                 ? perMinuteText('⏱️ +' + cafeNumberText(cafe.passiveCashPerMinute), 'コイン')
                 : '⏱️ 未導入') + '</b></span>' +
      '    </div>' +
      '    <div class="cafe-meta-row">' +
      '      <span>☕ 累計 ' + numberText(cafe.cups) + '杯</span>' +
      (networkUnlocked
        ? '      <span>ブランド ×' + multiplierText(cafe.brandMultiplierBasisPoints) + '</span>'
        : '') +
      '      <span>★ ' + numberText(stars) + '</span>' +
      '    </div>' +
      '    <div class="cafe-level-progress"><i style="width:' + levelPct + '%"></i></div>' +
      '    <p class="cafe-next-level">' + (level.next
              ? '次の店構えまであと ★' + numberText(level.next - stars)
              : finalLevelMessage) + '</p>' +
      '  </div>' +
      '</section>';
  }

  /** 繰り返し投資でき、1回ごとの出店数も増えていくチェーン展開。 */
  function renderCafeExpansion() {
    var cafe = cafeState();
    var storeCount = cafe.storeCount || 1;
    var expansionDiscount = (cafe.items || []).some(function (item) {
      return item.owned && item.effectType === 'expansion_discount';
    });
    var maximum = storeCount >= (cafe.maxStores || 512);
    var progressLocked = !maximum && cafe.expansionCost == null;
    var canExpand = !maximum && !progressLocked;
    var affordable = canExpand && cafe.cash >= cafe.expansionCost;
    var buttonText = maximum ? '最大ネットワーク達成'
      : (progressLocked ? '★' + numberText(cafe.nextStoreUnlockStars) + 'で次の出店枠を解放'
        : '+' + numberText(cafe.nextStoreGain) + '店舗を出店 · '
          + cafeNumberText(cafe.expansionCost) + 'コイン');

    return '<section class="cafe-tab-panel cafe-expansion" id="cafePanelnetwork" role="tabpanel"'
      + ' aria-labelledby="cafeTabnetwork" data-section="network"'
      + (activeCafeSection === 'network' ? '' : ' hidden') + '>' +
      '<header class="cafe-section-heading"><div><span class="screen-eyebrow">FRANCHISE GROWTH</span>' +
      '<h2>店舗ネットワーク</h2><p>出店して、1回の学習でもらえる報酬を育てます。</p></div>' +
      '<span class="cafe-section-status"><b>' + numberText(storeCount) + '</b>店舗'
        + (canExpand ? '<small>次は +' + numberText(cafe.nextStoreGain) + '店舗</small>'
          : (!maximum ? '<small>現在の上限 ' + numberText(cafe.storeLimit) + '店舗</small>' : '')) + '</span></header>' +
      '<div class="cafe-expansion-content">' +
      '<div class="cafe-expansion-copy">' +
      '<p class="menu-note">全店舗に設備効果が乗るため、出店するほど1回の学習報酬が大きくなります。'
        + '出店枠は★で段階的に解放され、ブランド倍率は完成した章の問題数に応じて育ちます。</p>' +
      '<div class="cafe-expansion-stats">' +
      '<span><small>現在</small><b>' + numberText(storeCount) + '店舗</b></span>' +
      '<span><small>店舗倍率</small><b>×' + numberText(storeCount) + '</b></span>' +
      '<span><small>現在の出店上限</small><b>' + numberText(cafe.storeLimit || 1) + '店舗</b></span>' +
      '<span><small>ブランド倍率</small><b>×' + multiplierText(cafe.brandMultiplierBasisPoints) + '</b></span>' +
      (expansionDiscount ? '<span class="discount-active"><small>地図の効果</small><b>出店費 25%OFF</b></span>' : '') +
      (canExpand ? '<span><small>出店後</small><b>' + numberText(cafe.nextStoreCount) + '店舗</b></span>' : '') +
      '</div>' +
      '<button class="primary-btn cafe-expand-btn" id="cafeExpandBtn"'
        + (!affordable ? ' disabled' : '') + '>' + buttonText + '</button>' +
      (canExpand && !affordable
        ? '<small class="cafe-expansion-short">あと '
          + cafeNumberText(cafe.expansionCost - cafe.cash) + 'コインで出店できます</small>'
        : '') +
      '</div>' +
      '<div class="cafe-network">' +
      // role="img" は地図だけに付ける。カードごとに付けると、
      // 下の見出しと説明文が「画像の一部」として読み上げから外れてしまう
      '<div class="cafe-network-map" role="img" aria-label="営業中の店舗 '
        + numberText(storeCount) + '店">' +
      CafeScene.networkMap({
        storeCount: storeCount,
        locked: progressLocked,
        maximum: maximum
      }) +
      '</div>' +
      '<div class="cafe-network-caption">' +
      '<b>' + (maximum ? 'WORLDWIDE NETWORK' : (progressLocked ? 'NEXT AREA LOCKED' : 'JAVA CAFÉ NETWORK')) + '</b>' +
      '<small>' + (progressLocked ? '問題を解くと次の地域へ出店できます' : '1 → 2 → 3 → 5 → 8… と出店規模も加速') + '</small>' +
      '</div>' +
      '</div>' +
      '</div></section>';
  }

  /** 設備とは別に所持できる、抽選・連続達成・割引などのスペシャルアイテム。 */
  function renderCafeItems() {
    var cafe = cafeState();
    var items = cafe.items || [];
    if (!items.length) { return ''; }

    var ownedCount = items.filter(function (item) { return item.owned; }).length;
    return '<section class="cafe-tab-panel cafe-item-shop" id="cafePanelitems" role="tabpanel"'
      + ' aria-labelledby="cafeTabitems" data-section="items"'
      + (activeCafeSection === 'items' ? '' : ' hidden') + '>' +
      '<header class="cafe-section-heading"><div><span class="screen-eyebrow">SPECIAL COLLECTION</span>' +
      '<h2>スペシャルアイテム</h2><p>学習中に見つけた、経営を助ける特別な品です。</p></div>' +
      '<div class="cafe-item-summary">' +
      (ownedCount ? '<span>所持 ' + ownedCount + '個</span>' : '') +
      '<span title="正確な累計 ' + numberText(cafe.lifetimeCash) + 'コイン">累計獲得 '
        + cafeNumberText(cafe.lifetimeCash) + 'コイン</span></div></header>' +
      '<p class="menu-note cafe-section-note">新しい品は学習の節目、または特別な達成条件で見つかります。'
        + '購入した効果は常に有効です。</p>' +
      '<div class="cafe-item-grid">' + items.map(function (item) {
        var affordable = !item.owned && cafe.cash >= item.cost;
        var buttonText = item.owned ? '所持中'
          : cafeNumberText(item.cost) + 'コインで獲得';
        var shortage = !item.owned && !affordable
          ? '<small class="cafe-item-shortage">あと ' + cafeNumberText(item.cost - cafe.cash) + 'コイン</small>'
          : '';

        return '<article class="cafe-item-card' + (item.owned ? ' owned' : '')
          + (item.unseen ? ' newly-discovered' : '') + '">' +
          '<div class="cafe-item-icon">' + esc(item.emoji) + '</div>' +
          '<div class="cafe-item-body"><span>' + (item.owned ? 'ACTIVE ITEM' : (item.unseen ? 'NEW ITEM' : 'DISCOVERED'))
            + '</span><h3>' + esc(item.name) + '</h3><p>' + esc(item.description) + '</p>'
            + (item.unlockNote
              ? '<p class="cafe-item-unlock">🎯 達成：' + esc(item.unlockNote) + '</p>' : '')
            + '</div>' +
          '<div class="cafe-item-action"><button class="cafe-buy cafe-item-buy" data-id="'
            + esc(item.id) + '"' + ((!affordable || item.owned) ? ' disabled' : '') + '>'
            + esc(buttonText) + '</button>' + shortage + '</div>' +
          '</article>';
      }).join('') + '</div></section>';
  }

  /**
   * 設備カードの「?」に出す説明。
   * 系統ごとに「何が増えるのか」と「いつ入るのか」を1つにまとめる。
   */
  var EQUIPMENT_HELP = {
    sales: {
      name: '注文売上',
      body: '問題を初めてクリアして★を取ったとき、章をすべてクリアしたときにもらうコインが増えます。'
    },
    cups: {
      name: '毎注文',
      body: '1回の学習で提供する杯数が増えます。杯数が増えるほど、そのときの売上も増えます。'
    },
    chapter: {
      name: '章ボーナス',
      body: '章の問題をすべてクリアしたときだけ入る、まとめの追加売上が増えます。'
    },
    tips: {
      name: '正解チップ',
      body: '確認クイズに初めて正解したときの追加コインが増えます。★の判定には影響しません。'
    },
    streak: {
      name: '連続効果',
      body: '連続して学習した日数に応じて注文売上が増えます。効果は7日分が上限です。'
    },
    automation: {
      name: '自動売上',
      body: 'アプリを表示している間だけ、ゆっくり売上を作ります。オフラインでは増えません。'
        + '次の★を取るまでの自動売上は最大0.5問分なので、問題を解く方が必ず大きく稼げます。'
    }
  };

  /** 設備カードの見出し。「?」にカーソルを当てると、その系統の説明が出る。 */
  function equipmentHeadHtml(type, label, tier) {
    var help = EQUIPMENT_HELP[type] || EQUIPMENT_HELP.sales;
    var tipId = 'equipmentHelp-' + type;
    return '<header><div><span class="upgrade-type">' + esc(label) + '</span>' +
      '<span class="equipment-title-row"><b>' + esc(label) + 'スロット</b>' +
      '<span class="equipment-help">' +
      '<button class="equipment-help-btn" type="button" aria-describedby="' + tipId + '"' +
      ' aria-label="' + esc(label) + 'の説明">?</button>' +
      '<span class="equipment-help-tip" id="' + tipId + '" role="tooltip">' +
      '<span class="equipment-help-title">' + esc(help.name) + '</span>' +
      '<span class="equipment-help-body">' + esc(help.body) + '</span>' +
      '<span class="equipment-help-foot">同じ系統の設備は合算されず、' +
      '現在装備している一番上のRankの効果に置き換わります。</span>' +
      '</span></span></span></div>' +
      '<strong>Rank ' + tier + '</strong></header>';
  }

  /** アプリを表示している間だけ、学習報酬より低い速度で売上を作る設備。 */
  function renderCafeAutomation() {
    var cafe = cafeState();
    var automation = cafe.automation || [];
    if (!automation.length) { return ''; }

    var current = automation.filter(function (item) { return item.equipped; })[0] || null;
    var next = automation.filter(function (item) { return item.available; })[0] || null;
    var currentTier = current ? current.tier : 0;
    var affordable = next && next.starReady && cafe.cash >= next.cost;
    var currentHtml = current
      ? '<span class="equipment-item-icon">' + esc(current.emoji) + '</span><div><small>現在装備 · Rank&nbsp;'
        + current.tier + '</small><b>' + esc(current.name) + '</b><em>'
        + perMinuteText('+' + cafeNumberText(cafe.passiveCashPerMinute), 'コイン') + '</em></div>'
      : '<span class="equipment-item-icon empty">－</span><div><small>現在装備</small><b>未導入</b>'
        + '<em>自動売上なし</em></div>';
    var nextEstimate = next
      ? Math.max(1, Math.floor(Number(cafe.nextOrderCash || 0)
          * next.rateBasisPointsPerMinute / 10000))
      : 0;
    var nextHtml = next
      ? '<span class="equipment-item-icon">' + esc(next.emoji) + '</span><div><small>次の上位設備 · Rank&nbsp;'
        + next.tier + '</small><b>' + esc(next.name) + '</b><em>'
        + perMinuteText('約 +' + cafeNumberText(nextEstimate), 'コイン')
        + ' ·&nbsp;★' + next.requiredStars + '</em></div>'
      : '<span class="equipment-item-icon max">★</span><div><small>アップグレード完了</small>'
        + '<b>最高ランク</b><em>' + perMinuteText('学習1回分の5%', '') + '</em></div>';
    var button = next
      ? '<button class="cafe-buy cafe-automation-buy" data-id="' + esc(next.id) + '"'
        + (!affordable ? ' disabled' : '') + '>Rank&nbsp;' + next.tier + 'へ · '
        + (next.discounted ? '<s>' + cafeNumberText(next.baseCost) + '</s> ' : '')
        + cafeNumberText(next.cost) + 'コイン</button>'
      : '<button class="cafe-buy cafe-automation-buy" disabled>MAX</button>';
    var shortage = next && !next.starReady
      ? '<small class="equipment-shortage">★' + next.requiredStars + 'で解放</small>'
      : (next && !affordable
        ? '<small class="equipment-shortage">あと ' + cafeNumberText(next.cost - cafe.cash) + 'コイン</small>'
        : '');

    return '<article class="equipment-path effect-automation">' +
      equipmentHeadHtml('automation', '自動営業', currentTier) +
      '<div class="equipment-swap"><div class="equipment-current">' + currentHtml + '</div>' +
      '<span class="equipment-arrow" aria-hidden="true">→</span>' +
      '<div class="equipment-next">' + nextHtml + '</div></div>' +
      '<div class="equipment-action">' + button + shortage + '</div></article>';
  }

  function renderCafeShop() {
    var cafe = cafeState();
    var upgrades = cafe.upgrades || [];
    if (!upgrades.length) {
      return '<section class="cafe-tab-panel cafe-empty-panel" id="cafePanelequipment" role="tabpanel"'
        + ' aria-labelledby="cafeTabequipment" data-section="equipment"'
        + (activeCafeSection === 'equipment' ? '' : ' hidden') + '>設備はまだありません。</section>';
    }

    var trackOrder = ['sales', 'cups', 'chapter', 'tips', 'streak'];

    function effectLabel(type) {
      if (type === 'cups') { return '抽出力'; }
      if (type === 'chapter') { return 'イベント'; }
      if (type === 'tips') { return 'クイズ接客'; }
      if (type === 'streak') { return '常連サービス'; }
      return '販売戦略';
    }

    function effectText(type, value) {
      if (type === 'cups') { return '毎注文 +' + numberText(value) + '杯'; }
      if (type === 'chapter') { return '章ボーナス +' + numberText(value) + '%'; }
      if (type === 'tips') { return '正解チップ +' + numberText(value) + '%'; }
      if (type === 'streak') { return '連続1日ごと +' + numberText(value) + '%'; }
      return '注文売上 +' + numberText(value) + '%';
    }

    var activeAutomation = (cafe.automation || []).some(function (item) { return item.equipped; });
    var equippedCount = upgrades.filter(function (u) { return u.equipped; }).length
      + (activeAutomation ? 1 : 0);
    var automationCount = (cafe.ownedAutomation || []).length;
    return '<section class="cafe-tab-panel cafe-shop" id="cafePanelequipment" role="tabpanel"'
      + ' aria-labelledby="cafeTabequipment" data-section="equipment"'
      + (activeCafeSection === 'equipment' ? '' : ' hidden') + '>' +
      '<header class="cafe-section-heading"><div><span class="screen-eyebrow">EQUIPMENT PATHS</span>' +
      '<h2>設備アップグレード</h2><p>手持ちの設備を一段ずつ更新します。</p></div>' +
      '<div class="cafe-effects">' +
      '<span>稼働中 ' + equippedCount + '系統</span>' +
      '<span>設備更新 ' + ((cafe.ownedUpgrades || []).length + automationCount) + '回</span>' +
      '<span>単価 +' + (cafe.salesBonusPercent || 0) + '%</span>' +
      '<span>毎注文 +' + (cafe.extraCups || 0) + '杯</span>' +
      '<span>章ボーナス +' + (cafe.chapterBonusPercent || 0) + '%</span>' +
      '<span>正解チップ +' + (cafe.quizTipPercent || 0) + '%</span>' +
      '<span>連続効果 +' + (cafe.streakBonusPercent || 0) + '%</span>' +
      '<span>' + perMinuteText('自動 +' + cafeNumberText(cafe.passiveCashPerMinute), '') + '</span>' +
      '</div></header>' +
      '<p class="menu-note cafe-section-note">売上は、問題を初めてクリアして★を取ったときに入ります。'
        + '上位設備を買うと、同じ系統の設備と効果が上位性能へ置き換わります。'
        + ' 各設備は★に応じて段階解放されます。'
        + 'それぞれの設備が何を増やすのかは、カードの「?」にカーソルを当てると出ます。'
        + ((cafe.items || []).some(function (item) {
          return item.owned && item.effectType === 'equipment_discount';
        }) ? ' マイスター工具箱の20%OFFを適用中です。' : '') + '</p>' +
      '<div class="equipment-paths">' + trackOrder.map(function (type) {
        var items = upgrades.filter(function (u) { return u.effectType === type; })
          .sort(function (a, b) { return a.tier - b.tier; });
        var current = null;
        items.forEach(function (u) {
          if (u.equipped || (u.owned && (!current || u.tier > current.tier))) { current = u; }
        });
        var currentTier = current ? current.tier : 0;
        var next = null;
        items.forEach(function (u) {
          if (!next && u.tier === currentTier + 1) { next = u; }
        });
        var starReady = next && next.starReady;
        var affordable = next && starReady && cafe.cash >= next.cost;
        var currentHtml = current
          ? '<span class="equipment-item-icon">' + esc(current.emoji) + '</span><div><small>現在装備 · Rank&nbsp;'
            + current.tier + '</small><b>' + esc(current.name) + '</b><em>'
            + esc(effectText(type, current.effectValue)) + '</em></div>'
          : '<span class="equipment-item-icon empty">－</span><div><small>現在装備</small><b>未導入</b>'
            + '<em>効果なし</em></div>';
        var nextHtml = next
          ? '<span class="equipment-item-icon">' + esc(next.emoji) + '</span><div><small>次の上位設備 · Rank&nbsp;'
            + next.tier + '</small><b>' + esc(next.name) + '</b><em>'
            + esc(effectText(type, next.effectValue)) + ' ·&nbsp;★' + next.requiredStars + '</em></div>'
          : '<span class="equipment-item-icon max">★</span><div><small>アップグレード完了</small>'
            + '<b>最高ランク</b><em>この系統は完成しました</em></div>';
        var button = next
          ? '<button class="cafe-buy equipment-upgrade-btn" data-id="' + esc(next.id) + '"'
            + (!affordable ? ' disabled' : '') + '>Rank&nbsp;' + next.tier + 'へ · '
            + (next.discounted ? '<s>' + cafeNumberText(next.baseCost) + '</s> ' : '')
            + cafeNumberText(next.cost) + 'コイン</button>'
          : '<button class="cafe-buy equipment-upgrade-btn" disabled>MAX</button>';
        var shortage = next && !starReady
          ? '<small class="equipment-shortage">★' + next.requiredStars + 'で解放</small>'
          : (next && !affordable
            ? '<small class="equipment-shortage">あと ' + cafeNumberText(next.cost - cafe.cash) + 'コイン</small>'
            : '');

        return '<article class="equipment-path effect-' + esc(type) + '">' +
          equipmentHeadHtml(type, effectLabel(type), currentTier) +
          '<div class="equipment-swap"><div class="equipment-current">' + currentHtml + '</div>' +
          '<span class="equipment-arrow" aria-hidden="true">→</span>' +
          '<div class="equipment-next">' + nextHtml + '</div></div>' +
          '<div class="equipment-action">' + button + shortage + '</div>' +
          '</article>';
      }).join('') + renderCafeAutomation() + '</div></section>';
  }

  function purchaseCafeUpgrade(id) {
    api('cafe/purchase', { id: id })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        renderCafe(true);
        toast(res.upgrade.replacedName
          ? res.upgrade.emoji + ' 「' + res.upgrade.replacedName + '」から「'
            + res.upgrade.name + '」へ更新しました'
          : res.upgrade.emoji + ' 「' + res.upgrade.name + '」を導入しました');
      })
      .catch(toastError);
  }

  function purchaseCafeAutomation(id) {
    api('cafe/automation/purchase', { id: id })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        renderCafe(true);
        syncCafePassiveMode();
        toast(res.automation.replacedName
          ? res.automation.emoji + ' 自動営業を「' + res.automation.name + '」へ更新しました'
          : res.automation.emoji + ' 「' + res.automation.name + '」が自動営業を始めました');
      })
      .catch(toastError);
  }

  function purchaseCafeItem(id) {
    api('cafe/item/purchase', { id: id })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        renderCafe(true);
        toast(res.item.emoji + ' 「' + res.item.name + '」を獲得しました');
      })
      .catch(toastError);
  }

  function expandCafeNetwork() {
    api('cafe/expand', {})
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        renderCafe(true);
        toast('🏪 +' + numberText(res.expansion.addedStores) + '店舗オープン！ 全'
          + numberText(res.expansion.storeCount) + '店舗になりました');
      })
      .catch(toastError);
  }

  function newCafePassiveSessionId() {
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  /** アプリが見えている間だけサーバへ定期連絡し、オフライン収益を作らない。 */
  function syncCafePassiveMode() {
    if (!state || document.hidden || !(cafeState().passiveCashPerMinute > 0)) {
      closeCafePassiveMode(true);
      return;
    }
    if (cafePassiveSessionId) { return; }

    var sessionId = newCafePassiveSessionId();
    cafePassiveSessionId = sessionId;
    api('cafe/passive/start', { sessionId: sessionId })
      .then(function (res) {
        if (cafePassiveSessionId !== sessionId) { return; }
        applyDelta(res.delta);
        renderHeader();
        cafePassiveTimer = setInterval(collectCafePassiveSales, CAFE_PASSIVE_INTERVAL_MS);
      })
      .catch(function () {
        if (cafePassiveSessionId === sessionId) { cafePassiveSessionId = null; }
      });
  }

  function collectCafePassiveSales() {
    var sessionId = cafePassiveSessionId;
    if (!sessionId || cafePassiveBusy || document.hidden) { return; }
    cafePassiveBusy = true;
    api('cafe/passive/collect', { sessionId: sessionId })
      .then(function (res) {
        if (cafePassiveSessionId !== sessionId) { return; }
        if (!res.passive.active) {
          closeCafePassiveMode(false);
          return;
        }
        applyDelta(res.delta);
        renderHeader();
        if (res.passive.cash > 0 && currentView === 'cafe') {
          renderCafe(true);
          var live = document.getElementById('cafePassiveLive');
          if (live) {
            var gain = document.createElement('i');
            gain.className = 'cafe-passive-gain';
            gain.textContent = '+' + numberText(res.passive.cash);
            live.appendChild(gain);
            setTimeout(function () { if (gain.parentNode) { gain.parentNode.removeChild(gain); } }, 1300);
          }
        }
      })
      .catch(function () { /* 次の定期連絡で再試行する */ })
      .finally(function () {
        if (cafePassiveSessionId === sessionId) { cafePassiveBusy = false; }
      });
  }

  function closeCafePassiveMode(sendStop) {
    var sessionId = cafePassiveSessionId;
    cafePassiveSessionId = null;
    cafePassiveBusy = false;
    if (cafePassiveTimer) {
      clearInterval(cafePassiveTimer);
      cafePassiveTimer = null;
    }
    if (!sessionId || !sendStop) { return; }
    api('cafe/passive/stop', { sessionId: sessionId })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
      })
      .catch(function () { /* 画面を閉じる途中なら精算できなくても継続しない */ });
  }

  /** 学習の記録。カードを増やさず、1本の常時表示バーで現在地を把握できるようにする。 */
  function renderMenuStats() {
    // クリアした問題だけを数えると、8件中7件通っている問題が0件扱いになって
    // 実際より進んでいないように見える。サーバが持っている最高記録で数える。
    var casesPassed = allTasks().reduce(function (n, t) {
      return n + (t.cleared ? t.totalCaseCount : (t.passedCount || 0));
    }, 0);
    var attempts = sumValues(state.progress.attempts);

    var tiles = [
      { icon: '★', value: state.progress.starCount, unit: '個', label: '獲得したスター' },
      { icon: '🔥', value: state.progress.streak, unit: '日', label: '連続で学習した日数' },
      { icon: '✅', value: casesPassed, unit: '件', label: '通過したテストケース' },
      { icon: '✍️', value: attempts, unit: '回', label: '提出した回数' }
    ];
    if (state.quizTotal) {
      tiles.push({
        icon: '🧠',
        value: state.quizCorrect,
        unit: '問',
        label: '正解した確認クイズ'
      });
    }

    return '' +
      '<section class="menu-section stat-grid" aria-label="学習の記録">' +
      tiles.map(function (t) {
        return '<div class="stat-tile"><span class="stat-icon">' + t.icon + '</span>' +
          '<span class="stat-copy"><span class="stat-label">' + esc(t.label) + '</span>' +
          '<strong class="stat-value">' + t.value + '<span class="stat-unit">' + esc(t.unit) + '</span></strong>' +
          '</span></div>';
      }).join('') +
      '</section>';
  }

  /** 左で章を選び、右でレッスンへ進む。開閉操作のないマスター・詳細UI。 */
  function renderChapterCards() {
    var listHost = document.getElementById('chGrid');
    var detailHost = document.getElementById('chapterDetail');
    var tabs = document.getElementById('partTabs');
    var todoChapter = chapterOf(firstTodo());
    var parts = curriculumParts();
    var todoPart = todoChapter && partOfChapter(todoChapter);

    if (!activePartId || !parts.some(function (part) { return part.id === activePartId; })) {
      activePartId = todoPart ? todoPart.id : parts[0].id;
    }

    tabs.innerHTML = '';
    parts.forEach(function (part) {
      var progress = partProgress(part);
      var progressLabel = progress.cleared === progress.total && progress.total > 0
        ? 'クリア' : (progress.cleared > 0 ? '学習中' : '');
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'part-tab' + (part.id === activePartId ? ' active' : '');
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', part.id === activePartId ? 'true' : 'false');
      button.innerHTML =
        '<span class="part-tab-emoji">' + esc(part.emoji) + '</span>' +
        '<span class="part-tab-body">' +
        '  <span class="part-tab-title">' + esc(part.title) + '</span>' +
        '  <span class="part-tab-sub">' + esc(part.subtitle) + '</span>' +
        '</span>' +
        (progressLabel ? '<span class="part-tab-progress">' + progressLabel + '</span>' : '');
      button.addEventListener('click', function () {
        activePartId = part.id;
        renderChapterCards();
        var activeTab = tabs.querySelector('.part-tab.active');
        if (activeTab) { activeTab.focus(); }
      });
      tabs.appendChild(button);
    });

    var activePart = parts.find(function (part) { return part.id === activePartId; }) || parts[0];
    var activeChapters = chaptersOfPart(activePart);
    listHost.innerHTML = '';

    var suggestedChapter = null;
    if (todoChapter && todoPart && todoPart.id === activePart.id) {
      suggestedChapter = todoChapter;
    } else {
      suggestedChapter = activeChapters.find(function (ch) { return !ch.cleared; }) || activeChapters[0];
    }
    var selectedChapter = activeChapters.find(function (ch) {
      return ch.id === selectedChapterByPart[activePart.id];
    }) || suggestedChapter;
    if (!selectedChapter) {
      detailHost.innerHTML = '<div class="chapter-empty">この編には章がありません。</div>';
      return;
    }
    selectedChapterByPart[activePart.id] = selectedChapter.id;

    activeChapters.forEach(function (ch) {
      var recommended = suggestedChapter && suggestedChapter.id === ch.id;
      var selected = ch.id === selectedChapter.id;
      var chapterStatus = ch.cleared ? '✓' : (ch.clearedCount ? '学習中' : '');
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'chapter-list-item' + (selected ? ' active' : '')
        + (ch.cleared ? ' cleared' : '') + (recommended && !ch.cleared ? ' recommended' : '');
      button.dataset.chapter = ch.id;
      button.setAttribute('aria-current', selected ? 'true' : 'false');
      button.innerHTML =
        '<span class="chapter-list-emoji">' + esc(ch.emoji) + '</span>' +
        '<span class="chapter-list-copy"><span class="chapter-list-kicker">第'
          + displayChapterNumber(ch) + '章' + (recommended && !ch.cleared ? ' · 次におすすめ' : '') + '</span>' +
        '<strong>' + esc(ch.title) + '</strong></span>' +
        '<span class="chapter-list-count">' + chapterStatus + '</span>';
      button.addEventListener('click', function () {
        selectedChapterByPart[activePart.id] = ch.id;
        renderChapterCards();
        var activeButton = listHost.querySelector('[data-chapter="' + ch.id + '"]');
        if (activeButton) { activeButton.focus(); }
      });
      listHost.appendChild(button);
    });

    var selectedListButton = listHost.querySelector('[data-chapter="' + selectedChapter.id + '"]');
    if (selectedListButton) {
      if (listHost.scrollWidth > listHost.clientWidth) {
        listHost.scrollLeft = Math.max(0, selectedListButton.offsetLeft - 12);
      } else {
        listHost.scrollTop = Math.max(0, selectedListButton.offsetTop - listHost.offsetTop - 12);
      }
    }

    var nextLessonInChapter = selectedChapter.lessons.find(function (lesson) { return !lesson.cleared; })
      || selectedChapter.lessons[0];
    var selectedStatus = selectedChapter.cleared ? 'クリア済み'
      : (selectedChapter.clearedCount ? '学習中' : '');
    var chapterAction = selectedChapter.cleared ? 'もう一度復習する'
      : (selectedChapter.clearedCount ? 'この章を続ける' : 'この章を始める');
    detailHost.innerHTML =
      '<header class="chapter-detail-head">' +
      '  <div class="chapter-detail-title"><span class="chapter-detail-emoji">' + esc(selectedChapter.emoji) + '</span>' +
      '  <div><span class="screen-eyebrow">第' + displayChapterNumber(selectedChapter) + '章</span>' +
      '  <h3>' + esc(selectedChapter.title) + '</h3><p>' + esc(selectedChapter.subtitle) + '</p></div></div>' +
      (selectedStatus
        ? '  <div class="chapter-detail-progress"><strong>' + selectedStatus + '</strong></div>'
        : '') +
      '</header>' +
      (activePart.prerequisite
        ? '<div class="part-prerequisite"><strong>学習の前提</strong><span>'
          + esc(activePart.prerequisite) + '</span></div>'
        : '') +
      (nextLessonInChapter ? '<button class="primary-btn chapter-start-btn" id="chapterStartBtn" data-target="'
        + esc(nextLessonInChapter.id) + '">▶ ' + chapterAction + '</button>' : '') +
      '<ul class="chapter-lesson-list">' + selectedChapter.lessons.map(function (lesson) {
        var done = lesson.clearedCount || 0;
        var lessonStatus = lesson.cleared ? 'クリア済み' : (done ? '学習中' : '');
        return '<li><button type="button" class="chapter-lesson-row' + (lesson.cleared ? ' cleared' : '')
          + '" data-lesson="' + esc(lesson.id) + '">' +
          '<span class="chapter-lesson-status">' + (lesson.cleared ? '✓' : displayLessonId(lesson)) + '</span>' +
          '<span class="chapter-lesson-copy"><strong>' + esc(lesson.title) + '</strong>' +
          (lessonStatus ? '<small>' + lessonStatus + '</small>' : '') + '</span>' +
          '<span class="chapter-lesson-arrow">→</span></button></li>';
      }).join('') + '</ul>';

    var startButton = document.getElementById('chapterStartBtn');
    if (startButton) {
      startButton.addEventListener('click', function () { selectLesson(startButton.dataset.target); });
    }
    Array.prototype.forEach.call(detailHost.getElementsByClassName('chapter-lesson-row'), function (button) {
      button.addEventListener('click', function () { selectLesson(button.dataset.lesson); });
    });
  }

  /** 初回だけ、開始に必要な4ステップを常時表示する。 */
  function renderMenuGuide(stars) {
    if (stars > 0) { return ''; }
    var steps = [
      ['📖', '解説を読む', '具体例つきで、1つの話題に絞って書いてあります。'],
      ['▶', 'サンプルを動かす', '解説中のコードは「▶ サンプルを実行」でその場で動きます。まず動かすのが理解の近道です。'],
      ['⌨️', '自分で書く', 'ひな形から書き始められます。行番号・色付け・自動インデントつきです。'],
      ['✓', '提出して採点', '全ケースを通ればクリアです。詰まったときはヒントも使えます。']
    ];

    return '' +
      '<section class="menu-section onboarding-panel">' +
      '  <header><span class="screen-eyebrow">FIRST STEP</span><h2>1レッスンの進め方</h2></header>' +
      '    <ol class="guide-steps">' +
      steps.map(function (s) {
        return '<li><span class="guide-icon">' + s[0] + '</span>' +
          '<span class="guide-text"><b>' + esc(s[1]) + '</b>' + esc(s[2]) + '</span></li>';
      }).join('') +
      '    </ol>' +
      '    <p class="guide-foot">書いたコードは自動で保存されます。ブラウザを閉じても続きから再開できます。</p>' +
      '</section>';
  }

  // ------------------------------------------------------------ 本文の描画

  function renderLesson() {
    var lesson = findLesson(currentId);
    var chapter = chapterOf(currentId);
    var part = partOfChapter(chapter);
    var main = document.getElementById('content');
    if (!lesson) {
      main.innerHTML = '<div class="loading">レッスンが見つかりません</div>';
      return;
    }

    editors = {};
    main.innerHTML =
      '<article class="lesson-view">' +

      '  <div class="lesson-head">' +
      '    <div class="crumb">' +
      '      <button class="crumb-home" id="crumbHome">メニュー</button>' +
      '      <span class="crumb-sep">›</span>' +
             (part ? '<span class="crumb-part">' + esc(part.emoji) + ' ' + esc(part.title) + '</span>' +
             '<span class="crumb-sep">›</span>' : '') +
             esc(chapter.emoji) + ' 第' + displayChapterNumber(chapter) + '章 ' + esc(chapter.title) +
      '    </div>' +
      '    <h1 class="lesson-h1">' +
      '      <span class="lesson-h1-id">' + esc(displayLessonId(lesson)) + '</span>' + esc(lesson.title) +
             (lesson.cleared ? '<span class="badge badge-clear">★ クリア済み</span>' : '') +
      '    </h1>' +
      '  </div>' +

      '  <section class="card card-explain">' + renderMarkdown(lesson.explanation) + '</section>' +

      '  <section class="samples" id="samples"></section>' +

      '  <section class="tasks" id="tasks"></section>' +
      '  <section class="quiz" id="quiz"></section>' +
      '  <nav class="lesson-next" id="lessonNext" aria-label="次のレッスン"></nav>' +
      '</article>';

    renderSamples(lesson);

    var tasksHost = document.getElementById('tasks');
    lesson.tasks.forEach(function (task, index) {
      tasksHost.appendChild(buildTaskBlock(lesson, task, index));
      // 挿してから呼ぶ。開示済みヒントと模範解答ボタンは id で要素を引くので、
      // 繋ぐ前に呼ぶと見つからない（ヒント欄が空のまま描画が止まる）。
      renderRevealedHints(lesson, task);
    });

    document.getElementById('crumbHome').addEventListener('click', goHome);

    renderQuiz(lesson);
    renderLessonNext(lesson);
    main.scrollTop = 0;
  }

  /** レッスン全体の末尾に置く、次のページへの導線。 */
  function renderLessonNext(lesson) {
    var host = document.getElementById('lessonNext');
    var next = nextLesson(lesson.id);
    if (!next) {
      host.innerHTML =
        '<div class="lesson-next-copy"><small>すべてのレッスンが終わりました</small>' +
        '<b>Java Caféを確認しましょう</b></div>' +
        '<button class="primary-btn lesson-next-btn" id="lessonNextBtn">Java Caféへ →</button>';
      document.getElementById('lessonNextBtn').addEventListener('click', goCafe);
      return;
    }

    host.innerHTML =
      '<div class="lesson-next-copy"><small>次のレッスン</small>' +
      '<b><span>' + esc(displayLessonId(next)) + '</span>' + esc(next.title) + '</b></div>' +
      '<button class="primary-btn lesson-next-btn" id="lessonNextBtn">次のレッスンへ →</button>';
    document.getElementById('lessonNextBtn').addEventListener('click', function () {
      selectLesson(next.id);
    });
  }

  // ------------------------------------------------------- 練習問題1問ぶん

  /**
   * 練習問題1問（問題文 + エディタ + ヒント + 採点結果）のかたまりを作る。
   *
   * 1レッスンに複数問あるので、DOMのidは問題ごとに接尾辞を付けて衝突させない。
   * エディタも問題ごとに別インスタンスにする（textarea が別なので、書きかけの
   * コードも採点結果も混ざらない）。
   */
  function buildTaskBlock(lesson, task, index) {
    var n = task.id;

    var block = document.createElement('section');
    block.className = 'task-block';
    block.id = 'task-' + n;
    block.innerHTML =
      '<div class="task-block-head">' +
      '  <span class="task-no">問題' + (index + 1) + '</span>' +
      '  <span class="task-kind task-kind-' + esc(task.kind) + '">' + esc(task.label) + '</span>' +
      '  <span class="task-head-status" id="taskStatus-' + n + '">' +
           (task.cleared ? '★ クリア済み' : '') +
      '  </span>' +
      '</div>' +

      '<div class="task-block-body">' +
      '  <div class="card card-task">' +
      '    <div class="task-body">' + renderMarkdown(task.task) + '</div>' +
           renderCasePreview(task) +
      '  </div>' +

      '  <div class="card card-code">' +
      '    <div class="code-head">' +
      '      <h2 class="card-h"><span class="card-h-icon">⌨️</span>コードを書く</h2>' +
      '      <div class="code-head-actions">' +
      '        <button class="ghost-btn" data-role="restore" title="最初のひな形に戻す">ひな形に戻す</button>' +
      '      </div>' +
      '    </div>' +
      '    <div id="editorHost-' + n + '"></div>' +
      '    <div class="actions">' +
      '      <button class="run-btn" id="runBtn-' + n + '">▶ 実行してみる</button>' +
      '      <button class="primary-btn" id="submitBtn-' + n + '">✓ 提出して採点</button>' +
      '      <span class="spacer"></span>' +
             renderHintButton(task) +
      '    </div>' +
      '    <div class="shortcut-note">⌘/Ctrl + Enter で提出　·　⌘/Ctrl + Shift + Enter で実行</div>' +
      '  </div>' +

      '  <div class="hints" id="hints-' + n + '"></div>' +
      '  <div class="result" id="result-' + n + '"></div>' +
      '  <div class="solution-area" id="solution-' + n + '"></div>' +
      '</div>';

    var editor = new window.JQEditor(block.querySelector('#editorHost-' + n));
    editor.setValue(task.savedCode != null && task.savedCode !== ''
      ? task.savedCode
      : task.starterCode);
    editor.onSubmit = function () { submit(n); };
    editor.onRun = function () { runOnce(n); };
    editor.input.addEventListener('input', function () { scheduleSave(n); });
    editors[n] = editor;

    block.querySelector('#runBtn-' + n).addEventListener('click', function () { runOnce(n); });
    block.querySelector('#submitBtn-' + n).addEventListener('click', function () { submit(n); });
    block.querySelector('[data-role="restore"]').addEventListener('click', function () {
      if (window.confirm('書いたコードを消して、最初のひな形に戻します。よろしいですか？')) {
        editor.setValue(task.starterCode);
        editor.focus();
        scheduleSave(n);
      }
    });

    var hintBtn = block.querySelector('.hint-btn');
    if (hintBtn) { hintBtn.addEventListener('click', function () { revealNextHint(n); }); }

    // 開示済みヒントの描画は、このかたまりを document に挿してから
    // （renderRevealedHints は id で引くので、繋ぐ前だと見つからない）
    return block;
  }

  // ------------------------------------------------------- 確認クイズ（4択）

  var CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E', 'F'];

  /**
   * レッスンの最後に置く選択式クイズ。
   *
   * 正解の番号はサーバから送られてこないので、答え合わせは必ず /api/quiz に投げる。
   * 一度答えた問題は state に残っているので、開き直しても結果が見える。
   */
  function renderQuiz(lesson) {
    var host = document.getElementById('quiz');
    if (!host) { return; }
    var quizzes = lesson.quizzes || [];
    if (!quizzes.length) {
      host.innerHTML = '';
      return;
    }

    var results = lesson.quizResults || [];
    var correct = 0;
    var answered = 0;
    results.forEach(function (r) {
      if (r) { answered++; if (r.correct) { correct++; } }
    });

    host.innerHTML =
      '<div class="card card-quiz">' +
      '  <div class="quiz-head">' +
      '    <h2 class="card-h"><span class="card-h-icon">🧠</span>確認クイズ</h2>' +
      '    <span class="quiz-score">' + correct + ' / ' + quizzes.length + ' 正解' +
             (answered < quizzes.length ? '（未回答 ' + (quizzes.length - answered) + '）' : '') +
      '    </span>' +
      '  </div>' +
      '  <p class="quiz-note">★ の判定には影響しません。何度でも答え直せます。</p>' +
      quizzes.map(function (q, i) { return quizItemHtml(q, i, results[i]); }).join('') +
      '</div>';

    var buttons = host.getElementsByClassName('quiz-choice');
    Array.prototype.forEach.call(buttons, function (btn) {
      btn.addEventListener('click', function () {
        answerQuiz(Number(btn.dataset.index), Number(btn.dataset.choice));
      });
    });
  }

  function quizItemHtml(quiz, index, result) {
    var choices = quiz.choices.map(function (text, i) {
      var cls = 'quiz-choice';
      if (result) {
        if (i === result.choice) { cls += result.correct ? ' is-picked-ok' : ' is-picked-ng'; }
        if (!result.correct && i === result.answer) { cls += ' is-answer'; }
      }
      return '<button class="' + cls + '" data-index="' + index + '" data-choice="' + i + '">' +
        '<span class="quiz-mark">' + CHOICE_LABELS[i] + '</span>' +
        '<span class="quiz-choice-text">' + renderMarkdown(text) + '</span>' +
        '</button>';
    }).join('');

    return '<div class="quiz-item">' +
      '  <div class="quiz-q"><span class="quiz-no">Q' + (index + 1) + '</span>' + renderMarkdown(quiz.question) + '</div>' +
      '  <div class="quiz-choices">' + choices + '</div>' +
         quizFeedbackHtml(result) +
      '</div>';
  }

  function quizFeedbackHtml(result) {
    if (!result) { return ''; }
    var head = result.correct
      ? '<span class="quiz-verdict quiz-ok">✅ 正解</span>'
      : '<span class="quiz-verdict quiz-ng">❌ 不正解　正解は '
          + CHOICE_LABELS[result.answer] + '</span>';
    return '<div class="quiz-feedback">' + head +
      (result.explanation ? '<div class="quiz-explain">' + renderMarkdown(result.explanation) + '</div>' : '') +
      '</div>';
  }

  function answerQuiz(index, choice) {
    var lessonId = currentId;
    api('quiz', { lessonId: lessonId, index: index, choice: choice })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        var lesson = findLesson(lessonId);
        if (lesson && lessonId === currentId) { renderQuiz(lesson); }
        if (res.cafeAward && res.cafeAward.cash > 0) {
          var itemEvents = res.cafeAward.itemEvents || [];
          toast((itemEvents.length ? itemEvents.join(' / ') + '　' : '🪙 初正解チップ ')
            + '+' + cafeNumberText(res.cafeAward.cash) + 'コイン');
        }
      })
      .catch(toastError);
  }

  /** 問題文の下に置く「どんな入出力が試されるか」の表。 */
  function renderCasePreview(task) {
    if (!task.visibleCases.length && !task.hiddenCaseCount) { return ''; }

    var usesStdin = task.visibleCases.some(function (c) { return c.stdin !== ''; });
    var rows = task.visibleCases.map(function (c) {
      return '<tr>' +
        (usesStdin ? '<td class="io"><pre>' + esc(c.stdin) + '</pre></td>' : '') +
        '<td class="io"><pre>' + esc(c.expected) + '</pre></td>' +
        '</tr>';
    }).join('');

    var hiddenNote = task.hiddenCaseCount
      ? '<p class="case-hidden-note">🔒 このほかに <b>' + task.hiddenCaseCount
        + '件</b> の隠しテストがあります（提出すると結果が見えます）。'
        + 'たまたま通るコードではなく、どんな入力でも正しく動くコードを書きましょう。</p>'
      : '';

    return '<div class="cases">' +
      '<div class="cases-title">試されること（全' + task.totalCaseCount + '件）</div>' +
      '<table class="case-table">' +
      '<thead><tr>' + (usesStdin ? '<th>入力</th>' : '') + '<th>期待する出力</th></tr></thead>' +
      '<tbody>' + rows + '</tbody></table>' +
      hiddenNote +
      '</div>';
  }

  function renderHintButton(task) {
    if (!task.hintCount) { return ''; }
    var left = task.hintCount - task.hintsRevealed;
    var label = left > 0 ? '💡 ヒント（残り' + left + '）' : '💡 ヒントはすべて表示済み';
    return '<button class="ghost-btn hint-btn"' + (left > 0 ? '' : ' disabled') + '>'
      + label + '</button>';
  }

  function renderSamples(lesson) {
    var host = document.getElementById('samples');
    if (!lesson.samples.length) { return; }

    lesson.samples.forEach(function (sample, index) {
      var box = document.createElement('div');
      box.className = 'card card-sample';
      box.innerHTML =
        '<div class="sample-head">' +
        '  <span class="sample-caption">🧪 ' + esc(sample.caption) + '</span>' +
        '  <button class="run-btn small" data-i="' + index + '">▶ サンプルを実行</button>' +
        '</div>' +
        '<pre class="code"><code>' + hlJava(sample.code) + '</code></pre>' +
        '<div class="sample-out" hidden></div>';

      var out = box.querySelector('.sample-out');
      box.querySelector('button').addEventListener('click', function () {
        var btn = this;
        btn.disabled = true;
        btn.textContent = '実行中…';
        out.hidden = false;
        out.className = 'sample-out';
        out.textContent = '';
        // libLessonId は「同梱ライブラリの引き当て」専用。lessonId を送ると
        // サンプルのコードが1問目の書きかけとして保存されてしまう
        api('run', { code: sample.code, stdin: sample.stdin || '', libLessonId: currentId })
          .then(function (res) {
            out.innerHTML = renderRunOutput(res);
          })
          .catch(function (e) {
            out.innerHTML = '<div class="err">' + esc(e.message) + '</div>';
          })
          .then(function () {
            btn.disabled = false;
            btn.textContent = '▶ サンプルを実行';
          });
      });
      host.appendChild(box);
    });
  }

  // -------------------------------------------------------------- 実行結果

  /** 採点なし実行の結果（コンパイルエラー / 出力 / 例外）をHTMLにする。 */
  function renderRunOutput(res) {
    if (!res.compiled) {
      return '<div class="out-label out-label-err">コンパイルできませんでした</div>'
        + renderDiagnostics(res.diagnostics);
    }
    var run = res.run;
    var html = '';
    if (run.timedOut) {
      html += '<div class="out-label out-label-err">⏱ 5秒を超えたので止めました</div>';
    }
    html += '<div class="out-label">出力</div>'
      + '<pre class="out-pre">' + (run.stdout ? esc(run.stdout) : '<em>（何も出力されていません）</em>')
      + '</pre>';
    if (run.truncated) {
      html += '<div class="out-note">出力が多すぎるため途中で打ち切りました。</div>';
    }
    if (run.stderr) {
      html += '<div class="out-label out-label-err">エラー</div>'
        + '<pre class="out-pre out-pre-err">' + esc(run.stderr) + '</pre>';
    }
    if (res.hint) {
      html += '<div class="hint-box">💡 ' + esc(res.hint) + '</div>';
    }
    return html;
  }

  function renderDiagnostics(diagnostics) {
    if (!diagnostics || !diagnostics.length) { return ''; }
    return '<ul class="diag-list">' + diagnostics.map(function (d) {
      return '<li class="diag diag-' + esc(d.kind) + '">' +
        (d.line ? '<span class="diag-loc">' + d.line + '行目</span>' : '') +
        '<span class="diag-msg">' + esc(d.message) + '</span>' +
        (d.hint ? '<span class="diag-hint">💡 ' + esc(d.hint) + '</span>' : '') +
        '</li>';
    }).join('') + '</ul>';
  }

  // -------------------------------------------------------------- アクション

  function scheduleSave(taskId) {
    clearTimeout(saveTimers[taskId]);
    var id = currentId;
    var code = editors[taskId].getValue();
    saveTimers[taskId] = setTimeout(function () {
      api('save', { lessonId: id, taskId: taskId, code: code }).catch(function () {
        // 保存に失敗しても学習は続けられるので黙って見送る（次の入力で再試行される）
      });
    }, 800);
  }

  /** 実行・採点は1問ずつ。走っている問題のボタンだけを止める。 */
  function setBusy(taskId, on, label) {
    busyTask = on ? taskId : null;
    var runBtn = document.getElementById('runBtn-' + taskId);
    var submitBtn = document.getElementById('submitBtn-' + taskId);
    if (runBtn) { runBtn.disabled = on; }
    if (submitBtn) {
      submitBtn.disabled = on;
      submitBtn.textContent = on ? (label || '実行中…') : '✓ 提出して採点';
    }
  }

  function runOnce(taskId) {
    if (busyTask) { return; }
    var lesson = findLesson(currentId);
    var task = findTask(lesson, taskId);
    var stdin = task.visibleCases.length ? task.visibleCases[0].stdin : '';
    var result = document.getElementById('result-' + taskId);
    setBusy(taskId, true);
    result.className = 'result';
    result.innerHTML = '<div class="card card-result"><div class="spinner">実行中…</div></div>';

    api('run', { lessonId: currentId, taskId: taskId, code: editors[taskId].getValue(), stdin: stdin })
      .then(function (res) {
        var note = stdin
          ? '<div class="out-note">最初のテストケースの入力（<code>' + esc(stdin)
            + '</code>）を与えて動かしました。採点はしていません。</div>'
          : '<div class="out-note">採点はしていません。まず動かして確かめるためのボタンです。</div>';
        result.innerHTML = '<div class="card card-result">'
          + '<h2 class="card-h"><span class="card-h-icon">▶</span>実行結果</h2>'
          + note + renderRunOutput(res) + '</div>';
      })
      .catch(function (e) { showError(e, taskId); })
      .then(function () { setBusy(taskId, false); });
  }

  function submit(taskId) {
    if (busyTask) { return; }
    var result = document.getElementById('result-' + taskId);
    setBusy(taskId, true, '採点中…');
    result.innerHTML = '<div class="card card-result"><div class="spinner">採点中…</div></div>';

    api('submit', { lessonId: currentId, taskId: taskId, code: editors[taskId].getValue() })
      .then(function (res) {
        var wasCurrent = currentId;
        applyDelta(res.delta);
        renderHeader();
        renderSidebar();
        refreshClearedBadge(wasCurrent);
        refreshTaskStatus(wasCurrent, taskId);
        renderJudgement(res, taskId);
        if (res.allPass) {
          // 正解した直後にも、問題ブロック末尾から模範解答を確認できるようにする。
          maybeShowSolutionButton(wasCurrent, taskId);
          celebrate(res, wasCurrent, taskId);
        }
      })
      .catch(function (e) { showError(e, taskId); })
      .then(function () { setBusy(taskId, false); });
  }

  /**
   * 見出しの「★ クリア済み」バッジを、いまの state に合わせる。
   *
   * 提出後にレッスン全体を描き直すとエディタの中身と採点結果が消えてしまうので、
   * バッジだけを差し込む。
   */
  function refreshClearedBadge(lessonId) {
    if (lessonId !== currentId) { return; }
    var lesson = findLesson(lessonId);
    var head = document.querySelector('.lesson-h1');
    if (!lesson || !head) { return; }
    var badge = head.querySelector('.badge-clear');
    if (lesson.cleared && !badge) {
      badge = document.createElement('span');
      badge.className = 'badge badge-clear';
      badge.textContent = '★ クリア済み';
      head.appendChild(badge);
    } else if (!lesson.cleared && badge) {
      badge.remove();
    }
  }

  /**
   * 問題ヘッダの「★ クリア済み」を、いまの state に合わせる。
   *
   * ここでは畳まない。採点結果をこれから読むところなので、通った瞬間に
   * 閉じられると何が起きたのか分からなくなる。畳むのは開き直したときだけ。
   */
  function refreshTaskStatus(lessonId, taskId) {
    if (lessonId !== currentId) { return; }
    var lesson = findLesson(lessonId);
    var task = lesson && findTask(lesson, taskId);
    var status = document.getElementById('taskStatus-' + taskId);
    if (!task || !status) { return; }
    status.textContent = task.cleared ? '★ クリア済み' : '';
  }

  function renderJudgement(res, taskId) {
    var result = document.getElementById('result-' + taskId);
    var html = '<div class="card card-result ' + (res.allPass ? 'ok' : 'ng') + '">';

    if (!res.compiled) {
      html += '<h2 class="card-h"><span class="card-h-icon">🛠</span>コンパイルできませんでした</h2>'
        + '<p class="result-lead">まだ実行できる形になっていません。'
        + '下のエラーを1つずつ直してみましょう（上のものから直すと連鎖して消えることが多いです）。</p>'
        + renderDiagnostics(res.diagnostics);
      result.innerHTML = html + '</div>';
      return;
    }

    var total = res.cases.length;
    var sourceFailures = res.sourceFailures || [];
    var outputAllPass = res.passedCount === total;
    html += '<h2 class="card-h"><span class="card-h-icon">' + (res.allPass ? '🎉' : '🔍') + '</span>'
      + (res.allPass ? 'クリア！全ケース通過'
        : (sourceFailures.length && outputAllPass
          ? '出力は全ケース通過・書き方を確認'
          : '結果: ' + res.passedCount + ' / ' + total + ' 通過'))
      + '</h2>';

    if (sourceFailures.length) {
      html += '<div class="hint-box"><strong>🧩 問題で指定された書き方を確認してください</strong><ul>'
        + sourceFailures.map(function (message) { return '<li>' + esc(message) + '</li>'; }).join('')
        + '</ul></div>';
    }

    if (res.cafeAward && (res.cafeAward.cash > 0 || res.cafeAward.cups > 0)) {
      var itemEvents = res.cafeAward.itemEvents || [];
      html += '<div class="cafe-receipt">' +
        '<span title="正確な報酬 ' + numberText(res.cafeAward.cash) + 'コイン"><small>獲得コイン</small><b>+'
          + cafeNumberText(res.cafeAward.cash) + 'コイン</b></span>' +
        '<span><small>提供しました</small><b>+' + numberText(res.cafeAward.cups) + '杯 ☕</b></span>' +
        (res.chapterCleared ? '<em>章クリアボーナス込み</em>' : '') +
        (itemEvents.length ? '<div class="cafe-item-events">' + itemEvents.map(function (event) {
          return '<strong>✨ ' + esc(event) + '</strong>';
        }).join('') + '</div>' : '') +
        '</div>';
    }

    if (!res.allPass && !outputAllPass) {
      html += '<p class="result-lead">通らなかったケースを見て、どんな入力で答えがずれるか確かめましょう。</p>';
    }

    html += '<ul class="case-results">';
    res.cases.forEach(function (c) {
      html += '<li class="case-result ' + (c.pass ? 'pass' : 'fail') + '">' +
        '<div class="case-result-head">' +
        '  <span class="case-mark">' + (c.pass ? '✅' : '❌') + '</span>' +
        '  <span class="case-label">' + esc(c.label) + '</span>' +
        (c.hidden ? '<span class="badge badge-hidden">隠しテスト</span>' : '') +
        '</div>';

      if (!c.pass) {
        if (c.timedOut) {
          html += '<div class="case-detail"><div class="out-label out-label-err">'
            + '⏱ 5秒以内に終わりませんでした</div></div>';
        } else if (c.stderr) {
          html += '<div class="case-detail">'
            + '<div class="out-label out-label-err">実行中にエラーが出ました</div>'
            + '<pre class="out-pre out-pre-err">' + esc(c.stderr) + '</pre></div>';
        } else {
          html += '<div class="case-detail">' + renderDiff(c) + '</div>';
        }
        if (c.hint) {
          html += '<div class="hint-box">💡 ' + esc(c.hint) + '</div>';
        }
      }
      html += '</li>';
    });
    html += '</ul>';

    if (res.allPass && !res.next) {
      html += '<div class="all-done">これで全問完了です。おつかれさまでした！</div>';
    }

    result.innerHTML = html + '</div>';
  }

  /** 次の問題へ移る。同じレッスン内ならスクロールするだけ。 */
  function goToTask(next) {
    if (!next) { return; }
    if (next.lessonId !== currentId) {
      selectLesson(next.lessonId);
      return;
    }
    var block = document.getElementById('task-' + next.taskId);
    if (block) {
      block.scrollIntoView({ behavior: 'smooth', block: 'start' });
      var editor = editors[next.taskId];
      if (editor) { editor.focus(); }
    }
  }

  /** 期待と実際を行単位で並べる。ずれた行だけ色を付ける。 */
  function renderDiff(c) {
    var rows = c.diff.map(function (d) {
      var show = function (v) {
        return v === null
          ? '<span class="diff-missing">（この行が無い）</span>'
          : (v === '' ? '<span class="diff-missing">（空行）</span>' : esc(v));
      };
      return '<tr class="' + (d.same ? 'same' : 'differ') + '">' +
        '<td class="diff-no">' + d.lineNo + '</td>' +
        '<td class="diff-cell">' + show(d.expected) + '</td>' +
        '<td class="diff-cell">' + show(d.actual) + '</td>' +
        '</tr>';
    }).join('');

    var inputRow = c.stdin
      ? '<div class="diff-input">入力: <code>' + esc(c.stdin).replace(/\n/g, '␤') + '</code></div>'
      : '';

    // 出力が長すぎるときはサーバ側で行を切っている。黙って隠すと
    // 「ここから先は合っている」と誤解させるので、切ったことを書いておく
    var truncatedNote = c.diffTruncated
      ? '<div class="out-note">差分が長いため最初の' + c.diff.length
        + '行だけを表示しています。まずはこの範囲を直してみましょう。</div>'
      : '';

    return inputRow +
      '<table class="diff-table">' +
      '<thead><tr><th></th><th>期待する出力</th><th>あなたの出力</th></tr></thead>' +
      '<tbody>' + rows + '</tbody></table>' + truncatedNote;
  }

  function showError(e, taskId) {
    var result = document.getElementById('result-' + taskId);
    if (result) {
      result.innerHTML = '<div class="card card-result ng"><div class="err">'
        + esc(e.message) + '</div></div>';
    }
  }

  /** 問題に紐づかない操作（クイズの回答・進捗リセット）の失敗。置き場所が無いので通知で出す。 */
  function toastError(e) {
    toast('⚠️ ' + e.message);
  }

  // ------------------------------------------------------------------ ヒント

  function renderRevealedHints(lesson, task) {
    var host = document.getElementById('hints-' + task.id);
    host.innerHTML = '';
    // 開示済みのヒント本文は /api/state に入っているので、開き直しでも通信は要らない
    (task.revealedHints || []).forEach(function (text, index) {
      appendHint(task.id, index, text);
    });
    maybeShowSolutionButton(lesson.id, task.id);
  }

  function appendHint(taskId, index, text) {
    var host = document.getElementById('hints-' + taskId);
    if (host.querySelector('[data-hint="' + index + '"]')) { return; }
    var box = document.createElement('div');
    box.className = 'card card-hint';
    box.setAttribute('data-hint', String(index));
    box.innerHTML = '<div class="hint-no">ヒント ' + (index + 1) + '</div>'
      + '<div class="hint-text">' + renderMarkdown(text) + '</div>';
    host.appendChild(box);
  }

  function revealedHintCount(taskId) {
    return document.querySelectorAll('#hints-' + taskId + ' [data-hint]').length;
  }

  function revealNextHint(taskId) {
    var lesson = findLesson(currentId);
    var task = findTask(lesson, taskId);
    var next = revealedHintCount(taskId);
    if (next >= task.hintCount) { return; }
    api('hint', { lessonId: currentId, taskId: taskId, index: next })
      .then(function (res) {
        appendHint(taskId, res.index, res.text);
        task.hintsRevealed = res.hintsRevealed;
        // 手元にも残しておく（このレッスンを開き直したときに再取得しないため）
        task.revealedHints = task.revealedHints || [];
        task.revealedHints[res.index] = res.text;
        var btn = document.querySelector('#task-' + taskId + ' .hint-btn');
        var left = task.hintCount - res.hintsRevealed;
        btn.textContent = left > 0 ? '💡 ヒント（残り' + left + '）' : '💡 ヒントはすべて表示済み';
        btn.disabled = left <= 0;
        if (res.solutionUnlocked) { maybeShowSolutionButton(currentId, taskId); }
      })
      .catch(function (e) { showError(e, taskId); });
  }

  function maybeShowSolutionButton(lessonId, taskId) {
    var lesson = findLesson(lessonId);
    var task = lesson && findTask(lesson, taskId);
    if (!task || !task.hasSolution) { return; }
    var host = document.getElementById('solution-' + taskId);
    if (!host || host.querySelector('.solution-row')) { return; }
    if (!task.cleared && revealedHintCount(taskId) < task.hintCount) { return; }

    var row = document.createElement('div');
    row.className = 'solution-row';
    row.innerHTML = '<button class="ghost-btn" data-role="solution">📖 模範解答を見る</button>';
    host.appendChild(row);

    row.querySelector('[data-role="solution"]').addEventListener('click', function () {
      api('solution', { lessonId: lessonId, taskId: taskId })
        .then(function (res) {
          row.innerHTML = '<div class="card card-solution">'
            + '<div class="solution-head">📖 模範解答'
            + '<button class="ghost-btn small" data-role="copy">エディタに入れる</button></div>'
            + '<pre class="code"><code>' + hlJava(res.solution) + '</code></pre>'
            + '<p class="solution-note">写すだけでなく、1行ずつ「なぜそう書くのか」を'
            + '声に出して説明できるか試してみましょう。</p></div>';
          row.querySelector('[data-role="copy"]').addEventListener('click', function () {
            editors[taskId].setValue(res.solution);
            editors[taskId].focus();
            scheduleSave(taskId);
          });
        })
        .catch(function (e) { showError(e, taskId); });
    });
  }

  // -------------------------------------------------------------- お祝い演出

  function toast(message) {
    var el = document.getElementById('toast');
    el.textContent = message;
    el.classList.add('show');
    setTimeout(function () { el.classList.remove('show'); }, 2600);
  }

  function celebrate(res, lessonId, taskId) {
    var lesson = findLesson(lessonId);
    if (res.newStar) {
      var task = lesson && findTask(lesson, taskId);
      // 1レッスンに複数問あるので、どの問題で★が付いたのか分かるようにする
      var label = lesson ? lesson.title : '';
      if (task && lesson && lesson.taskCount > 1) { label += '（' + task.label + '）'; }
      toast('★ 注文完了！　' + label);
    }
    if (res.allChaptersCleared) {
      // 章数・問題数はカリキュラムから取る（章を足しても文言が古びないように）
      showOverlay('🏆', '全問制覇！',
        '全' + state.chapters.length + '章 ' + state.totalTasks + '問、すべてクリアです。'
        + 'ここまで自分の手で書いてきたことが、そのまま力になっています。',
        null);
    } else if (res.chapterCleared) {
      showOverlay('🎉', '第' + res.chapterNumber + '章クリア！',
        '「' + res.chapterTitle + '」を全問クリアしました。カフェにも章制覇ボーナスが届きました。'
          + ' ブランド倍率も、この章で身につけた問題数に応じて成長しました！',
        res.next);
    }
  }

  function showOverlay(emoji, title, body, next) {
    var overlay = document.getElementById('overlay');
    document.getElementById('overlayEmoji').textContent = emoji;
    document.getElementById('overlayTitle').textContent = title;
    document.getElementById('overlayBody').textContent = body;

    var btn = document.getElementById('overlayBtn');
    btn.textContent = next ? '次の章へ進む' : '閉じる';
    overlay.hidden = false;
    dropConfetti();

    btn.onclick = function () {
      overlay.hidden = true;
      goToTask(next);
    };
  }

  function dropConfetti() {
    var host = document.getElementById('confetti');
    host.innerHTML = '';
    var colors = ['#f4b942', '#e8734a', '#5aa9e6', '#7ad151', '#c77dff'];
    for (var i = 0; i < 60; i++) {
      var p = document.createElement('i');
      p.style.left = Math.random() * 100 + '%';
      p.style.background = colors[i % colors.length];
      p.style.animationDelay = (Math.random() * 0.6).toFixed(2) + 's';
      p.style.animationDuration = (1.6 + Math.random() * 1.2).toFixed(2) + 's';
      p.style.transform = 'rotate(' + Math.floor(Math.random() * 360) + 'deg)';
      host.appendChild(p);
    }
  }

  // ------------------------------------------------------------------ 画面遷移

  /** URLのハッシュから、いま表示すべき画面を決める。知らないIDなら学習ホームに落とす。 */
  function routeFromHash() {
    var hash = location.hash.replace(/^#/, '');
    if (hash === 'cafe') { return { view: 'cafe', id: null }; }
    if (hash && findLesson(hash)) { return { view: 'lesson', id: hash }; }
    return { view: 'menu', id: null };
  }

  /** 現在の画面状態に合わせて描く。 */
  function render() {
    var isHub = currentView !== 'lesson';
    document.body.classList.toggle('view-menu', isHub);
    document.body.classList.toggle('view-cafe', currentView === 'cafe');
    renderHeader();
    // サイドバーはメニュー画面でも描いておく（☰で開けるように）。
    renderSidebar();
    if (currentView === 'menu') {
      renderMenu();
    } else if (currentView === 'cafe') {
      renderCafe();
    } else {
      renderLesson();
    }
    syncCafePassiveMode();
  }

  function selectLesson(id) {
    if (!findLesson(id)) { return; }
    currentId = id;
    currentView = 'lesson';
    try { localStorage.setItem('jq-last-lesson', id); } catch (e) { /* 使えなくても困らない */ }
    if (location.hash.replace(/^#/, '') !== id) { location.hash = id; }
    render();
  }

  function goHome() {
    currentId = null;
    currentView = 'menu';
    if (location.hash !== '#menu') { location.hash = 'menu'; }
    render();
  }

  function goCafe() {
    currentId = null;
    currentView = 'cafe';
    if (location.hash !== '#cafe') { location.hash = 'cafe'; }
    render();
  }

  function boot() {
    api('state')
      .then(function (data) {
        setState(data);
        // ハッシュ付きで開いたときだけそのレッスンへ。それ以外はメインメニューから始める
        var route = routeFromHash();
        currentView = route.view;
        currentId = route.id;
        render();
      })
      .catch(function (e) {
        document.getElementById('content').innerHTML =
          '<div class="card card-result ng"><div class="err">'
          + '読み込みに失敗しました: ' + esc(e.message) + '</div></div>';
      });
  }

  document.getElementById('homeBtn').addEventListener('click', goHome);
  document.getElementById('learningBtn').addEventListener('click', goHome);
  document.getElementById('cafeBtn').addEventListener('click', goCafe);

  // ── サイドバー全体の開閉（既定は非表示。集中を妨げないため） ─────
  var SIDEBAR_HIDE_KEY = 'jq-sidebar-hidden';
  function isSidebarHidden() {
    try {
      var v = localStorage.getItem(SIDEBAR_HIDE_KEY);
      return v === null ? true : v === '1';   // 未設定なら隠す
    } catch (e) { return true; }
  }
  function applySidebarVisibility() {
    var hidden = isSidebarHidden();
    document.body.classList.toggle('sidebar-hidden', hidden);
    var btn = document.getElementById('sidebarToggle');
    if (btn) { btn.setAttribute('aria-expanded', String(!hidden)); }
  }
  document.getElementById('sidebarToggle').addEventListener('click', function () {
    var next = !isSidebarHidden();
    try { localStorage.setItem(SIDEBAR_HIDE_KEY, next ? '1' : '0'); } catch (e) { /* 使えなくても困らない */ }
    applySidebarVisibility();
  });
  applySidebarVisibility();

  function resetProgress() {
    if (!window.confirm('★・書いたコード・カフェのコイン・店舗・設備・アイテムがすべて消えます。本当にリセットしますか？')) { return; }
    api('reset', {})
      .then(function (data) {
        setState(data);
        sideExpanded = {};
        try { localStorage.removeItem('jq-last-lesson'); } catch (e) { /* 同上 */ }
        goHome();
        toast('進捗をリセットしました');
      })
      .catch(toastError);
  }

  window.addEventListener('hashchange', function () {
    var route = routeFromHash();
    if (route.view === currentView && route.id === currentId) { return; }
    currentView = route.view;
    currentId = route.id;
    if (currentId) {
      try { localStorage.setItem('jq-last-lesson', currentId); } catch (e) { /* 同上 */ }
    }
    render();
  });

  function beaconCafePassiveStop() {
    var sessionId = cafePassiveSessionId;
    cafePassiveSessionId = null;
    cafePassiveBusy = false;
    if (cafePassiveTimer) {
      clearInterval(cafePassiveTimer);
      cafePassiveTimer = null;
    }
    if (!sessionId || !navigator.sendBeacon) { return; }
    var payload = new Blob([JSON.stringify({ sessionId: sessionId })], { type: 'application/json' });
    navigator.sendBeacon('/api/cafe/passive/stop', payload);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      beaconCafePassiveStop();
    } else {
      syncCafePassiveMode();
    }
  });
  window.addEventListener('pagehide', beaconCafePassiveStop);

  boot();
})();
