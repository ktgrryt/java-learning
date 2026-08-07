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
  var editor = null;
  var saveTimer = null;
  var busy = false;
  var expanded = {};       // メニューで開いている章のID

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

  function clearedLessons() {
    return allLessons().filter(function (l) { return l.cleared; });
  }

  function sumValues(obj) {
    var total = 0;
    Object.keys(obj || {}).forEach(function (k) { total += obj[k]; });
    return total;
  }

  // ------------------------------------------------------------ ヘッダ描画

  function renderHeader() {
    var total = state.totalLessons;
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
      var total = ch.lessons.length;
      var pct = Math.round((ch.clearedCount / total) * 100);
      var isCurrentChapter = ch.lessons.some(function (l) { return l.id === currentId; });

      var section = document.createElement('section');
      section.className = 'ch'
        + (ch.cleared ? ' ch-cleared' : '')
        + (isCurrentChapter ? ' ch-current' : '');

      // 全部クリアなら ✅、途中なら「2/4」。どの章がどこまで進んだか一目で分かるように。
      // まだ0件の章は数字を強調しない（0が目立つと、進んでいるように見えてしまう）
      var status = ch.cleared
        ? '<span class="ch-done">✅</span>'
        : '<span class="ch-count' + (ch.clearedCount ? '' : ' ch-count-zero') + '">'
          + '<b>' + ch.clearedCount + '</b>/' + total + '</span>';

      section.innerHTML =
        '<div class="ch-head">' +
        '  <span class="ch-emoji">' + esc(ch.emoji) + '</span>' +
        '  <span class="ch-titles">' +
        '    <span class="ch-title">第' + ch.number + '章　' + esc(ch.title) + '</span>' +
        '    <span class="ch-sub">' + esc(ch.subtitle) + '</span>' +
        '  </span>' +
        '  <span class="ch-status">' + status + '</span>' +
        '</div>' +
        '<div class="ch-bar"><div class="ch-bar-fill" style="width:' + pct + '%"></div></div>';

      var ul = document.createElement('ul');
      ul.className = 'lessons';
      ch.lessons.forEach(function (l) {
        var li = document.createElement('li');
        li.className = 'lesson'
          + (l.cleared ? ' lesson-cleared' : '')
          + (l.id === currentId ? ' lesson-current' : '');
        li.innerHTML =
          '<span class="lesson-mark">' + (l.cleared ? '★' : '○') + '</span>' +
          '<span class="lesson-id">' + esc(l.id) + '</span>' +
          '<span class="lesson-title">' + esc(l.title) + '</span>';
        li.title = (l.cleared ? 'クリア済み: ' : '') + l.title;
        li.addEventListener('click', function () { selectLesson(l.id); });
        ul.appendChild(li);
      });
      section.appendChild(ul);
      nav.appendChild(section);
    });
  }

  // ------------------------------------------------------ メインメニュー描画

  function renderMenu() {
    var total = state.totalLessons;
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
    var cleared = clearedLessons();
    var casesPassed = cleared.reduce(function (n, l) { return n + l.totalCaseCount; }, 0);
    var casesTotal = allLessons().reduce(function (n, l) { return n + l.totalCaseCount; }, 0);
    var attempts = sumValues(state.progress.attempts);

    var tiles = [
      { icon: '★', value: state.progress.starCount, unit: '/ ' + state.totalLessons, label: 'クリアしたレッスン' },
      { icon: '🔥', value: state.progress.streak, unit: '日', label: '連続で学習した日数' },
      { icon: '✅', value: casesPassed, unit: '/ ' + casesTotal, label: '通過したテストケース' },
      { icon: '✍️', value: attempts, unit: '回', label: '提出した回数' }
    ];

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
      var total = ch.lessons.length;
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

      '  <section class="card card-task">' +
      '    <h2 class="card-h"><span class="card-h-icon">✍️</span>練習問題</h2>' +
      '    <div class="task-body">' + md(lesson.task) + '</div>' +
             renderCasePreview(lesson) +
      '  </section>' +

      '  <section class="card card-code">' +
      '    <div class="code-head">' +
      '      <h2 class="card-h"><span class="card-h-icon">⌨️</span>コードを書く</h2>' +
      '      <div class="code-head-actions">' +
      '        <button class="ghost-btn" id="restoreBtn" title="最初のひな形に戻す">ひな形に戻す</button>' +
      '      </div>' +
      '    </div>' +
      '    <div id="editorHost"></div>' +
      '    <div class="actions">' +
      '      <button class="run-btn" id="runBtn">▶ 実行してみる</button>' +
      '      <button class="primary-btn" id="submitBtn">✓ 提出して採点</button>' +
      '      <span class="spacer"></span>' +
             renderHintButton(lesson) +
      '    </div>' +
      '    <div class="shortcut-note">⌘/Ctrl + Enter で提出　·　⌘/Ctrl + Shift + Enter で実行</div>' +
      '  </section>' +

      '  <section class="hints" id="hints"></section>' +
      '  <section class="result" id="result"></section>' +
      '</article>';

    renderSamples(lesson);

    editor = new window.JQEditor(document.getElementById('editorHost'));
    editor.setValue(lesson.savedCode != null && lesson.savedCode !== ''
      ? lesson.savedCode
      : lesson.starterCode);
    editor.onSubmit = submit;
    editor.onRun = runOnce;
    editor.input.addEventListener('input', scheduleSave);

    document.getElementById('crumbHome').addEventListener('click', goHome);
    document.getElementById('runBtn').addEventListener('click', runOnce);
    document.getElementById('submitBtn').addEventListener('click', submit);
    document.getElementById('restoreBtn').addEventListener('click', function () {
      if (window.confirm('書いたコードを消して、最初のひな形に戻します。よろしいですか？')) {
        editor.setValue(lesson.starterCode);
        editor.focus();
        scheduleSave();
      }
    });

    var hintBtn = document.getElementById('hintBtn');
    if (hintBtn) { hintBtn.addEventListener('click', revealNextHint); }

    // すでに開示済みのヒントは開き直したときにも見えるようにする
    renderRevealedHints(lesson);
    main.scrollTop = 0;
  }

  /** 問題文の下に置く「どんな入出力が試されるか」の表。 */
  function renderCasePreview(lesson) {
    if (!lesson.visibleCases.length && !lesson.hiddenCaseCount) { return ''; }

    var usesStdin = lesson.visibleCases.some(function (c) { return c.stdin !== ''; });
    var rows = lesson.visibleCases.map(function (c) {
      return '<tr>' +
        (usesStdin ? '<td class="io"><pre>' + esc(c.stdin) + '</pre></td>' : '') +
        '<td class="io"><pre>' + esc(c.expected) + '</pre></td>' +
        '</tr>';
    }).join('');

    var hiddenNote = lesson.hiddenCaseCount
      ? '<p class="case-hidden-note">🔒 このほかに <b>' + lesson.hiddenCaseCount
        + '件</b> の隠しテストがあります（提出すると結果が見えます）。'
        + 'たまたま通るコードではなく、どんな入力でも正しく動くコードを書きましょう。</p>'
      : '';

    return '<div class="cases">' +
      '<div class="cases-title">試されること（全' + lesson.totalCaseCount + '件）</div>' +
      '<table class="case-table">' +
      '<thead><tr>' + (usesStdin ? '<th>入力</th>' : '') + '<th>期待する出力</th></tr></thead>' +
      '<tbody>' + rows + '</tbody></table>' +
      hiddenNote +
      '</div>';
  }

  function renderHintButton(lesson) {
    if (!lesson.hintCount) { return ''; }
    var left = lesson.hintCount - lesson.hintsRevealed;
    var label = left > 0 ? '💡 ヒント（残り' + left + '）' : '💡 ヒントはすべて表示済み';
    return '<button class="ghost-btn" id="hintBtn"' + (left > 0 ? '' : ' disabled') + '>'
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
        api('run', { code: sample.code, stdin: sample.stdin || '' })
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

  function scheduleSave() {
    clearTimeout(saveTimer);
    var id = currentId;
    var code = editor.getValue();
    saveTimer = setTimeout(function () {
      api('save', { lessonId: id, code: code }).catch(function () {
        // 保存に失敗しても学習は続けられるので黙って見送る（次の入力で再試行される）
      });
    }, 800);
  }

  function setBusy(on, label) {
    busy = on;
    var runBtn = document.getElementById('runBtn');
    var submitBtn = document.getElementById('submitBtn');
    if (runBtn) { runBtn.disabled = on; }
    if (submitBtn) {
      submitBtn.disabled = on;
      submitBtn.textContent = on ? (label || '実行中…') : '✓ 提出して採点';
    }
  }

  function runOnce() {
    if (busy) { return; }
    var lesson = findLesson(currentId);
    var stdin = lesson.visibleCases.length ? lesson.visibleCases[0].stdin : '';
    var result = document.getElementById('result');
    setBusy(true);
    result.className = 'result';
    result.innerHTML = '<div class="card card-result"><div class="spinner">実行中…</div></div>';

    api('run', { lessonId: currentId, code: editor.getValue(), stdin: stdin })
      .then(function (res) {
        var note = stdin
          ? '<div class="out-note">最初のテストケースの入力（<code>' + esc(stdin)
            + '</code>）を与えて動かしました。採点はしていません。</div>'
          : '<div class="out-note">採点はしていません。まず動かして確かめるためのボタンです。</div>';
        result.innerHTML = '<div class="card card-result">'
          + '<h2 class="card-h"><span class="card-h-icon">▶</span>実行結果</h2>'
          + note + renderRunOutput(res) + '</div>';
      })
      .catch(showError)
      .then(function () { setBusy(false); });
  }

  function submit() {
    if (busy) { return; }
    var result = document.getElementById('result');
    setBusy(true, '採点中…');
    result.innerHTML = '<div class="card card-result"><div class="spinner">採点中…</div></div>';

    api('submit', { lessonId: currentId, code: editor.getValue() })
      .then(function (res) {
        var wasCurrent = currentId;
        state = res.state;
        renderHeader();
        renderSidebar();
        renderJudgement(res);
        if (res.allPass) {
          celebrate(res, wasCurrent);
        }
      })
      .catch(showError)
      .then(function () { setBusy(false); });
  }

  function renderJudgement(res) {
    var result = document.getElementById('result');
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
      var next = res.nextLessonId;
      html += '<div class="next-row">'
        + (next
          ? '<button class="primary-btn" id="nextBtn">次のレッスンへ →</button>'
          : '<span class="all-done">これで全レッスン完了です。おつかれさまでした！</span>')
        + '</div>';
    }

    result.innerHTML = html + '</div>';

    var nextBtn = document.getElementById('nextBtn');
    if (nextBtn) {
      nextBtn.addEventListener('click', function () { selectLesson(res.nextLessonId); });
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

  function showError(e) {
    var result = document.getElementById('result');
    if (result) {
      result.innerHTML = '<div class="card card-result ng"><div class="err">'
        + esc(e.message) + '</div></div>';
    }
  }

  // ------------------------------------------------------------------ ヒント

  function renderRevealedHints(lesson) {
    var host = document.getElementById('hints');
    host.innerHTML = '';
    if (!lesson.hintsRevealed) { return; }
    // 開き直したときは、開示済みの件数ぶんをサーバから取り直す
    var chain = Promise.resolve();
    for (var i = 0; i < lesson.hintsRevealed; i++) {
      (function (index) {
        chain = chain.then(function () {
          return api('hint', { lessonId: lesson.id, index: index })
            .then(function (res) { appendHint(res.index, res.text); });
        });
      })(i);
    }
    chain.then(function () { maybeShowSolutionButton(lesson.id); });
  }

  function appendHint(index, text) {
    var host = document.getElementById('hints');
    if (host.querySelector('[data-hint="' + index + '"]')) { return; }
    var box = document.createElement('div');
    box.className = 'card card-hint';
    box.setAttribute('data-hint', String(index));
    box.innerHTML = '<div class="hint-no">ヒント ' + (index + 1) + '</div>'
      + '<div class="hint-text">' + md(text) + '</div>';
    host.appendChild(box);
  }

  function revealNextHint() {
    var lesson = findLesson(currentId);
    var next = document.querySelectorAll('#hints [data-hint]').length;
    if (next >= lesson.hintCount) { return; }
    api('hint', { lessonId: currentId, index: next })
      .then(function (res) {
        appendHint(res.index, res.text);
        lesson.hintsRevealed = res.hintsRevealed;
        var btn = document.getElementById('hintBtn');
        var left = lesson.hintCount - res.hintsRevealed;
        btn.textContent = left > 0 ? '💡 ヒント（残り' + left + '）' : '💡 ヒントはすべて表示済み';
        btn.disabled = left <= 0;
        if (res.solutionUnlocked) { maybeShowSolutionButton(currentId); }
      })
      .catch(showError);
  }

  function maybeShowSolutionButton(lessonId) {
    var lesson = findLesson(lessonId);
    if (!lesson || !lesson.hasSolution) { return; }
    var host = document.getElementById('hints');
    if (!host || host.querySelector('.solution-row')) { return; }
    var revealed = document.querySelectorAll('#hints [data-hint]').length;
    if (!lesson.cleared && revealed < lesson.hintCount) { return; }

    var row = document.createElement('div');
    row.className = 'solution-row';
    row.innerHTML = '<button class="ghost-btn" id="solutionBtn">📖 模範解答を見る</button>';
    host.appendChild(row);

    document.getElementById('solutionBtn').addEventListener('click', function () {
      api('solution', { lessonId: lessonId })
        .then(function (res) {
          row.innerHTML = '<div class="card card-solution">'
            + '<div class="solution-head">📖 模範解答'
            + '<button class="ghost-btn small" id="copySolution">エディタに入れる</button></div>'
            + '<pre class="code"><code>' + hlJava(res.solution) + '</code></pre>'
            + '<p class="solution-note">写すだけでなく、1行ずつ「なぜそう書くのか」を'
            + '声に出して説明できるか試してみましょう。</p></div>';
          document.getElementById('copySolution').addEventListener('click', function () {
            editor.setValue(res.solution);
            editor.focus();
            scheduleSave();
          });
        })
        .catch(showError);
    });
  }

  // -------------------------------------------------------------- お祝い演出

  function toast(message) {
    var el = document.getElementById('toast');
    el.textContent = message;
    el.classList.add('show');
    setTimeout(function () { el.classList.remove('show'); }, 2600);
  }

  function celebrate(res, lessonId) {
    if (res.newStar) {
      toast('★ 獲得！　' + (findLesson(lessonId) || {}).title);
    }
    if (res.allChaptersCleared) {
      showOverlay('🏆', '全レッスン制覇！',
        '基礎6章、すべてクリアです。ここまで自分の手で書いてきたことが、そのまま力になっています。',
        null);
    } else if (res.chapterCleared) {
      showOverlay('🎉', '第' + res.chapterNumber + '章クリア！',
        '「' + res.chapterTitle + '」を全問クリアしました。この調子で次の章へ進みましょう。',
        res.nextLessonId);
    }
  }

  function showOverlay(emoji, title, body, nextLessonId) {
    var overlay = document.getElementById('overlay');
    document.getElementById('overlayEmoji').textContent = emoji;
    document.getElementById('overlayTitle').textContent = title;
    document.getElementById('overlayBody').textContent = body;

    var btn = document.getElementById('overlayBtn');
    btn.textContent = nextLessonId ? '次の章へ進む' : '閉じる';
    overlay.hidden = false;
    dropConfetti();

    btn.onclick = function () {
      overlay.hidden = true;
      if (nextLessonId) { selectLesson(nextLessonId); }
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
    if (isMenu) {
      document.getElementById('sidebar').innerHTML = '';
      renderMenu();
    } else {
      renderSidebar();
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

  document.getElementById('resetBtn').addEventListener('click', function () {
    if (!window.confirm('★も書いたコードもすべて消えます。本当にリセットしますか？')) { return; }
    api('reset', {})
      .then(function (data) {
        state = data;
        expanded = {};
        try { localStorage.removeItem('jq-last-lesson'); } catch (e) { /* 同上 */ }
        goHome();
        toast('進捗をリセットしました');
      })
      .catch(showError);
  });

  window.addEventListener('hashchange', function () {
    var target = routeFromHash();
    if (target !== currentId) {
      if (target === null) { goHome(); } else { selectLesson(target); }
    }
  });

  boot();
})();
