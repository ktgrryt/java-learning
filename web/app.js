/*
 * 画面の組み立てとサーバとのやり取り。
 *
 * 状態はサーバの /api/state が唯一の情報源。提出のたびにサーバが最新の state を
 * 返してくるので、画面はそれを受け取って描き直すだけにしている（画面側で★を
 * 数え直さないことで、リロードしてもズレない）。
 *
 * 画面は2つだけ。URLのハッシュで切り替える。
 *   #menu（または空） … メインメニュー
 *   #3-2 のようなID    … そのレッスン
 * レッスンの順番にロックはかけていない。どこからでも自由に開ける。
 */
(function () {
  'use strict';

  var md = window.JQMarkdown.render;
  var esc = window.JQHighlight.escapeHtml;
  var hlJava = window.JQHighlight.java;

  var state = null;        // サーバから受け取った最新の state
  var currentId = null;    // いま開いているレッスンID（メニュー表示中は null）
  var editors = {};        // 問題ID -> エディタ（1レッスンに複数問あるので複数持つ）
  var saveTimers = {};     // 問題ID -> 自動保存のタイマー
  var busyTask = null;     // 実行・採点中の問題ID（同時に走らせない）
  var expanded = {};       // メニューで開いている章のID
  var sideExpanded = {};   // サイドバーで開いている章のID（既定は全部たたむ）

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
      return res.json().then(function (data) {
        if (!res.ok) { throw new Error(data.error || ('通信に失敗しました (' + res.status + ')')); }
        return data;
      });
    });
  }

  // ------------------------------------------------------- state のとり回し

  function allLessons() {
    var list = [];
    state.chapters.forEach(function (ch) {
      ch.lessons.forEach(function (l) { list.push(l); });
    });
    return list;
  }

  function findLesson(id) {
    var found = null;
    allLessons().forEach(function (l) { if (l.id === id) { found = l; } });
    return found;
  }

  function chapterOf(id) {
    var found = null;
    state.chapters.forEach(function (ch) {
      ch.lessons.forEach(function (l) { if (l.id === id) { found = ch; } });
    });
    return found;
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

  // ------------------------------------------------------------ ヘッダ描画

  function renderHeader() {
    var total = state.totalTasks;
    var stars = state.progress.starCount;
    var pct = total ? Math.round((stars / total) * 100) : 0;

    document.getElementById('overallFill').style.width = pct + '%';
    document.getElementById('overallText').textContent = stars + ' / ' + total + ' クリア';
    document.querySelector('#statStars b').textContent = stars;
    document.querySelector('#statStreak b').textContent = state.progress.streak;
  }

  // -------------------------------------------------------- サイドバー描画

  function renderSidebar() {
    var nav = document.getElementById('sidebar');
    nav.innerHTML = '';

    state.chapters.forEach(function (ch) {
      var total = ch.taskCount;
      var pct = Math.round((ch.clearedCount / total) * 100);
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

      // 全部クリアなら ✅、途中なら「2/9」（分母は章の問題数）。
      // まだ0件の章は数字を強調しない（0が目立つと、進んでいるように見えてしまう）
      var status = ch.cleared
        ? '<span class="ch-done">✅</span>'
        : '<span class="ch-count' + (ch.clearedCount ? '' : ' ch-count-zero') + '">'
          + '<b>' + ch.clearedCount + '</b>/' + total + '</span>';

      section.innerHTML =
        '<button type="button" class="ch-head" aria-expanded="' + open + '">' +
        '  <span class="ch-emoji">' + esc(ch.emoji) + '</span>' +
        '  <span class="ch-titles">' +
        '    <span class="ch-title">第' + ch.number + '章　' + esc(ch.title) + '</span>' +
        '    <span class="ch-sub">' + esc(ch.subtitle) + '</span>' +
        '  </span>' +
        '  <span class="ch-status">' + status + '</span>' +
        '  <span class="ch-caret">' + (open ? '▲' : '▼') + '</span>' +
        '</button>' +
        '<div class="ch-bar"><div class="ch-bar-fill" style="width:' + pct + '%"></div></div>';

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
          '<span class="lesson-id">' + esc(l.id) + '</span>' +
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
   * レッスン行に出す「1/2」。
   *
   * 1問しかないレッスンでは★か○だけで足りるので出さない。複数問あるレッスンで
   * 途中まで進んでいるときに、あと何問残っているかが分かるようにする。
   */
  function lessonTaskProgress(lesson) {
    if (lesson.taskCount < 2 || lesson.cleared) { return ''; }
    return '<span class="lesson-frac">' + (lesson.clearedCount || 0)
      + '/' + lesson.taskCount + '</span>';
  }

  function lessonTooltip(lesson) {
    if (lesson.cleared) { return 'クリア済み: ' + lesson.title; }
    if (lesson.taskCount < 2) { return lesson.title; }
    return lesson.title + '（' + (lesson.clearedCount || 0) + '/' + lesson.taskCount + '問クリア）';
  }

  // ------------------------------------------------------ メインメニュー描画

  function renderMenu() {
    var total = state.totalTasks;
    var stars = state.progress.starCount;
    var pct = total ? Math.round((stars / total) * 100) : 0;
    var done = stars === total;

    var main = document.getElementById('content');
    main.innerHTML =
      '<div class="menu">' +
      renderMenuHero(stars, total, pct, done) +
      renderMenuStats() +
      '  <section class="menu-section">' +
      '    <h2 class="menu-h2">章を選ぶ</h2>' +
      '    <p class="menu-note">好きな章から始められます。カードを押すとレッスン一覧が開きます。</p>' +
      '    <div class="ch-grid" id="chGrid"></div>' +
      '  </section>' +
      renderMenuGuide(stars) +
      '</div>';

    renderChapterCards();

    var goBtn = document.getElementById('continueBtn');
    if (goBtn) {
      goBtn.addEventListener('click', function () { selectLesson(goBtn.dataset.target); });
    }
    main.scrollTop = 0;
  }

  /** 上部の見出しと「続ける」ボタン。進捗はリング（円グラフ）で見せる。 */
  function renderMenuHero(stars, total, pct, done) {
    var target = resumeTarget();
    var lesson = findLesson(target);
    var label = done ? '🏆 もう一度見なおす' : (stars === 0 ? '▶ はじめる' : '▶ 続ける');
    var lead = done ? '全レッスン制覇！ おつかれさまでした'
      : (stars === 0 ? 'まずはここから' : '前回の続きはここから');

    // 円周 = 2πr。r=52 なので約326.7。塗り残す長さで進捗を表す
    var circumference = 2 * Math.PI * 52;
    var offset = circumference * (1 - pct / 100);

    return '' +
      '<section class="menu-hero">' +
      '  <div class="hero-ring">' +
      '    <svg viewBox="0 0 120 120" aria-hidden="true">' +
      '      <circle class="ring-track" cx="60" cy="60" r="52"></circle>' +
      '      <circle class="ring-fill" cx="60" cy="60" r="52"' +
      '              stroke-dasharray="' + circumference.toFixed(1) + '"' +
      '              stroke-dashoffset="' + offset.toFixed(1) + '"></circle>' +
      '    </svg>' +
      '    <div class="ring-label"><span class="ring-num">' +
      '      <b>' + pct + '</b><i>%</i></span></div>' +
      '  </div>' +
      '  <div class="hero-body">' +
      '    <h1 class="hero-title">☕ Java Quest</h1>' +
      '    <p class="hero-sub">手を動かしながらJavaを学ぶ　·　★ ' + stars + ' / ' + total + ' クリア</p>' +
      '    <div class="hero-cta">' +
      '      <div class="cta-lead">' + esc(lead) + '</div>' +
      '      <button class="primary-btn big" id="continueBtn" data-target="' + esc(target) + '">' +
             esc(label) +
      '      </button>' +
      (lesson ? '      <div class="cta-target"><span class="cta-id">' + esc(lesson.id) + '</span>'
        + esc(lesson.title) + '</div>' : '') +
      '    </div>' +
      '  </div>' +
      '</section>';
  }

  /** 学習の記録。★・連続日数・通過したテストケース・提出回数。 */
  function renderMenuStats() {
    // クリアした問題だけを数えると、8件中7件通っている問題が0件扱いになって
    // 実際より進んでいないように見える。サーバが持っている最高記録で数える。
    var casesPassed = allTasks().reduce(function (n, t) {
      return n + (t.cleared ? t.totalCaseCount : (t.passedCount || 0));
    }, 0);
    var casesTotal = allTasks().reduce(function (n, t) { return n + t.totalCaseCount; }, 0);
    var attempts = sumValues(state.progress.attempts);

    var tiles = [
      { icon: '★', value: state.progress.starCount, unit: '/ ' + state.totalTasks, label: 'クリアした問題' },
      { icon: '🔥', value: state.progress.streak, unit: '日', label: '連続で学習した日数' },
      { icon: '✅', value: casesPassed, unit: '/ ' + casesTotal, label: '通過したテストケース' },
      { icon: '✍️', value: attempts, unit: '回', label: '提出した回数' }
    ];
    if (state.quizTotal) {
      tiles.push({
        icon: '🧠',
        value: state.quizCorrect,
        unit: '/ ' + state.quizTotal,
        label: '正解した確認クイズ'
      });
    }

    return '' +
      '<section class="menu-section">' +
      '  <h2 class="menu-h2">学習の記録</h2>' +
      '  <div class="stat-grid">' +
      tiles.map(function (t) {
        return '<div class="stat-tile">' +
          '<div class="stat-icon">' + t.icon + '</div>' +
          '<div class="stat-value">' + t.value + '<span class="stat-unit">' + esc(t.unit) + '</span></div>' +
          '<div class="stat-label">' + esc(t.label) + '</div>' +
          '</div>';
      }).join('') +
      '  </div>' +
      '</section>';
  }

  /** 章のカード。押すと中にレッスン一覧が開く。 */
  function renderChapterCards() {
    var grid = document.getElementById('chGrid');
    var todoChapter = chapterOf(firstTodo());

    state.chapters.forEach(function (ch) {
      var total = ch.taskCount;
      var pct = Math.round((ch.clearedCount / total) * 100);
      // まだ初回なら、次にやる章だけ最初から開いておく
      if (expanded[ch.id] === undefined && todoChapter && ch.id === todoChapter.id) {
        expanded[ch.id] = true;
      }
      var open = !!expanded[ch.id];

      var card = document.createElement('section');
      card.className = 'ch-card' + (ch.cleared ? ' ch-card-cleared' : '') + (open ? ' open' : '');

      var status = ch.cleared
        ? '<span class="ch-card-done">✅ クリア</span>'
        : '<span class="ch-card-count' + (ch.clearedCount ? '' : ' zero') + '">'
          + '<b>' + ch.clearedCount + '</b> / ' + total + '</span>';

      card.innerHTML =
        '<button class="ch-card-head" aria-expanded="' + open + '">' +
        '  <span class="ch-card-emoji">' + esc(ch.emoji) + '</span>' +
        '  <span class="ch-card-titles">' +
        '    <span class="ch-card-no">第' + ch.number + '章</span>' +
        '    <span class="ch-card-title">' + esc(ch.title) + '</span>' +
        '    <span class="ch-card-sub">' + esc(ch.subtitle) + '</span>' +
        '  </span>' +
        '  <span class="ch-card-right">' + status +
        '    <span class="ch-card-caret">' + (open ? '▲' : '▼') + '</span>' +
        '  </span>' +
        '</button>' +
        '<div class="ch-card-bar"><div class="ch-card-bar-fill" style="width:' + pct + '%"></div></div>' +
        '<ul class="ch-card-lessons"' + (open ? '' : ' hidden') + '></ul>';

      var ul = card.querySelector('.ch-card-lessons');
      ch.lessons.forEach(function (l) {
        var li = document.createElement('li');
        li.className = 'ch-card-lesson' + (l.cleared ? ' cleared' : '');
        li.innerHTML =
          '<span class="ccl-mark">' + (l.cleared ? '★' : '○') + '</span>' +
          '<span class="ccl-id">' + esc(l.id) + '</span>' +
          '<span class="ccl-title">' + esc(l.title) + '</span>' +
          lessonTaskProgress(l) +
          '<span class="ccl-go">→</span>';
        li.addEventListener('click', function () { selectLesson(l.id); });
        ul.appendChild(li);
      });

      card.querySelector('.ch-card-head').addEventListener('click', function () {
        expanded[ch.id] = !expanded[ch.id];
        grid.innerHTML = '';
        renderChapterCards();
      });

      grid.appendChild(card);
    });
  }

  /** 使い方の案内。まだ1問もクリアしていない人には最初から開いて見せる。 */
  function renderMenuGuide(stars) {
    var steps = [
      ['📖', '解説を読む', '具体例つきで、1つの話題に絞って書いてあります。'],
      ['▶', 'サンプルを動かす', '解説中のコードは「▶ サンプルを実行」でその場で動きます。まず動かすのが理解の近道です。'],
      ['⌨️', '自分で書く', 'ひな形から書き始められます。行番号・色付け・自動インデントつきです。'],
      ['✓', '提出して採点', '隠しテストを含む全ケースで採点します。全部通ればクリア、★が付きます。'],
      ['🔁', '同じ回に2問目', '解説で出てきた話は、なるべく全部その場で書いて確かめられるようにしています。'],
      ['💡', '詰まったらヒント', '1つずつ開けます。全部開くと模範解答も見られます。使ってもクリア扱いです。']
    ];

    return '' +
      '<section class="menu-section">' +
      '  <details class="guide"' + (stars === 0 ? ' open' : '') + '>' +
      '    <summary class="guide-summary">使い方（1レッスンの流れ）</summary>' +
      '    <ol class="guide-steps">' +
      steps.map(function (s) {
        return '<li><span class="guide-icon">' + s[0] + '</span>' +
          '<span class="guide-text"><b>' + esc(s[1]) + '</b>' + esc(s[2]) + '</span></li>';
      }).join('') +
      '    </ol>' +
      '    <div class="guide-keys">' +
      '      <span><kbd>⌘</kbd>/<kbd>Ctrl</kbd> + <kbd>Enter</kbd> 提出</span>' +
      '      <span><kbd>⌘</kbd>/<kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>Enter</kbd> 実行</span>' +
      '      <span><kbd>Tab</kbd> インデント</span>' +
      '    </div>' +
      '    <p class="guide-foot">書いたコードは自動で保存されます。ブラウザを閉じても続きから再開できます。</p>' +
      '  </details>' +
      '</section>';
  }

  // ------------------------------------------------------------ 本文の描画

  function renderLesson() {
    var lesson = findLesson(currentId);
    var chapter = chapterOf(currentId);
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
             esc(chapter.emoji) + ' 第' + chapter.number + '章 ' + esc(chapter.title) +
      '    </div>' +
      '    <h1 class="lesson-h1">' +
      '      <span class="lesson-h1-id">' + esc(lesson.id) + '</span>' + esc(lesson.title) +
             (lesson.cleared ? '<span class="badge badge-clear">★ クリア済み</span>' : '') +
      '    </h1>' +
      '  </div>' +

      '  <section class="card card-explain">' + md(lesson.explanation) + '</section>' +

      '  <section class="samples" id="samples"></section>' +

      '  <section class="tasks" id="tasks"></section>' +
      '  <section class="quiz" id="quiz"></section>' +
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
    main.scrollTop = 0;
  }

  // ------------------------------------------------------- 練習問題1問ぶん

  /**
   * 練習問題1問（問題文 + エディタ + ヒント + 採点結果）のかたまりを作る。
   *
   * 1レッスンに複数問あるので、DOMのidは問題ごとに接尾辞を付けて衝突させない。
   * エディタも問題ごとに別インスタンスにする（textarea が別なので、書きかけの
   * コードも採点結果も混ざらない）。
   *
   * すでにクリアした問題は headerだけに畳んでおく。開き直したときに全問が
   * 開いていると、いま解くべき問題がどれか分からなくなるため。
   */
  function buildTaskBlock(lesson, task, index) {
    var n = task.id;
    var collapsed = task.cleared;

    var block = document.createElement('section');
    block.className = 'task-block' + (collapsed ? ' is-collapsed' : '');
    block.id = 'task-' + n;
    block.innerHTML =
      '<button type="button" class="task-block-head" aria-expanded="' + (!collapsed) + '">' +
      '  <span class="task-no">問題' + (index + 1) + '</span>' +
      '  <span class="task-kind task-kind-' + esc(task.kind) + '">' + esc(task.label) + '</span>' +
      '  <span class="task-head-status" id="taskStatus-' + n + '">' +
           (task.cleared ? '★ クリア済み' : '') +
      '  </span>' +
      '  <span class="task-caret">' + (collapsed ? '▼' : '▲') + '</span>' +
      '</button>' +

      '<div class="task-block-body">' +
      '  <div class="card card-task">' +
      '    <div class="task-body">' + md(task.task) + '</div>' +
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

    block.querySelector('.task-block-head').addEventListener('click', function () {
      toggleTaskBlock(n);
    });

    // 開示済みヒントの描画は、このかたまりを document に挿してから
    // （renderRevealedHints は id で引くので、繋ぐ前だと見つからない）
    return block;
  }

  function toggleTaskBlock(taskId, forceOpen) {
    var block = document.getElementById('task-' + taskId);
    if (!block) { return; }
    var collapsed = forceOpen === true ? false : !block.classList.contains('is-collapsed');
    block.classList.toggle('is-collapsed', collapsed);
    block.querySelector('.task-block-head').setAttribute('aria-expanded', String(!collapsed));
    block.querySelector('.task-caret').textContent = collapsed ? '▼' : '▲';
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
        '<span class="quiz-choice-text">' + md(text) + '</span>' +
        '</button>';
    }).join('');

    return '<div class="quiz-item">' +
      '  <div class="quiz-q"><span class="quiz-no">Q' + (index + 1) + '</span>' + md(quiz.question) + '</div>' +
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
      (result.explanation ? '<div class="quiz-explain">' + md(result.explanation) + '</div>' : '') +
      '</div>';
  }

  function answerQuiz(index, choice) {
    var lessonId = currentId;
    api('quiz', { lessonId: lessonId, index: index, choice: choice })
      .then(function (res) {
        applyDelta(res.delta);
        var lesson = findLesson(lessonId);
        if (lesson && lessonId === currentId) { renderQuiz(lesson); }
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
    html += '<h2 class="card-h"><span class="card-h-icon">' + (res.allPass ? '🎉' : '🔍') + '</span>'
      + (res.allPass ? 'クリア！全ケース通過' : '結果: ' + res.passedCount + ' / ' + total + ' 通過')
      + '</h2>';

    if (!res.allPass) {
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

    if (res.allPass) {
      // 次が同じレッスンの中なら「次の問題へ」。まだ解説を読み終えた流れの中にいるので、
      // レッスンを離れずにその問題まで運ぶ。
      var next = res.next;
      var sameLesson = next && next.lessonId === currentId;
      html += '<div class="next-row">'
        + (next
          ? '<button class="primary-btn" id="nextBtn-' + taskId + '">'
            + (sameLesson ? '次の問題へ ↓' : '次のレッスンへ →') + '</button>'
          : '<span class="all-done">これで全問完了です。おつかれさまでした！</span>')
        + '</div>';
    }

    result.innerHTML = html + '</div>';

    var nextBtn = document.getElementById('nextBtn-' + taskId);
    if (nextBtn) {
      nextBtn.addEventListener('click', function () { goToTask(res.next); });
    }
  }

  /** 次の問題へ移る。同じレッスン内ならスクロールするだけ。 */
  function goToTask(next) {
    if (!next) { return; }
    if (next.lessonId !== currentId) {
      selectLesson(next.lessonId);
      return;
    }
    toggleTaskBlock(next.taskId, true);
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

    return inputRow +
      '<table class="diff-table">' +
      '<thead><tr><th></th><th>期待する出力</th><th>あなたの出力</th></tr></thead>' +
      '<tbody>' + rows + '</tbody></table>';
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
      + '<div class="hint-text">' + md(text) + '</div>';
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
    var host = document.getElementById('hints-' + taskId);
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
      toast('★ 獲得！　' + label);
    }
    if (res.allChaptersCleared) {
      // 章数・問題数はカリキュラムから取る（章を足しても文言が古びないように）
      showOverlay('🏆', '全問制覇！',
        '全' + state.chapters.length + '章 ' + state.totalTasks + '問、すべてクリアです。'
        + 'ここまで自分の手で書いてきたことが、そのまま力になっています。',
        null);
    } else if (res.chapterCleared) {
      showOverlay('🎉', '第' + res.chapterNumber + '章クリア！',
        '「' + res.chapterTitle + '」を全問クリアしました。この調子で次の章へ進みましょう。',
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

  /** URLのハッシュから、いま表示すべき画面を決める。知らないIDならメニューに落とす。 */
  function routeFromHash() {
    var hash = location.hash.replace(/^#/, '');
    if (hash && findLesson(hash)) { return hash; }
    return null;   // null = メインメニュー
  }

  /** 現在の currentId に合わせて画面を描く。メニューではサイドバーを隠す。 */
  function render() {
    var isMenu = currentId === null;
    document.body.classList.toggle('view-menu', isMenu);
    renderHeader();
    // サイドバーはメニュー画面でも描いておく（☰で開けるように）。
    renderSidebar();
    if (isMenu) {
      renderMenu();
    } else {
      renderLesson();
    }
  }

  function selectLesson(id) {
    if (!findLesson(id)) { return; }
    currentId = id;
    try { localStorage.setItem('jq-last-lesson', id); } catch (e) { /* 使えなくても困らない */ }
    if (location.hash.replace(/^#/, '') !== id) { location.hash = id; }
    render();
  }

  function goHome() {
    currentId = null;
    if (location.hash !== '#menu') { location.hash = 'menu'; }
    render();
  }

  function boot() {
    api('state')
      .then(function (data) {
        state = data;
        // ハッシュ付きで開いたときだけそのレッスンへ。それ以外はメインメニューから始める
        currentId = routeFromHash();
        render();
      })
      .catch(function (e) {
        document.getElementById('content').innerHTML =
          '<div class="card card-result ng"><div class="err">'
          + '読み込みに失敗しました: ' + esc(e.message) + '</div></div>';
      });
  }

  document.getElementById('homeBtn').addEventListener('click', goHome);

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

  document.getElementById('resetBtn').addEventListener('click', function () {
    if (!window.confirm('★も書いたコードもすべて消えます。本当にリセットしますか？')) { return; }
    api('reset', {})
      .then(function (data) {
        state = data;
        expanded = {};
        sideExpanded = {};
        try { localStorage.removeItem('jq-last-lesson'); } catch (e) { /* 同上 */ }
        goHome();
        toast('進捗をリセットしました');
      })
      .catch(toastError);
  });

  window.addEventListener('hashchange', function () {
    var target = routeFromHash();
    if (target !== currentId) {
      if (target === null) { goHome(); } else { selectLesson(target); }
    }
  });

  boot();
})();
