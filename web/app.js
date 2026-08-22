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
 *   #review            … 復習モード（クリア済みの問題を解き直す）
 *   #review/3-2/1      … その問題を1問だけ復習する
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
  var lessonNumber = {};   // レッスンID -> 画面で見せる章内の番号（事前確認は0）
  var lessonOrder = {};    // レッスンID -> 出題順の位置（案内をどこから出すかの判断に使う）
  var currentId = null;    // いま開いているレッスンID（ホーム／カフェ表示中は null）
  var currentView = 'menu'; // menu / cafe / lesson / review / reviewTask
  // 解いていた場所へ戻すための3つ。カフェへ寄り道しても、読んでいた位置ごと再開できるように。
  var paintedLessonId = null;    // いま #content に描いてあるレッスンID（位置の持ち主）
  var lessonScroll = null;       // 直前に離れたレッスンで読んでいた位置 { lessonId, top }
  var cafeReturnLessonId = null; // カフェの「📚 学習」で帰るレッスンID（寄り道でなければ null）
  // 復習を解いている途中でカフェへ寄り道したときの帰り先。レッスンと分けてあるのは、
  // 帰り方が違うから ―― レッスンは selectLesson で開けるが、復習はセットの何問目か
  // （reviewSession）ごと戻さないと帯が「3 / 4問」を出せない（→ goLearning）。
  var paintedReview = null;      // いま #content に描いてある復習 { lessonId, taskId, quiz }
  var cafeReturnReview = null;   // カフェの「📚 学習」で帰る復習（寄り道でなければ null）
  var editors = {};        // 問題ID -> エディタ（1レッスンに複数問あるので複数持つ）
  var saveTimers = {};     // 問題ID -> 自動保存のタイマー
  var busyTask = null;     // 実行・採点中の問題ID（同時に走らせない）
  var sideExpanded = {};   // サイドバーで開いている章のID（既定は全部たたむ）
  var sideScrolledFor = null; // サイドバーのスクロールを合わせた単元のID（同じ単元なら動かさない）
  var sideQuery = '';      // サイドバーの検索語（空なら章の一覧を出す）
  var sideHitIndex = -1;   // 検索結果でキーボードが選んでいる行（-1 は未選択）
  var searchIndex = null;  // 検索用の索引。setState で捨て、最初の検索で作る
  var SIDE_HIT_LIMIT = 40; // 検索結果に並べる上限。残りは件数だけ知らせる
  var activePartId = null; // メニューで表示中の大区分（Java基礎編 / Web・Jakarta EE編など）
  var selectedChapterByPart = {}; // ホームの編ごとに、最後に見ていた章を覚える
  var onboardingTourStep = 0; // 初回だけホーム上に重ねる操作ガイドの現在位置

  // 右上通知。同時に複数の成果が発生しても上書きせず、読める時間を確保して順番に出す。
  var notificationQueue = [];
  var activeNotification = null;
  var notificationTimer = null;
  var notificationStartedAt = 0;
  var notificationRemainingMs = 0;
  var confettiTimer = null; // 降り終わった紙吹雪をDOMから片付けるまでの待ち

  // 獲得したコインの控え。通知は数秒で消えるので、ヘッダのコインから読み返せるようにする。
  var COIN_LOG_KEY = 'jq-coin-log';
  var COIN_LOG_LIMIT = 40;  // 古いぶんから捨てる。読み返したいのは直近だけ
  var coinLog = null;       // 最初に必要になったときだけ localStorage から読む

  // 復習モード。解いている途中のセットは localStorage にも控える（→ saveReviewRun）
  var reviewSession = null; // { queue: [{lessonId, taskId}], index, cleared, clearedKeys,
                            //   quizQueue: [{lessonId, index}], quizIndex, quizCorrect }
                            // quizIndex が 0 以上ならクイズの段（問題を解き終えたあと）
  var reviewTaskId = null;  // 復習で開いている問題ID（レッスンIDは currentId）
  var reviewFilter = 'all'; // 復習の絞り込み（all / weak / bookmark）
  var reviewSummary = null; // 直前に終えたセットの結果。復習ホームの先頭に1回だけ出す
  var reviewRun = null;     // 続けて重ねたセットの積み上げ（→ startReviewRun）
  var REVIEW_RUN_KEY = 'jq-review-run'; // 途中のセットの控え（→ saveReviewRun）

  /**
   * 1セットの問題数。
   *
   * 以前は10問だった。1問が数分かかるので、始める前に身構える重さになっていた。
   * 短いセットにして「もう1セット」で足せるようにすると、やめる場所を自分で選べる。
   * 解ける総量を減らしたのではなく、区切りを細かくしただけ（何セットでも続けられる）。
   */
  var REVIEW_SESSION_SIZE = 4;
  /**
   * 1セットの最後に続けて出すクイズの数。
   *
   * 📣の解放は「異なる20問へ連続正解」なので、1回で20問出すと1セットで取れてしまう。
   * 問題より少なくしてあるのは、セット全体を短く保つほうが「もう1セット」を押しやすく、
   * クイズは数秒で終わるぶん、1セットに詰めるより回数を重ねたほうが効くため。
   */
  var REVIEW_QUIZ_SESSION_SIZE = 3;
  var REVIEW_LIST_LIMIT = 50; // 一覧に並べる上限。残りは件数だけ知らせる
  var quizFocus = null;     // しおりから開いたクイズ { lessonId, index }。描画側で1回だけ使う

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
    lessonNumber = {};
    lessonOrder = {};
    searchIndex = null;   // 教材が入れ替わったので、次の検索で作り直す
    state.chapters.forEach(function (ch) {
      chapterIndex[ch.id] = ch;
      // 章のidは大半が `chNN` だが、`34` のように数字だけのものが5章ある。
      // `localizeChapterReferences` は `第NN章` から `chNN` を組み立てて引くので、
      // 別名を入れておかないと**内部番号がそのまま画面に出る**（第34章 → 実際は第3章）。
      var digits = String(ch.id).replace(/\D/g, '');
      if (digits) {
        var alias = 'ch' + (digits.length < 2 ? '0' + digits : digits);
        if (!chapterIndex[alias]) { chapterIndex[alias] = ch; }
      }
      // 画面の番号は**章の中の位置**から振り直す。IDの番号をそのまま出すと、
      // 章を分けたところで `4-1 4-2 4-3 4-6` のように飛んで見える（IDは進捗ファイルの
      // 互換のため変えられない）。事前確認は章クリアの対象外なので `-0` のまま見せ、
      // 本編の番号には数えない。
      var shownNumber = 0;
      ch.lessons.forEach(function (l) {
        lessonIndex[l.id] = l;
        chapterOfLesson[l.id] = ch;
        lessonOrder[l.id] = lessonList.length;
        lessonList.push(l);
        lessonNumber[l.id] = l.type === 'preflight' ? 0 : ++shownNumber;
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

  /**
   * 保存用ID（21-1など）は変えず、画面では編内の番号（1-1など）を見せる。
   *
   * 前半（章）は編内番号へ、<b>後半（レッスン）は章の中の位置へ</b>読み替える。
   * 章を分けたときレッスンIDは変えない方針なので、IDの番号をそのまま出すと
   * `4-1 4-2 4-3 4-6` のように画面で番号が飛び、学習者には抜けているように見える。
   * 番号は `setState` で作った索引から引く（サイドバーが全レッスンぶん呼ぶため）。
   */
  function displayLessonId(lesson) {
    var chapter = chapterOf(lesson.id);
    var dash = lesson.id.indexOf('-');
    if (!chapter || dash < 0) { return lesson.id; }
    var number = lessonNumber.hasOwnProperty(lesson.id)
      ? lessonNumber[lesson.id]
      : lesson.id.substring(dash + 1);
    return displayChapterNumber(chapter) + '-' + number;
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
   *
   * @param from どの章から見た番号として書くか。省略すると開いているレッスンの章。
   *             サイドバーの検索結果は開いているレッスンと関係ない章の文を引用するので、
   *             そこでは引用元の章を渡す（{@link describeHit}）
   */
  function localizeChapterReferences(text, from) {
    var here = from || (currentId && chapterOf(currentId));
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

  // 教材が書くレッスン参照（`13-5` `46-5#4` など）。前後に英数字・ドット・`[` `]` が
  // 付くものは対象外にする（`postgres:16-alpine` や 正規表現の `[1-4]` を拾わないため）。
  // 先読みだけでは前を見られないので、直前の1文字を捕まえてそのまま書き戻す。
  var LESSON_REF = /(^|[^\w.[-])(\d{1,2})-(\d{1,2})(#\d+)?(?![\w.\]-])/g;

  /**
   * 教材に書かれたレッスン番号を、画面の表示番号へ読み替える。
   *
   * `localizeChapterReferences` と対になる処理。教材は `13-5` のように内部IDで
   * レッスンを指すが、画面の番号は章ごとに振り直すので、そのまま出すと別の
   * レッスンを指してしまう（`13-5` は画面では `14-5`）。
   *
   * <b>コードブロックの中は読み替えない。</b> 二重ループの出力例が
   * `1-1 1-2 2-1 …` のようにレッスンIDと同じ形をしていて、これは番号ではない。
   * 解決できないものは、誤った番号を作らないためそのまま残す。
   */
  function localizeLessonReferences(text) {
    var here = currentId && chapterOf(currentId);
    if (!here) { return text; }
    return outsideCodeBlocks(String(text || ''), function (chunk) {
      return chunk.replace(LESSON_REF, function (whole, before, chapterPart, lessonPart, taskPart) {
        var id = chapterPart + '-' + lessonPart;
        var chapter = chapterOf(id);
        if (!chapter || !lessonNumber.hasOwnProperty(id)) { return whole; }
        var shown = displayChapterNumber(chapter) + '-' + lessonNumber[id] + (taskPart || '');
        if (chapter.partId === here.partId) { return before + shown; }
        var part = partOfChapter(chapter);
        return before + (part ? part.title + ' ' + shown : shown);
      });
    });
  }

  /** ```で囲んだ範囲を素通しし、それ以外だけを変換する。 */
  function outsideCodeBlocks(text, transform) {
    var parts = text.split(/(```[\s\S]*?```)/);
    for (var i = 0; i < parts.length; i += 2) {
      parts[i] = transform(parts[i]);
    }
    return parts.join('');
  }

  function renderMarkdown(text) {
    return md(localizeLessonReferences(localizeChapterReferences(text)));
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
      l.tasks.forEach(function (t) { if (t.required !== false) list.push(t); });
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
   * サイドバーで目印を付け、そこまでスクロールする単元のID。
   *
   * レッスンや復習を開いている間はそれ自身。ホームやカフェを見ている間は、
   * 最後に開いた単元を指し続ける（そこまでスクロールした状態を保つ）。
   * 「続ける」の行き先（resumeTarget）とは分けている。あちらはクリア済みなら
   * 次の単元へ進めるが、ここは最後にいた場所そのものを指したいため。
   */
  function sidebarFocusId() {
    if (currentId) { return currentId; }
    var saved = null;
    try { saved = localStorage.getItem('jq-last-lesson'); } catch (e) { /* 使えなくても困らない */ }
    if (saved && findLesson(saved)) { return saved; }
    return resumeTarget();   // この端末で一度も開いていないときの寄せ先
  }

  /**
   * 提出・クイズ回答の応答に入っている差分を、手元の state に上書きする。
   *
   * サーバはカリキュラム全体（解説やサンプル込みで3MB以上）を返さず、
   * 変わったところ＝提出した問題のレッスンとその章、それに進捗の集計だけを返す。
   * 解説文などは最初の /api/state で受け取ったものをそのまま使い続ける。
   *
   * 差分に入っていないものは触らない（id で引き当てて、来た項目だけ上書きする）。
   * サーバが送る範囲を絞っても、ここは書き換えなくてよいようにしてある。
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
        // 3層の到達状況。数え方はサーバーにしかないので、来た値をそのまま入れ替える
        if (u.layers) { ch.layers = u.layers; }
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

    // ★・通過ケース数・ヒント開示数・復習の苦手度は問題ごとに持っている
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
          t.bookmarked = u.bookmarked;
          t.reviewWeight = u.reviewWeight;
          // 期限とレベルも入れ替える。ここを入れていなかったので、復習で通した問題の
          // 間隔が伸びたことが、画面を再読み込みするまで復習一覧に出なかった
          // （未クリアの問題では3つとも来ない。reviewCandidates が未定義を0として扱う）
          t.reviewLevel = u.reviewLevel;
          t.reviewDue = u.reviewDue;
          t.reviewDueDays = u.reviewDueDays;
          t.reviewCleanRun = u.reviewCleanRun;
          t.reviewFastTrack = u.reviewFastTrack;
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
      reviewBonusPercent: 0, reviewRewardPercent: 50,
      reviewEarlyRewardPercent: 12, reviewEarlyRewardPerDay: 6, reviewEarlyRewardLeft: 6,
      extraCups: 0, chapterBonusPercent: 0,
      quizTipPercent: 0, clearedChapters: 0, brandMultiplierBasisPoints: 10000,
      reviewBrandBasisPoints: 0, reviewedTasks: 0, reviewedTaskPercent: 0,
      equipmentDiscountPercent: 0,
      storeCount: 1, maxStores: 512, nextStoreGain: 1, nextStoreCount: 2,
      storeLimit: 1, nextStoreUnlockStars: 4,
      expansionCost: null, lifetimeCash: 0, ownedItems: [], items: [],
      investmentLevel: 0, investmentAvailableLevel: 0, endgameInvestment: null,
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

  /**
   * ブランド倍率が伸びた幅を「これから何%多く受け取れるか」に直す。
   *
   * 倍率の差（例 ×1.34 → ×1.51）をそのまま出しても増減の実感に結びつかないので、
   * 前の倍率を基準にした割合で見せる。基準が0や下がった場合は空文字を返し、
   * 呼び出し側が行そのものを出さない。
   */
  function brandGainPercentText(before, after) {
    if (!(before > 0) || !(after > before)) { return ''; }
    var gain = (after / before - 1) * 100;
    return gain.toLocaleString('ja-JP', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 1
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
    paintCoinLog();   // 開いていれば残高と今日の合計をそろえる（閉じていれば何もしない）

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
    // 「📚 学習」の行き先は場面で変わる（解いている途中で寄り道したならそのレッスンへ）ので、
    // 押す前に分かるよう説明も合わせる。
    learningBtn.title = learningReturnLessonId() || learningReturnReview()
      ? '解いていた問題に戻る' : '学習ホームに戻る';
  }

  // ---------------------------------------------------- サイドバーの検索

  /**
   * 検索用の索引。レッスン1件につき1エントリ。
   *
   * 教材の全文（解説・サンプル・課題文）は起動時の {@code /api/state} で手元に来ているので、
   * 検索はサーバへ行かずに済む。ただし本文は全部で3MB以上あり、打鍵ごとに小文字へ
   * 直していては間に合わない。小文字にした干し草をここで1回だけ作って持つ。
   *
   * 段（レッスン名 → 章名 → 到達目標 → 本文）に分けてあるのは、照合の優先順が
   * そのまま結果の並びになるためである（{@link searchLessons}）。
   *
   * レッスン名に表示IDを混ぜていないのは、混ぜると `6-1` が `16-1` `26-1` にも
   * 当たってしまうため。番号は {@link matchesLessonId} が番号の形の語だけで照合する。
   *
   * 照合するのは<b>画面に出る番号だけ</b>で、保存用ID（`6-2`）は入れない。番号は編ごとに
   * 振り直すので両方入れると、`6-2` と打った人に `7-2` と書かれた行が混ざって出てしまう。
   *
   * 模範解答・ヒント・クイズの解説は入れない。当たった箇所の抜粋を出す作りなので、
   * 入れると探しただけで答えが見えてしまう（模範解答とヒントはそもそも state に載っていない）。
   */
  function buildSearchIndex() {
    var entries = [];
    state.chapters.forEach(function (ch) {
      var objectiveText = {};
      (ch.objectives || []).forEach(function (o) { objectiveText[o.id] = o.text || ''; });
      var chapterName = '第' + displayChapterNumber(ch) + '章 ' + ch.title
        + ' ' + (ch.subtitle || '');
      var part = partOfChapter(ch);
      ch.lessons.forEach(function (l) {
        var bodies = [];
        if (l.explanation) { bodies.push({ label: '解説', text: l.explanation }); }
        var taskText = (l.tasks || []).map(function (t) { return t.task || ''; }).join('\n');
        if (taskText.trim()) { bodies.push({ label: '課題', text: taskText }); }
        var sampleText = (l.samples || []).map(function (s) {
          return (s.caption || '') + '\n' + (s.code || '');
        }).join('\n');
        if (sampleText.trim()) { bodies.push({ label: 'サンプル', text: sampleText }); }
        bodies.forEach(function (b) { b.lower = b.text.toLowerCase(); });

        // 章の目標のうち、このレッスンが担うものだけ。章の全目標を入れると
        // 同じ章のレッスンが全部同じ強さで当たってしまう。
        var objectives = (l.objectiveIds || []).map(function (id) {
          return objectiveText[id] || '';
        }).join(' ');

        var shownId = displayLessonId(l);
        entries.push({
          order: entries.length,   // カリキュラム順。同じ段の中の並びに使う
          lesson: l,
          chapter: ch,
          part: part,
          shownId: shownId,
          ids: [shownId.toLowerCase()],
          name: String(l.title || '').toLowerCase(),
          chapterName: chapterName.toLowerCase(),
          objectivesText: objectives,
          objectives: objectives.toLowerCase(),
          bodies: bodies
        });
      });
    });
    return entries;
  }

  /** レッスン番号として扱う語（`6-2` など）。数字だけの語は番号照合に使わない。 */
  var LESSON_ID_TERM = /^\d{1,2}-\d{1,2}$/;
  /**
   * 抜粋で一致箇所の前後に付ける字数。
   * 後ろを長く取るのは、288pxのサイドバーでは2行しか出せないため。前を詰めておけば
   * 当たった語が1行目に残り、そこから読み進める形になる。
   */
  var SNIPPET_LEAD = 12;
  var SNIPPET_TAIL = 44;

  /**
   * 検索語を正規化して語に分ける（空白区切りのAND）。
   *
   * NFKCするのは<b>クエリだけ</b>。教材は半角で書かれているので、IMEで全角のまま
   * 打った `Ａｒｒａｙ` を直すにはこちら側だけで足りる。干し草を正規化すると
   * 字数が動いて、抜粋を切り出す位置がずれてしまう。
   */
  function searchTerms(query) {
    var text = String(query || '');
    if (text.normalize) { text = text.normalize('NFKC'); }
    return text.toLowerCase().trim().split(/\s+/).filter(function (t) { return !!t; });
  }

  function matchesLessonId(entry, term) {
    if (!LESSON_ID_TERM.test(term)) { return false; }
    return entry.ids.some(function (id) { return id.indexOf(term) === 0; });
  }

  /** 本文のどの段に当たったか。当たらなければ null。 */
  function bodyHit(entry, term) {
    for (var i = 0; i < entry.bodies.length; i++) {
      var at = entry.bodies[i].lower.indexOf(term);
      if (at >= 0) { return { body: entry.bodies[i], at: at }; }
    }
    return null;
  }

  /**
   * 全語がそろった最初の段。0 なら一致しない。
   *
   * 段は積み上げていく（レッスン名 → ＋章名 → ＋到達目標 → ＋本文）。こうすると
   * 「配列 合計」のように語が章名と本文へ分かれていても拾えて、なお
   * 「名前で当たったもの」が上に来る。
   */
  function matchTier(entry, terms, deep) {
    var pools = [
      function (term) { return entry.name.indexOf(term) >= 0 || matchesLessonId(entry, term); },
      function (term) { return entry.chapterName.indexOf(term) >= 0; },
      function (term) { return entry.objectives.indexOf(term) >= 0; },
      function (term) { return !!bodyHit(entry, term); }
    ];
    var limit = deep ? pools.length : 3;
    for (var tier = 1; tier <= limit; tier++) {
      var upto = tier;
      var all = terms.every(function (term) {
        for (var i = 0; i < upto; i++) { if (pools[i](term)) { return true; } }
        return false;
      });
      if (all) { return tier; }
    }
    return 0;
  }

  /**
   * 1行ぶんの表示材料。名前や章名で当たった行はそれ自体が見えているので、
   * 抜粋は「レッスン名にも章名にも無かった語」の周りだけを出す。
   */
  function describeHit(entry, terms, tier) {
    var hit = { entry: entry, tier: tier, order: entry.order, label: '', snippet: '' };
    if (tier < 3) { return hit; }
    for (var i = 0; i < terms.length; i++) {
      var term = terms[i];
      if (entry.name.indexOf(term) >= 0 || entry.chapterName.indexOf(term) >= 0) { continue; }
      var at = entry.objectives.indexOf(term);
      if (at >= 0) {
        hit.label = '到達目標';
        hit.snippet = snippetChapterNumbers(
          searchSnippet(entry.objectivesText, at, term), entry.chapter);
        return hit;
      }
      var found = bodyHit(entry, term);
      if (found) {
        hit.label = found.body.label;
        hit.snippet = snippetChapterNumbers(
          searchSnippet(found.body.text, found.at, term), entry.chapter);
        return hit;
      }
    }
    return hit;
  }

  /**
   * 抜粋の中の章番号を、画面の番号へ読み替える。
   *
   * 教材はファイル名の番号（`第16章`）で参照を書くが、画面の番号は編ごとに振り直す。
   * 引用したまま出すと存在しない章を指してしまうので、引用元の章から見た番号に直す。
   *
   * レッスン番号（`13-5`）の読み替えはしない。抜粋はサンプルコードから切ることもあり、
   * コードの中の `1-1` まで書き換えてしまう（本文では ``` で囲まれた範囲を避けているが、
   * 抜粋にはその囲みが残らない）。
   */
  function snippetChapterNumbers(text, chapter) {
    return localizeChapterReferences(text, chapter);
  }

  /**
   * 一致したところの周りを1行に抜き出す。
   *
   * 位置は小文字にした側で数えたものなので、原文と1〜2字ずれることがある
   * （`İ` のように小文字化で長さが変わる字が教材に実在する）。前後に余裕を取って
   * 切るので読めるものになる。ここで位置合わせは要求しない。
   */
  function searchSnippet(text, at, term) {
    var source = String(text || '');
    var from = Math.max(0, at - SNIPPET_LEAD);
    var to = Math.min(source.length, at + term.length + SNIPPET_TAIL);
    var piece = plainSnippetText(source.substring(from, to));
    return (from > 0 ? '…' : '') + piece + (to < source.length ? '…' : '');
  }

  /**
   * 抜粋から書式の記号を落として1行にする。
   *
   * 教材はMarkdownで書いてあるので、そのまま切ると `` ` `` や `**` や引用の `>` が
   * 混ざって読みにくい。落とすのは誤爆しないものだけにする ―
   * 単独の `*` や `_` はコード（`w * h`・`MAX_VALUE`）で使うので触らない。
   * 行頭の記号は改行をつぶす前に落とす（つぶした後では行頭が分からなくなる）。
   */
  function plainSnippetText(text) {
    return String(text || '')
      .replace(/^[ \t]*>+[ \t]?/gm, '')      // 引用の目印
      .replace(/^[ \t]*#{1,6}[ \t]+/gm, '')  // 見出しの #
      .replace(/\*\*/g, '')                  // 太字（Javaに ** は無い）
      .replace(/`/g, '')                     // コード記法
      .replace(/\s+/g, ' ')
      .trim();
  }

  /**
   * 一致した語だけ `<mark>` で包む。
   *
   * 原文を一致位置で切り分けてから片ごとに {@code esc()} する。HTMLにしてから探すと、
   * エスケープで増えた `&amp;` や `&lt;` の中の文字にも当たってしまう。
   */
  function highlightTerms(text, terms) {
    var source = String(text || '');
    if (!terms || !terms.length) { return esc(source); }
    var lower = source.toLowerCase();
    var html = '';
    var at = 0;
    while (at < source.length) {
      var bestAt = -1;
      var bestLen = 0;
      terms.forEach(function (term) {
        var found = lower.indexOf(term, at);
        if (found < 0) { return; }
        if (bestAt < 0 || found < bestAt || (found === bestAt && term.length > bestLen)) {
          bestAt = found;
          bestLen = term.length;
        }
      });
      if (bestAt < 0) { break; }
      html += esc(source.substring(at, bestAt))
        + '<mark class="side-hit-mark">' + esc(source.substr(bestAt, bestLen)) + '</mark>';
      at = bestAt + bestLen;
    }
    return html + esc(source.substring(at));
  }

  /**
   * 検索の本体。{ terms, hits, total } を返す。
   *
   * 1文字のクエリでは本文まで広げない。「配」のような1字はほぼ全章の解説に出るので、
   * 広げると結果が全レッスンになって役に立たない。
   */
  function searchLessons(query) {
    var terms = searchTerms(query);
    if (!terms.length) { return { terms: terms, hits: [], total: 0 }; }
    if (!searchIndex) { searchIndex = buildSearchIndex(); }
    var deep = terms.join('').length >= 2;
    var hits = [];
    searchIndex.forEach(function (entry) {
      var tier = matchTier(entry, terms, deep);
      if (tier) { hits.push(describeHit(entry, terms, tier)); }
    });
    hits.sort(function (a, b) { return a.tier - b.tier || a.order - b.order; });
    return { terms: terms, hits: hits, total: hits.length };
  }

  function sidebarTree() {
    return document.getElementById('sidebarTree');
  }

  /**
   * 検索欄そのものの見た目。
   *
   * 入力欄の値は打っている本人が持っているので、ずれているときだけ書き戻す
   * （毎回入れ直すと、IMEの変換中に横取りしてしまう）。
   */
  function paintSidebarSearch(result) {
    var input = document.getElementById('sidebarSearch');
    var clear = document.getElementById('sidebarSearchClear');
    var count = document.getElementById('sidebarSearchCount');
    if (!input) { return; }
    if (input.value !== sideQuery) { input.value = sideQuery; }
    input.setAttribute('aria-expanded', sideQuery ? 'true' : 'false');
    if (clear) { clear.hidden = !sideQuery; }
    if (!count) { return; }
    if (!sideQuery || !result) {
      count.hidden = true;
      count.textContent = '';
      return;
    }
    count.hidden = false;
    count.textContent = result.total > SIDE_HIT_LIMIT
      ? '一致 ' + result.total + '件（上から ' + SIDE_HIT_LIMIT + '件）'
      : '一致 ' + result.total + '件';
  }

  /** サイドバーの行頭の印。章の一覧と検索結果で同じ規則を使う。 */
  function lessonMark(lesson) {
    if (lesson.type === 'preflight') { return '⚙'; }
    return lesson.cleared ? '★' : '○';
  }

  /**
   * 検索結果。章の階層はたたんで、当たったレッスンだけを平らに並べる。
   *
   * どこに居るレッスンなのかが分からないと飛べないので、編と章名を必ず添える。
   * レッスン名にも章名にも無い語で当たった行は、当たった箇所の抜粋も出す
   * （出さないと「なぜこの行が出たのか」が分からない）。
   */
  function renderSidebarHits(tree, result) {
    tree.innerHTML = '';
    tree.scrollTop = 0;
    if (!result.total) {
      tree.innerHTML =
        '<div class="side-hits-empty">' +
        '  <p><b>' + esc(sideQuery) + '</b> に一致するレッスンはありません。</p>' +
        '  <p class="side-hits-hint">レッスン名・章名・到達目標・解説・課題文・サンプルから探します。'
          + 'レッスン番号（6-2 など）でも開けます。</p>' +
        '</div>';
      return;
    }

    var shown = result.hits.slice(0, SIDE_HIT_LIMIT);
    if (sideHitIndex >= shown.length) { sideHitIndex = -1; }
    var ul = document.createElement('ul');
    ul.className = 'side-hits';
    ul.id = 'sidebarHits';
    ul.setAttribute('role', 'listbox');
    ul.setAttribute('aria-label', '検索結果');
    shown.forEach(function (hit, i) {
      var l = hit.entry.lesson;
      var active = i === sideHitIndex;
      var li = document.createElement('li');
      li.className = 'side-hit'
        + (l.cleared ? ' side-hit-cleared' : '')
        + (l.id === currentId ? ' side-hit-current' : '')
        + (active ? ' active' : '');
      li.id = 'sidebarHit' + i;
      li.dataset.lesson = l.id;
      li.setAttribute('role', 'option');
      li.setAttribute('aria-selected', active ? 'true' : 'false');
      li.title = lessonTooltip(l);
      li.innerHTML =
        '<span class="side-hit-head">' +
        '<span class="lesson-mark">' + lessonMark(l) + '</span>' +
        '<span class="lesson-id">'
          + highlightTerms(hit.entry.shownId, result.terms) + '</span>' +
        '<span class="side-hit-title">' + highlightTerms(l.title, result.terms) + '</span>' +
        '</span>' +
        '<span class="side-hit-where">'
          + (hit.entry.part ? esc(hit.entry.part.emoji + ' ' + hit.entry.part.title) + ' · ' : '')
          + highlightTerms('第' + displayChapterNumber(hit.entry.chapter) + '章 '
              + hit.entry.chapter.title, result.terms)
        + '</span>' +
        (hit.snippet
          ? '<span class="side-hit-snippet">' + esc(hit.label) + '「'
            + highlightTerms(hit.snippet, result.terms) + '」</span>'
          : '');
      li.addEventListener('click', function () { openSideHit(i); });
      ul.appendChild(li);
    });
    tree.appendChild(ul);

    // 上限で切った分は黙って落とさない。件数と、絞る手立てを一緒に出す。
    if (result.total > SIDE_HIT_LIMIT) {
      var more = document.createElement('p');
      more.className = 'side-hits-more';
      more.textContent = 'ほか ' + (result.total - SIDE_HIT_LIMIT)
        + '件。語を足すと絞り込めます（例: 配列 合計）。';
      tree.appendChild(more);
    }
  }

  function sideHitNodes() {
    var tree = sidebarTree();
    return tree ? Array.prototype.slice.call(tree.querySelectorAll('.side-hit')) : [];
  }

  /** 検索結果の選択行を動かす。端は回り込む。 */
  function moveSideHit(step) {
    var nodes = sideHitNodes();
    if (!nodes.length) { return; }
    var at = sideHitIndex < 0
      ? (step > 0 ? 0 : nodes.length - 1)
      : (sideHitIndex + step + nodes.length) % nodes.length;
    sideHitIndex = at;
    nodes.forEach(function (node, i) {
      node.classList.toggle('active', i === at);
      node.setAttribute('aria-selected', i === at ? 'true' : 'false');
    });
    var input = document.getElementById('sidebarSearch');
    if (input) { input.setAttribute('aria-activedescendant', nodes[at].id); }
    if (nodes[at].scrollIntoView) { nodes[at].scrollIntoView({ block: 'nearest' }); }
  }

  /**
   * 検索結果からレッスンを開く。
   *
   * 語は消さずに残す。1つ開いて違ったときに、打ち直さず次の行へ移れるようにするため。
   * 章の一覧へ戻りたいときは × か Esc で戻す。
   */
  function openSideHit(index) {
    var nodes = sideHitNodes();
    var at = index == null ? (sideHitIndex < 0 ? 0 : sideHitIndex) : index;
    var node = nodes[at];
    if (!node) { return; }
    sideHitIndex = at;
    selectLesson(node.dataset.lesson);
  }

  /** 検索語を変える。空にしたら章の一覧へ戻る。 */
  function setSideQuery(value) {
    var next = String(value || '');
    if (next === sideQuery) { return; }
    sideQuery = next;
    sideHitIndex = -1;
    var input = document.getElementById('sidebarSearch');
    if (input) { input.removeAttribute('aria-activedescendant'); }
    // 章の一覧へ戻ったら、いま居る単元まで寄せ直す（検索中は寄せていない）
    if (!sideQuery) { sideScrolledFor = null; }
    // 読み込み中に打たれた語は控えるだけにする。教材が届いたあとの render が拾う
    if (state) { renderSidebar(); }
  }

  function isMacKeys() {
    return !!(window.JQComplete && window.JQComplete.isMac && window.JQComplete.isMac());
  }

  /** 検索の近道の表記。使えないキーを案内しないよう、環境で出し分ける。 */
  function searchShortcutText() {
    return isMacKeys() ? '⌘K' : 'Ctrl+K';
  }

  function isTextEntry(node) {
    if (!node || !node.tagName) { return false; }
    var tag = node.tagName.toLowerCase();
    return tag === 'textarea' || tag === 'input' || !!node.isContentEditable;
  }

  /**
   * 近道（⌘K）の行き先。閉じているサイドバーは開いてから合わせる。
   * 初回案内の最中は何もしない（そのあいだサイドバーは出していない）。
   */
  function focusSidebarSearch() {
    if (document.body.classList.contains('view-onboarding')) { return; }
    if (isSidebarHidden()) { setSidebarHidden(false); }
    var input = document.getElementById('sidebarSearch');
    if (!input) { return; }
    input.focus();
    input.select();
  }

  function bindSidebarSearch() {
    var input = document.getElementById('sidebarSearch');
    var clear = document.getElementById('sidebarSearchClear');
    if (!input) { return; }
    input.placeholder = 'レッスンを検索（' + searchShortcutText() + '）';
    input.title = 'レッスン名・章名・解説から探す（' + searchShortcutText() + '）';

    input.addEventListener('input', function () { setSideQuery(input.value); });
    input.addEventListener('keydown', function (e) {
      if (e.isComposing || e.keyCode === 229) { return; }   // IMEの変換中は横取りしない
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        moveSideHit(e.key === 'ArrowDown' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        openSideHit();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        if (sideQuery) { setSideQuery(''); } else { input.blur(); }
      }
    });
    if (clear) {
      clear.addEventListener('click', function () {
        setSideQuery('');
        input.focus();
      });
    }

    // ⌘K / Ctrl+K でどこからでも検索へ。サイドバーは既定で閉じているので、
    // ここが「開いてから探す」までを1手にする。
    //
    // ただし **macOSの Ctrl+K は文字入力欄では本来「行末まで削除」** である。
    // コードを書いている最中に奪うと打鍵が壊れるので、その組み合わせだけ譲る
    // （complete.js が Ctrl+P / Ctrl+N を窓が開いている間だけ横取りするのと同じ理由）。
    // macOSでは ⌘K が空いているので、エディタからでも1手で届く。
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'k' && e.key !== 'K' && e.code !== 'KeyK') { return; }
      if (!e.metaKey && !e.ctrlKey) { return; }
      if (e.altKey || e.shiftKey) { return; }
      if (e.ctrlKey && !e.metaKey && isMacKeys() && isTextEntry(e.target)) { return; }
      e.preventDefault();
      focusSidebarSearch();
    });
  }

  // -------------------------------------------------------- サイドバー描画

  function renderSidebar() {
    var nav = sidebarTree();
    if (sideQuery) {
      var result = searchLessons(sideQuery);
      paintSidebarSearch(result);
      renderSidebarHits(nav, result);
      return;
    }
    paintSidebarSearch(null);
    var keepScrollTop = nav.scrollTop;
    nav.innerHTML = '';

    // 開いている単元が無いとき（ホーム・カフェ）も、最後にいた単元を指し続ける。
    var focusId = sidebarFocusId();

    var lastPartId = null;
    var partSection = null;
    state.chapters.forEach(function (ch) {
      var part = partOfChapter(ch);
      if (part && part.id !== lastPartId) {
        var currentPart = chaptersOfPart(part).some(function (partChapter) {
          return partChapter.lessons.some(function (l) { return l.id === focusId; });
        });
        var progress = partProgress(part);
        var partStatus = progress.cleared === progress.total && progress.total > 0
          ? '✓' : (progress.cleared > 0 ? '学習中' : '');
        partSection = document.createElement('section');
        partSection.className = 'side-part' + (currentPart ? ' current' : '');
        var partHead = document.createElement('div');
        partHead.className = 'side-part-head' + (currentPart ? ' current' : '');
        if (currentPart) { partHead.setAttribute('aria-current', 'true'); }
        partHead.innerHTML =
          '<span class="side-part-emoji">' + esc(part.emoji) + '</span>' +
          '<span class="side-part-title">' + esc(part.title) + '</span>' +
          '<span class="side-part-count">' + partStatus + '</span>';
        partSection.appendChild(partHead);
        nav.appendChild(partSection);
        lastPartId = part.id;
      }
      var isCurrentChapter = ch.lessons.some(function (l) { return l.id === focusId; });

      // 章は既定でたたんでおく。ただし自分で開閉していない章のうち、
      // 目印を付ける単元の章だけは現在地が分かるように開いておく。
      if (sideExpanded[ch.id] === undefined && isCurrentChapter) {
        sideExpanded[ch.id] = true;
      }
      var open = !!sideExpanded[ch.id];

      var section = document.createElement('section');
      section.className = 'ch'
        + (ch.cleared ? ' ch-cleared' : '')
        + (isCurrentChapter ? ' ch-current' : '');

      // 全部終わった章に印は出さない。面の色（.ch-cleared の背景）で示す。
      // 空の <span> を置くと flex の gap がそのぶん余って見出しの右が間延びするので、
      // 見せる中身があるときだけ入れる。
      var status = !ch.cleared && ch.clearedCount
        ? '  <span class="ch-status"><span class="ch-count">学習中</span></span>'
        : '';

      section.innerHTML =
        '<button type="button" class="ch-head" aria-expanded="' + open + '">' +
        '  <span class="ch-emoji">' + esc(ch.emoji) + '</span>' +
        '  <span class="ch-titles">' +
        '    <span class="ch-title">第' + displayChapterNumber(ch) + '章　' + esc(ch.title) + '</span>' +
        '    <span class="ch-sub">' + esc(ch.subtitle) + '</span>' +
        '  </span>' +
        status +
        '  <span class="ch-caret">' + (open ? '▲' : '▼') + '</span>' +
        '</button>';

      var ul = document.createElement('ul');
      ul.className = 'lessons';
      if (!open) { ul.hidden = true; }
      ch.lessons.forEach(function (l) {
        var li = document.createElement('li');
        // 開いている単元は塗り（lesson-current）。開いていないが最後にいた単元は、
        // 「いまここを見ている」と誤解しないよう控えめな目印（lesson-focus）にする。
        li.className = 'lesson'
          + (l.cleared ? ' lesson-cleared' : '')
          + (l.id === currentId ? ' lesson-current'
            : (l.id === focusId ? ' lesson-focus' : ''));
        li.innerHTML =
          '<span class="lesson-mark">' + lessonMark(l) + '</span>' +
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

      // 編ごとに囲うことで、sticky な編見出しがその編の末尾で止まり、
      // 次の編の見出しに自然に押し出されるようにする。
      (partSection || nav).appendChild(section);
    });

    // 作り直すとスクロールは先頭に戻ってしまう。章をたたんだだけなら見ていた位置に
    // 戻し、開いている単元が変わったとき（最初の描画や別レッスンへ移ったとき）だけ
    // その単元まで動かす。
    nav.scrollTop = keepScrollTop;
    if (focusId !== sideScrolledFor) { scrollSidebarToFocus(nav); }
  }

  /**
   * 目印を付けた単元が見える位置までサイドバーをスクロールする。
   * サイドバーを閉じている間（display:none）は寸法が測れないので何もせず、
   * ☰ で開いたときにやり直す。検索中は章の一覧そのものが出ていないので何もしない。
   */
  function scrollSidebarToFocus(nav) {
    if (sideQuery) { return; }
    nav = nav || sidebarTree();
    if (!nav || !nav.clientHeight) { return; }   // 閉じている＝測れない
    var focusId = sidebarFocusId();
    if (!focusId) {
      sideScrolledFor = null;
      return;
    }
    var target = nav.querySelector('.lesson-current') || nav.querySelector('.lesson-focus');
    // 章をたたんでいると単元は見えていないので、そのときは章の見出しに寄せる。
    if (!target || !target.offsetParent) { target = nav.querySelector('.ch-current'); }
    if (!target) { return; }
    // 真ん中あたりに置く。前後の単元も一緒に見えるので現在地が分かりやすく、
    // 貼り付いた編の見出しの裏に隠れることもない。
    var box = target.getBoundingClientRect();
    var offset = (box.top - nav.getBoundingClientRect().top)
      - Math.max(0, (nav.clientHeight - box.height) / 2);
    nav.scrollTop += offset;
    // 非表示中や描画途中で対象が見つからなかった場合は完了扱いにしない。
    // 実際に移動できたここで記録すれば、サイドバーを開いた時に再試行できる。
    sideScrolledFor = focusId;
  }

  /**
   * 複数問あるレッスンも分母は見せず、途中なら現在の状態だけを示す。
   */
  function lessonTaskProgress(lesson) {
    if (lesson.type === 'preflight') { return '<span class="lesson-frac">準備</span>'; }
    // 概念レッスンは提出課題を持たないので、開く前に「クイズで★が付く」と分かるようにする。
    if (lesson.type === 'concept') {
      return lesson.cleared ? '' : '<span class="lesson-frac">クイズ</span>';
    }
    if (lesson.taskCount < 2 || lesson.cleared) { return ''; }
    return lesson.clearedCount ? '<span class="lesson-frac">学習中</span>' : '';
  }

  function lessonTooltip(lesson) {
    if (lesson.type === 'preflight') { return '環境の事前確認（★対象外）: ' + lesson.title; }
    if (lesson.cleared) { return 'クリア済み: ' + lesson.title; }
    if (lesson.type === 'concept') { return 'クイズ全問正解で★: ' + lesson.title; }
    return lesson.clearedCount ? lesson.title + '（学習中）' : lesson.title;
  }

  // -------------------------------------------------------- 学習ホーム描画

  /** 初回だけ、実際のホーム画面に重ねて操作する場所を順番に案内する。 */
  function onboardingTourSteps() {
    return [
      {
        selector: '#continueBtn',
        title: 'ここから学習を始めます',
        body: '「はじめる」をクリックすると、最初のレッスンが開きます。次回からは、中断したレッスンへ「続ける」で戻れます。'
      },
      {
        selector: '.part-tab.active',
        title: '学びたい編を切り替えます',
        body: 'このタブをクリックすると、Java基礎編やWeb・Jakarta EE編など、学びたい分野へ切り替えられます。'
      },
      {
        selector: '#chapterStartBtn',
        title: '章やレッスンを選べます',
        body: '左側で章を選び、右側のレッスン名または「この章を始める」をクリックすると、その教材が開きます。'
      },
      {
        selector: '#cafeBtn',
        title: '学んだ成果でカフェを育てます',
        body: '「カフェ」をクリックすると、学習で得たコインを使って設備やお店を育てる画面へ移動できます。'
      }
    ];
  }

  function mountOnboardingTour() {
    removeOnboardingTour();
    onboardingTourStep = 0;
    document.body.insertAdjacentHTML('beforeend',
      '<div class="onboarding-tour" id="onboardingTour" role="dialog" aria-modal="true"' +
      ' aria-labelledby="onboardingTourTitle" aria-describedby="onboardingTourBody">' +
      '  <div class="onboarding-tour-shade" aria-hidden="true"></div>' +
      '  <div class="onboarding-tour-spotlight" id="onboardingSpotlight" aria-hidden="true"></div>' +
      '  <section class="onboarding-tour-card">' +
      '    <header class="onboarding-tour-head">' +
      '      <span class="screen-eyebrow" id="onboardingTourCount"></span>' +
      '      <button class="onboarding-tour-skip" id="onboardingSkip" type="button">スキップ</button>' +
      '    </header>' +
      '    <div class="onboarding-tour-dots" id="onboardingTourDots" aria-hidden="true"></div>' +
      '    <h2 id="onboardingTourTitle"></h2>' +
      '    <p id="onboardingTourBody"></p>' +
      '    <p class="onboarding-tour-note">学習の進捗と書いたコードは自動で保存されます。</p>' +
      '    <p class="onboarding-tour-error" id="onboardingTourError" role="alert"></p>' +
      '    <footer class="onboarding-tour-actions">' +
      '      <button class="ghost-btn" id="onboardingBack" type="button">← 戻る</button>' +
      '      <button class="primary-btn" id="onboardingNext" type="button"></button>' +
      '    </footer>' +
      '  </section>' +
      '</div>');

    var root = document.getElementById('onboardingTour');
    document.getElementById('onboardingBack').addEventListener('click', function () {
      if (onboardingTourStep > 0) {
        onboardingTourStep--;
        showOnboardingTourStep();
      }
    });
    document.getElementById('onboardingNext').addEventListener('click', function () {
      if (onboardingTourStep + 1 < onboardingTourSteps().length) {
        onboardingTourStep++;
        showOnboardingTourStep();
      } else {
        completeOnboarding();
      }
    });
    document.getElementById('onboardingSkip').addEventListener('click', completeOnboarding);
    root.addEventListener('keydown', trapOnboardingTourFocus);
    showOnboardingTourStep();
  }

  function showOnboardingTourStep() {
    var root = document.getElementById('onboardingTour');
    var steps = onboardingTourSteps();
    var step = steps[onboardingTourStep];
    if (!root || !step) { return; }

    document.getElementById('onboardingTourCount').textContent =
      'QUICK TOUR  ' + (onboardingTourStep + 1) + ' / ' + steps.length;
    document.getElementById('onboardingTourTitle').textContent = step.title;
    document.getElementById('onboardingTourBody').textContent = step.body;
    document.getElementById('onboardingTourError').textContent = '';
    document.getElementById('onboardingBack').hidden = onboardingTourStep === 0;
    document.getElementById('onboardingNext').textContent =
      onboardingTourStep + 1 === steps.length ? '案内を終える' : '次へ →';
    document.getElementById('onboardingTourDots').innerHTML = steps.map(function (_, index) {
      return '<i class="' + (index === onboardingTourStep ? 'active' : '') + '"></i>';
    }).join('');

    var target = document.querySelector(step.selector);
    if (!target) { return; }
    if (target.closest('#content')) {
      target.scrollIntoView({ block: 'center', inline: 'nearest' });
    }
    requestAnimationFrame(function () {
      positionOnboardingSpotlight(target);
      document.getElementById('onboardingNext').focus();
    });
  }

  function positionOnboardingSpotlight(target) {
    var spotlight = document.getElementById('onboardingSpotlight');
    var card = document.querySelector('.onboarding-tour-card');
    if (!spotlight || !card || !target || !document.body.contains(target)) { return; }

    var rect = target.getBoundingClientRect();
    var cardTop = card.getBoundingClientRect().top;
    var main = document.getElementById('content');
    if (target.closest('#content') && rect.bottom > cardTop - 24) {
      main.scrollTop += rect.bottom - cardTop + 24;
      rect = target.getBoundingClientRect();
    }

    var gap = 7;
    var left = Math.max(8, rect.left - gap);
    var top = Math.max(8, rect.top - gap);
    var right = Math.min(window.innerWidth - 8, rect.right + gap);
    var bottom = Math.min(window.innerHeight - 8, rect.bottom + gap);
    spotlight.style.left = left + 'px';
    spotlight.style.top = top + 'px';
    spotlight.style.width = Math.max(0, right - left) + 'px';
    spotlight.style.height = Math.max(0, bottom - top) + 'px';
  }

  function repositionOnboardingTour() {
    var step = onboardingTourSteps()[onboardingTourStep];
    var target = step && document.querySelector(step.selector);
    if (target) { positionOnboardingSpotlight(target); }
  }

  function trapOnboardingTourFocus(event) {
    var root = document.getElementById('onboardingTour');
    if (!root || event.key !== 'Tab') { return; }
    var buttons = Array.prototype.filter.call(root.querySelectorAll('button:not(:disabled)'), function (button) {
      return !button.hidden;
    });
    if (!buttons.length) { return; }
    var first = buttons[0];
    var last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function removeOnboardingTour() {
    var root = document.getElementById('onboardingTour');
    if (root) { root.remove(); }
  }

  function completeOnboarding() {
    var root = document.getElementById('onboardingTour');
    var button = document.getElementById('onboardingNext');
    var error = document.getElementById('onboardingTourError');
    if (!button || button.disabled) { return; }
    var buttons = root ? root.querySelectorAll('button') : [];
    Array.prototype.forEach.call(buttons, function (item) { item.disabled = true; });
    var previousLabel = button.textContent;
    button.textContent = '保存しています…';
    error.textContent = '';

    api('onboarding/complete', {})
      .then(function (res) {
        applyDelta(res.delta);
        currentId = null;
        reviewTaskId = null;
        currentView = 'menu';
        removeOnboardingTour();
        if (location.hash !== '#menu') { location.hash = 'menu'; }
        render();
        document.getElementById('content').scrollTop = 0;
      })
      .catch(function (e) {
        Array.prototype.forEach.call(buttons, function (item) { item.disabled = false; });
        button.textContent = previousLabel;
        error.textContent = '保存できませんでした: ' + e.message;
      });
  }

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
      '</div>';

    renderChapterCards();

    var goBtn = document.getElementById('continueBtn');
    if (goBtn) {
      goBtn.addEventListener('click', function () { selectLesson(goBtn.dataset.target); });
    }
    var reviewBtn = document.getElementById('reviewBtn');
    if (reviewBtn && !reviewBtn.disabled) {
      reviewBtn.addEventListener('click', goReview);
    }
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
      '<section class="menu-hero learning-hero home-learning-hero">' +
      '  <div class="hero-milestone" aria-label="これまでに' + stars + '問クリア">' +
      '    <span>★</span><b>' + stars + '</b><small>問クリア</small>' +
      '  </div>' +
      '  <div class="hero-body learning-hero-copy">' +
      '    <span class="screen-eyebrow">TODAY\'S LEARNING</span>' +
      '    <h1 class="hero-title">Javaを学ぶ</h1>' +
      '    <p class="hero-sub">手を動かして身につける · 自分のペースで一問ずつ</p>' +
      '  </div>' +
      '  <div class="hero-action learning-hero-actions">' +
      '      <div class="hero-next"><div class="cta-lead">' + esc(lead) + '</div>' +
      (lesson ? '      <div class="cta-target"><span class="cta-id">' + esc(displayLessonId(lesson)) + '</span>'
        + esc(lesson.title) + '</div>' : '') + '</div>' +
      '      <div class="hero-buttons">' +
      '        <button class="primary-btn big" id="continueBtn" data-target="' + esc(target) + '">' +
               esc(label) +
      '        </button>' +
               reviewHeroButtonHtml(stars) +
      '      </div>' +
      '  </div>' +
      '</section>';
  }

  /**
   * 「続ける」の下に置く復習の入口。
   * まだ1問もクリアしていないうちは解き直せるものが無いので、押せないまま理由を出す。
   *
   * ただしクイズのしおりは★0でも付けられて、置き場所は復習ホームしかない。
   * 押せないままにすると付けた印へ戻れなくなるので、しおりがあるなら開けるようにする。
   */
  function reviewHeroButtonHtml(stars) {
    if (!stars) {
      var marks = quizBookmarkEntries().length;
      if (marks) {
        return '<button class="ghost-btn big review-cta" id="reviewBtn">🔁 復習する' +
          '<small>クイズのしおり ' + marks + '件</small></button>';
      }
      return '<button class="ghost-btn big review-cta" id="reviewBtn" disabled' +
        ' title="問題を1問クリアすると復習できます">🔁 復習する' +
        '<small>クリア後に使えます</small></button>';
    }
    // 「苦手」の数え方は復習ホームの絞り込みと同じ（→ reviewMatchesFilter）。
    // 別に書くと、ホームの「苦手 3問」を押した先が全部「安定」になる
    var weak = reviewCandidates().filter(function (entry) {
      return reviewMatchesFilter(entry, 'weak');
    }).length;
    return '<button class="ghost-btn big review-cta" id="reviewBtn">🔁 復習する' +
      // 数えているのは「苦手」（しきい値以上）なので、0のときも同じ言葉で言う。
      // 「間違えた問題はありません」と書くと、しきい値未満の苦手度が残っている問題まで
      // 「1問も間違えていない」と読めてしまう（2026-08-22・利用者の指摘）
      '<small>' + (weak ? '苦手 ' + weak + '問' : '苦手な問題はありません') + '</small></button>';
  }

  // ------------------------------------------------------------ 復習モード

  /*
   * 復習モードは「クリア済みの問題をもう一度解く」場所。
   *
   * 出題は抽選で決める。間違えた回数（苦手度 = task.reviewWeight）が多い問題ほど
   * 当たりやすくし、復習で正解するとサーバ側で苦手度が下がるので、次からは出にくくなる。
   * 出題は<b>忘却曲線の期限順</b>で決める。問題ごとに「次に確認したい日」をサーバが持ち
   * （最後に復習した日 + レベルごとの間隔）、期限が過ぎたものから順に出す。
   * 一度正解すると間隔が伸びるので、しばらく出てこない。
   *
   * <b>できている問題は早く抜ける。</b>失敗を挟まずに2回続けて通した問題は間隔を2段
   * まとめて飛ばし（サーバの ProgressStore.updateReviewPlan）、ヒントを見ずに一発で
   * クリアした問題は最初の期限が翌日ではなく3日後になる。回数は詰まった問題に使う。
   *
   * 期限が来たものが1セット分に足りない日は、期限が近い順で「早めの復習」として補う。
   * 0問の画面を見せると復習の習慣が途切れるので、いつでも始められる形にしてある。
   *
   * 1回で全部出すのではなく<b>短いセットを重ねる</b>形にしてある。セットを終えるたびに
   * 復習ホームへ戻り、そこから「もう1セット」で続けられる。続けているあいだは
   * {@link reviewRun} が「もう出した問題」を覚えていて、同じ問題が続けて出ないようにする。
   *
   * 苦手度と期限はサーバが持つ（web側で数えると再読込でズレる）。ここでは
   * サーバから来た値を読んで、並べ替えと表示に使うだけ。
   */

  /** 復習できる問題（クリア済みのもの）を、出題順の元になる配列にして返す。 */
  function reviewCandidates() {
    var list = [];
    allLessons().forEach(function (lesson) {
      lesson.tasks.forEach(function (task) {
        if (!task.cleared || task.required === false) { return; }
        var dueDays = task.reviewDueDays == null ? 0 : Number(task.reviewDueDays);
        list.push({
          lesson: lesson,
          task: task,
          weight: Number(task.reviewWeight || 0),
          bookmarked: !!task.bookmarked,
          level: Number(task.reviewLevel || 0),
          cleanRun: Number(task.reviewCleanRun || 0),
          fastTrack: !!task.reviewFastTrack,
          dueDays: dueDays,
          overdue: dueDays <= 0
        });
      });
    });
    return list;
  }

  /**
   * 絞り込みに残すか。
   *
   * <b>「🔥 苦手」はバッジと同じしきい値で判定する</b>（`reviewWeightLevel` が0より上）。
   * 以前は `weight > 0` だったので、0.25点（失敗1回）の問題が「苦手 3問」に数えられるのに
   * 行のバッジは `安定` と出ていた ―― 押した先が全部「安定」で、何を絞り込んだのか
   * 読めなかった（2026-08-22）。しきい値を2か所に書くとまた食い違うので、
   * ここは段（level）で見る。
   */
  function reviewMatchesFilter(entry, filter) {
    if (filter === 'weak') { return reviewWeightLevel(entry.weight) > 0; }
    if (filter === 'bookmark') { return entry.bookmarked; }
    return true;
  }

  function filteredReviewCandidates(filter) {
    return reviewCandidates().filter(function (entry) {
      return reviewMatchesFilter(entry, filter);
    });
  }

  function reviewFilterLabel(filter) {
    if (filter === 'weak') { return '苦手な問題'; }
    if (filter === 'bookmark') { return 'ブックマークした問題'; }
    return 'クリア済みの問題';
  }

  /**
   * 苦手度は4単位で1点。失敗1回は1単位しか増えないので、表示は点に直して見せる。
   *
   * 単位はサーバが決める（`/api/state` の `reviewWeightScale`）。**失敗1回は0.5点**で、
   * 復習で正解すると1点（=失敗2回ぶん）下がる。「試しに実行」はここを通らない
   * （採点しないので苦手度も動かない ―― 試行錯誤はあちらが引き受ける）。
   */
  var REVIEW_WEIGHT_SCALE = 4;

  function reviewWeightPoints(weight) {
    return Number(weight || 0) / REVIEW_WEIGHT_SCALE;
  }

  /**
   * 苦手度の段（0〜3）。バッジの言葉と色はこの段で決まる。
   *
   * <b>しきい値は「失敗した回数」で読むこと</b>（失敗1回 = 0.5点 →
   * `ProgressStore.REVIEW_WEIGHT_PER_FAIL`）。0.5 / 1.5 / 3点 = 失敗 1 / 3 / 6 回である。
   *
   * 以前は 1 / 3 / 5点で、しかも失敗1回が0.25点だった（＝失敗 4 / 12 / 20 回）。
   * **失敗3回まで「安定」と同じ表示**になるため、実際の記録では全問が「安定」と
   * 出ていた（2026-08-22・利用者の指摘）。**目盛りとしきい値は別のファイルにあるので、
   * 片方だけ動かすと表示がずれる。**
   */
  function reviewWeightLevel(weight) {
    var points = reviewWeightPoints(weight);
    if (points >= 3) { return 3; }
    if (points >= 1.5) { return 2; }
    if (points >= 0.5) { return 1; }
    return 0;
  }

  /** 苦手度の見せ方。数字だけでは何回で危ないのか伝わらないので、短い言葉にする。 */
  function reviewWeightText(weight) {
    var points = reviewWeightPoints(weight);
    if (points >= 3) { return '🔥 よく間違えた'; }
    if (points >= 1.5) { return '🔥 苦手'; }
    if (points >= 0.5) { return 'もう一度'; }
    return '安定';
  }

  /**
   * 一覧の右に出す「いまどの扱いか」。苦手度と、間隔を飛ばしている印を同じ枠で出す。
   *
   * 枠を増やさないのは、行に並ぶ情報を増やすと期限が読み取りにくくなるため。
   * 苦手度が付いている問題では苦手度を優先する ―― 飛び級は苦手度0の問題しか到達しないが、
   * 「安定」と「⚡」が同じ意味の枠に交互に出るより、危ない側を必ず見せるほうがよい。
   */
  function reviewStandingHtml(entry) {
    if (entry.fastTrack && !entry.weight) {
      return '<span class="review-weight review-fast" data-level="0"'
        + ' title="失敗を挟まずに' + entry.cleanRun + '回続けて通しました。間隔を2段ずつ飛ばします">'
        + '⚡ 一発' + entry.cleanRun + '連続</span>';
    }
    return '<span class="review-weight" data-level="' + reviewWeightLevel(entry.weight) + '">' +
      esc(reviewWeightText(entry.weight)) + '</span>';
  }

  /** 期限の見せ方。「いつ確認したいのか」が一目で分かる短い言葉にする。 */
  function reviewDueText(entry) {
    if (entry.dueDays <= -1) { return '⏰ ' + (-entry.dueDays) + '日 過ぎている'; }
    if (entry.dueDays === 0) { return '⏰ 今日が期限'; }
    if (entry.dueDays === 1) { return '明日が期限'; }
    return 'あと' + entry.dueDays + '日';
  }

  /**
   * 出題の並び順。期限を過ぎたものが先、その中では過ぎている日数が多い順。
   *
   * 同じ日数なら、苦手なもの・ブックマークしたものを先に出す。抽選をやめたのは、
   * 「そろそろ確認したい問題」を運任せにすると、期限切れが後回しになるため。
   */
  function compareReviewEntries(a, b) {
    if (a.overdue !== b.overdue) { return a.overdue ? -1 : 1; }
    if (a.dueDays !== b.dueDays) { return a.dueDays - b.dueDays; }
    if (b.weight !== a.weight) { return b.weight - a.weight; }
    return (b.bookmarked ? 1 : 0) - (a.bookmarked ? 1 : 0);
  }

  /**
   * 今回のセットの出題を決める。期限が過ぎたものを先に、足りなければ期限が近い順で補う。
   *
   * 1セットを {@code REVIEW_SESSION_SIZE} 問で切るのは、終わりが見えないと復習を
   * 始めにくいため。もっと解きたい人は「もう1セット」で足す。
   *
   * 続けているあいだは、そのセットまでに出した問題を外す。通せなかった問題は期限切れの
   * まま先頭に残るので、外さないと次のセットも同じ問題で埋まり、そこで足止めになる。
   */
  function buildReviewQueue(filter) {
    return nextReviewEntries(filter).map(function (entry) {
      return { lessonId: entry.lesson.id, taskId: entry.task.id };
    });
  }

  /** まだこの回で出していない問題を、出題順に並べて返す。 */
  function pendingReviewCandidates(filter) {
    var served = reviewRun ? reviewRun.servedTaskKeys : {};
    return filteredReviewCandidates(filter)
      .filter(function (entry) {
        return !served[entry.lesson.id + '#' + entry.task.id];
      })
      .sort(compareReviewEntries);
  }

  /** 次のセットで出る問題。復習ホームの「今回のセット」もこれを見て数える。 */
  function nextReviewEntries(filter) {
    return pendingReviewCandidates(filter).slice(0, REVIEW_SESSION_SIZE);
  }

  /**
   * セッションの最後に続けて出すクイズを決める。
   *
   * <b>一度答えたクイズだけ</b>を対象にする ― まだ答えていないクイズは「1度目の回答」の
   * 在庫で、復習で先に出すとその機会を奪ってしまう（チップも📣の初回答の連続もそこにある）。
   *
   * すでに「復習の連続正解」に入っているクイズは外す。サーバ側は重複しない集合で数えるので
   * （覚えた1問を繰り返すだけで並ばないように）、外さないと20問そろわない。
   *
   * 並びは 誤答 → しおり → 残り で、それぞれ教材の順。抽選はしない（問題側の出題と同じ方針）。
   *
   * セットを重ねているあいだは、すでに出したクイズも外す（間違えたクイズは連続の集合に
   * 入らないので、外さないと次のセットでも同じ問いが先頭に来てしまう）。
   */
  function buildReviewQuizQueue() {
    var inRun = {};
    (cafeState().quizReviewRunKeys || []).forEach(function (key) { inRun[key] = true; });
    var served = reviewRun ? reviewRun.servedQuizKeys : {};
    var wrong = [];
    var marked = [];
    var rest = [];
    allLessons().forEach(function (lesson) {
      (lesson.quizzes || []).forEach(function (quiz, index) {
        var result = (lesson.quizResults || [])[index];
        var key = lesson.id + '#' + index;
        if (!result || inRun[key] || served[key]) { return; }
        var entry = { lessonId: lesson.id, index: index };
        if (!result.correct) { wrong.push(entry); }
        else if (quizBookmarked(lesson, index)) { marked.push(entry); }
        else { rest.push(entry); }
      });
    });
    return wrong.concat(marked, rest).slice(0, REVIEW_QUIZ_SESSION_SIZE);
  }

  function setReviewFilter(filter) {
    reviewFilter = filter;
    try { localStorage.setItem('jq-review-filter', filter); } catch (e) { /* 使えなくても困らない */ }
    renderReview();
  }

  /**
   * 続けて重ねたセットの控えを作る（作り直す）。
   *
   * 「もう1セット」で続けているあいだだけ生きていて、出し終えた問題とクイズ、それまでの
   * 成績を覚えている。画面の中の積み上げは、復習ホームから始め直したとき・途中で終えた
   * とき・結果を出さずに復習ホームを開いたときに捨てる（{@link renderReview} の先頭）。
   *
   * <b>新しい回を始めるときは、途中のセットの控えも差し替える。</b>ここへ来たのは
   * 「はじめから」を選んだときなので、前の続きを残すと復習ホームに続きのカードが
   * 出たままになる（→ {@link saveReviewRun}）。
   */
  function startReviewRun() {
    clearReviewRun();
    reviewRun = {
      sets: 0, total: 0, cleared: 0, quizTotal: 0, quizCorrect: 0, cash: 0,
      servedTaskKeys: {}, servedQuizKeys: {}
    };
  }

  /*
   * ------------------------------------------- 解いている途中のセットの控え
   *
   * 解いている途中で抜けても「続きから」戻れるようにするための控えである（2026-08-21）。
   * これが無かったころは、抜け方によらず次は<b>新しい1セット目</b>から始まっていた
   * ―― 3問目まで解いた人が戻ってくると、また別の4問が並ぶ。
   *
   * <b>どの抜け方でも戻れる。</b>移動（ヘッダ・サイドバー・ブラウザの戻る）でも、
   * 帯の「復習を終える」でも、再読み込みでも、タブを閉じても同じように残す。抜け方で
   * 結果が変わると、押したボタンを後悔する場面ができてしまう（→ 取り返しのつかない
   * 要素を作らない方針）。だから {@link endReviewSession} はここを消さない。
   *
   * 置き場所は localStorage である。サーバの進捗（`progress.json`）が持っているのは
   * 苦手度と期限で、<b>セットの枠は画面側にしか無い</b>。ここをサーバへ移すと進捗
   * ファイルの形が増えるいっぽう、控えなので消えても学習の記録は1つも失われない
   * （＝別のブラウザで開くと続きは無い。獲得の履歴と同じ扱い → COIN_LOG_KEY）。
   *
   * <b>控えるのは「途中のセット」だけ。</b>結果まで進んだセットは消す
   * （{@link finishReviewSession}）―― 続きは結果カードの「もう1セット」が持っているし、
   * 出し終えた回の `servedTaskKeys` を後日まで残すと、次に開いた1セット目から
   * 「もう出した」ぶんが抜けたままになる。
   *
   * <b>日付が変わったら捨てる。</b>期限（`reviewDueDays`）は日単位で引き直すので、
   * 昨日組んだ4問は今日の期限と噛み合わない。翌日は新しい1セット目から始める。
   */

  /** 控えが「いつのものか」。時刻は見ない（獲得の履歴と同じ数え方を使う）。 */
  function reviewRunDayKey() {
    return coinLogDayKey(new Date());
  }

  /**
   * いま解いているセットを控える。
   *
   * 呼ぶのは中身が動いた直後（始めた・次へ進んだ・通した・クイズに答えた）。まとめて
   * 1か所で書けないのは、どこで抜けられても直前の状態が残っていてほしいため。
   */
  function saveReviewRun() {
    if (!reviewSession || !reviewRun) { return; }
    try {
      localStorage.setItem(REVIEW_RUN_KEY, JSON.stringify({
        day: reviewRunDayKey(), filter: reviewFilter, set: reviewSession, run: reviewRun
      }));
    } catch (e) { /* 保存できなくても、開いている間の復習は成立する */ }
  }

  function clearReviewRun() {
    try { localStorage.removeItem(REVIEW_RUN_KEY); } catch (e) { /* 同上 */ }
  }

  /**
   * 控えを読む。<b>いま出しても成り立つものだけ</b>返し、そうでなければ捨てて null。
   *
   * 崩れていたら部分的に直さず丸ごと捨てる。何問目かはキューの並びを前提にした数字なので、
   * 消えた問題を抜いて詰めると「3 / 4問目」が別の問題を指してしまう。
   *
   * 進捗のリセットや別の進捗ファイルで開いた場合も、クリア済みの確認で落ちる
   * （復習は「クリアした問題を解き直す」場所なので、{@code routeFromHash} が
   * 復習の1問に対してしている確認と同じものを使う）。
   */
  function loadReviewRun() {
    var saved = null;
    try { saved = JSON.parse(localStorage.getItem(REVIEW_RUN_KEY) || 'null'); }
    catch (e) { saved = null; }
    if (!saved || typeof saved !== 'object') { clearReviewRun(); return null; }

    var set = saved.set;
    var run = saved.run;
    var ok = saved.day === reviewRunDayKey()
      && (saved.filter === 'all' || saved.filter === 'weak' || saved.filter === 'bookmark')
      && set && run && Array.isArray(set.queue) && Array.isArray(set.quizQueue)
      // 問題もクイズも出し切った位置なら「途中」ではない（結果まで進んだセット）
      && (Number(set.index) < set.queue.length
        || (Number(set.quizIndex) >= 0 && Number(set.quizIndex) < set.quizQueue.length))
      && set.queue.every(function (item) {
        var lesson = item && findLesson(item.lessonId);
        var task = lesson && findTask(lesson, item.taskId);
        return !!(task && task.cleared);
      })
      && set.quizQueue.every(function (item) {
        var lesson = item && findLesson(item.lessonId);
        var quiz = lesson && (lesson.quizzes || [])[item.index];
        // 復習で出すのは「一度答えたクイズ」だけ（→ buildReviewQuizQueue）
        return !!(quiz && (lesson.quizResults || [])[item.index]);
      });
    if (!ok) { clearReviewRun(); return null; }
    return saved;
  }

  /**
   * 「続きから」。控えを画面の状態へ戻し、抜けたところをもう一度開く。
   *
   * クイズの段の戻し方は {@link startReviewQuizSession} と同じ（復習ホームの上に
   * 塗り替えるので、`currentView` は `review` のまま）。
   */
  function resumeReviewRun() {
    var saved = loadReviewRun();
    if (!saved) {
      toast('続きが見つかりませんでした（新しい1セットから始められます）');
      renderReview();
      return;
    }
    reviewFilter = saved.filter;
    reviewRun = saved.run;
    reviewSession = saved.set;
    reviewSummary = null;
    if (inReviewQuizPhase()) {
      renderReviewQuiz();
      return;
    }
    var item = reviewSession.queue[reviewSession.index];
    selectReviewTask(item.lessonId, item.taskId);
  }

  /**
   * 1セットを始める。
   *
   * @param continued 「もう1セット」から来たなら true（それまでの積み上げを引き継ぐ）
   */
  function startReviewSession(filter, continued) {
    if (!continued || !reviewRun) { startReviewRun(); }
    var queue = buildReviewQueue(filter);
    if (!queue.length) {
      toast(continued
        ? '続けて出せる問題は出し切りました'
        : 'この絞り込みには復習できる問題がありません');
      return;
    }
    reviewSummary = null;
    reviewSession = {
      queue: queue, index: 0, cleared: 0, clearedKeys: {}, cash: 0,
      quizQueue: buildReviewQuizQueue(), quizIndex: -1, quizCorrect: 0
    };
    noteReviewServed(reviewSession);
    saveReviewRun();
    selectReviewTask(queue[0].lessonId, queue[0].taskId);
  }

  /**
   * クイズだけを解き直すセット。問題の復習が無い日と、問題を出し切ったあとの入口。
   *
   * 問題のキューを空にしてクイズの段から始めるだけで、数え方は通常のセットと同じ。
   */
  function startReviewQuizSession(continued) {
    if (!continued || !reviewRun) { startReviewRun(); }
    var quizQueue = buildReviewQuizQueue();
    if (!quizQueue.length) {
      toast(continued ? '続けて出せるクイズは出し切りました' : '解き直せるクイズがありません');
      return;
    }
    reviewSummary = null;
    reviewSession = {
      queue: [], index: 0, cleared: 0, clearedKeys: {}, cash: 0,
      quizQueue: quizQueue, quizIndex: 0, quizCorrect: 0
    };
    noteReviewServed(reviewSession);
    saveReviewRun();
    renderReviewQuiz();
  }

  /** このセットで出す問題とクイズを「もう出した」側へ移す（次のセットで外すため）。 */
  function noteReviewServed(session) {
    session.queue.forEach(function (item) {
      reviewRun.servedTaskKeys[item.lessonId + '#' + item.taskId] = true;
    });
    session.quizQueue.forEach(function (item) {
      reviewRun.servedQuizKeys[item.lessonId + '#' + item.index] = true;
    });
  }

  /**
   * 「もう1セット」。問題が残っていれば問題から、無ければクイズだけで続ける。
   *
   * 押した先で「もう無い」と言われるのを避けるため、ボタン側でも残りを見て出し分けている
   * （→ reviewMoreButtonHtml）。ここはその判断をもう一度なぞるだけ。
   */
  function continueReviewRun() {
    if (buildReviewQueue(reviewFilter).length) {
      startReviewSession(reviewFilter, true);
      return;
    }
    startReviewQuizSession(true);
  }

  /**
   * 1つ前の問題へ戻る（2026-08-22・利用者の要望）。
   *
   * 飛ばした問題や、通したあとで「もう一度あれを解きたい」に応える道である。無いと
   * 前の問題へ戻るには復習を終えて1問だけ選び直すしかなく、そこで抜けるとセットの
   * 続きが分からなくなる。
   *
   * <b>数え直しは起きない。</b>このセットで通した問題は {@code clearedKeys} が覚えて
   * いるので、戻って解き直しても正解数は増えない（→ {@link onReviewCleared}）。コインも
   * 通した時点で次の期限が動いているため、同じ日の2回目は0が返る。
   *
   * <b>クイズの段からは戻さない。</b>答えたクイズをもう一度出すと、サーバへ投げ直して
   * 復習の連続正解を数え直せてしまう（→ {@link answerReviewQuiz}）。クイズの段に入ったら
   * 問題へは戻らない。
   */
  function backReviewSession() {
    if (!reviewSession || inReviewQuizPhase() || reviewSession.index <= 0) { return; }
    reviewSession.index--;
    saveReviewRun();
    var prev = reviewSession.queue[reviewSession.index];
    selectReviewTask(prev.lessonId, prev.taskId);
  }

  /**
   * 次の問題へ。問題を出し切ったらクイズの段へ進み、それも終わったら復習ホームへ戻す。
   */
  function advanceReviewSession() {
    if (!reviewSession) {
      goReview();
      return;
    }
    reviewSession.index++;
    if (reviewSession.index >= reviewSession.queue.length) {
      if (reviewSession.quizQueue.length) {
        reviewSession.quizIndex = 0;
        saveReviewRun();
        renderReviewQuiz();
        return;
      }
      finishReviewSession();
      return;
    }
    var next = reviewSession.queue[reviewSession.index];
    saveReviewRun();
    selectReviewTask(next.lessonId, next.taskId);
  }

  /**
   * 今回のセットの成績を控えてセッションを閉じ、復習ホームへ戻す。
   *
   * <b>途中のセットの控えはここで消す。</b>結果まで進んだ回は「続き」ではなく、
   * 続けたい人は結果カードの「もう1セット」で進む（→ saveReviewRun のまえがき）。
   */
  function finishReviewSession() {
    if (!reviewRun) { startReviewRun(); }
    var quizTotal = reviewSession.quizIndex < 0 ? 0 : reviewSession.quizQueue.length;
    reviewRun.sets++;
    reviewRun.total += reviewSession.queue.length;
    reviewRun.cleared += reviewSession.cleared;
    reviewRun.quizTotal += quizTotal;
    reviewRun.quizCorrect += reviewSession.quizCorrect;
    reviewRun.cash += reviewSession.cash;
    reviewSummary = {
      total: reviewSession.queue.length,
      cleared: reviewSession.cleared,
      quizTotal: quizTotal,
      quizCorrect: reviewSession.quizCorrect,
      sets: reviewRun.sets,
      runTotal: reviewRun.total,
      runCleared: reviewRun.cleared,
      runQuizTotal: reviewRun.quizTotal,
      runQuizCorrect: reviewRun.quizCorrect,
      cash: reviewSession.cash,
      runCash: reviewRun.cash
    };
    reviewSession = null;
    clearReviewRun();
    goReview();
  }

  /**
   * 途中で切り上げる（帯の「復習を終える」・パンくずの「🔁 復習」）。
   *
   * 画面の中のセッションと積み上げは畳むが、<b>控えは消さない</b>。抜け方によって
   * 続きが残る／残らないが変わると、押したボタンを後悔する場面ができてしまう。
   * 復習ホームには「▶ 続きから」が出て、新しい1セットも選べる（→ reviewResumeHtml）。
   */
  function endReviewSession() {
    reviewSession = null;
    reviewSummary = null;
    reviewRun = null;
    goReview();
  }

  // ------------------------------------------------------- 復習ホームの描画

  function renderReview() {
    // 結果を出さずにここへ来たなら、続けているセットではない（画面の中の積み上げを捨てる）。
    // 残しておくと、あとで開き直したときに「もう出した」ぶんが出題から抜けたままになる。
    // 途中のセットの控え（localStorage）はこれとは別で、下の resume がそれを見る。
    if (!reviewSummary) { reviewRun = null; }
    // 解いている途中で抜けたセット。結果カードとは同時に出ない（結果まで進んだ回は
    // finishReviewSession が控えを消しているので、ここは必ず null になる）。
    var resume = loadReviewRun();
    var candidates = reviewCandidates();
    // 数え方は絞り込みそのものと同じ関数で決める（別に書くと「苦手 3問」と中身が食い違う）
    var counts = {
      all: candidates.length,
      weak: candidates.filter(function (entry) {
        return reviewMatchesFilter(entry, 'weak');
      }).length,
      bookmark: candidates.filter(function (entry) {
        return reviewMatchesFilter(entry, 'bookmark');
      }).length
    };
    var overdue = candidates.filter(function (entry) { return entry.overdue; }).length;
    // 絞り込んだ先が空になっていたら「すべて」へ戻す（0問の画面を見せないため）
    if (reviewFilter === 'weak' && !counts.weak) { reviewFilter = 'all'; }
    if (reviewFilter === 'bookmark' && !counts.bookmark) { reviewFilter = 'all'; }
    var filter = reviewFilter;

    var main = document.getElementById('content');
    var head =
      '  <header class="screen-heading">' +
      '    <div><span class="screen-eyebrow">REVIEW</span>' +
      '    <h1>🔁 復習する</h1>' +
      '    <p>一度クリアした問題を解き直します。そろそろ確認したい問題から出ます。</p></div>' +
      '    <button class="ghost-btn screen-back" id="backToLearningBtn">📚 章を選ぶ</button>' +
      '  </header>';

    if (!counts.all) {
      // クイズのしおりは★0でも付けられるので、解き直せる問題が無い日でもここに出す。
      // 答えたクイズがあるなら、それだけを解き直せるようにもする ―― 📣の解放は
      // 「復習で異なる20問に連続正解」でも進むので、問題の復習が無い日に道を塞がない
      var quizOnly = buildReviewQuizQueue().length;
      main.innerHTML =
        '<div class="menu review-page">' + head +
        // クイズだけの復習もここへ戻ってくるので、成績はこの分岐でも出す。
        // 続きのカードも同じ理由でここに要る ―― クイズだけのセットはこの分岐から
        // 始まるので、その途中で抜けた人はここにしか戻る場所が無い
             reviewSummaryHtml() +
             reviewResumeHtml(resume) +
        '  <section class="menu-section review-empty">' +
        '    <span class="review-empty-icon">📚</span>' +
        '    <div><strong>復習できる問題はまだありません</strong>' +
        '    <p>問題を1問クリアすると、ここで解き直せるようになります。</p></div>' +
        '    <button class="primary-btn" id="reviewEmptyBtn">章を選ぶ</button>' +
        '  </section>' +
        (quizOnly
          ? '  <section class="menu-section review-empty">' +
            '    <span class="review-empty-icon">🧠</span>' +
            '    <div><strong>答えた確認クイズなら解き直せます</strong>' +
            '    <p>1セット' + quizOnly + '問を、答えと解説を隠して出し直します'
            + '（チップは出ません）。終わったら続けられます。</p></div>' +
            '    <button class="primary-btn" id="reviewQuizOnlyBtn">▶ クイズを復習する</button>' +
            '  </section>'
          : '') +
             quizBookmarkSectionHtml() +
        '</div>';
      document.getElementById('backToLearningBtn').addEventListener('click', goHome);
      document.getElementById('reviewEmptyBtn').addEventListener('click', goHome);
      var quizOnlyBtn = document.getElementById('reviewQuizOnlyBtn');
      if (quizOnlyBtn) {
        quizOnlyBtn.addEventListener('click', function () { startReviewQuizSession(false); });
      }
      bindReviewSummary();
      bindReviewResume();
      bindReviewRows(main);
      // 知らせは1回だけ（開き直すたびに前回の成績が出ると、いまの状態が読みにくい）
      reviewSummary = null;
      main.scrollTop = 0;
      return;
    }

    // 一覧は全部見せる（1問だけ選ぶ道はいつでも通す）。数えるのは次のセットで出るぶんだけ。
    var pool = filteredReviewCandidates(filter).slice().sort(compareReviewEntries);
    var pending = pendingReviewCandidates(filter);
    var next = nextReviewEntries(filter);
    var sessionSize = next.length;
    var sessionOverdue = next.filter(function (entry) { return entry.overdue; }).length;
    var hidden = Math.max(0, pool.length - REVIEW_LIST_LIMIT);
    var shown = pool.slice(0, REVIEW_LIST_LIMIT);

    main.innerHTML =
      '<div class="menu review-page">' + head +
      reviewSummaryHtml() +
      reviewResumeHtml(resume) +
      '  <section class="menu-hero learning-hero review-hero">' +
      '    <div class="hero-milestone" aria-label="期限が来た問題は' + overdue + '問">' +
      '      <span>⏰</span><b>' + overdue + '</b><small>問 期限が来た</small>' +
      '    </div>' +
      '    <div class="hero-body">' +
      '      <span class="screen-eyebrow">SPACED REVIEW</span>' +
      '      <h1 class="hero-title">解き直して定着させる</h1>' +
      '      <p class="hero-sub">忘却曲線で「そろそろ確認したい問題」から出ます · '
        + '正解すると間隔が伸び、⚡ 一発正解が2回続いた問題は2段飛ばして当面出ません</p>' +
             reviewSetNoteHtml(sessionSize, pending.length, sessionOverdue) +
             reviewQuizNoteHtml() +
             reviewCafeNoteHtml() +
             reviewFilterTabsHtml(counts, filter) +
      '      <div class="hero-action">' +
      '        <div class="hero-next"><div class="cta-lead">今回のセット</div>' +
      '        <div class="cta-target">' + reviewQueueBreakdown(sessionSize, sessionOverdue)
                 + '</div></div>' +
               reviewStartButtonHtml(sessionSize, sessionOverdue, !!resume) +
      '      </div>' +
      '    </div>' +
      '  </section>' +
      '  <section class="menu-section review-list-section">' +
      '    <header class="section-heading">' +
      '      <div><span class="screen-eyebrow">PICK ONE</span>' +
      '      <h2 class="menu-h2">1問だけ選んで復習する</h2></div>' +
      '      <p class="menu-note">期限が近い順に並んでいます。'
        + 'しおりのボタンで印を付けると、上で絞り込めます。</p>' +
      '    </header>' +
      '    <ul class="review-list">' + shown.map(reviewRowHtml).join('') + '</ul>' +
      (hidden
        ? '    <p class="menu-note review-list-more">ほか ' + hidden
          + '問。「1セット復習する」なら一覧に出ていない問題からも出題します。</p>'
        : '') +
      '  </section>' +
         quizBookmarkSectionHtml() +
      '</div>';

    document.getElementById('backToLearningBtn').addEventListener('click', goHome);
    var startBtn = document.getElementById('reviewStartBtn');
    // 続けている途中（結果を出したところ）なら積み上げを引き継ぐ。引き継がないと
    // ここを押しただけで、たったいま解いた問題がもう一度出てくる。
    // 「はじめから」だけは、その積み上げを捨てるために引き継がない。
    var restart = startBtn.dataset.mode === 'restart';
    startBtn.addEventListener('click', function () {
      startReviewSession(filter, !restart);
    });
    bindReviewSummary();
    bindReviewResume();
    bindReviewFilters(main);
    bindReviewRows(main);
    // 結果の知らせは1回だけ。開き直すたびに前回の成績が出ると、今の状態が読みにくい
    reviewSummary = null;
    main.scrollTop = 0;
  }

  /**
   * ヒーローの開始ボタン。
   *
   * 続けているあいだに出し切ったら、押せないままにするのではなく<b>積み上げを捨てて
   * 出し直す</b>ボタンにする。クリア済みが2〜3問しか無い人は1セットで出し切ってしまい、
   * 押せないボタンだけが残ると、その日はもう復習できないように見えるため。
   *
   * <b>途中のセットが残っているあいだは「はじめから」の顔にする</b>（@param resuming）。
   * 上の続きのカードに「▶ 続きから」があるので、同じ見た目の太いボタンが2つ並ぶと
   * どちらが続きなのか読めなくなる。押せば控えは差し替わる（→ startReviewRun）。
   */
  function reviewStartButtonHtml(sessionSize, sessionOverdue, resuming) {
    if (!sessionSize) {
      return '      <button class="primary-btn big stacked-cta" id="reviewStartBtn"'
        + ' data-mode="restart">▶ はじめから復習する'
        + '<small>出した問題も含める</small></button>';
    }
    if (resuming) {
      return '      <button class="ghost-btn big stacked-cta" id="reviewStartBtn"'
        + ' data-mode="restart">▶ はじめから1セット復習する'
        + '<small>途中のセットは終わりにする</small></button>';
    }
    // 期限が来ていない日は、押さないことも選べると分かる見た目にする（薄いボタン）。
    // 太いボタンに「やるべきこと」の顔をさせると、期限前の問題まで宿題に見えてしまう
    if (!sessionOverdue) {
      return '      <button class="ghost-btn big stacked-cta" id="reviewStartBtn">'
        + '▶ 早めに1セット復習する<small>期限前・やらなくても大丈夫</small></button>';
    }
    return '      <button class="primary-btn big" id="reviewStartBtn">▶ 1セット復習する</button>';
  }

  /**
   * 「1セットで終わりではない」ことを、始める前に1行で出す。
   *
   * 短いセットにした狙いは「区切りを選べること」なので、続けられると分かっていないと
   * ただ出題が減ったように読まれてしまう。残りの数もここで見せる。
   */
  function reviewSetNoteHtml(sessionSize, poolSize, sessionOverdue) {
    if (sessionSize && !sessionOverdue) {
      // 「今日ぶんは終わっている」を言葉で出す。⏰0 の数字だけでは、
      // 下に4問並んでいるほうが目に入って、まだ宿題が残っているように読める
      return '      <p class="hero-sub review-set-note">✅ 期限が来た問題はありません。'
        + 'ここで止めて大丈夫です（続けたいときは下のボタンで期限前の問題を出せます）</p>';
    }
    if (!sessionSize) {
      return '      <p class="hero-sub review-set-note">🧩 続けて出せるぶんは出し切りました。'
        + 'もう一度回すなら「はじめから」、1問だけなら下の一覧から選べます'
        + '（正解した問題は期限が伸びたので、日をあけると戻ってきます）</p>';
    }
    var rest = Math.max(0, poolSize - sessionSize);
    return '      <p class="hero-sub review-set-note">🧩 1セットは' + sessionSize
      + '問。終わると結果が出て、そこから「もう1セット」で続けられます'
      + (rest ? '（続けて出せるぶんが、ほか ' + rest + '問）' : '') + '</p>';
  }

  /**
   * 復習がカフェへ何を渡すのかを、復習ホームに1行で出す。
   *
   * コインは2段になっている（期限が来た問題は満額・期限前の「早めの復習」は小額で
   * 1日の本数に上限）。**始める前に両方の額と残りの本数を出す** ―― 通してから
   * 「これは0コインだったのか」と気づく形にしない。倍率と自動売上の枠は目に見えないので、
   * それも1行添える（無いと「復習はカフェに無関係」と読まれてしまう）。
   */
  function reviewCafeNoteHtml() {
    var cafe = cafeState();
    var reviewed = Number(cafe.reviewedTasks || 0);
    var brand = Number(cafe.reviewBrandBasisPoints || 0);
    var detail = reviewed
      ? '復習で仕上げた ' + numberText(reviewed) + '問がブランド倍率を +'
        + multiplierText(brand) + ' 押し上げています'
      : '復習で正解した問題はブランド倍率を育てます（1問につき1回）';
    // 復習手当系統は両方の額へ掛かるので、掛けたあとの割合で出す（設備を買った人の画面が
    // 動かないと、買った意味が読めない）
    var withBonus = function (base) {
      return Number(base || 0) * (100 + Number(cafe.reviewBonusPercent || 0)) / 100;
    };
    var percent = withBonus(cafe.reviewRewardPercent);
    var earlyPercent = withBonus(cafe.reviewEarlyRewardPercent);
    var left = Number(cafe.reviewEarlyRewardLeft || 0);
    // 早めのぶんは1日の本数で止まるので、残りを添える。0の日に額だけ出すと約束が食い違う
    var early = earlyPercent
      ? ' · 早めの復習は ' + numberText(Math.round(earlyPercent)) + '%'
        + (left ? '（今日あと' + left + '問ぶん）' : '（今日のぶんは受け取り済み）')
      : '';
    var coin = percent
      ? '🪙 期限が来た問題は初クリアの ' + numberText(Math.round(percent))
        + '%（期限が来るたび1回）' + early
      : '';
    return (coin ? '      <p class="hero-sub review-cafe-note">' + esc(coin) + '</p>' : '')
      + '      <p class="hero-sub review-cafe-note">☕ ' + esc(detail)
      + '・自動売上の枠も戻ります</p>';
  }

  /**
   * 「今回の出題」の内訳。期限切れと、それを埋める早めの復習を分けて出す。
   *
   * 期限が来ていないものまで同じ顔で出すと「なぜこれが出たのか」が分からなくなるので、
   * 補充ぶんはそう見えるようにしておく。
   */
  function reviewQueueBreakdown(sessionSize, sessionOverdue) {
    // クリア済みの問題が無い日はこの画面へ来ないので、0なら「この回で出し切った」ほう
    if (!sessionSize) { return '続けて出せる問題は出し切りました'; }
    var early = sessionSize - sessionOverdue;
    if (!early) { return '⏰ 期限切れ ' + sessionOverdue + '問'; }
    if (!sessionOverdue) { return '早めの復習 ' + early + '問（期限前）'; }
    return '⏰ 期限切れ ' + sessionOverdue + '問 ＋ 早めの復習 ' + early + '問';
  }

  /**
   * 「問題のあとにクイズが続く」ことを、始める前に見せる。
   *
   * 出題の一覧には混ぜない（押した先が出題なのか移動なのか読めなくなるため）。
   * 代わりにここへ書いて、セッションの最後に続けて出す。
   */
  function reviewQuizNoteHtml() {
    var quizzes = buildReviewQuizQueue().length;
    if (!quizzes) { return ''; }
    var run = Number(cafeState().quizReviewRun || 0);
    var goal = Number(cafeState().quizStreakGoal || 20);
    return '      <p class="hero-sub review-quiz-note">🧠 セットの問題のあとに、答えた確認クイズを'
      + quizzes + '問続けて出します（答えと解説は隠して出し直します）· '
      + '異なる' + goal + '問に連続正解すると 📣 が解放 · いまの連続 ' + run + '問</p>';
  }

  /**
   * 直前のセットの結果。ここに「もう1セット」を置く。
   *
   * 1セットを短くしたぶん、続ける操作が結果から離れていると、続けたい人が毎回
   * ヒーローまで目を戻すことになる。結果のすぐ隣に置いて、押すだけで次のセットへ行く。
   *
   * 残りが無いときはボタンを出さない（押しても何も起きない操作を置かない）。
   */
  function reviewSummaryHtml() {
    if (!reviewSummary) { return ''; }
    var perfect = reviewSummary.total > 0 && reviewSummary.cleared === reviewSummary.total;
    var quiz = '';
    if (reviewSummary.quizTotal) {
      var run = Number(cafeState().quizReviewRun || 0);
      var goal = Number(cafeState().quizStreakGoal || 20);
      quiz = 'クイズは' + reviewSummary.quizTotal + '問のうち '
        + reviewSummary.quizCorrect + '問に正解（連続 ' + run + ' / ' + goal + '問）。';
    }
    var cash = Number(reviewSummary.cash || 0);
    var runCash = Number(reviewSummary.runCash || 0);
    // 期限ぶん＋早めのぶんの合計。1日の本数を使い切ったあとのセットは0になるので、
    // そのときは何も出さない（0コインを「獲得しました」と書かない）
    var earned = cash
      ? '🪙 ' + cafeNumberText(cash) + ' コインを獲得しました。'
      : '';
    var sets = Number(reviewSummary.sets || 1);
    var stacked = sets > 1
      ? 'ここまで' + sets + 'セット'
        + (reviewSummary.runTotal
          ? '・問題は' + reviewSummary.runTotal + '問のうち ' + reviewSummary.runCleared + '問'
          : '')
        + (reviewSummary.runQuizTotal
          ? '・クイズは' + reviewSummary.runQuizTotal + '問のうち '
            + reviewSummary.runQuizCorrect + '問'
          : '')
        + 'に正解しています。'
        + (runCash > cash ? '獲得コインは合計 ' + cafeNumberText(runCash) + ' です。' : '')
      : '';
    return '<section class="menu-section review-summary">' +
      '<span class="review-summary-icon">' + (perfect ? '🎉' : '📝') + '</span>' +
      '<div><strong>' + sets + 'セット目が終わりました</strong>' +
      '<p>' + (reviewSummary.total
        ? reviewSummary.total + '問のうち ' + reviewSummary.cleared + '問に正解しました。'
          + (perfect ? '全問正解です！' : '通らなかった問題は、次の復習で出やすくなります。')
        : '') +
      (quiz ? (reviewSummary.total ? '<br>' : '') + quiz : '') +
      (earned ? '<br>' + earned : '') +
      (stacked ? '<br>' + stacked : '') +
      '</p></div>' +
      reviewMoreButtonHtml() +
      '</section>';
  }

  /**
   * 「もう1セット」のボタン。問題が残っていれば問題から、無ければクイズだけで続ける。
   *
   * 出せるものが何も無いときは何も置かない。理由はこのすぐ下（ヒーローの🧩の行と
   * 「はじめから」のボタン）に出るので、結果の中で言い直すと同じ話が2つ並ぶ。
   */
  function reviewMoreButtonHtml() {
    var tasks = buildReviewQueue(reviewFilter).length;
    if (tasks) {
      return '<button class="primary-btn" id="reviewMoreBtn">▶ もう1セット'
        + '<small>問題' + tasks + '問</small></button>';
    }
    var quizzes = buildReviewQuizQueue().length;
    if (quizzes) {
      return '<button class="primary-btn" id="reviewMoreBtn">▶ クイズをもう1セット'
        + '<small>' + quizzes + '問</small></button>';
    }
    return '';
  }

  function bindReviewSummary() {
    var more = document.getElementById('reviewMoreBtn');
    if (more) { more.addEventListener('click', continueReviewRun); }
  }

  /**
   * 解いている途中で抜けたセットへ戻る道（→ saveReviewRun のまえがき）。
   *
   * <b>何問目まで進んでいたかを先に出す。</b>「続きから」だけでは、押した先が
   * 前のセットの続きなのか新しいセットなのかが読めない。
   *
   * 隣に「はじめから」も置く ―― 続きを見て「これは今やらない」と決めた人が、
   * 続きを終わらせるためにわざわざ1問開く必要がないように。
   */
  function reviewResumeHtml(resume) {
    if (!resume) { return ''; }
    var set = resume.set;
    var quizPhase = Number(set.quizIndex) >= 0;
    var where = quizPhase
      ? 'クイズ ' + (Number(set.quizIndex) + 1) + ' / ' + set.quizQueue.length + '問目'
      : '問題 ' + (Number(set.index) + 1) + ' / ' + set.queue.length + '問目';
    var detail = [];
    if (Number(set.cleared || 0)) { detail.push('ここまで' + set.cleared + '問に正解'); }
    if (!quizPhase && set.quizQueue.length) {
      detail.push('このあとクイズが' + set.quizQueue.length + '問続きます');
    }
    if (resume.filter !== 'all') {
      detail.push('「' + reviewFilterLabel(resume.filter) + '」で始めたセットです');
    }
    return '<section class="menu-section review-resume">' +
      '<span class="review-summary-icon">🔁</span>' +
      '<div><strong>' + (Number(resume.run.sets || 0) + 1) + 'セット目の途中です（'
        + where + '）</strong>' +
      '<p>' + (detail.length ? detail.join(' · ') + '。' : '') +
      // 「解いたぶんは消えていない」を先に言う（通した問題があるときだけ ―― 1問も
      // 通していないセットで言うと、起きていないことを言うことになる）。
      // そのあとに、この控えが消える条件（日付）を断る
      (Number(set.cleared || 0) ? '通した問題の期限と苦手度はもう動いています。' : '')
      + '日付が変わると期限を引き直すので、そのときは新しい1セット目から始まります。</p></div>' +
      '<div class="review-resume-actions">' +
      '<button class="primary-btn" id="reviewResumeBtn">▶ 続きから</button>' +
      '<button class="ghost-btn" id="reviewResumeRestartBtn">はじめから</button>' +
      '</div>' +
      '</section>';
  }

  function bindReviewResume() {
    var go = document.getElementById('reviewResumeBtn');
    if (go) { go.addEventListener('click', resumeReviewRun); }
    var restart = document.getElementById('reviewResumeRestartBtn');
    // 出せる問題が無ければクイズだけで始める（「もう1セット」と同じ出し分け）。
    // 控えを捨てるのは始められたときだけ ―― 先に捨てると、何も始まらなかったときに
    // 続きまで失うことになる（捨てるのは startReviewRun）
    if (restart) {
      restart.addEventListener('click', function () {
        if (buildReviewQueue(reviewFilter).length) {
          startReviewSession(reviewFilter, false);
          return;
        }
        startReviewQuizSession(false);
      });
    }
  }

  function reviewFilterTabsHtml(counts, filter) {
    var tabs = [
      { id: 'all', icon: '🔁', label: 'すべて', count: counts.all },
      { id: 'weak', icon: '🔥', label: '苦手', count: counts.weak },
      { id: 'bookmark', icon: bookmarkIconSvg(true), label: 'ブックマーク', count: counts.bookmark }
    ];
    return '<div class="review-filters" role="tablist" aria-label="復習の絞り込み">' +
      tabs.map(function (tab) {
        var active = tab.id === filter;
        return '<button type="button" class="review-filter' + (active ? ' active' : '') + '"' +
          ' role="tab" aria-selected="' + active + '" tabindex="' + (active ? '0' : '-1') + '"' +
          ' data-filter="' + tab.id + '"' + (tab.count ? '' : ' disabled') + '>' +
          '<span class="review-filter-icon">' + tab.icon + '</span>' +
          '<span class="review-filter-copy"><strong>' + esc(tab.label) + '</strong>' +
          '<small>' + tab.count + '問</small></span></button>';
      }).join('') + '</div>';
  }

  /** 絞り込みタブ。カフェのタブと同じく ←→ でも移動できるようにする。 */
  function bindReviewFilters(host) {
    var tabs = Array.prototype.slice.call(host.getElementsByClassName('review-filter'));
    tabs.forEach(function (button, index) {
      button.addEventListener('click', function () { setReviewFilter(button.dataset.filter); });
      button.addEventListener('keydown', function (event) {
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') { return; }
        event.preventDefault();
        var direction = event.key === 'ArrowRight' ? 1 : -1;
        for (var step = 1; step <= tabs.length; step++) {
          var next = tabs[(index + direction * step + tabs.length * step) % tabs.length];
          if (next && !next.disabled) {
            setReviewFilter(next.dataset.filter);
            var moved = document.querySelector('.review-filter[data-filter="' + next.dataset.filter + '"]');
            if (moved) { moved.focus(); }
            return;
          }
        }
      });
    });
  }

  function reviewRowHtml(entry) {
    var chapter = chapterOf(entry.lesson.id);
    var lesson = entry.lesson;
    return '<li class="review-row">' +
      '<button type="button" class="review-row-main" data-lesson="' + esc(lesson.id) + '"' +
      ' data-task="' + esc(entry.task.id) + '">' +
      '<span class="review-row-id">' + esc(displayLessonId(lesson)) + '</span>' +
      '<span class="review-row-copy"><strong>' + esc(lesson.title) + '</strong>' +
      '<small>' + (chapter ? '第' + displayChapterNumber(chapter) + '章 · ' : '') +
      esc(entry.task.label) + (lesson.taskCount > 1 ? ' · 問題' + esc(entry.task.id) : '') +
      '</small></span>' +
      '<span class="review-due' + (entry.overdue ? ' overdue' : '') + '">' +
      esc(reviewDueText(entry)) + '</span>' +
      reviewStandingHtml(entry) +
      '</button>' +
      bookmarkButtonHtml(lesson.id, entry.task) +
      '</li>';
  }

  // ----------------------------------------- ブックマークしたクイズの一覧

  /*
   * クイズは復習で出題しない（解き直す提出物が無く、期限も苦手度も持たない）。
   * ここは「あとで見に戻る」ためのしおりの置き場所である。
   *
   * 解き直す一覧（1問だけ選んで復習する）と同じ節に混ぜると、押した先が出題なのか
   * 移動なのか区別が付かなくなるので、節を分けて見出しにもそう書く。
   */

  /** しおりを付けたクイズを、章の順に並べて返す。 */
  function quizBookmarkEntries() {
    var list = [];
    allLessons().forEach(function (lesson) {
      (lesson.quizBookmarks || []).forEach(function (on, index) {
        var quiz = (lesson.quizzes || [])[index];
        // 教材からクイズが減ったときは、印だけ残っていても出さない
        if (!on || !quiz) { return; }
        list.push({
          lesson: lesson,
          index: index,
          question: questionHeadline(quiz.question),
          result: (lesson.quizResults || [])[index] || null
        });
      });
    });
    return list;
  }

  /** 答え合わせの状態。色だけに頼らないよう、言葉も一緒に返す。 */
  function quizBookmarkState(result) {
    if (!result) { return { text: '未回答', cls: '' }; }
    return result.correct
      ? { text: '✅ 正解', cls: ' is-ok' }
      : { text: '❌ 不正解', cls: ' is-ng' };
  }

  function quizBookmarkSectionHtml() {
    var entries = quizBookmarkEntries();
    if (!entries.length) { return ''; }
    var shown = entries.slice(0, REVIEW_LIST_LIMIT);
    var hidden = entries.length - shown.length;
    return '  <section class="menu-section review-list-section">' +
      '    <header class="section-heading">' +
      '      <div><span class="screen-eyebrow">BOOKMARKED QUIZ</span>' +
      '      <h2 class="menu-h2">🔖 ブックマークしたクイズ</h2></div>' +
      '      <p class="menu-note">押すとそのクイズへ移動します（出題はされません）。</p>' +
      '    </header>' +
      '    <ul class="review-list">' + shown.map(quizBookmarkRowHtml).join('') + '</ul>' +
      (hidden ? '    <p class="menu-note review-list-more">ほか ' + hidden + '件。</p>' : '') +
      '  </section>';
  }

  function quizBookmarkRowHtml(entry) {
    var chapter = chapterOf(entry.lesson.id);
    var state = quizBookmarkState(entry.result);
    return '<li class="review-row">' +
      '<button type="button" class="review-row-main" data-lesson="' + esc(entry.lesson.id) + '"' +
      ' data-quiz="' + entry.index + '">' +
      '<span class="review-row-id">' + esc(displayLessonId(entry.lesson)) + '</span>' +
      '<span class="review-row-copy"><strong>' + esc(entry.question) + '</strong>' +
      '<small>' + (chapter ? '第' + displayChapterNumber(chapter) + '章 · ' : '') +
      esc(entry.lesson.title) + ' · Q' + (entry.index + 1) + '</small></span>' +
      '<span class="quiz-bookmark-state' + state.cls + '">' + esc(state.text) + '</span>' +
      '</button>' +
      quizBookmarkButtonHtml(entry.lesson, entry.index) +
      '</li>';
  }

  /** 一覧の行（問題は解き直す、クイズは見に行く）と、しおりのボタンを繋ぐ。 */
  function bindReviewRows(host) {
    Array.prototype.forEach.call(host.getElementsByClassName('review-row-main'), function (button) {
      button.addEventListener('click', function () {
        if (button.dataset.quiz != null) {
          openBookmarkedQuiz(button.dataset.lesson, Number(button.dataset.quiz));
          return;
        }
        selectReviewTask(button.dataset.lesson, button.dataset.task);
      });
    });
    bindBookmarkButtons(host);
  }

  /**
   * しおりを付けたクイズを開く。
   *
   * スクロールは描き終わったあとに行う（{@link renderLesson} は最後に「読んでいた位置」を
   * 入れるので、描く前に動かしても上書きされる）。同じレッスン内の移動は
   * {@link goToTask} と同じ考え方で、控えを1つ置いて描画側で消費する。
   */
  function openBookmarkedQuiz(lessonId, index) {
    if (!findLesson(lessonId)) { return; }
    quizFocus = { lessonId: lessonId, index: index };
    selectLesson(lessonId);
  }

  /** 控えがこのレッスンのものなら、そのクイズまでスクロールして少しの間だけ光らせる。 */
  function focusBookmarkedQuiz(lesson) {
    if (!quizFocus || quizFocus.lessonId !== lesson.id) { return; }
    var item = document.getElementById(quizItemId(quizFocus.index));
    quizFocus = null;
    if (!item) { return; }
    item.scrollIntoView({ block: 'center' });
    item.classList.add('is-target');
    setTimeout(function () { item.classList.remove('is-target'); }, 2000);
  }

  /**
   * 一覧の1行に出す問い文。
   *
   * 問いに ``` のコード欄が付いていることがあるので、最初のコード欄より前だけを使う。
   * 全体を詰めると「次のコードの出力はどれでしょう。javaSystem.out.print("A"); …」のように
   * コードが混ざって、どの問いなのか読めなくなる。
   */
  function questionHeadline(question) {
    var text = String(question || '');
    var head = text.split('```')[0];
    return plainText(head.trim() ? head : text);
  }

  /** Markdownの記法を落とした素の文。一覧の1行に詰めるときに使う。 */
  function plainText(markdown) {
    var box = document.createElement('div');
    box.innerHTML = renderMarkdown(markdown || '');
    return (box.textContent || '').replace(/\s+/g, ' ').trim();
  }

  // --------------------------------------------------- 復習で1問を解き直す

  /**
   * 復習で開く1問。
   *
   * 通常のレッスン画面と違い、その問題だけを出す（解説は畳んでおく）。
   * 復習で見たいのは「いま解けるか」なので、解説とサンプルを毎回抜けてくるより
   * 1問に絞ったほうが短く回せる。問題のかたまり自体は
   * {@link buildTaskBlock} をそのまま使う（エディタ・ヒント・採点の作りを分けないため）。
   */
  function renderReviewTask() {
    var lesson = findLesson(currentId);
    var task = lesson && findTask(lesson, reviewTaskId);
    var chapter = chapterOf(currentId);
    var main = document.getElementById('content');
    if (!task) {
      main.innerHTML = '<div class="loading">問題が見つかりません</div>';
      return;
    }

    editors = {};
    main.innerHTML =
      '<article class="lesson-view review-view">' +
      reviewBarHtml() +
      '  <div class="lesson-head">' +
      '    <div class="crumb">' +
      '      <button class="crumb-home" id="crumbHome">メニュー</button>' +
      '      <span class="crumb-sep">›</span>' +
      '      <button class="crumb-home" id="crumbReview">🔁 復習</button>' +
      '      <span class="crumb-sep">›</span>' +
             esc(chapter.emoji) + ' 第' + displayChapterNumber(chapter) + '章 ' + esc(chapter.title) +
      '    </div>' +
      '    <h1 class="lesson-h1">' +
      '      <span class="lesson-h1-id">' + esc(displayLessonId(lesson)) + '</span>' + esc(lesson.title) +
      '    </h1>' +
      '  </div>' +
      '  <details class="card review-explain">' +
      '    <summary>📖 このレッスンの解説をもう一度読む</summary>' +
      '    <div class="review-explain-body">' + renderMarkdown(lesson.explanation) + '</div>' +
      '  </details>' +
      '  <section class="tasks" id="tasks"></section>' +
      '  <nav class="lesson-next" id="reviewFooter" aria-label="復習の進み方"></nav>' +
      '</article>';

    var tasksHost = document.getElementById('tasks');
    tasksHost.appendChild(buildTaskBlock(lesson, task, taskIndexOf(lesson, task), { review: true }));
    // 挿してから呼ぶ（開示済みヒントと模範解答ボタンは id で要素を引く）
    renderRevealedHints(lesson, task);
    // カフェへ寄り道しても「📚 学習」でここへ帰れるようにする（→ rememberLessonScroll）
    paintedReview = { lessonId: lesson.id, taskId: task.id, quiz: false };

    document.getElementById('crumbHome').addEventListener('click', goHome);
    document.getElementById('crumbReview').addEventListener('click', endReviewSession);
    bindReviewBar();
    renderReviewFooter(false);
    main.scrollTop = 0;
  }

  // ------------------------------------------- 復習でクイズを1問ずつ解き直す

  /**
   * 復習の段で出すクイズ1問。
   *
   * <b>前の答えと解説は出さない。</b>答えが見えている状態で数えると、押すだけで
   * 連続が並んでしまう（レッスン画面の答え直しを数えないのと同じ理由）。
   * 答えたあとだけ、その回の結果と解説を見せる。
   */
  function renderReviewQuiz(answered) {
    var entry = reviewSession && reviewSession.quizQueue[reviewSession.quizIndex];
    var lesson = entry && findLesson(entry.lessonId);
    var quiz = lesson && (lesson.quizzes || [])[entry.index];
    if (!quiz) {
      finishReviewSession();
      return;
    }
    var chapter = chapterOf(entry.lessonId);
    var main = document.getElementById('content');
    var run = Number(cafeState().quizReviewRun || 0);
    var goal = Number(cafeState().quizStreakGoal || 20);

    main.innerHTML =
      '<article class="lesson-view review-view review-quiz-view">' +
      reviewBarHtml() +
      '  <div class="lesson-head">' +
      '    <div class="crumb">' +
      '      <button class="crumb-home" id="crumbHome">メニュー</button>' +
      '      <span class="crumb-sep">›</span>' +
      '      <button class="crumb-home" id="crumbReview">🔁 復習</button>' +
      '      <span class="crumb-sep">›</span>' +
             (chapter ? esc(chapter.emoji) + ' 第' + displayChapterNumber(chapter) + '章 '
               + esc(chapter.title) : '') +
      '    </div>' +
      '    <h1 class="lesson-h1">' +
      '      <span class="lesson-h1-id">' + esc(displayLessonId(lesson)) + '</span>' +
             esc(lesson.title) +
      '    </h1>' +
      '  </div>' +
      '  <section class="tasks">' +
      '    <div class="card card-quiz">' +
      '      <div class="quiz-head">' +
      '        <h2 class="card-h"><span class="card-h-icon">🧠</span>確認クイズの復習</h2>' +
      '        <span class="quiz-score">連続 ' + run + ' / ' + goal + '問</span>' +
      '      </div>' +
      '      <p class="quiz-note">チップは出ません。★と正解数も動きません。'
        + '異なる' + goal + '問へ連続で正解すると 📣 ひらめきメガホン が解放されます'
        + '（間違えると連続は0に戻ります）。</p>' +
             reviewQuizItemHtml(lesson, quiz, entry.index, answered) +
      '    </div>' +
      '  </section>' +
      '  <nav class="lesson-next" id="reviewFooter" aria-label="復習の進み方"></nav>' +
      '</article>';

    // カフェへ寄り道しても「📚 学習」でここへ帰れるようにする（→ rememberLessonScroll）。
    // クイズの段は問題を開いていないので、戻すのは控え（jq-review-run）ごとになる
    paintedReview = { lessonId: entry.lessonId, taskId: null, quiz: true };
    document.getElementById('crumbHome').addEventListener('click', goHome);
    document.getElementById('crumbReview').addEventListener('click', endReviewSession);
    bindReviewBar();
    if (!answered) {
      Array.prototype.forEach.call(main.getElementsByClassName('quiz-choice'), function (btn) {
        btn.addEventListener('click', function () {
          answerReviewQuiz(Number(btn.dataset.choice));
        });
      });
    }
    renderReviewQuizFooter(answered);
    main.scrollTop = 0;
  }

  /** 復習で出すクイズの本体。{@code answered} が来た回だけ結果と解説を足す。 */
  function reviewQuizItemHtml(lesson, quiz, index, answered) {
    var choices = quiz.choices.map(function (text, i) {
      var cls = 'quiz-choice';
      if (answered) {
        if (i === answered.choice) { cls += answered.correct ? ' is-picked-ok' : ' is-picked-ng'; }
        if (!answered.correct && i === answered.answer) { cls += ' is-answer'; }
      }
      return '<button class="' + cls + '" type="button" data-choice="' + i + '"'
        + (answered ? ' disabled' : '') + '>' +
        '<span class="quiz-mark">' + CHOICE_LABELS[i] + '</span>' +
        '<span class="quiz-choice-text">' + renderMarkdown(text) + '</span>' +
        '</button>';
    }).join('');
    return '<div class="quiz-item">' +
      '  <div class="quiz-item-head">' +
      '    <div class="quiz-q"><span class="quiz-no">Q' + (index + 1) + '</span>' +
           renderMarkdown(quiz.question) + '</div>' +
           quizBookmarkButtonHtml(lesson, index) +
      '  </div>' +
      '  <div class="quiz-choices">' + choices + '</div>' +
         quizFeedbackHtml(answered) +
      '</div>';
  }

  /**
   * 復習のクイズへ答える。
   *
   * {@code review} を付けて投げるので、サーバはチップを払わず、選んだ答えも残さない
   * （★と正解数は動かない ― 復習で間違えて★を失わないため）。
   */
  function answerReviewQuiz(choice) {
    var entry = reviewSession && reviewSession.quizQueue[reviewSession.quizIndex];
    if (!entry) { return; }
    api('quiz', {
      lessonId: entry.lessonId, index: entry.index, choice: choice, review: true
    })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        if (res.correct) { reviewSession.quizCorrect++; }
        saveReviewRun();
        renderReviewQuiz({
          choice: choice, correct: res.correct,
          answer: res.answer, explanation: res.explanation
        });
        toast(res.correct
          ? '🧠 正解！　連続 ' + Number(cafeState().quizReviewRun || 0) + '問'
          : '🧠 不正解　連続は0に戻りました');
      })
      .catch(toastError);
  }

  /** 次のクイズへ。出し切ったらセッションを閉じる。 */
  function advanceReviewQuiz() {
    if (!reviewSession) {
      goReview();
      return;
    }
    reviewSession.quizIndex++;
    if (reviewSession.quizIndex >= reviewSession.quizQueue.length) {
      finishReviewSession();
      return;
    }
    saveReviewRun();
    renderReviewQuiz();
  }

  /** クイズの段の「次へ」。答える前は先に進ませない（飛ばすのは帯のボタン）。 */
  function renderReviewQuizFooter(answered) {
    var host = document.getElementById('reviewFooter');
    if (!host) { return; }
    var remaining = reviewSession.quizQueue.length - reviewSession.quizIndex - 1;
    if (!answered) {
      host.innerHTML =
        '<div class="lesson-next-copy"><small>クイズの復習</small>' +
        '<b>' + (remaining > 0 ? 'あと' + (remaining + 1) + '問' : 'これが最後の1問') + '</b></div>';
      return;
    }
    host.innerHTML =
      '<div class="lesson-next-copy"><small>' + (answered.correct ? '正解' : '不正解') + '</small>' +
      '<b>' + (remaining > 0 ? 'あと' + remaining + '問' : 'これが最後の1問') + '</b></div>' +
      '<button class="primary-btn lesson-next-btn" id="reviewFooterBtn">' +
      (remaining > 0 ? '次のクイズへ →' : 'セットの結果へ →') + '</button>';
    document.getElementById('reviewFooterBtn').addEventListener('click', advanceReviewQuiz);
  }

  function taskIndexOf(lesson, task) {
    var index = 0;
    lesson.tasks.forEach(function (candidate, i) {
      if (candidate.id === task.id) { index = i; }
    });
    return index;
  }

  /** 復習中だと分かる帯。何問目か・どこで抜けられるかを常に見せる。 */
  function reviewBarHtml() {
    var quizPhase = inReviewQuizPhase();
    var progress = '1問だけ復習中';
    if (quizPhase) {
      progress = 'クイズ ' + (reviewSession.quizIndex + 1) + ' / '
        + reviewSession.quizQueue.length + '問';
    } else if (reviewSession) {
      progress = (reviewSession.index + 1) + ' / ' + reviewSession.queue.length + '問';
    }
    return '<div class="review-bar">' +
      '<span class="review-bar-title">🔁 復習モード</span>' +
      '<span class="review-bar-progress">' + progress + '</span>' +
      // 問題の段の一言は短く保つ。この帯の幅は本文と同じ830pxで、ボタンが3つ並ぶと
      // 「復習を終える」が2段目へ落ちる（余りは実測36px。長い一言にすると必ず折り返す）
      '<span class="review-bar-note">' + (quizPhase
        ? '答えと解説は答えたあとに出ます。チップは出ません'
        : 'ひな形から解き直します（前の解答は残ります）') + '</span>' +
      '<span class="spacer"></span>' +
      // ボタンはひとまとめにする。狭い画面では3つそろって2段目へ落ちる
      // （ばらばらに折り返すと「復習を終える」だけが下に取り残される）
      '<span class="review-bar-actions">' +
      // 1つ前の問題へ戻す（→ backReviewSession）。押せないボタンは置かないので、
      // 1問目とクイズの段では出さない
      (reviewSession && !quizPhase && reviewSession.index > 0
        ? '<button class="ghost-btn small" id="reviewBackBtn">← 前の問題へ</button>'
        : '') +
      (reviewSession
        ? '<button class="ghost-btn small" id="reviewSkipBtn">'
          + (quizPhase ? 'このクイズを飛ばす' : 'この問題を飛ばす') + '</button>'
        : '') +
      '<button class="ghost-btn small" id="reviewExitBtn">' +
      (reviewSession ? '復習を終える' : '復習メニューへ') + '</button>' +
      '</span>' +
      '</div>';
  }

  /** いまクイズの段にいるか（問題を出し切ったあと）。 */
  function inReviewQuizPhase() {
    return !!(reviewSession && reviewSession.quizIndex >= 0);
  }

  function bindReviewBar() {
    var back = document.getElementById('reviewBackBtn');
    if (back) { back.addEventListener('click', backReviewSession); }
    var skip = document.getElementById('reviewSkipBtn');
    if (skip) {
      skip.addEventListener('click',
        inReviewQuizPhase() ? advanceReviewQuiz : advanceReviewSession);
    }
    document.getElementById('reviewExitBtn').addEventListener('click', endReviewSession);
  }

  /**
   * 通ったときのひとこと。<b>苦手度が付いていた問題にだけ「下がった」と言う。</b>
   *
   * 苦手度0の問題は通しても0のまま（{@code addReviewWeight} が0で頭打ち）なので、
   * 「下がりました」は起きていないことになる。ここは1問だけ復習したときの
   * 唯一の知らせなので、無条件に言うと嘘になる（2026-08-21）。
   *
   * 0の側で「安定しています」と言わないのは、主語が問題になって「問題そのものが
   * 落ち着いている」と読めるため（一覧のバッジ `安定` は短いラベルなので成り立つ）。
   * 言いたいのは解く側のことなので、そちらを主語にする。
   *
   * 苦手度は提出の応答で入れ替わっているため（{@code applyDelta}）、読むのは今の値でよい。
   */
  function reviewClearedLead() {
    var lesson = findLesson(currentId);
    var task = lesson && findTask(lesson, reviewTaskId);
    return Number(task && task.reviewWeight || 0) > 0
      ? 'お見事！ 出題頻度が下がりました'
      : 'お見事！ しっかり身についています';
  }

  /**
   * 問題の下に置く、次へ進む導線。
   *
   * @param justCleared いま提出が通ったところなら true（言葉を変えるだけ）
   */
  function renderReviewFooter(justCleared) {
    var host = document.getElementById('reviewFooter');
    if (!host) { return; }

    if (!reviewSession) {
      host.innerHTML =
        '<div class="lesson-next-copy"><small>' + (justCleared ? '復習クリア' : '復習中') + '</small>' +
        '<b>' + (justCleared ? reviewClearedLead() : '解き直せたら提出しましょう') + '</b></div>' +
        '<button class="primary-btn lesson-next-btn" id="reviewFooterBtn">復習メニューへ →</button>';
      document.getElementById('reviewFooterBtn').addEventListener('click', endReviewSession);
      return;
    }

    var remaining = reviewSession.queue.length - reviewSession.index - 1;
    var quizzes = reviewSession.quizQueue.length;
    // 最後の1問でも「復習を終える」とは言わない（このあと結果から続けられる）
    var label = remaining > 0
      ? '次の問題へ →'
      : (quizzes ? 'クイズの復習へ →' : 'セットの結果へ →');
    var lead = remaining > 0
      ? 'あと' + remaining + '問'
      : (quizzes ? '問題はこれで最後（クイズが' + quizzes + '問続きます）' : 'これが最後の1問');
    host.innerHTML =
      '<div class="lesson-next-copy"><small>' + (justCleared ? '復習クリア' : '復習中') + '</small>' +
      '<b>' + lead + '</b></div>' +
      '<button class="primary-btn lesson-next-btn" id="reviewFooterBtn">' + label + '</button>';
    document.getElementById('reviewFooterBtn').addEventListener('click', advanceReviewSession);
  }

  /**
   * 復習で通ったとき。
   *
   * <b>右上の通知は出さない</b>（2026-08-21・利用者の判断）。同じ瞬間に採点結果・問題ヘッダの
   * 苦手度バッジ・フッタの3つが「通った」を示すので、4つ目は言い直しになる。とくに苦手度0の
   * 問題では通しても0のままで、通知に書くべき変化が無い。★はもう付いているため報酬の通知も無い。
   *
   * 状態の変化はその場の画面で見せる ―― バッジ（🔥苦手 → もう一度 → 安定）とフッタが担う。
   */
  function onReviewCleared(taskId, res) {
    if (reviewSession) {
      var key = currentId + '#' + taskId;
      if (!reviewSession.clearedKeys[key]) {
        reviewSession.clearedKeys[key] = true;
        reviewSession.cleared++;
      }
      // 期限が来ていた問題ぶんだけ入る。同じ問題を通し直しても2回目は0が返る
      reviewSession.cash += res && res.cafeAward ? Number(res.cafeAward.cash || 0) : 0;
      // 通したところで抜ける人がいちばん多いので、ここは必ず控える
      saveReviewRun();
    }
    refreshReviewWeightBadge(taskId);
    renderReviewFooter(true);
  }

  /** 問題ヘッダの苦手度バッジを、いまの state に合わせる（採点結果は消さない）。 */
  function refreshReviewWeightBadge(taskId) {
    var badge = document.getElementById('reviewWeight-' + taskId);
    var lesson = findLesson(currentId);
    var task = lesson && findTask(lesson, taskId);
    if (!badge || !task) { return; }
    var weight = Number(task.reviewWeight || 0);
    badge.setAttribute('data-level', String(reviewWeightLevel(weight)));
    badge.textContent = reviewWeightText(weight);
  }

  // ----------------------------------------------------------- ブックマーク

  /**
   * ブックマークのしおり。塗りが「付いている」、線だけが「付いていない」。
   *
   * 絵文字（🔖）を使わないのは、絵文字は色が固定で、付いていない状態を灰色にしても
   * 「消えている」ように見えず、小さく置くとしおりだと読み取れないため。
   * SVGなら今の文字色（装備中はアクセント色、未設定は淡色）がそのまま乗る。
   */
  function bookmarkIconSvg(filled) {
    if (filled) {
      return '<svg class="bookmark-icon" width="13" height="15" viewBox="0 0 13 15"' +
        ' fill="currentColor" aria-hidden="true">' +
        '<path d="M2 1h9a1 1 0 0 1 1 1v11.3a.6.6 0 0 1-.95.5L6.5 10.6 1.95 13.8' +
        'A.6.6 0 0 1 1 13.3V2a1 1 0 0 1 1-1z"/></svg>';
    }
    return '<svg class="bookmark-icon" width="13" height="15" viewBox="0 0 13 15"' +
      ' fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true">' +
      '<path d="M2.2 1.7h8.6a.5.5 0 0 1 .5.5v10.9a.5.5 0 0 1-.79.4L6.5 10.2l-4.01 3.3' +
      'a.5.5 0 0 1-.79-.4V2.2a.5.5 0 0 1 .5-.5z"/></svg>';
  }

  /** 問題ヘッダに置く、ブックマークの付け外しボタン。 */
  function bookmarkButtonHtml(lessonId, task) {
    var on = !!task.bookmarked;
    return '<button type="button" class="bookmark-btn' + (on ? ' on' : '') + '"' +
      ' data-lesson="' + esc(lessonId) + '" data-task="' + esc(task.id) + '"' +
      ' aria-pressed="' + on + '" title="' + bookmarkTitle(on) + '">' +
      '<span class="bookmark-mark">' + bookmarkIconSvg(on) + '</span>' +
      '<span class="bookmark-label">ブックマーク</span></button>';
  }

  function bookmarkTitle(on) {
    return on ? 'ブックマークを外す' : 'ブックマークに入れて、あとで重点的に復習する';
  }

  /**
   * クイズの問いに置くしおりボタン。
   *
   * クイズは復習で出題しないので、付けても出題順は変わらない（あとで見に戻るための印）。
   * そのぶん説明文も問題側とは変える。
   */
  function quizBookmarkButtonHtml(lesson, index) {
    var on = quizBookmarked(lesson, index);
    return '<button type="button" class="bookmark-btn' + (on ? ' on' : '') + '"' +
      ' data-lesson="' + esc(lesson.id) + '" data-quiz="' + index + '"' +
      ' aria-pressed="' + on + '" title="' + quizBookmarkTitle(on) + '">' +
      '<span class="bookmark-mark">' + bookmarkIconSvg(on) + '</span>' +
      '<span class="bookmark-label">ブックマーク</span></button>';
  }

  function quizBookmarkTitle(on) {
    return on ? 'クイズのしおりを外す' : 'しおりを付けて、復習画面からこのクイズへ戻れるようにする';
  }

  function quizBookmarked(lesson, index) {
    return !!(lesson.quizBookmarks || [])[index];
  }

  function quizItemId(index) { return 'quiz-item-' + index; }

  function bindBookmarkButtons(host) {
    Array.prototype.forEach.call(host.getElementsByClassName('bookmark-btn'), function (button) {
      button.addEventListener('click', function (event) {
        // 一覧では行そのものが「その問題を復習する」ボタンなので、そちらへ渡さない
        event.stopPropagation();
        if (button.dataset.quiz != null) {
          toggleQuizBookmark(button.dataset.lesson, Number(button.dataset.quiz), button);
          return;
        }
        toggleBookmark(button.dataset.lesson, button.dataset.task, button);
      });
    });
  }

  function toggleBookmark(lessonId, taskId, button) {
    if (button) { button.disabled = true; }
    api('bookmark', { lessonId: lessonId, taskId: taskId })
      .then(function (res) {
        var lesson = findLesson(res.lessonId);
        var task = lesson && findTask(lesson, res.taskId);
        if (task) { task.bookmarked = res.bookmarked; }
        // 復習ホームでは件数と並びが変わるので描き直す。問題を解いている画面では
        // ボタンの見た目だけ変える（描き直すとエディタと採点結果が消える）
        if (currentView === 'review') {
          renderReview();
        } else if (button) {
          applyBookmarkButton(button, res.bookmarked);
        }
        toast(res.bookmarked
          ? '★ ブックマークに入れました'
          : '☆ ブックマークを外しました');
      })
      .catch(toastError)
      .then(function () {
        if (button) { button.disabled = false; }
      });
  }

  function applyBookmarkButton(button, bookmarked) {
    button.classList.toggle('on', bookmarked);
    button.setAttribute('aria-pressed', String(bookmarked));
    button.title = button.dataset.quiz != null
      ? quizBookmarkTitle(bookmarked)
      : bookmarkTitle(bookmarked);
    var mark = button.querySelector('.bookmark-mark');
    if (mark) { mark.innerHTML = bookmarkIconSvg(bookmarked); }
  }

  /**
   * クイズのしおりを付け外しする。
   *
   * 手元の状態（{@code lesson.quizBookmarks}）を直すのは問題側と同じ理由で、
   * レッスン画面では描き直さずボタンの見た目だけ変える（描き直すと解答済みの
   * 答え合わせとエディタが消える）。復習ホームでは件数と一覧が変わるので描き直す。
   */
  function toggleQuizBookmark(lessonId, index, button) {
    if (button) { button.disabled = true; }
    api('bookmark', { lessonId: lessonId, quizIndex: index })
      .then(function (res) {
        var lesson = findLesson(res.lessonId);
        if (lesson) {
          if (!lesson.quizBookmarks) { lesson.quizBookmarks = []; }
          lesson.quizBookmarks[res.quizIndex] = res.bookmarked;
        }
        if (currentView === 'review') {
          renderReview();
        } else if (button) {
          applyBookmarkButton(button, res.bookmarked);
        }
        toast(res.bookmarked
          ? '🔖 クイズにしおりを付けました'
          : '☆ クイズのしおりを外しました');
      })
      .catch(toastError)
      .then(function () {
        if (button) { button.disabled = false; }
      });
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
    var investmentBtn = document.getElementById('cafeInvestmentBtn');
    if (investmentBtn) { investmentBtn.addEventListener('click', purchaseCafeInvestment); }
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
    // 残り枠の数字は出さない。見えていると「提出を遅らせて枠を使い切るほうが得」に
    // 見えてしまい、学習を止める動機になる（枠は★を取ると満タンに戻るので、
    // 実際に待ったほうが多く取れてしまう）。止まったことだけは値側で知らせる。
    var passiveCapped = cafe.passiveCashPerMinute > 0
      && !(cafe.passiveCashRemaining > 0);
    var passiveLabel = '表示中の自動売上';
    var finalLevelMessage = Number(cafe.storeCount || 1) >= Number(cafe.maxStores || 512)
      ? ((cafe.investmentLevel || 0) > 0
        ? '終盤改装 PROJECT Lv.' + numberText(cafe.investmentLevel) + 'を完了'
        : 'Java学習と店舗ネットワークを制覇しました')
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
      '      <span class="cafe-passive-metric' + (passiveCapped ? ' capped' : '') + '">'
               + '<small>' + passiveLabel + '</small><b id="cafePassiveLive">'
               + (cafe.passiveCashPerMinute <= 0
                 ? '⏱️ 未導入'
                 : passiveCapped
                   ? '⏱️ 上限に達しました'
                   : perMinuteText('⏱️ +' + cafeNumberText(cafe.passiveCashPerMinute), 'コイン'))
               + '</b></span>' +
      '    </div>' +
      '    <div class="cafe-meta-row">' +
      '      <span>☕ 累計 ' + numberText(cafe.cups) + '杯</span>' +
      (networkUnlocked
        ? '      <span>ブランド ×' + multiplierText(cafe.brandMultiplierBasisPoints) + '</span>'
        : '') +
      '      <span>★ ' + numberText(stars) + '</span>' +
      ((cafe.investmentLevel || 0) > 0
        ? '      <span>🏛️ 終盤改装 Lv.' + numberText(cafe.investmentLevel) + '</span>' : '') +
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
      return item.owned && (item.effects || []).some(function (e) {
        return e.type === 'expansion_discount';
      });
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
        + '出店枠は★で段階的に解放され、ブランド倍率は完成した章の問題数と、'
        + '復習で仕上げた問題数に応じて育ちます。'
        + 'ブランド倍率はこれからの報酬に掛かるので、復習は早いほど得です。</p>' +
      '<div class="cafe-expansion-stats">' +
      '<span><small>現在</small><b>' + numberText(storeCount) + '店舗</b></span>' +
      '<span><small>店舗倍率</small><b>×' + numberText(storeCount) + '</b></span>' +
      '<span><small>現在の出店上限</small><b>' + numberText(cafe.storeLimit || 1) + '店舗</b></span>' +
      '<span><small>ブランド倍率</small><b>×' + multiplierText(cafe.brandMultiplierBasisPoints) + '</b></span>' +
      ((cafe.reviewBrandBasisPoints || 0) > 0
        ? '<span><small>うち復習ぶん</small><b>+' + multiplierText(cafe.reviewBrandBasisPoints)
          + '<em>（' + numberText(cafe.reviewedTasks || 0) + '問）</em></b></span>'
        : '') +
      (expansionDiscount ? '<span class="discount-active"><small>工具箱の効果</small><b>出店費 25%OFF</b></span>' : '') +
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
      '</div>' + renderCafeInvestment() + '</section>';
  }

  /** ★520以降、20問ごとに追加される収益効果のない任意投資。 */
  function renderCafeInvestment() {
    var cafe = cafeState();
    var investment = cafe.endgameInvestment;
    if (!investment) { return ''; }

    var available = !!investment.available;
    var affordable = available && cafe.cash >= investment.cost;
    var buttonText = available
      ? (cafeNumberText(investment.cost) + 'コインで投資')
      : ('★' + numberText(investment.requiredStars) + 'で解放');
    var status = (investment.completedLevel || 0) > 0
      ? 'PROJECT Lv.' + numberText(investment.completedLevel) + '完了'
      : '★520から開始';

    return '<section class="cafe-endgame-investment">' +
      '<div class="cafe-investment-icon">' + esc(investment.emoji) + '</div>' +
      '<div class="cafe-investment-copy"><span class="screen-eyebrow">OPTIONAL LEGACY PROJECT</span>' +
      '<h3>' + esc(investment.name) + '</h3><p>' + esc(investment.description) + '</p>' +
      '<small>売上倍率には影響しない任意投資です。今後は20問追加されるたびに次の段階が解放されます。</small></div>' +
      '<div class="cafe-investment-action"><b>' + status + '</b>' +
      '<button class="primary-btn" id="cafeInvestmentBtn"' + (!affordable ? ' disabled' : '') + '>'
        + esc(buttonText) + '</button>' +
      (available && !affordable
        ? '<small>あと ' + cafeNumberText(investment.cost - cafe.cash) + 'コイン</small>'
        : (!available ? '<small>あと ★' + numberText(investment.requiredStars - state.progress.starCount) + '</small>' : '')) +
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
      body: 'コインが入るすべての場面（問題・章・確認クイズのチップ・自動売上）の単価が増えます。'
        + '全体に掛かる倍率はこの系統だけが持ちます。'
    },
    cups: {
      name: '毎注文',
      body: '1回の学習で提供する杯数が増えます。杯数はすべての報酬の土台なので、'
        + '問題・章・クイズ・自動売上のすべてが一緒に増えます。'
    },
    chapter: {
      name: '章ボーナス',
      body: '章の問題をすべてクリアしたときだけ入る、まとめの追加売上が増えます。'
    },
    tips: {
      name: '正解チップ',
      body: '確認クイズに1度目の回答で正解したときの追加コインが増えます。'
        + '答え直しの正解では出ません。'
    },
    review: {
      name: '復習手当',
      body: '復習で「期限が来た問題」を通したときの報酬が増えます。'
        + '期限前の早めの復習では出ません。通すと次の期限が先へ動くので、'
        + '同じ問題を続けて通しても2回目からは出ません。'
    },
    automation: {
      name: '自動売上',
      body: 'アプリを表示している間だけ、ゆっくり売上を作ります。'
        + '次の★を取るまでの自動売上は最大5問分です。'
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
    var affordable = next && cafe.cash >= next.cost;
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
        + perMinuteText('約 +' + cafeNumberText(nextEstimate), 'コイン') + '</em></div>'
      : '<span class="equipment-item-icon max">★</span><div><small>アップグレード完了</small>'
        + '<b>最高ランク</b><em>' + perMinuteText('学習1回分の5%', '') + '</em></div>';
    var button = next
      ? '<button class="cafe-buy cafe-automation-buy" data-id="' + esc(next.id) + '"'
        + (!affordable ? ' disabled' : '') + '>Rank&nbsp;' + next.tier + 'へ · '
        + (next.discounted ? '<s>' + cafeNumberText(next.baseCost) + '</s> ' : '')
        + cafeNumberText(next.cost) + 'コイン</button>'
      : '<button class="cafe-buy cafe-automation-buy" disabled>MAX</button>';
    var shortage = next && !affordable
      ? '<small class="equipment-shortage">あと ' + cafeNumberText(next.cost - cafe.cash) + 'コイン</small>'
      : '';

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

    var trackOrder = ['sales', 'cups', 'chapter', 'tips', 'review'];

    function effectLabel(type) {
      if (type === 'cups') { return '抽出力'; }
      if (type === 'chapter') { return 'イベント'; }
      if (type === 'tips') { return 'クイズ接客'; }
      if (type === 'review') { return '復習手当'; }
      return '販売戦略';
    }

    function effectText(type, value) {
      if (type === 'cups') { return '毎注文 +' + numberText(value) + '杯'; }
      if (type === 'chapter') { return '章ボーナス +' + numberText(value) + '%'; }
      if (type === 'tips') { return '正解チップ +' + numberText(value) + '%'; }
      if (type === 'review') { return '復習報酬 +' + numberText(value) + '%'; }
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
      '<span>復習報酬 +' + (cafe.reviewBonusPercent || 0) + '%</span>' +
      '<span>' + perMinuteText('自動 +' + cafeNumberText(cafe.passiveCashPerMinute), '') + '</span>' +
      '</div></header>' +
      '<p class="menu-note cafe-section-note">コインは学習を進めると入手できます。'
        + ((cafe.equipmentDiscountPercent || 0) > 0
          ? ' 設備費は現在 ' + cafe.equipmentDiscountPercent + '%OFF です。' : '') + '</p>' +
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
        var affordable = next && cafe.cash >= next.cost;
        var currentHtml = current
          ? '<span class="equipment-item-icon">' + esc(current.emoji) + '</span><div><small>現在装備 · Rank&nbsp;'
            + current.tier + '</small><b>' + esc(current.name) + '</b><em>'
            + esc(effectText(type, current.effectValue)) + '</em></div>'
          : '<span class="equipment-item-icon empty">－</span><div><small>現在装備</small><b>未導入</b>'
            + '<em>効果なし</em></div>';
        var nextHtml = next
          ? '<span class="equipment-item-icon">' + esc(next.emoji) + '</span><div><small>次の上位設備 · Rank&nbsp;'
            + next.tier + '</small><b>' + esc(next.name) + '</b><em>'
            + esc(effectText(type, next.effectValue)) + '</em></div>'
          : '<span class="equipment-item-icon max">★</span><div><small>アップグレード完了</small>'
            + '<b>最高ランク</b><em>この系統は完成しました</em></div>';
        var button = next
          ? '<button class="cafe-buy equipment-upgrade-btn" data-id="' + esc(next.id) + '"'
            + (!affordable ? ' disabled' : '') + '>Rank&nbsp;' + next.tier + 'へ · '
            + (next.discounted ? '<s>' + cafeNumberText(next.baseCost) + '</s> ' : '')
            + cafeNumberText(next.cost) + 'コイン</button>'
          : '<button class="cafe-buy equipment-upgrade-btn" disabled>MAX</button>';
        var shortage = next && !affordable
          ? '<small class="equipment-shortage">あと ' + cafeNumberText(next.cost - cafe.cash) + 'コイン</small>'
          : '';

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

  function purchaseCafeInvestment() {
    api('cafe/investment/purchase', {})
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        renderCafe(true);
        toast(res.investment.emoji + ' 「' + res.investment.name + '」へ投資しました');
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
        // 収入が出たときだけ描き直せば足りる。枠を使い切るのは必ず「最後の1コインを
        // 受け取った回」なので、その回の描き直しで「上限に達しました」に変わる。
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
      { icon: '🔥', value: state.progress.streak, unit: '日', label: '連続で学習した日数（区切りは午前4時）' },
      { icon: '✅', value: casesPassed, unit: '件', label: '通過したテスト・構成検証' },
      { icon: '✍️', value: attempts, unit: '回', label: '実行した回数' }
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

  /*
   * 章の到達状況を「概念・コード・実践」の3層に分けて数える。
   *
   * ★は問題ごとに1つで、簡略な模型と実物を動かす課題が同じ見た目になる。
   * 何を説明できるのか、何を書けるのか、何を動かして直せるのかは別の能力なので、
   * 問題の種類とクイズから3層を作って分けて示す。
   *
   *   概念 … 確認クイズ（★の判定には影響しないが、知識の確認はここで数える）
   *   コード … single-file と artifact（コードや設定を書く）
   *   実践 … project と runtime-lab（実物を起動・観測・修正する）
   *
   * 任意発展問題は章クリアの対象外なので、どの層の分母にも入れない。
   */
  // 層の数え方はサーバー側（Curriculum.layerProgress）に1つだけ置いてある。
  // ここで数え直すと定義が2箇所になってずれるので、返ってきた値をそのまま使う。
  // completedAt は「最初に達成した日」で、章へ問題が増えても消えない。
  function chapterLayers(chapter) {
    var empty = { total: 0, done: 0, complete: false, completedAt: null };
    var layers = chapter.layers || {};
    return {
      concept: layers.concept || empty,
      coding: layers.coding || empty,
      practice: layers.practice || empty
    };
  }

  /*
   * 章の到達目標（objectives）は画面に出さない。
   *
   * 「この章でできるようになること」を3層の下へ並べていたが外した。章を選ぶ画面で必要なのは
   * 「次に何をするか」であり、目標の一覧はそこでは読まれない。未着手の章では `0/4問` が
   * 並ぶだけになり、rubricの5軸表を外したときと同じ理由が当てはまる。
   *
   * データは残す。到達目標は**問題を書く側の基準**として要る（docs/guide.md「到達目標を書く」）。
   *   - `sourceChecks` を書いてよいかの判断が、その章の目標に紐づく（字面を縛るかどうか）
   *   - 628問と391クイズが必ずどれかの目標へ解決されることを
   *     `tools/check-objectives.sh` が見張る（測る問題の無い目標、目標の無いレッスンを落とす）
   *
   * `/api/state` の `objectives` と `objectiveIds` も残してあるので、別の見せ方をしたくなれば
   * 使える。章クリアや★の判定は目標と無関係なので、出さなくても進行は変わらない。
   */

  function chapterLayersHtml(layers) {
    var rows = [
      { key: 'concept', label: '概念', note: 'クイズで確かめる', empty: 'クイズはありません' },
      { key: 'coding', label: 'コード', note: '書いて動かす', empty: 'コード問題はありません' },
      { key: 'practice', label: '実践', note: '実物を起動して直す', empty: 'この章にはありません' }
    ];
    return '<div class="chapter-layers" aria-label="この章の到達状況">' +
      rows.map(function (row) {
        var layer = layers[row.key];
        var done = !!layer.complete;
        // 一度達成した層は、章へ問題が増えても記録として残る。
        // いま満たしていないなら、残件も分かるように両方出す。
        var earned = !!layer.completedAt;
        var note = row.note;
        if (layer.total > 0 && !done) {
          note = row.key === 'practice' ? '章クリアに必要' : row.note;
          if (earned) { note = layer.completedAt + ' に達成（追加分が残り）'; }
        } else if (earned) {
          note = layer.completedAt + ' に達成';
        }
        return '<div class="chapter-layer'
          + (layer.total ? '' : ' layer-none')
          + (done ? ' layer-done' : '') + (earned && !done ? ' layer-earned' : '') + '">' +
          '<span class="layer-name">' + row.label + (earned ? ' ✓' : '') + '</span>' +
          '<span class="layer-count">'
          + (layer.total ? layer.done + ' / ' + layer.total : '—') + '</span>' +
          '<span class="layer-note">' + (layer.total ? note : row.empty) + '</span>' +
          '</div>';
      }).join('') +
      '</div>';
  }

  /*
   * 実務rubric（説明・実装・診断・test・判断）は画面に出さない。
   * 章の5軸表（☆☆×5＋合計）も、レッスン行の軸タグ（「説明」「診断」など）も外した。
   *
   * 章を選ぶ画面で必要なのは「次に何をするか」で、採点の内訳ではなかった。
   * 未着手の章では ☆☆ が5つ並び、「test / 対象なし」「合計 0 / 8」
   * 「実装と診断が各1点以上＋8割」だけが読める状態になり、行動につながらない。
   * レッスン行のタグも、その表があって初めて意味が通るものだった。
   * 表を外したあとは凡例のないラベルになり、学習者には何のタグか分からない。
   *
   * 章クリアの判定はこの点数と無関係（★の達成率で決まる）ので、消しても進行は変わらない。
   * 算出は API（ApiHandler.chapterRubric / lessonRubric）に残してあるので、
   * 別の見せ方をしたくなれば使える。問題JSONの rubric 欄は、
   * 問題を書くときにどの能力を測るか判断するための基準として引き続き必要（docs/guide.md §8.4）。
   */

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

    var uncheckedPreflight = selectedChapter.cleared ? null : selectedChapter.lessons.find(function (lesson) {
      return lesson.type === 'preflight' && !preflightRecentlyReady(lesson.id);
    });
    var layers = chapterLayers(selectedChapter);
    var nextLessonInChapter = uncheckedPreflight || selectedChapter.lessons.find(function (lesson) {
      return lesson.type !== 'preflight' && !lesson.cleared;
    }) || selectedChapter.lessons.find(function (lesson) { return lesson.type !== 'preflight'; })
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
      chapterLayersHtml(layers) +
      (activePart.prerequisite
        ? '<div class="part-prerequisite"><strong>学習の前提</strong><span>'
          + esc(activePart.prerequisite) + '</span></div>'
        : '') +
      (nextLessonInChapter ? '<button class="primary-btn chapter-start-btn" id="chapterStartBtn" data-target="'
        + esc(nextLessonInChapter.id) + '">▶ ' + chapterAction + '</button>' : '') +
      '<ul class="chapter-lesson-list">' + selectedChapter.lessons.map(function (lesson) {
        var done = lesson.clearedCount || 0;
        var isPreflight = lesson.type === 'preflight';
        var lessonStatus = isPreflight ? '環境チェック · ★対象外'
          : (lesson.cleared ? 'クリア済み' : (done ? '学習中' : ''));
        return '<li><button type="button" class="chapter-lesson-row' + (lesson.cleared ? ' cleared' : '')
          + '" data-lesson="' + esc(lesson.id) + '">' +
          '<span class="chapter-lesson-status">' + (isPreflight ? '⚙' : (lesson.cleared ? '✓' : displayLessonId(lesson))) + '</span>' +
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

      '  <section class="preflight" id="preflight"></section>' +

      '  <section class="concept-note" id="conceptNote"></section>' +

      '  <section class="tasks" id="tasks"></section>' +
      '  <section class="quiz" id="quiz"></section>' +
      '  <nav class="lesson-next" id="lessonNext" aria-label="次のレッスン"></nav>' +
      '</article>';

    renderSamples(lesson);
    renderPreflight(lesson);
    renderConceptNote(lesson);

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
    paintedLessonId = lesson.id;
    // 直前に離れたレッスンへ帰ってきたときだけ、読んでいた位置から再開する。
    // 中身の高さが足りなければ入れた値は縮められるが、それはその窓での行き止まりなので任せる。
    main.scrollTop = lessonScroll && lessonScroll.lessonId === lesson.id ? lessonScroll.top : 0;
    // しおりから開いたときは、読んでいた位置より「そのクイズ」を優先する
    focusBookmarkedQuiz(lesson);
  }

  var PREFLIGHT_READY_MAX_AGE = 7 * 24 * 60 * 60 * 1000;

  function preflightStorageKey(lessonId) { return 'jq-preflight-ready-' + lessonId; }

  function preflightRecentlyReady(lessonId) {
    try {
      var checkedAt = Number(localStorage.getItem(preflightStorageKey(lessonId)) || 0);
      return checkedAt > 0 && Date.now() - checkedAt < PREFLIGHT_READY_MAX_AGE;
    } catch (e) { return false; }
  }

  function rememberPreflight(lessonId, ready) {
    try {
      if (ready) localStorage.setItem(preflightStorageKey(lessonId), String(Date.now()));
      else localStorage.removeItem(preflightStorageKey(lessonId));
    } catch (e) { /* 保存できなくても確認そのものは続けられる */ }
  }

  /**
   * 概念レッスンに提出課題が無い理由を、その場で書いておく。
   *
   * 何も書かないと「問題が抜けている」と読める。工程名や成果物の対応のように、
   * 提出物にすると測る対象がずれてしまう論点をここで扱っていること、★はクイズで付くことを示す。
   */
  function renderConceptNote(lesson) {
    var host = document.getElementById('conceptNote');
    if (!host || lesson.type !== 'concept') { return; }
    host.innerHTML = '<div class="card card-concept">' +
      '<div class="concept-head"><div><span class="screen-eyebrow">CONCEPT</span>' +
      '<h2 class="card-h"><span class="card-h-icon">📋</span>このレッスンは用語と判断を扱います</h2></div>' +
      '<span class="concept-star">★はクイズ全問正解</span></div>' +
      '<p class="concept-lead">提出するコードはありません。工程の名前・成果物・どちらを選ぶかの判断は、' +
      'コードにすると測る対象がずれてしまうため、解説と確認クイズで身につけます。' +
      '下のクイズに全問正解すると★が付き、章クリアの条件にも入ります。</p>' +
      '</div>';
  }

  function renderPreflight(lesson) {
    var host = document.getElementById('preflight');
    if (!host || lesson.type !== 'preflight') { return; }
    var spec = lesson.preflight;
    host.innerHTML = '<div class="card card-preflight">' +
      '<div class="preflight-head"><div><span class="screen-eyebrow">ENVIRONMENT CHECK</span>' +
      '<h2 class="card-h"><span class="card-h-icon">⚙️</span>ローカル環境を実測する</h2></div>' +
      '<span class="preflight-unscored">★対象外</span></div>' +
      '<p class="preflight-lead">必須項目がそろっているか確認します。インストールや設定変更は自動では行いません。</p>' +
      '<ul class="preflight-list">' + spec.checks.map(function (check) {
        return '<li class="preflight-check is-pending" data-check="' + esc(check.id) + '">' +
          '<span class="preflight-mark">○</span><div><strong>' + esc(check.label) + '</strong>' +
          '<span class="preflight-requirement">' + (check.required ? '必須' : '任意') +
          (check.minimumVersion ? ' · ' + esc(check.minimumVersion) + '以上' : '') + '</span>' +
          '<p>未確認</p></div></li>';
      }).join('') + '</ul>' +
      '<div class="preflight-actions"><button class="primary-btn" id="preflightRun">' +
      esc(spec.buttonLabel) + '</button><span id="preflightSummary" aria-live="polite"></span></div>' +
      '</div>';

    document.getElementById('preflightRun').addEventListener('click', function () {
      runPreflight(lesson);
    });
  }

  function runPreflight(lesson) {
    var button = document.getElementById('preflightRun');
    var summary = document.getElementById('preflightSummary');
    if (!button || button.disabled) { return; }
    button.disabled = true;
    button.textContent = '確認中…';
    summary.textContent = 'ツールとポートを確認しています';
    api('preflight', { lessonId: lesson.id }).then(function (res) {
      res.checks.forEach(function (check) {
        var row = document.querySelector('.preflight-check[data-check="' + check.id + '"]');
        if (!row) { return; }
        row.className = 'preflight-check ' + (check.pass ? 'is-pass' : (check.required ? 'is-fail' : 'is-optional'));
        row.querySelector('.preflight-mark').textContent = check.pass ? '✓' : (check.required ? '!' : '△');
        row.querySelector('p').innerHTML = '<strong>' + esc(check.summary) + '</strong>' +
          (check.detail ? '<span>' + esc(check.detail) + '</span>' : '') +
          (!check.pass ? '<span class="preflight-help">対処: ' + esc(check.help) + '</span>' : '');
      });
      rememberPreflight(lesson.id, res.ready);
      summary.className = res.ready ? 'is-ready' : 'is-not-ready';
      summary.textContent = res.ready
        ? '✓ 必須項目は準備できています'
        : '必須項目を直してから、もう一度確認してください';
    }).catch(function (error) {
      summary.className = 'is-not-ready';
      summary.textContent = error.message || String(error);
    }).then(function () {
      button.disabled = false;
      button.textContent = lesson.preflight.buttonLabel;
    });
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
   *
   * @param options {review:true} なら復習モード。保存済みの解答ではなくひな形から始め、
   *                苦手度のバッジを見出しに出す（保存を止めるのは {@link scheduleSave}）
   */
  /**
   * 補完の候補を選ぶキーの案内。
   *
   * macOSでは Ctrl+P / Ctrl+N でも動く（complete.js の `handleKeyDown`）。使えない環境で
   * 書いてしまうと案内が嘘になるので、そちらの判定をそのまま借りて出し分ける。
   */
  function completionMoveKeysText() {
    var mac = window.JQComplete && window.JQComplete.isMac && window.JQComplete.isMac();
    return mac ? '↑↓ か Ctrl+P / Ctrl+N' : '↑↓';
  }

  /**
   * 問題ヘッダに出す状態チップ。
   *
   * 文言を2箇所（描くとき・採点で通ったとき）で組み立てていたので、ここへ寄せた。
   *
   * 復習モードでは出さない。あの画面はクリア済みの問題しか出さないので、全部に
   * 「クリア済み」と付けても何も区別しない（復習での状態は苦手度チップと上の帯が担う）。
   */
  function taskStatusHtml(task, cleared) {
    if (!cleared) { return ''; }
    return '<b class="task-clear-chip">'
      + (task.required === false ? '✓ 発展課題完了' : '★ クリア済み') + '</b>';
  }

  /**
   * ボタンの文字。
   *
   * 「試しに実行」と「提出して採点」を分けてある（2026-08-19・利用者の指示）。
   * 分けていなかったあいだの狙い（考えながら書けるように、気軽に走らせる）は
   * <b>試しに実行が引き受ける</b> ―― こちらは記録に何も残らないので、提出より気軽に押せる。
   * 提出の側に「✓」と「提出」を入れているのは、押すと記録が動くと分かるようにするため。
   */
  var TRY_BUTTON_LABEL = '▶ 試しに実行';
  var SUBMIT_BUTTON_LABEL = '✓ 提出して採点';

  /** 提出ボタンの文字。問題の型で変わる（組み立てと実行中の戻しで同じものを使う）。 */
  function submitButtonLabel(task) {
    if (task && task.type === 'runtime-lab') { return '▶ runtime labを実行'; }
    if (task && task.type === 'project') { return '▶ テストを実行'; }
    if (task && task.type === 'artifact') { return '✓ 構成を検証'; }
    return SUBMIT_BUTTON_LABEL;
  }

  /**
   * 「試しに実行」に渡す入力の初期値。見えているケースの入力をそのまま入れておく。
   *
   * <b>隠しケースからは取らない。</b>入力そのものが「どんな入力で試されるか」の
   * 手がかりになるため（→ [[case-fairness-hidden-literals]] と同じ理由）。
   */
  function tryStdinSeed(task) {
    var cases = (task && task.visibleCases) || [];
    for (var i = 0; i < cases.length; i++) {
      if (cases[i].stdin) { return cases[i].stdin; }
    }
    return '';
  }

  /**
   * 「試しに実行」で使う入力欄。<b>入力を使う問題だけ</b>に出す。
   *
   * 入力を読まない問題に空の欄を出すと、書かないと動かないように見える。
   * 書き換えた入力は保存しない（保存する口は自動保存と提出の2つに絞ってある）。
   * レッスンを開き直すとケースの入力に戻る。
   */
  function tryInputHtml(task) {
    var seed = tryStdinSeed(task);
    if (!seed) { return ''; }
    var rows = Math.min(5, Math.max(2, seed.split('\n').length));
    return '    <div class="try-input">' +
      '      <label class="try-input-label" for="tryStdin-' + task.id + '">' +
      '⌨️ 入力（「試しに実行」だけで使います・提出は全ケースの入力で採点します）</label>' +
      '      <textarea class="try-stdin" id="tryStdin-' + task.id + '" rows="' + rows + '"' +
      ' spellcheck="false" aria-label="試しに実行するときの入力">' + esc(seed) + '</textarea>' +
      '    </div>';
  }

  function buildTaskBlock(lesson, task, index, options) {
    var n = task.id;
    var review = !!(options && options.review);
    var weight = Number(task.reviewWeight || 0);
    var artifact = task.type === 'artifact';
    var project = task.type === 'project';
    var runtimeLab = task.type === 'runtime-lab';
    var multiFile = project || runtimeLab;
    var workspace = runtimeLab ? task.runtimeLab : task.project;
    var editTitle = runtimeLab ? '実行環境を使うlabを編集'
      : (project ? 'プロジェクトを編集' : (artifact ? 'ファイルを編集' : 'コードを書く'));
    var submitLabel = submitButtonLabel(task);
    // 採点せずに走らせられるのは単一ファイルのJavaだけ。artifactは設定ファイルの検証、
    // project / runtime-lab は外の道具を動かすので、1回走らせる意味がない
    var tryable = !artifact && !multiFile;
    var tryInput = tryable ? tryInputHtml(task) : '';
    var shortcut = multiFile
      ? 'ファイルを切り替えて編集　·　⌘/Ctrl + Enter で実行'
      : artifact
      ? 'Tab で字下げ　·　⌘/Ctrl + Enter で検証'
      : 'Tab で補完（候補は ' + completionMoveKeysText() + ' で選ぶ）　·　'
        + '⌘/Ctrl + Enter で提出　·　⇧ を足すと試しに実行';

    // 復習モードでは緑にしない（→ taskStatusHtml）
    var cleared = !review && !!task.cleared;

    var block = document.createElement('section');
    block.className = 'task-block' + (task.required === false ? ' task-block-optional' : '')
      + (cleared ? ' is-cleared' : '');
    block.id = 'task-' + n;
    block.innerHTML =
      '<div class="task-block-head">' +
      '  <span class="task-mark" aria-hidden="true">' + (cleared ? '✓' : '') + '</span>' +
      '  <span class="task-no">' + (task.required === false ? '任意' : '問題' + (index + 1)) + '</span>' +
      '  <span class="task-kind task-kind-' + esc(task.kind) + '">' + esc(task.label) + '</span>' +
      '  <span class="task-head-status" id="taskStatus-' + n + '">' +
           taskStatusHtml(task, cleared) +
      '  </span>' +
      (review
        ? '  <span class="review-weight" id="reviewWeight-' + n + '" data-level="'
          + reviewWeightLevel(weight) + '">' + esc(reviewWeightText(weight)) + '</span>'
        : '') +
         bookmarkButtonHtml(lesson.id, task) +
      '</div>' +

      '<div class="task-block-body">' +
      '  <div class="card card-task">' +
      '    <h2 class="card-h"><span class="card-h-icon">📝</span>課題</h2>' +
      '    <div class="task-body">' + renderMarkdown(task.task) + '</div>' +
           renderCasePreview(task) +
      '  </div>' +

      '  <div class="card card-code' + (tryInput ? ' has-try-input' : '') + '">' +
      '    <div class="code-head">' +
      '      <h2 class="card-h"><span class="card-h-icon">⌨️</span>' + editTitle + '</h2>' +
      '      <div class="code-head-actions">' +
      '        <button class="ghost-btn" data-role="restore" title="最初のひな形に戻す">ひな形に戻す</button>' +
      '      </div>' +
      '    </div>' +
          (artifact ? renderArtifactFileHead(task.artifact) : '') +
          (project ? renderProjectHead(task.project) : '') +
          (runtimeLab ? renderRuntimeLabHead(task.runtimeLab) : '') +
      '    <div id="editorHost-' + n + '"></div>' +
           tryInput +
      '    <div class="actions">' +
          (tryable
            ? '      <button class="ghost-btn" id="tryBtn-' + n + '">'
              + TRY_BUTTON_LABEL + '</button>'
            : '') +
      '      <button class="primary-btn" id="submitBtn-' + n + '">' + submitLabel + '</button>' +
      '      <span class="spacer"></span>' +
             renderHintButton(task) +
      '    </div>' +
      '    <div class="shortcut-note">' + shortcut + '</div>' +
      '  </div>' +

      // 採点結果はヒントより先。ヒントを開いていると、コード欄（と「試しに実行」の
      // 入力欄）と出力のあいだにヒントが挟まって、書いたものと結果を見比べられなくなる
      // （2026-08-21・利用者の指摘）。ヒントは模範解答と続けて下に置く。
      '  <div class="result" id="result-' + n + '"></div>' +
      '  <div class="hints" id="hints-' + n + '"></div>' +
      '  <div class="solution-area" id="solution-' + n + '"></div>' +
      '</div>';

    var editor;
    if (multiFile) {
      editor = new ProjectEditor(block.querySelector('#editorHost-' + n), workspace);
      if (!review && task.savedFiles) { editor.setFiles(task.savedFiles); }
      editor.onSubmit = function () { submit(n); };
      editor.onChange(function () { scheduleSave(n); });
    } else {
      editor = new window.JQEditor(block.querySelector('#editorHost-' + n), {
        language: artifact ? task.artifact.format : 'java',
        ariaLabel: artifact ? task.artifact.path + 'を編集する欄' : 'コードを書く欄'
      });
      // 復習は解き直しなので、通した解答が最初から入っていては意味がない。ひな形から始める
      editor.setValue(!review && task.savedCode != null && task.savedCode !== ''
        ? task.savedCode
        : task.starterCode);
      editor.onSubmit = function () { submit(n); };
      if (tryable) { editor.onTryRun = function () { tryRun(n); }; }
      editor.input.addEventListener('input', function () { scheduleSave(n); });
    }
    editors[n] = editor;

    block.querySelector('#submitBtn-' + n).addEventListener('click', function () { submit(n); });
    if (tryable) {
      block.querySelector('#tryBtn-' + n).addEventListener('click', function () { tryRun(n); });
    }
    block.querySelector('[data-role="restore"]').addEventListener('click', function () {
      if (window.confirm((multiFile ? '編集した複数ファイル' : (artifact ? '編集した内容' : '書いたコード'))
          + 'を消して、最初のひな形に戻します。よろしいですか？')) {
        if (multiFile) { editor.restore(); } else { editor.setValue(task.starterCode); }
        editor.focus();
        scheduleSave(n);
      }
    });

    var hintBtn = block.querySelector('.hint-btn');
    if (hintBtn) { hintBtn.addEventListener('click', function () { revealNextHint(n); }); }
    bindBookmarkButtons(block);

    // 開示済みヒントの描画は、このかたまりを document に挿してから
    // （renderRevealedHints は id で引くので、繋ぐ前だと見つからない）
    return block;
  }

  function renderArtifactFileHead(artifact) {
    return '<div class="artifact-file-head">' +
      '<span class="artifact-file-icon">📄</span>' +
      '<code>' + esc(artifact.path) + '</code>' +
      '<span class="artifact-format">' + esc(artifact.format.toUpperCase()) + '</span>' +
      '</div>';
  }

  function renderProjectHead(project) {
    return '<div class="project-head">' +
      '<span>📦 ' + esc(project.name) + '</span>' +
      '<code>' + esc(project.command) + '</code>' +
      '<span>' + project.editableFileCount + '編集 / ' + project.fileCount + '表示</span>' +
      '</div>';
  }

  function renderRuntimeLabHead(lab) {
    var capabilities = (lab.capabilities || []).map(function (name) {
      return '<span class="runtime-capability">' + esc(name) + '</span>';
    }).join('');
    return '<div class="runtime-lab-head">' +
      '<div><strong>🧪 ' + esc(lab.name) + '</strong>' + capabilities + '</div>' +
      '<code>' + esc(lab.command) + '</code>' +
      '<span>' + lab.editableFileCount + '編集 / ' + lab.fileCount + '表示</span>' +
      '</div>';
  }

  // ---- ファイル一覧の木（VSCodeのエクスプローラーのような見せ方） ----------

  /**
   * `{path: "src/main/java/App.java", …}` の並びから、フォルダとファイルの木を作る。
   *
   * 並び順はVSCodeに合わせて「フォルダが先、次にファイル」。名前の大小文字は区別しない。
   * サーバが渡してくるのは path のコード順（大文字が先で `README.md` が `pom.xml` より前）
   * なので、ここで並べ替える。
   */
  function buildFileTree(files) {
    var root = { name: '', path: '', dirs: [], files: [] };
    files.forEach(function (file) {
      var parts = file.path.split('/');
      var node = root;
      for (var i = 0; i < parts.length - 1; i++) {
        node = findOrAddDir(node, parts[i]);
      }
      node.files.push({ name: parts[parts.length - 1], file: file });
    });
    sortTree(root);
    // フォルダが1つだけ続く区間は1行にまとめる（VSCodeの compact folders と同じ）。
    // src / main / java / example / greeting と5段字下げしても、
    // 分かることは増えないのに横幅だけ食われる。
    root.dirs.forEach(compactDirs);
    return root;
  }

  function findOrAddDir(node, name) {
    var found = node.dirs.find(function (dir) { return dir.name === name; });
    if (found) { return found; }
    var dir = { name: name, path: node.path ? node.path + '/' + name : name, dirs: [], files: [] };
    node.dirs.push(dir);
    return dir;
  }

  function byName(a, b) {
    return a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.name.localeCompare(b.name);
  }

  function sortTree(node) {
    node.dirs.sort(byName);
    node.files.sort(byName);
    node.dirs.forEach(sortTree);
  }

  function compactDirs(dir) {
    while (dir.dirs.length === 1 && dir.files.length === 0) {
      var only = dir.dirs[0];
      dir.name = dir.name + '/' + only.name;
      dir.path = only.path;
      dir.files = only.files;
      dir.dirs = only.dirs;
    }
    dir.dirs.forEach(compactDirs);
  }

  /** project問題用のファイルナビゲータと、ファイルごとの軽量エディタ。 */
  function ProjectEditor(host, project) {
    this.host = host;
    this.project = project;
    this.fileEditors = {};
    this.changeHandlers = [];
    this.onSubmit = null;
    this.activePath = null;
    this._build();
  }

  ProjectEditor.prototype._build = function () {
    var self = this;
    this.host.classList.add('project-editor');
    this.host.innerHTML = '<div class="project-file-list" role="tablist" aria-label="プロジェクトのファイル"></div>' +
      '<div class="project-file-work"></div>';
    var list = this.host.querySelector('.project-file-list');
    var work = this.host.querySelector('.project-file-work');
    this._renderFileTree(list);

    this.project.files.forEach(function (file, index) {
      var pane = document.createElement('div');
      pane.className = 'project-file-pane';
      pane.setAttribute('data-path', file.path);
      pane.hidden = true;
      pane.innerHTML = '<div class="project-file-bar"><code>' + esc(file.path) + '</code>' +
        '<span>' + (file.editable ? '編集できます' : '参照専用') + '</span></div>' +
        '<div class="project-file-editor-host"></div>';
      work.appendChild(pane);

      var editor = new window.JQEditor(pane.querySelector('.project-file-editor-host'), {
        language: file.language,
        ariaLabel: file.path + (file.editable ? 'を編集する欄' : 'の内容')
      });
      editor.setValue(file.content);
      editor.input.readOnly = !file.editable;
      if (!file.editable) { editor.host.classList.add('is-readonly'); }
      editor.onSubmit = function () { if (self.onSubmit) { self.onSubmit(); } };
      if (file.editable) {
        editor.input.addEventListener('input', function () {
          self.changeHandlers.forEach(function (handler) { handler(); });
        });
      }
      self.fileEditors[file.path] = editor;
      if (index === 0) self.activePath = file.path;
    });

    var firstEditable = this.project.files.find(function (file) { return file.editable; });
    this.show(firstEditable ? firstEditable.path : this.activePath);
  };

  /**
   * ファイル一覧を、フォルダの行とファイルの行を並べて描く。
   *
   * フォルダは場所を示す見出しで、押せない（折りたたみは無い）。押せるのは
   * ファイルの行だけなので、一覧は今までどおり「表示するファイルを選ぶ」ものになる。
   */
  ProjectEditor.prototype._renderFileTree = function (list) {
    var self = this;
    (function walk(node, depth) {
      node.dirs.forEach(function (dir) {
        list.appendChild(self._dirRow(dir, depth));
        walk(dir, depth + 1);
      });
      node.files.forEach(function (entry) {      // ファイルはフォルダのあと
        list.appendChild(self._fileRow(entry, depth));
      });
    })(buildFileTree(this.project.files), 0);
  };

  /**
   * フォルダの見出し。
   *
   * 読み上げからは外す（aria-hidden）。ファイルの行がフルパスを読ませるので、
   * 場所は文字で伝わっており、見出しはその字下げを目で追うためだけにある。
   */
  ProjectEditor.prototype._dirRow = function (dir, depth) {
    var row = this._row('div', 'project-file-dir', depth);
    row.setAttribute('aria-hidden', 'true');
    row.innerHTML = '<span class="project-file-mark"></span>' +
      '<span class="project-file-dirname">' + esc(dir.name) + '</span>';
    return row;
  };

  ProjectEditor.prototype._fileRow = function (entry, depth) {
    var self = this;
    var file = entry.file;
    var row = this._row('button', 'project-file-tab' + (file.editable ? ' is-editable' : ''), depth);
    row.type = 'button';
    row.setAttribute('role', 'tab');
    row.setAttribute('data-path', file.path);
    // 色だけで編集できるかを表さない。読み上げと吹き出しには文字で入れる
    row.setAttribute('aria-label', file.path + (file.editable ? '（編集できます）' : '（参照専用）'));
    row.title = row.getAttribute('aria-label');
    row.innerHTML = '<span class="project-file-mark" aria-hidden="true"></span>' +
      '<span class="project-file-name">' + esc(entry.name) + '</span>' +
      (file.editable ? '<span class="project-file-mode">編集</span>' : '');
    row.addEventListener('click', function () { self.show(file.path); });
    return row;
  };

  ProjectEditor.prototype._row = function (tag, className, depth) {
    var row = document.createElement(tag);
    row.className = 'project-file-row ' + className;
    row.style.setProperty('--depth', String(depth));   // 字下げの段数
    return row;
  };

  ProjectEditor.prototype.show = function (path) {
    this.activePath = path;
    Array.prototype.forEach.call(this.host.querySelectorAll('.project-file-tab'), function (tab) {
      var active = tab.getAttribute('data-path') === path;
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    Array.prototype.forEach.call(this.host.querySelectorAll('.project-file-pane'), function (pane) {
      pane.hidden = pane.getAttribute('data-path') !== path;
    });
  };

  ProjectEditor.prototype.getFiles = function () {
    var result = {};
    var self = this;
    this.project.files.forEach(function (file) {
      if (file.editable) result[file.path] = self.fileEditors[file.path].getValue();
    });
    return result;
  };

  ProjectEditor.prototype.setFiles = function (files) {
    var self = this;
    this.project.files.forEach(function (file) {
      if (file.editable && Object.prototype.hasOwnProperty.call(files, file.path)) {
        self.fileEditors[file.path].setValue(files[file.path]);
      }
    });
  };

  ProjectEditor.prototype.restore = function () {
    var initial = {};
    this.project.files.forEach(function (file) { if (file.editable) initial[file.path] = file.content; });
    this.setFiles(initial);
  };

  ProjectEditor.prototype.onChange = function (handler) { this.changeHandlers.push(handler); };
  ProjectEditor.prototype.focus = function () {
    if (this.fileEditors[this.activePath]) this.fileEditors[this.activePath].focus();
  };

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

    // チップの条件は答える前に見えていないと意味がないので、注記へ入れる
    // （答え直しの正解にも払うと、表示された正解を押すだけでコインが増えてしまう）
    var note = (lesson.type === 'concept'
      ? '全問正解すると★が付きます。'
      : '★ の判定には影響しません。')
      + '何度でも答え直せますが、チップは1度目の回答で正解したときだけです。';

    host.innerHTML =
      '<div class="card card-quiz">' +
      '  <div class="quiz-head">' +
      '    <h2 class="card-h"><span class="card-h-icon">🧠</span>確認クイズ</h2>' +
      '    <span class="quiz-score">' + correct + ' / ' + quizzes.length + ' 正解' +
             (answered < quizzes.length ? '（未回答 ' + (quizzes.length - answered) + '）' : '') +
      '    </span>' +
      '  </div>' +
      '  <p class="quiz-note">' + note + '</p>' +
      quizzes.map(function (q, i) {
        return quizItemHtml(lesson, q, i, results[i]);
      }).join('') +
      '</div>';

    var buttons = host.getElementsByClassName('quiz-choice');
    Array.prototype.forEach.call(buttons, function (btn) {
      btn.addEventListener('click', function () {
        answerQuiz(Number(btn.dataset.index), Number(btn.dataset.choice));
      });
    });
    bindBookmarkButtons(host);
  }

  function quizItemHtml(lesson, quiz, index, result) {
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

    // id は復習ホームのしおりから飛んでくる先（focusBookmarkedQuiz が引く）
    return '<div class="quiz-item" id="' + quizItemId(index) + '">' +
      '  <div class="quiz-item-head">' +
      '    <div class="quiz-q"><span class="quiz-no">Q' + (index + 1) + '</span>' + renderMarkdown(quiz.question) + '</div>' +
           quizBookmarkButtonHtml(lesson, index) +
      '  </div>' +
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
    var cafeBefore = cafeLevelSnapshot();
    api('quiz', { lessonId: lessonId, index: index, choice: choice })
      .then(function (res) {
        applyDelta(res.delta);
        renderHeader();
        var lesson = findLesson(lessonId);
        if (lesson && lessonId === currentId) { renderQuiz(lesson); }
        // 概念レッスンは、この回で全問そろうと★が付く。報酬・章クリア・次への導線は
        // 問題をクリアしたときと同じ通知に出す（同じ意味の出来事を2種類の見た目にしない）。
        if (res.newStar) {
          notifyConceptReward(res, lesson, cafeBefore);
          celebrate(res);
          if (lessonId === currentId) { markLessonCleared(lesson); }
          return;
        }
        if (res.cafeAward && res.cafeAward.cash > 0) {
          showCafeRewardNotification(res.cafeAward, {
            kicker: '確認クイズ正解',
            title: 'チップを獲得しました',
            label: lesson ? lesson.title : '',
            balance: cafeState().cash
          });
        }
      })
      .catch(toastError);
  }

  /** 概念レッスンの★を、問題クリアと同じ1枚の通知で知らせる。 */
  function notifyConceptReward(res, lesson, cafeBefore) {
    if (!res.cafeAward || !(res.cafeAward.cash > 0 || res.cafeAward.cups > 0)) { return; }
    var cafeAfter = cafeLevelSnapshot();
    showCafeRewardNotification(res.cafeAward, {
      kicker: 'クイズ全問正解',
      title: '★ を獲得しました',
      label: lesson ? lesson.title : '',
      balance: cafeState().cash,
      newStar: true,
      chapterCleared: res.chapterCleared,
      chapterNumber: res.chapterNumber,
      chapterTitle: res.chapterTitle,
      chapterBonusCash: res.chapterBonusCash,
      brandBefore: cafeBefore && cafeBefore.brandBasisPoints,
      brandAfter: cafeAfter.brandBasisPoints,
      next: res.next,
      levelUp: cafeBefore && cafeAfter.level > cafeBefore.level
        ? { before: cafeBefore, after: cafeAfter }
        : null
    });
  }

  /**
   * 開いているレッスンの見出しへ★のバッジを足す。
   *
   * レッスンごと描き直すと画面の先頭へ戻ってしまい、いま答えたクイズの解説が視界から消える。
   * 変わったのはバッジだけなので、そこだけ足す。
   */
  function markLessonCleared(lesson) {
    var head = document.querySelector('.lesson-h1');
    if (!head || !lesson || !lesson.cleared || head.querySelector('.badge-clear')) { return; }
    var badge = document.createElement('span');
    badge.className = 'badge badge-clear';
    badge.textContent = '★ クリア済み';
    head.appendChild(badge);
    renderSidebar();
  }

  /** 問題文の下に置く「どんな入出力が試されるか」の表。 */
  function renderCasePreview(task) {
    if (task.type === 'runtime-lab') {
      var lab = task.runtimeLab;
      var runtimeChecks = (lab.checks || []).map(function (check) {
        return '<li>' + esc(check.label) + '</li>';
      }).join('');
      var requirements = (lab.requiredTools || []).map(function (tool) {
        return tool === 'docker-or-podman'
          ? '<code>Docker</code> または <code>Podman</code>'
          : '<code>' + esc(tool) + '</code>';
      }).join('、');
      if ((lab.requiredImages || []).length) {
        requirements += '、container image ' + lab.requiredImages.map(function (image) {
          return '<code>' + esc(image) + '</code>';
        }).join('、');
      }
      // ツールやビルドを動かすだけのlabはポートを使わない。使わない条件を書くと嘘になる。
      var portless = ['jdk-tool', 'build'];
      var needsPort = (lab.capabilities || []).some(function (name) {
        return portless.indexOf(name) < 0;
      });
      var runNote = needsPort
        ? '提出すると動的なlocalhostポートを使い、<code>' + esc(lab.command) +
          '</code>で起動・観測・停止まで実行します。'
        : '提出すると一時コピーの中で<code>' + esc(lab.command) +
          '</code>を実行し、生成物とツールの出力を検査します。';
      return '<div class="cases runtime-verification">' +
        '<div class="cases-title">実環境で検証すること（全' + lab.checkCount + '件）</div>' +
        '<ul>' + runtimeChecks + '</ul>' +
        '<p class="case-hidden-note">必要環境: ' + requirements + '。' + runNote +
        '元のlabは変更しません。</p></div>';
    }
    if (task.type === 'project') {
      return '<div class="cases project-verification">' +
        '<div class="cases-title">完了条件</div>' +
        '<ul><li>' + esc(task.project.verification) + '</li></ul>' +
        '<p class="case-hidden-note">提出すると、一時コピーしたプロジェクトで <code>' +
        esc(task.project.command) + '</code> を実行します。元のlabは変更しません。</p></div>';
    }
    if (task.type === 'artifact') {
      var requirements = (task.artifact.requirements || []).map(function (message) {
        return '<li>' + esc(message) + '</li>';
      }).join('');
      return '<div class="cases artifact-requirements">' +
        '<div class="cases-title">検証すること（全' + task.artifact.checkCount + '件）</div>' +
        '<ul>' + requirements + '</ul></div>';
    }
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
    // 復習中は保存しない。ひな形から解き直している途中の中身で、すでに通した解答を
    // 上書きしてしまわないため（提出のときもサーバ側で保存しないようにしている）。
    // ここで止めれば、自動保存・ひな形に戻す・模範解答を入れる、のどれからでも守れる。
    if (isReviewing()) { return; }
    clearTimeout(saveTimers[taskId]);
    var id = currentId;
    var lesson = findLesson(id);
    var task = lesson && findTask(lesson, taskId);
    var multiFile = task && (task.type === 'project' || task.type === 'runtime-lab');
    var value = multiFile
      ? { files: editors[taskId].getFiles() }
      : { code: editors[taskId].getValue() };
    // 同じ画面セッション内で別レッスンへ移って戻っても、サーバ再取得前のstateが
    // 古いひな形を描かないよう手元の状態も更新する。
    if (multiFile) { task.savedFiles = value.files; }
    else if (task) { task.savedCode = value.code; }
    saveTimers[taskId] = setTimeout(function () {
      var payload = { lessonId: id, taskId: taskId };
      Object.keys(value).forEach(function (key) { payload[key] = value[key]; });
      api('save', payload).catch(function () {
        // 保存に失敗しても学習は続けられるので黙って見送る（次の入力で再試行される）
      });
    }, 800);
  }

  /** 実行（採点）は1問ずつ。走っている問題のボタンだけを止める。 */
  /**
   * 走っているあいだ、その問題のボタンを止める。
   *
   * ボタンが2つあるので<b>両方止める</b>（片方だけだと、採点中に試しに実行を押せてしまい、
   * 先に返った方の結果が後から消される）。文字が変わるのは押した方だけ ―― 押していない
   * 側まで「実行中…」になると、どちらが走っているのか分からなくなる。
   *
   * @param who 押したボタン（'try' なら試しに実行、それ以外は提出）
   */
  function setBusy(taskId, on, label, who) {
    busyTask = on ? taskId : null;
    var lesson = findLesson(currentId);
    var task = lesson && findTask(lesson, taskId);
    [
      { el: document.getElementById('submitBtn-' + taskId),
        idle: submitButtonLabel(task), acting: who !== 'try' },
      { el: document.getElementById('tryBtn-' + taskId),
        idle: TRY_BUTTON_LABEL, acting: who === 'try' }
    ].forEach(function (button) {
      if (!button.el) { return; }
      button.el.disabled = on;
      button.el.textContent = on && button.acting ? (label || '実行中…') : button.idle;
    });
  }

  /**
   * 採点せずに1回だけ走らせる（「▶ 試しに実行」と ⇧⌘/Ctrl + Enter）。
   *
   * <b>記録には何も残らない。</b>★・コイン・苦手度・提出回数（`attempts`）・
   * 忘却曲線の期限はどれも動かない。書いたコードの保存は入力ごとの自動保存に任せる
   * （進捗を書く口は /api/save と /api/submit の2つに絞ってある）。
   *
   * 結果は採点と同じ枠（#result-…）へ出す。1問1枚のパネルに箱を増やすと、どちらが
   * 最後の結果なのか読めなくなるため。入力は欄の中身をそのまま渡す（末尾の改行は
   * サーバ側の JavaRunner が足す）。
   */
  function tryRun(taskId) {
    if (busyTask) { return; }
    var result = document.getElementById('result-' + taskId);
    setBusy(taskId, true, '実行中…', 'try');
    result.innerHTML = '<div class="card card-result"><div class="spinner">実行中…</div></div>';
    var stdinBox = document.getElementById('tryStdin-' + taskId);
    api('run', {
      code: editors[taskId].getValue(),
      stdin: stdinBox ? stdinBox.value : '',
      // libLessonId は同梱ライブラリの引き当て専用の参照ID（採点も保存もしない口）
      libLessonId: currentId
    })
      .then(function (res) {
        result.innerHTML =
          '<div class="card card-result card-try">' +
          '  <div class="try-result-head"><b>▶ 試しに実行した結果</b>' +
          '  <small>採点はしていません。★・苦手度・提出回数は動きません</small></div>' +
          renderRunOutput(res) +
          '</div>';
      })
      .catch(function (e) { showError(e, taskId); })
      .then(function () { setBusy(taskId, false, null, 'try'); });
  }

  function submit(taskId) {
    if (busyTask) { return; }
    var review = isReviewing();
    var result = document.getElementById('result-' + taskId);
    setBusy(taskId, true, '採点中…');
    result.innerHTML = '<div class="card card-result"><div class="spinner">採点中…</div></div>';

    var lesson = findLesson(currentId);
    var task = lesson && findTask(lesson, taskId);
    var payload = {
      lessonId: currentId,
      taskId: taskId,
      review: review
    };
    if (task && (task.type === 'project' || task.type === 'runtime-lab')) {
      payload.files = editors[taskId].getFiles();
    } else {
      payload.code = editors[taskId].getValue();
    }

    api('submit', payload)
      .then(function (res) {
        var wasCurrent = currentId;
        var cafeBefore = cafeLevelSnapshot();
        applyDelta(res.delta);
        renderHeader();
        renderSidebar();
        refreshClearedBadge(wasCurrent);
        refreshTaskStatus(wasCurrent, taskId);
        renderJudgement(res, taskId);
        if (res.allPass) {
          // 正解した直後にも、問題ブロック末尾から模範解答を確認できるようにする。
          maybeShowSolutionButton(wasCurrent, taskId);
          if (review) {
            notifyReviewReward(res, wasCurrent, taskId);
            onReviewCleared(taskId, res);
          } else {
            notifyTaskReward(res, wasCurrent, taskId, cafeBefore);
            celebrate(res);
          }
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
   * 問題ヘッダの状態（チップ・✓・パネルの緑）を、いまの state に合わせる。
   *
   * ここでレッスンを描き直さないのは、採点結果をこれから読むところだからである。
   * 描き直すと画面の先頭へ戻り、いま出た結果が視界から消える。変わるのは
   * この問題の状態だけなので、そこだけ差し替える。
   */
  function refreshTaskStatus(lessonId, taskId) {
    if (lessonId !== currentId) { return; }
    var lesson = findLesson(lessonId);
    var task = lesson && findTask(lesson, taskId);
    var status = document.getElementById('taskStatus-' + taskId);
    if (!task || !status) { return; }
    var cleared = currentView !== 'reviewTask' && !!task.cleared;
    status.innerHTML = taskStatusHtml(task, cleared);
    var block = document.getElementById('task-' + taskId);
    if (block) { block.classList.toggle('is-cleared', cleared); }
    var mark = block && block.querySelector('.task-mark');
    if (mark) { mark.textContent = cleared ? '✓' : ''; }
  }

  function renderJudgement(res, taskId) {
    var result = document.getElementById('result-' + taskId);
    var html = '<div class="card card-result ' + (res.allPass ? 'ok' : 'ng') + '">';

    if (res.runtimeLab) {
      if (!res.available || !res.started) {
        html += '<h2 class="card-h"><span class="card-h-icon">🧰</span>runtime labを開始できませんでした</h2>' +
          '<p class="result-lead">コードの不正解ではありません。必要なローカル環境を準備してから再実行してください。</p>' +
          '<div class="hint-box">' + esc(res.error || '実行環境を起動できませんでした。') + '</div>';
        result.innerHTML = html + '</div>';
        return;
      }
      html += '<h2 class="card-h"><span class="card-h-icon">' + (res.allPass ? '🎉' : '🔬') + '</span>' +
        (res.allPass ? 'クリア！実環境の検証に合格' :
          (res.timedOut ? '制限時間を超えたため、labを停止しました' :
            '実環境の結果: ' + res.passedCount + ' / ' + res.checks.length + ' 通過')) + '</h2>' +
        '<p class="result-lead">終了コード: ' + res.exitCode + '　·　' + res.durationMs + 'ms</p>' +
        '<ul class="case-results runtime-check-results">';
      (res.checks || []).forEach(function (check) {
        html += '<li class="case-result ' + (check.pass ? 'pass' : 'fail') + '">' +
          '<div class="case-result-head"><span class="case-mark">' + (check.pass ? '✅' : '❌') + '</span>' +
          '<span class="case-label">' + esc(check.message) + '</span></div></li>';
      });
      html += '</ul><div class="out-label">runtime lab出力</div><pre class="out-pre project-output">' +
        (res.output ? esc(res.output) : '<em>（出力はありません）</em>') + '</pre>';
      if (res.truncated) html += '<div class="out-note">出力が長いため、途中で表示を打ち切りました。</div>';
      result.innerHTML = html + '</div>';
      return;
    }

    if (res.project) {
      if (!res.started) {
        html += '<h2 class="card-h"><span class="card-h-icon">🛠</span>テストを開始できませんでした</h2>' +
          '<div class="hint-box">' + esc(res.error || '検証コマンドを起動できませんでした。') + '</div>';
        result.innerHTML = html + '</div>';
        return;
      }
      html += '<h2 class="card-h"><span class="card-h-icon">' + (res.allPass ? '🎉' : '🔍') + '</span>' +
        (res.allPass ? 'クリア！プロジェクトのテストに合格' :
          (res.timedOut ? '制限時間を超えたため停止しました' : 'テストが失敗しました')) + '</h2>' +
        '<p class="result-lead">終了コード: ' + res.exitCode + '　·　' + res.durationMs + 'ms</p>';
      if (!res.allPass) {
        html += '<p class="result-lead">最初の失敗から読み、変更したファイルと受け入れ条件を対応させましょう。</p>';
      }
      html += '<div class="out-label">ビルド・テスト出力</div>' +
        '<pre class="out-pre project-output">' +
        (res.output ? esc(res.output) : '<em>（出力はありません）</em>') + '</pre>';
      if (res.truncated) html += '<div class="out-note">出力が長いため、途中で表示を打ち切りました。</div>';
      result.innerHTML = html + '</div>';
      return;
    }

    if (res.artifact) {
      if (!res.syntaxValid) {
        html += '<h2 class="card-h"><span class="card-h-icon">🛠</span>ファイルを読み取れませんでした</h2>'
          + '<p class="result-lead">まず形式上の誤りを直しましょう。内容の検証は、その後に行います。</p>'
          + '<div class="hint-box">' + esc(res.syntaxError) + '</div>';
        result.innerHTML = html + '</div>';
        return;
      }
      html += '<h2 class="card-h"><span class="card-h-icon">' + (res.allPass ? '🎉' : '🔍') + '</span>'
        + (res.allPass ? 'クリア！構成はすべて有効です'
          : '結果: ' + res.passedCount + ' / ' + res.checks.length + ' 通過') + '</h2>';
      if (!res.allPass) {
        html += '<p class="result-lead">通らなかった項目を確認し、設定の要素・値・置き場所を見直しましょう。</p>';
      }
      html += '<ul class="case-results artifact-check-results">';
      res.checks.forEach(function (check) {
        html += '<li class="case-result ' + (check.pass ? 'pass' : 'fail') + '">' +
          '<div class="case-result-head"><span class="case-mark">' + (check.pass ? '✅' : '❌') + '</span>' +
          '<span class="case-label">' + esc(check.message) + '</span></div></li>';
      });
      html += '</ul>';
      result.innerHTML = html + '</div>';
      return;
    }

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

    if (res.allPass && res.optionalComplete) {
      html += '<div class="all-done">任意の発展課題を完了しました。章クリアや★の分母には影響しません。</div>';
    } else if (res.allPass && !res.next) {
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
    // 復習では畳んで置く。前に開いた答えが見えたままだと「いま解けるか」を測れない
    // （解説を畳むのと同じ理由。読み直したいときは自分で開ける）。
    (task.revealedHints || []).forEach(function (text, index) {
      appendHint(task.id, index, text, isReviewing() ? 'closed' : null);
    });
    maybeShowSolutionButton(lesson.id, task.id);
  }

  /**
   * 開示済みのヒントを1枚足す。
   *
   * fold に 'closed' / 'open' を渡すと、開き閉じできる形（&lt;details&gt;）で置く。
   * 復習では前に開いたヒントを 'closed'、その場で押して出したヒントを 'open' にする
   * （押した相手はすぐ読みたい。読み終わったら畳める）。
   */
  function appendHint(taskId, index, text, fold) {
    var host = document.getElementById('hints-' + taskId);
    if (host.querySelector('[data-hint="' + index + '"]')) { return; }
    var box = document.createElement(fold ? 'details' : 'div');
    box.className = 'card card-hint' + (fold ? ' card-hint-fold' : '');
    if (fold === 'open') { box.open = true; }
    box.setAttribute('data-hint', String(index));
    var head = fold ? 'summary' : 'div';
    box.innerHTML = '<' + head + ' class="hint-no">ヒント ' + (index + 1) + '</' + head + '>'
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
        appendHint(taskId, res.index, res.text, isReviewing() ? 'open' : null);
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
          var solutionBody;
          if (task.type === 'project' || task.type === 'runtime-lab') {
            solutionBody = Object.keys(res.files || {}).map(function (path) {
              var workspace = task.type === 'runtime-lab' ? task.runtimeLab : task.project;
              var file = workspace.files.find(function (item) { return item.path === path; });
              var highlighted = file && file.language === 'java'
                ? hlJava(res.files[path]) : esc(res.files[path]);
              return '<div class="project-solution-file"><code>' + esc(path) + '</code></div>' +
                '<pre class="code"><code>' + highlighted + '</code></pre>';
            }).join('');
          } else {
            solutionBody = '<pre class="code"><code>' +
              (task.type === 'artifact' ? esc(res.solution) : hlJava(res.solution)) + '</code></pre>';
          }
          row.innerHTML = '<div class="card card-solution">'
            + '<div class="solution-head">📖 模範解答'
            + '<button class="ghost-btn small" data-role="copy">エディタに入れる</button></div>'
            + solutionBody
            + '<p class="solution-note">写すだけでなく、1行ずつ「なぜそう書くのか」を'
            + '声に出して説明できるか試してみましょう。</p></div>';
          row.querySelector('[data-role="copy"]').addEventListener('click', function () {
            if (task.type === 'project' || task.type === 'runtime-lab') {
              editors[taskId].setFiles(res.files || {});
            } else {
              editors[taskId].setValue(res.solution);
            }
            editors[taskId].focus();
            scheduleSave(taskId);
          });
        })
        .catch(function (e) { showError(e, taskId); });
    });
  }

  // ---------------------------------------------------------- 右上通知とお祝い演出

  /**
   * 店構えとブランド倍率を、進捗の差分を適用する前後で比較するための小さなスナップショット。
   *
   * ブランド倍率は「章クリアでどれだけ伸びたか」を通知に出すために持つ。サーバは現在値しか
   * 返さないので、差分を当てる前の値をここで控えておかないと伸び幅が分からない。
   */
  function cafeLevelSnapshot() {
    var cafe = cafeState();
    return {
      level: Number(cafe.level || 1),
      title: cafe.levelTitle || '屋台カフェ',
      brandBasisPoints: Number(cafe.brandMultiplierBasisPoints || 10000)
    };
  }

  /** 初回クリアの報酬と、その瞬間に起きた店構えの変化を1枚の通知へまとめる。 */
  function notifyTaskReward(res, lessonId, taskId, cafeBefore) {
    if (!res.newStar || !res.cafeAward
        || !(res.cafeAward.cash > 0 || res.cafeAward.cups > 0)) { return; }
    var lesson = findLesson(lessonId);
    var task = lesson && findTask(lesson, taskId);
    var label = lesson ? lesson.title : '';
    if (task && lesson && lesson.taskCount > 1) { label += '（' + task.label + '）'; }
    var cafeAfter = cafeLevelSnapshot();
    var levelUp = cafeBefore && cafeAfter.level > cafeBefore.level
      ? { before: cafeBefore, after: cafeAfter }
      : null;

    showCafeRewardNotification(res.cafeAward, {
      kicker: '注文完了',
      title: '報酬を獲得しました',
      label: label,
      balance: cafeState().cash,
      newStar: true,
      chapterCleared: res.chapterCleared,
      chapterNumber: res.chapterNumber,
      chapterTitle: res.chapterTitle,
      chapterBonusCash: res.chapterBonusCash,
      brandBefore: cafeBefore && cafeBefore.brandBasisPoints,
      brandAfter: cafeAfter.brandBasisPoints,
      next: res.next,
      levelUp: levelUp
    });
  }

  /**
   * 復習で通したときの報酬を通知する。
   *
   * ★は動かないので紙吹雪も「次の問題」も出さない。コインだけの短い1枚にしてある。
   * 期限が来ていた回は満額、期限前の「早めの復習」は小額（1日の本数を使い切ると
   * サーバが0コインを返す）。**額はサーバが決めるので、ここは0でなければ出す** ――
   * 0の回に通知を出すと「もらえる」という約束と食い違うが、逆に入った回を黙ると
   * 復習の実入りが画面のどこにも出ない。
   */
  function notifyReviewReward(res, lessonId, taskId) {
    if (!res.cafeAward || !(res.cafeAward.cash > 0)) { return; }
    var lesson = findLesson(lessonId);
    var task = lesson && findTask(lesson, taskId);
    var label = lesson ? lesson.title : '';
    if (task && lesson && lesson.taskCount > 1) { label += '（' + task.label + '）'; }
    showCafeRewardNotification(res.cafeAward, {
      kicker: '復習の注文',
      title: '思い出せました',
      label: label,
      balance: cafeState().cash
    });
  }

  // ── 報酬の通知を出すかどうか（設定パネルの「報酬の通知」）──────────────
  // 既定は表示する。localStorage は保存先としてしか使わず、今の値はこの変数が持つ
  // （読むたびに localStorage を見る形にすると、保存できない環境で「書き込み失敗 →
  // 読み直すと元の値 → 画面が変わらない」になる。theme.js と同じ作り）。
  var REWARD_TOAST_KEY = 'jq-reward-toast';
  var rewardToastOn = true;

  (function () {
    var saved = null;
    try { saved = localStorage.getItem(REWARD_TOAST_KEY); } catch (e) { /* 使えなくても既定で動く */ }
    rewardToastOn = saved !== '0';   // 未設定・読めない＝表示する
  })();

  function setRewardToastOn(on) {
    rewardToastOn = !!on;
    try { localStorage.setItem(REWARD_TOAST_KEY, rewardToastOn ? '1' : '0'); }
    catch (e) { /* 保存できなくても、このセッションのあいだは効く */ }
  }

  /**
   * 金額を主役にした報酬通知。問題文を隠しすぎない短さで出し、ホバー中は時間を止める。
   *
   * 章クリアもこの1枚に載せる（以前は全画面のお祝いで手が止まっていた）。クリア直後は
   * 模範解答を見たりその場に残りたいことがあるので、画面は塞がず、次の章へ進むかどうかも
   * 自分で選べるようにしている。
   */
  function showCafeRewardNotification(award, options) {
    options = options || {};
    var events = award.itemEvents || [];
    var chapter = options.chapterCleared
      ? {
        number: options.chapterNumber,
        title: options.chapterTitle || '',
        bonusCash: Number(options.chapterBonusCash || 0),
        brandBefore: Number(options.brandBefore || 0),
        brandAfter: Number(options.brandAfter || 0),
        next: options.next || null
      }
      : null;
    var notification = {
      type: 'reward',
      kicker: options.kicker || 'JAVA CAFÉ',
      title: options.title || '報酬を獲得',
      label: options.label || '',
      cash: Number(award.cash || 0),
      cups: Number(award.cups || 0),
      balance: Number(options.balance || 0),
      newStar: !!options.newStar,
      chapter: chapter,
      levelUp: options.levelUp || null,
      events: events,
      // 章クリアは節目なので自動では消さない（0=時間で消さない）。それ以外は基本4.5秒、
      // 補足が多い通知は最大7秒まで延ばす。読みたいときはホバーで止まる。
      duration: chapter
        ? 0
        : Math.min(7000, 4500 + events.length * 500 + (options.levelUp ? 900 : 0))
    };
    // 消えたあとに読み返せるよう、出すのと同じ場所で控えも取る（→ コインの獲得履歴）
    recordCoinLog(notification);
    // 設定で切れるのは「その場に出す通知」だけ。控えは必ず残す（消しても 📒 で読み返せる）。
    // 章クリアはこの1枚にしか「次の章へ進む」導線が無いので、切っていても出す。
    if (!rewardToastOn && !chapter) { return; }
    enqueueNotification(notification);
  }

  /** 従来の短い操作結果も同じ右上通知へ送り、同時発生時の上書きを防ぐ。 */
  function toast(message) {
    enqueueNotification({ type: 'message', message: String(message), duration: 5000 });
  }

  function enqueueNotification(notification) {
    notificationQueue.push(notification);
    // 自動で消さない通知（章クリア）が出たままだと後続がいつまでも待たされる。次が来たら譲る。
    if (activeNotification && !activeNotification.duration) { dismissNotification(); }
    showNextNotification();
  }

  function showNextNotification() {
    if (activeNotification || !notificationQueue.length) { return; }
    activeNotification = notificationQueue.shift();
    notificationRemainingMs = activeNotification.duration;
    renderNotification(activeNotification);

    var el = document.getElementById('toast');
    // classを付ける前の位置を確定し、右から入る動きを毎回発生させる。
    void el.offsetWidth;
    el.classList.add('show');
    startNotificationTimer();
  }

  function renderNotification(notification) {
    var el = document.getElementById('toast');
    el.className = 'toast toast-' + notification.type;
    if (notification.type === 'reward') {
      var stats = [];
      if (notification.newStar) {
        stats.push('<div class="toast-stat"><span aria-hidden="true">★</span>'
          + '<small>スター</small><b>+1</b></div>');
      }
      if (notification.cups > 0) {
        stats.push('<div class="toast-stat"><span aria-hidden="true">☕</span>'
          + '<small>提供したコーヒー</small><b>+' + numberText(notification.cups) + '杯</b></div>');
      }
      // 章クリアで実際に増えたものを数字で出す。以前は「ボーナスと倍率も伸びています」と
      // 書いていたが、いくら増えたのか分からず読んでも判断に使えなかった。
      var chapterDetails = [];
      if (notification.chapter && notification.chapter.bonusCash > 0) {
        chapterDetails.push('🪙 章制覇ボーナス +'
          + numberText(notification.chapter.bonusCash) + 'コイン<small>（上の獲得コインに含む）</small>');
      }
      var brandGain = notification.chapter
        ? brandGainPercentText(notification.chapter.brandBefore, notification.chapter.brandAfter)
        : '';
      if (brandGain) {
        chapterDetails.push('📈 ブランド倍率 ×'
          + multiplierText(notification.chapter.brandBefore) + ' → ×'
          + multiplierText(notification.chapter.brandAfter)
          + '<small>（これからのコイン報酬が +' + brandGain + '%）</small>');
      }
      var chapterHtml = notification.chapter
        ? '<div class="toast-chapter"><span>🎉 第'
          + numberText(notification.chapter.number) + '章クリア！</span>'
          + '<b>「' + esc(notification.chapter.title) + '」を全問クリアしました。</b>'
          + chapterDetails.map(function (line) {
            return '<em>' + line + '</em>';
          }).join('') + '</div>'
        : '';
      var actionHtml = notification.chapter && notification.chapter.next
        ? '<div class="toast-actions"><button class="primary-btn toast-action" type="button"'
          + ' data-role="next">次の章へ進む</button></div>'
        : '';
      var levelHtml = notification.levelUp
        ? '<div class="toast-level-up"><span>店構えが成長しました</span><b>Lv.'
          + numberText(notification.levelUp.before.level) + ' '
          + esc(notification.levelUp.before.title) + ' → Lv.'
          + numberText(notification.levelUp.after.level) + ' '
          + esc(notification.levelUp.after.title) + '</b></div>'
        : '';
      var eventsHtml = notification.events.length
        ? '<div class="toast-events">' + notification.events.map(function (event) {
          return '<span>✨ ' + esc(event) + '</span>';
        }).join('') + '</div>'
        : '';
      el.innerHTML = '<div class="toast-head">'
        + '<div class="toast-title"><small>' + esc(notification.kicker)
        + '</small><strong>' + esc(notification.title) + '</strong></div>'
        + toastCloseButtonHtml() + '</div>'
        + (notification.label ? '<p class="toast-label">' + esc(notification.label) + '</p>' : '')
        + '<div class="toast-earned"><span class="toast-earned-icon" aria-hidden="true">🪙</span>'
        + '<div><small>獲得コイン</small><strong><b>+' + numberText(notification.cash)
        + '</b><span>コイン</span></strong></div></div>'
        + (stats.length ? '<div class="toast-stats">' + stats.join('') + '</div>' : '')
        + chapterHtml + levelHtml + eventsHtml
        + '<div class="toast-balance"><span>現在の残高</span><b>'
        + numberText(notification.balance) + 'コイン</b></div>'
        + actionHtml;
    } else {
      el.innerHTML = '<div class="toast-message-body"><span>' + esc(notification.message) + '</span>'
        + toastCloseButtonHtml() + '</div>';
    }
    bindNotificationEvents(el, notification);
  }

  function toastCloseButtonHtml() {
    return '<button class="toast-close" type="button" aria-label="通知を閉じる">×</button>';
  }

  function bindNotificationEvents(el, notification) {
    var close = el.querySelector('.toast-close');
    if (close) { close.addEventListener('click', dismissNotification); }
    var next = el.querySelector('[data-role="next"]');
    if (next) {
      // 行き先は描いた通知から取る（閉じたあとに activeNotification は消えている）
      var target = notification.chapter ? notification.chapter.next : null;
      next.addEventListener('click', function () {
        dismissNotification();
        goToTask(target);
      });
    }
    el.onmouseenter = pauseNotificationTimer;
    el.onmouseleave = resumeNotificationTimer;
    el.onfocusin = pauseNotificationTimer;
    el.onfocusout = resumeNotificationTimer;
  }

  function startNotificationTimer() {
    clearTimeout(notificationTimer);
    // 0 は「時間では消さない」。閉じるボタンか、次の通知が来たときだけ消える。
    if (!notificationRemainingMs) { notificationTimer = null; return; }
    notificationStartedAt = Date.now();
    notificationTimer = setTimeout(dismissNotification, notificationRemainingMs);
  }

  function pauseNotificationTimer() {
    if (!activeNotification || !notificationTimer) { return; }
    clearTimeout(notificationTimer);
    notificationTimer = null;
    notificationRemainingMs = Math.max(800,
      notificationRemainingMs - (Date.now() - notificationStartedAt));
  }

  function resumeNotificationTimer() {
    if (activeNotification && !activeNotification.closing && !notificationTimer) {
      startNotificationTimer();
    }
  }

  function dismissNotification() {
    if (!activeNotification || activeNotification.closing) { return; }
    activeNotification.closing = true;
    clearTimeout(notificationTimer);
    notificationTimer = null;
    document.getElementById('toast').classList.remove('show');
    setTimeout(function () {
      activeNotification = null;
      showNextNotification();
    }, 300);
  }

  // ------------------------------------------- コインの獲得履歴（ヘッダのコインを押す）

  /*
   * 報酬の通知は数秒で消える。手を動かしている最中に出るものなので、読む前に消えたり、
   * 次の通知に譲って消えたりする。ヘッダのコイン（🪙）を押せば、いつ・何で・いくら
   * 受け取ったかを新しい順に読み返せるようにしてある。
   *
   * 置き場所は localStorage である。サーバの progress.json が持っているのは残高と累計だけで、
   * 1件ずつの内訳は通知を組み立てるこの画面にしか無い。ここをサーバへ移すと進捗ファイルの形と
   * economyVersion の面倒が増えるいっぽう、読み返すための控えなので、消えても学習の記録は
   * 1つも失われない。だから画面側に置く（＝別のブラウザで開くと履歴は空から始まる）。
   *
   * 使ったコイン（設備・店舗・投資）は入れない。これは出納帳ではなく「獲得の履歴」で、
   * 混ぜると残高の計算に見えてしまう。見出しと末尾の断りでそう分かるようにしてある。
   */

  function loadCoinLog() {
    if (coinLog) { return coinLog; }
    coinLog = [];
    try {
      var saved = JSON.parse(localStorage.getItem(COIN_LOG_KEY) || '[]');
      if (Array.isArray(saved)) {
        coinLog = saved
          .filter(function (entry) { return entry && typeof entry === 'object'; })
          .slice(0, COIN_LOG_LIMIT);
      }
    } catch (e) {
      coinLog = [];   // 壊れていても空から作り直すだけ。学習の記録とは無関係
    }
    return coinLog;
  }

  function saveCoinLog() {
    try { localStorage.setItem(COIN_LOG_KEY, JSON.stringify(coinLog)); }
    catch (e) { /* 保存できなくても、開いている間は読み返せる */ }
  }

  /** 報酬の通知1枚を履歴へ積む。新しいものが先頭。 */
  function recordCoinLog(notification) {
    var log = loadCoinLog();
    log.unshift({
      at: Date.now(),
      reason: notification.kicker || '報酬',
      label: notification.label || '',
      cash: Number(notification.cash || 0),
      cups: Number(notification.cups || 0),
      newStar: !!notification.newStar,
      chapter: notification.chapter ? Number(notification.chapter.number || 0) : 0,
      events: (notification.events || []).slice(0, 4)
    });
    if (log.length > COIN_LOG_LIMIT) { log.length = COIN_LOG_LIMIT; }
    saveCoinLog();
    paintCoinLog();
  }

  /** リセットで残高が0に戻るので、履歴も一緒に捨てる（残っていると勘定が合わない） */
  function clearCoinLog() {
    coinLog = [];
    try { localStorage.removeItem(COIN_LOG_KEY); } catch (e) { /* 同上 */ }
    closeCoinLog();
  }

  /**
   * 1日の区切り（時）。<b>0時ではなく午前4時で切る</b>ので、深夜0〜3時台の学習は
   * 前日ぶんとして数える。
   *
   * 数字はサーバから受け取る（`LearningDay.START_HOUR` → /api/state の dayStartHour）。
   * 画面にも書くと片方だけ動いて「今日」が食い違うため、ここは読むだけにする。
   * 既定の4は、状態を受け取る前に呼ばれたときの受け皿である。
   */
  function dayStartHour() {
    var hour = state && state.progress ? Number(state.progress.dayStartHour) : NaN;
    return isNaN(hour) ? 4 : hour;
  }

  /**
   * 「今日ぶん」を数えるための日付の鍵。
   *
   * 区切りぶん戻してから年月日を読む。こうすると 8/22 の 2:30 は 8/21 の鍵になり、
   * 前の晩に受け取ったぶんと同じ「今日」に入る（連続日数・復習の期限と同じ境目）。
   */
  function coinLogDayKey(date) {
    var shifted = new Date(date.getTime() - dayStartHour() * 3600000);
    return shifted.getFullYear() + '-' + shifted.getMonth() + '-' + shifted.getDate();
  }

  /**
   * 時刻の見せ方。日付を毎行に出すと、その日のうちに何度も受け取る使い方では
   * 同じ日付が並ぶだけで読みにくい。今日と昨日は言葉にして、それより前だけ日付を出す。
   *
   * <b>「今日」も区切り（午前4時）で数える。</b>時刻そのものは時計どおりに出すので、
   * 朝4時を過ぎてから開くと、その晩の 2:30 のぶんは「昨日 2:30」と出る。
   * 昨日を暦の日付から作らないのは、区切りをまたぐと1日ずれるためである。
   */
  function coinLogTimeText(at) {
    var when = new Date(Number(at) || 0);
    if (!Number(at) || isNaN(when.getTime())) { return '時刻不明'; }
    var hm = when.getHours() + ':' + ('0' + when.getMinutes()).slice(-2);
    var now = new Date();
    if (coinLogDayKey(when) === coinLogDayKey(now)) { return '今日 ' + hm; }
    var yesterday = new Date(now.getTime() - 86400000);
    if (coinLogDayKey(when) === coinLogDayKey(yesterday)) { return '昨日 ' + hm; }
    return (when.getMonth() + 1) + '/' + when.getDate() + ' ' + hm;
  }

  function coinLogHtml() {
    var log = loadCoinLog();
    var today = coinLogDayKey(new Date());
    var todayCash = log.reduce(function (sum, entry) {
      return coinLogDayKey(new Date(Number(entry.at) || 0)) === today
        ? sum + Number(entry.cash || 0)
        : sum;
    }, 0);

    var items = log.map(function (entry) {
      var marks = [];
      if (entry.newStar) { marks.push('★ +1'); }
      if (Number(entry.cups) > 0) { marks.push('☕ +' + cafeNumberText(entry.cups) + '杯'); }
      var chapterMark = Number(entry.chapter) > 0
        ? '<span class="coin-log-mark chapter">🎉 第' + numberText(entry.chapter) + '章クリア</span>'
        : '';
      var events = (entry.events || []).map(function (event) {
        return '<span>✨ ' + esc(String(event)) + '</span>';
      }).join('');
      return '<li class="coin-log-item">'
        + '<div class="coin-log-line">'
        + '<span class="coin-log-when">' + esc(coinLogTimeText(entry.at)) + '</span>'
        + '<b class="coin-log-cash">+' + cafeNumberText(entry.cash) + 'コイン</b>'
        + '</div>'
        + '<div class="coin-log-reason">' + esc(entry.reason || '報酬') + '</div>'
        + (entry.label ? '<p class="coin-log-label">' + esc(entry.label) + '</p>' : '')
        + (marks.length || chapterMark
          ? '<div class="coin-log-marks">'
            + marks.map(function (mark) {
              return '<span class="coin-log-mark">' + esc(mark) + '</span>';
            }).join('') + chapterMark + '</div>'
          : '')
        + (events ? '<div class="coin-log-events">' + events + '</div>' : '')
        + '</li>';
    }).join('');

    return '<div class="coin-log-top">'
      + '<div class="coin-log-title"><small>JAVA CAFÉ</small>'
      + '<strong>コインの獲得履歴</strong></div>'
      + '<button class="coin-log-close" type="button" data-role="close"'
      + ' aria-label="履歴を閉じる">×</button></div>'
      + '<div class="coin-log-sum">'
      + '<div class="coin-log-sum-cell today"><small>今日の獲得</small><b>+'
      + cafeNumberText(todayCash) + 'コイン</b></div>'
      + '<div class="coin-log-sum-cell"><small>現在の残高</small><b>'
      + cafeNumberText(cafeState().cash) + 'コイン</b></div>'
      + '</div>'
      + (log.length
        ? '<ul class="coin-log-list">' + items + '</ul>'
        : '<p class="coin-log-empty">まだ記録がありません。問題をクリアすると、'
          + '受け取った報酬がここに残ります。</p>')
      + '<p class="coin-log-note">学習で受け取ったぶんを新しい順に'
      + numberText(COIN_LOG_LIMIT) + '件まで残します（使ったコインは含みません）。'
      + 'この履歴はこのブラウザにだけ保存されます。</p>';
  }

  /** 開いている履歴を描き直す。閉じているときは何もしない。 */
  function paintCoinLog() {
    var pop = document.getElementById('coinLog');
    if (!pop || pop.hidden) { return; }
    // 開いたまま報酬が来ることもある。中に居たフォーカスは描き直しで消えるので戻す。
    var hadFocus = pop.contains(document.activeElement);
    var scroll = pop.querySelector('.coin-log-list');
    var top = scroll ? scroll.scrollTop : 0;
    pop.innerHTML = coinLogHtml();
    var list = pop.querySelector('.coin-log-list');
    if (list) { list.scrollTop = top; }
    if (hadFocus) {
      var close = pop.querySelector('[data-role="close"]');
      if (close) { close.focus(); }
    }
  }

  /**
   * 進捗を消した跡に控えだけが残っていたら捨てる。リセットは画面のボタンだけでなく
   * `progress.json` を手で削除する道も案内してある（→ docs/guide.md「進捗のリセット」）ので、
   * clearCoinLog を通らずに残高が0へ戻ることがある。累計獲得が0なら1コインも受け取っていない
   * 進捗ということなので、履歴が残っていればそれは前の進捗のものである。
   */
  function dropStaleCoinLog() {
    if (Number(cafeState().lifetimeCash || 0) > 0 || !loadCoinLog().length) { return; }
    clearCoinLog();
  }

  function closeCoinLog() {
    var pop = document.getElementById('coinLog');
    var btn = document.getElementById('statCafe');
    if (pop) { pop.hidden = true; }
    if (btn) { btn.setAttribute('aria-expanded', 'false'); }
  }

  /**
   * ヘッダのコインを押したときの開閉。箱は body 直下にあるので、位置は自分で測って
   * ボタンの真下へ寄せる（設定パネル setupSettings と同じ作り）。
   */
  function setupCoinLog() {
    var btn = document.getElementById('statCafe');
    var pop = document.getElementById('coinLog');
    if (!btn || !pop) { return; }

    function isOpen() { return !pop.hidden; }

    function place() {
      var r = btn.getBoundingClientRect();
      pop.style.top = Math.round(r.bottom + 8) + 'px';
      pop.style.left = 'auto';
      // 右端をボタンの右端にそろえ、画面外に出ないよう最低8pxは残す
      pop.style.right = Math.max(8, Math.round(window.innerWidth - r.right)) + 'px';
    }

    function open() {
      pop.innerHTML = coinLogHtml();
      pop.hidden = false;
      btn.setAttribute('aria-expanded', 'true');
      place();
      var close = pop.querySelector('[data-role="close"]');
      if (close) { close.focus(); }
    }

    btn.addEventListener('click', function (e) {
      e.stopPropagation();   // 直後の document クリックで閉じてしまわないように
      if (isOpen()) { closeCoinLog(); } else { open(); }
    });

    pop.addEventListener('click', function (e) {
      var hit = e.target.closest ? e.target.closest('[data-role="close"]') : null;
      if (!hit) { return; }
      closeCoinLog();
      btn.focus();
    });

    // 外側クリックと Esc で閉じる。中の一覧をスクロールしても閉じないよう、箱の中は除く。
    document.addEventListener('click', function (e) {
      if (isOpen() && !pop.contains(e.target) && !btn.contains(e.target)) { closeCoinLog(); }
    });
    document.addEventListener('keydown', function (e) {
      if (!isOpen() || e.key !== 'Escape') { return; }
      e.preventDefault();
      closeCoinLog();
      btn.focus();
    });
    window.addEventListener('resize', function () { if (isOpen()) { place(); } });
  }

  /**
   * 章クリアのお祝いは報酬通知（showCafeRewardNotification）へ移した。画面を塞ぐのは
   * 学習の最後の1回、全問制覇だけにする。クリア直後は模範解答を読み返したり、その問題に
   * 残って考え直したりしたいので、進むかどうかを通知の中から選べればそれで足りる。
   *
   * 紙吹雪は章クリアでも降らせる。操作を邪魔しない層（.confetti）に置いてあるので、
   * 降っている間もそのまま画面を触れる。
   */
  function celebrate(res) {
    // 全問制覇は、最後の1問を初めてクリアした瞬間だけ祝う（クリア後の再提出では出さない）
    if (res.allChaptersCleared && res.newStar) {
      // 章数・問題数はカリキュラムから取る（章を足しても文言が古びないように）
      showOverlay('🏆', '全問制覇！',
        '全' + state.chapters.length + '章 ' + state.totalTasks + '問、すべてクリアです。'
        + 'ここまで自分の手で書いてきたことが、そのまま力になっています。');
    } else if (res.chapterCleared) {
      dropConfetti();
    }
  }

  /** 全画面のお祝い。行き先は用意しない（章クリアの「次の章へ進む」は通知側へ移した）。 */
  function showOverlay(emoji, title, body) {
    var overlay = document.getElementById('overlay');
    document.getElementById('overlayEmoji').textContent = emoji;
    document.getElementById('overlayTitle').textContent = title;
    document.getElementById('overlayBody').textContent = body;

    var btn = document.getElementById('overlayBtn');
    btn.textContent = '閉じる';
    overlay.hidden = false;
    dropConfetti();

    btn.onclick = function () { overlay.hidden = true; };
  }

  function dropConfetti() {
    var host = document.getElementById('confetti');
    clearTimeout(confettiTimer);
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
    // 降り終わった紙吹雪は片付ける。全画面のお祝いの中ではなく画面全体にかぶせる層に
    // なったので、置きっぱなしにすると次のお祝いまでDOMに残り続ける。
    // 待つ時間は「最も遅い開始(0.6s) + 最も長い落下(2.8s)」より少しだけ長く。
    confettiTimer = setTimeout(function () { host.innerHTML = ''; }, 3600);
  }

  // ------------------------------------------------------------------ 画面遷移

  /**
   * URLのハッシュから、いま表示すべき画面を決める。知らないIDなら学習ホームに落とす。
   *
   * 復習の1問（#review/3-2/1）は、クリア済みの問題だけ受け付ける。手で書いた
   * URLで未クリアの問題が復習として開くと、ひな形で始まって保存もされない画面に
   * なってしまうため、その場合は復習ホームへ落とす。
   */
  function routeFromHash() {
    if (state && state.progress && state.progress.onboardingRequired) {
      return { view: 'menu', id: null, taskId: null };
    }
    var hash = location.hash.replace(/^#/, '');
    if (hash === 'cafe') { return { view: 'cafe', id: null, taskId: null }; }
    if (hash === 'review') { return { view: 'review', id: null, taskId: null }; }
    if (hash.indexOf('review/') === 0) {
      var parts = hash.substring('review/'.length).split('/');
      var lesson = findLesson(parts[0]);
      var task = lesson && parts[1] ? findTask(lesson, parts[1]) : null;
      if (task && task.cleared) {
        return { view: 'reviewTask', id: parts[0], taskId: parts[1] };
      }
      return { view: 'review', id: null, taskId: null };
    }
    if (hash && findLesson(hash)) { return { view: 'lesson', id: hash, taskId: null }; }
    return { view: 'menu', id: null, taskId: null };
  }

  /** 復習で1問を解いている画面か。保存を止めるかどうかの判断にも使う。 */
  function isReviewing() {
    return currentView === 'reviewTask';
  }

  /**
   * 画面を描き替える前に、レッスンで読んでいた位置を控える。
   *
   * 控える相手は currentId ではなく paintedLessonId（いま画面に出ているレッスン）である。
   * ここへ来た時点で currentId はもう行き先を指しているので、currentId で控えると
   * カフェ（null）や移動先のレッスンの位置として覚えてしまう。
   *
   * 覚えるのは直前に離れた1つだけ。別のレッスンを開けば差し替わるので、読み直したくて
   * 開いたレッスンが途中から始まることはない。
   *
   * 併せて「📚 学習」の帰り先も決める。レッスンから直接カフェへ寄り道したときだけ、
   * そのレッスンへ帰す。自分でホームや復習へ移ったのなら、帰り先はホームのままでよい。
   * <b>復習を解いている途中の寄り道も同じように帰す</b>（2026-08-22）。以前は復習の画面が
   * 目印（paintedLessonId）を置いていなかったので、カフェから「📚 学習」を押すと
   * 学習ホームへ出てしまい、解いていたセットまで自分で辿り直すことになっていた。
   */
  function rememberLessonScroll() {
    var painted = paintedLessonId;
    var review = paintedReview;
    paintedLessonId = null;
    paintedReview = null;
    cafeReturnLessonId = painted && currentView === 'cafe' ? painted : null;
    cafeReturnReview = review && currentView === 'cafe' ? review : null;
    if (!painted) { return; }
    lessonScroll = { lessonId: painted, top: document.getElementById('content').scrollTop };
  }

  /** 現在の画面状態に合わせて描く。 */
  function render() {
    // 描き替えると位置が失われるので、中身を差し替える前に控える。
    rememberLessonScroll();
    // 復習セッション（今回の10問）は、問題を解いている画面の間だけ生きている。
    // ホームやレッスンへ移ったら捨てる。ブラウザの戻るも必ずここを通るので、
    // 捨てる場所を1つにしておくと「無関係な問題で 3 / 4問 と出る」ような
    // 取り残しが起きない。
    var onboarding = !!(state && state.progress && state.progress.onboardingRequired);
    if (onboarding) {
      currentView = 'menu';
      currentId = null;
      reviewTaskId = null;
    }
    if (currentView !== 'reviewTask') { reviewSession = null; }

    var isHub = currentView !== 'lesson' && currentView !== 'reviewTask';
    document.body.classList.toggle('view-menu', isHub);
    document.body.classList.toggle('view-cafe', currentView === 'cafe');
    document.body.classList.toggle('view-onboarding', onboarding);
    // 学習ホームではヘッダのコインを隠すので、開いたままだと押した相手が消えた箱が残る
    closeCoinLog();
    renderHeader();
    // 初回案内はホーム上で行う。レッスン用サイドバーだけは不要なので組み立てない。
    // 空にするのは章の一覧だけ。#sidebar ごと消すと検索欄まで無くなる。
    if (onboarding) {
      sidebarTree().innerHTML = '';
    } else {
      // サイドバーはメニュー画面でも描いておく（☰で開けるように）。
      renderSidebar();
    }
    if (currentView === 'menu') {
      renderMenu();
    } else if (currentView === 'cafe') {
      renderCafe();
    } else if (currentView === 'review') {
      renderReview();
    } else if (currentView === 'reviewTask') {
      renderReviewTask();
    } else {
      renderLesson();
    }
    if (onboarding) {
      mountOnboardingTour();
    } else {
      removeOnboardingTour();
    }
    syncCafePassiveMode();
  }

  function selectLesson(id) {
    if (!findLesson(id)) { return; }
    currentId = id;
    reviewTaskId = null;
    currentView = 'lesson';
    try { localStorage.setItem('jq-last-lesson', id); } catch (e) { /* 使えなくても困らない */ }
    if (location.hash.replace(/^#/, '') !== id) { location.hash = id; }
    render();
  }

  function goHome() {
    currentId = null;
    reviewTaskId = null;
    currentView = 'menu';
    if (location.hash !== '#menu') { location.hash = 'menu'; }
    render();
  }

  function goCafe() {
    currentId = null;
    reviewTaskId = null;
    currentView = 'cafe';
    if (location.hash !== '#cafe') { location.hash = 'cafe'; }
    render();
  }

  /**
   * ヘッダの「📚 学習」で帰るレッスンID。無ければ null（＝学習ホームへ）。
   *
   * レッスンから直接カフェへ寄り道したときだけ返る（{@code rememberLessonScroll} が決める）。
   * 覚えたあとに章が入れ替わって消えたIDでも困らないよう、ここで実在も確かめる。
   */
  function learningReturnLessonId() {
    if (currentView !== 'cafe' || !cafeReturnLessonId) { return null; }
    return findLesson(cafeReturnLessonId) ? cafeReturnLessonId : null;
  }

  /**
   * 復習を解いている途中でカフェへ寄り道したか（帰り先があるか）。
   *
   * レッスンと違って<b>控え（jq-review-run）が要る</b>。カフェへ移った時点で
   * {@link render} が `reviewSession` を捨てるので、セットの何問目かは localStorage 側
   * にしか残っていない。控えが無ければ「1問だけ復習していた」ほうなので、その1問へ帰す。
   */
  function learningReturnReview() {
    if (currentView !== 'cafe' || !cafeReturnReview) { return null; }
    var lesson = findLesson(cafeReturnReview.lessonId);
    if (!lesson) { return null; }
    // クイズの段は控えが無いと戻せない（開いていた問題が無いので行き先が作れない）
    if (cafeReturnReview.quiz) { return loadReviewRun() ? cafeReturnReview : null; }
    return findTask(lesson, cafeReturnReview.taskId) ? cafeReturnReview : null;
  }

  /**
   * ヘッダの「📚 学習」。
   *
   * 解いている途中でカフェへ寄り道したなら、そこへ1手で帰す ―― レッスンなら読んでいた
   * 位置ごと、復習ならセットの何問目かごと。それ以外はこれまでどおり学習ホームへ。
   * 章を選び直したいときは、カフェ画面の「📚 章を選ぶ」と左上のロゴがいつでもホームへ戻す。
   */
  function goLearning() {
    var review = learningReturnReview();
    if (review) { resumeReviewFromCafe(review); return; }
    var back = learningReturnLessonId();
    if (back) { selectLesson(back); return; }
    goHome();
  }

  /**
   * カフェから復習へ帰る。
   *
   * 控えの「いま何問目か」がカフェへ寄る前と同じ問題を指しているなら、
   * {@link resumeReviewRun} でセットごと戻す（帯の「3 / 4問」と「もう1セット」が生きる）。
   * 指していなければ復習ホームの一覧から1問だけ開いていたほうなので、その1問を開く
   * ―― 別のセットの続きを勝手に始めない。
   */
  function resumeReviewFromCafe(review) {
    var saved = loadReviewRun();
    if (review.quiz) {
      // クイズの段は問題を開いていないので、現在地を復習ホーム（#review）へ移してから上に塗る。
      // resumeReviewRun は renderReviewQuiz を直接呼ぶ（render を通らない）ので、
      // goReview を通さないとヘッダとカフェの体裁が残ったままクイズが出る。
      // 状態を先に合わせてからハッシュを変えるので、hashchange は「同じ現在地」で抜ける。
      goReview();
      // 控えが消えていたら復習ホームで止める（押しても何も起きないボタンにしない）
      if (saved) { resumeReviewRun(); }
      return;
    }
    var set = saved && saved.set;
    var item = set && set.queue[set.index];
    if (item && item.lessonId === review.lessonId && item.taskId === review.taskId) {
      resumeReviewRun();
      return;
    }
    // 復習ホームの一覧から1問だけ開いていたほう。別のセットの続きを勝手に始めない
    selectReviewTask(review.lessonId, review.taskId);
  }

  /** 復習ホームへ。今回のセッションは {@code render} が畳む。 */
  function goReview() {
    currentId = null;
    reviewTaskId = null;
    currentView = 'review';
    if (location.hash !== '#review') { location.hash = 'review'; }
    render();
  }

  /**
   * 復習で1問を開く。
   *
   * 「続ける」の行き先（jq-last-lesson）は書き換えない。復習で昔の章を開いたせいで、
   * 次に学習ホームへ戻ったときの続きがそこへ移ってしまわないようにする。
   */
  function selectReviewTask(lessonId, taskId) {
    var lesson = findLesson(lessonId);
    if (!lesson || !findTask(lesson, taskId)) { return; }
    currentId = lessonId;
    reviewTaskId = taskId;
    currentView = 'reviewTask';
    var hash = 'review/' + lessonId + '/' + taskId;
    if (location.hash.replace(/^#/, '') !== hash) { location.hash = hash; }
    render();
  }

  /**
   * ハッシュから読んだ現在地を画面の状態に反映する。
   *
   * 通常のレッスンを開いたときだけ「最後にいた単元」を控える。これは
   * 「続ける」の行き先と、サイドバーの目印（sidebarFocusId）の両方が見る。
   * 復習では動かさない（昔の章を復習しただけで、どちらも巻き戻らないように）。
   *
   * 起動時とハッシュ変更の2箇所から呼ぶ。以前は片方でしか控えていなかったため、
   * レッスンのURLを直接開いてホームへ戻ると最後にいた単元を見失っていた。
   */
  function applyRoute(route) {
    currentView = route.view;
    currentId = route.id;
    reviewTaskId = route.taskId;
    if (currentId && currentView === 'lesson') {
      try { localStorage.setItem('jq-last-lesson', currentId); } catch (e) { /* 使えなくても困らない */ }
    }
  }

  function boot() {
    try {
      var savedFilter = localStorage.getItem('jq-review-filter');
      // 知らない値が入っていると、どのタブも選ばれていない画面になる
      if (savedFilter === 'weak' || savedFilter === 'bookmark' || savedFilter === 'all') {
        reviewFilter = savedFilter;
      }
    } catch (e) { /* 使えなくても困らない */ }
    api('state')
      .then(function (data) {
        setState(data);
        dropStaleCoinLog();
        // 設定パネルに出す環境の情報。歯車を押した時点では手元にある状態にしておく
        loadEnvInfo();
        // ハッシュ付きで開いたときだけそのレッスンへ。それ以外はメインメニューから始める
        applyRoute(routeFromHash());
        render();
      })
      .catch(function (e) {
        document.getElementById('content').innerHTML =
          '<div class="card card-result ng"><div class="err">'
          + '読み込みに失敗しました: ' + esc(e.message) + '</div></div>';
      });
  }

  document.getElementById('homeBtn').addEventListener('click', goHome);
  document.getElementById('learningBtn').addEventListener('click', goLearning);
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
  function setSidebarHidden(hidden) {
    try {
      localStorage.setItem(SIDEBAR_HIDE_KEY, hidden ? '1' : '0');
    } catch (e) { /* 使えなくても困らない */ }
    applySidebarVisibility();
    // 開いた瞬間に初めて寸法が測れるようになる。閉じている間の描画ではスクロール
    // できていないので、ここで目印の単元まで寄せる。
    if (!hidden) { scrollSidebarToFocus(); }
  }
  document.getElementById('sidebarToggle').addEventListener('click', function () {
    setSidebarHidden(!isSidebarHidden());
  });
  applySidebarVisibility();
  bindSidebarSearch();

  // ── 設定（明るさ / 版 / 実行環境 / 進捗のリセット） ───────────────
  //
  // ヘッダの右端に置く歯車ひとつにまとめてある。以前は明るさだけがヘッダのボタンで、
  // 進捗のリセットは学習ホームの最下部にあった。設定と呼べるものが2箇所に分かれていて、
  // しかもリセットは「章を選ぶ」の下まで下がらないと見つからなかった。
  //
  // 明るさの読み書きと data-theme の管理は theme.js に置いてある。ここは見た目だけ。
  // 押すたびに順番に回す形にはしていない。3択だと目的の設定まで最大2回押すことになり、
  // 次に何が来るかも読めないので、3つ並べて選ばせる。
  var THEME_LABELS = {
    light:  { icon: '☀',  label: 'ライト' },
    dark:   { icon: '🌙', label: 'ダーク' },
    system: { icon: '💻', label: 'システム' }
  };

  // 実行環境（/api/env）。動いているJVMとOSの話なので、開くたびに取り直す必要はない。
  // 起動時に1回だけ取っておき、設定を開いたときには既に手元にある状態にする
  // （歯車を押してから「読み込み中…」が見えるのは、変わらない情報には大げさすぎる）。
  var envInfo = null;
  var envError = null;
  var envPending = false;

  /** まだ持っていなければ取る。失敗しても学習には関係ないので、画面の中だけで知らせる。 */
  function loadEnvInfo() {
    if (envInfo || envPending) { return; }
    envPending = true;
    envError = null;   // 取り直しのあいだは前回の失敗ではなく「読み込み中」を出す
    api('env')
      .then(function (data) { envInfo = data; envError = null; })
      .catch(function (e) { envError = e.message; })
      .then(function () {
        envPending = false;
        repaintSettingsInfo();   // 開いている間に届いたら、その場に差し込む
      });
  }

  function themeChoicesHtml() {
    var pref = window.JQTheme ? window.JQTheme.get() : 'system';
    return (window.JQTheme ? window.JQTheme.CHOICES : ['system']).map(function (key) {
      var meta = THEME_LABELS[key] || THEME_LABELS.system;
      return '<button class="theme-opt" type="button" role="radio"'
        + ' data-theme-choice="' + key + '"'
        + ' aria-checked="' + (key === pref ? 'true' : 'false') + '">'
        + '<span class="theme-opt-icon" aria-hidden="true">' + meta.icon + '</span>'
        + '<span class="theme-opt-label">' + esc(meta.label) + '</span>'
        + '<span class="theme-opt-check" aria-hidden="true">✓</span>'
        + '</button>';
    }).join('');
  }

  /** 報酬の通知の2択。行の見た目は明るさの3択と同じものを使う（.theme-opt）。 */
  function rewardToastChoicesHtml() {
    return [
      { value: '1', icon: '🔔', label: '表示する' },
      { value: '0', icon: '🔕', label: '表示しない' }
    ].map(function (meta) {
      var on = meta.value === (rewardToastOn ? '1' : '0');
      return '<button class="theme-opt" type="button" role="radio"'
        + ' data-toast-choice="' + meta.value + '"'
        + ' aria-checked="' + (on ? 'true' : 'false') + '">'
        + '<span class="theme-opt-icon" aria-hidden="true">' + meta.icon + '</span>'
        + '<span class="theme-opt-label">' + esc(meta.label) + '</span>'
        + '<span class="theme-opt-check" aria-hidden="true">✓</span>'
        + '</button>';
    }).join('');
  }

  /**
   * 選び直したときの印。明るさと同じで、選んでもパネルは閉じない。
   * ここで innerHTML を作り替えないこと（押したボタンがDOMから消え、続いて document へ
   * 上がるクリックが「外を押した」と判定されて勝手に閉じる）。
   */
  function repaintRewardToastChoices(pop) {
    var opts = pop.querySelectorAll('[data-toast-choice]');
    for (var i = 0; i < opts.length; i++) {
      opts[i].setAttribute('aria-checked',
        opts[i].dataset.toastChoice === (rewardToastOn ? '1' : '0') ? 'true' : 'false');
    }
  }

  /**
   * ラベルと値の1行。値が空なら「不明」（読めなかった項目を空欄で見せない）。
   *
   * ラベルの幅はCSS側で固定してあるので、区画をまたいでも値の左端が1本にそろう。
   * mono を立てるのは道（パス）だけ ―― 折り返しても字の並びで読めるようにするためで、
   * 行の形（左にラベル・右に値）は変えない。
   */
  function settingsRow(label, value, mono) {
    return '<div class="settings-row' + (mono ? ' mono' : '') + '">'
      + '<span>' + esc(label) + '</span>'
      + '<b>' + esc(value ? String(value) : '不明') + '</b></div>';
  }

  /**
   * 「このアプリ」と「実行環境」。版もサーバから受け取る（web側にも書くと、
   * 上げ忘れた方が画面に出る）。
   */
  function settingsInfoHtml() {
    if (envError) {
      return '<h3 class="settings-h">実行環境</h3>'
        + '<p class="settings-note">環境の情報を読めませんでした: ' + esc(envError) + '</p>';
    }
    if (!envInfo) {
      return '<h3 class="settings-h">実行環境</h3>'
        + '<p class="settings-note">読み込み中…</p>';
    }
    var os = envInfo.osName
      ? envInfo.osName + (envInfo.osVersion ? ' ' + envInfo.osVersion : '')
        + (envInfo.osArch ? '（' + envInfo.osArch + '）' : '')
      : '';
    var vm = envInfo.vmName
      ? envInfo.vmName + (envInfo.vmVersion ? ' ' + envInfo.vmVersion : '')
      : '';
    return ''
      + '<h3 class="settings-h">このアプリ</h3>'
      + '<div class="settings-rows">'
      + settingsRow('バージョン', 'v' + (envInfo.appVersion || '?'))
      + '</div>'
      + '<h3 class="settings-h settings-h-next">実行環境</h3>'
      + '<div class="settings-rows">'
      + settingsRow('Java', envInfo.javaVersion)
      + settingsRow('配布元', envInfo.javaVendor)
      + settingsRow('VM', vm)
      + settingsRow('コンパイラ',
          envInfo.compilerAvailable ? '利用できます' : '見つかりません（JREで起動しています）')
      + settingsRow('OS', os)
      + settingsRow('JDKの場所', envInfo.javaHome, true)   // 道だけ mono
      + '</div>'
      // 教材のコードはこのJDKで動く。学習者が別に入れたJDKと食い違うことがあるので、
      // どれで動いているのかを名指しで書いておく。
      + '<p class="settings-note">あなたが書いたコードは、上のJDKでコンパイル・実行されます。</p>';
  }

  function settingsStoreHtml() {
    if (!envInfo || !envInfo.progressFile) { return ''; }
    return settingsRow('保存先', envInfo.progressFile, true);
  }

  /** 環境の情報が後から届いたとき、開いているパネルの該当箇所だけ描き替える。 */
  function repaintSettingsInfo() {
    var info = document.getElementById('settingsInfo');
    if (info) { info.innerHTML = settingsInfoHtml(); }
    var store = document.getElementById('settingsStore');
    if (store) { store.innerHTML = settingsStoreHtml(); }
  }

  function settingsHtml() {
    return ''
      + '<div class="settings-top">'
      + '  <div class="settings-title"><small>SETTINGS</small><strong>設定</strong></div>'
      + '  <button class="settings-close" type="button" data-role="close"'
      + '          title="閉じる" aria-label="設定を閉じる">×</button>'
      + '</div>'
      + '<div class="settings-body">'
      + '  <section class="settings-group">'
      + '    <h3 class="settings-h">画面の明るさ</h3>'
      + '    <div class="settings-choices" role="radiogroup" aria-label="画面の明るさ">'
      + themeChoicesHtml()
      + '    </div>'
      + '  </section>'
      + '  <section class="settings-group">'
      + '    <h3 class="settings-h">報酬の通知</h3>'
      + '    <div class="settings-choices" role="radiogroup" aria-label="報酬の通知">'
      + rewardToastChoicesHtml()
      + '    </div>'
      + '    <p class="settings-note">問題をクリアしたときに右上へ出る「+○○コイン」の通知です。'
      + '表示しなくてもコインは入り、ヘッダのコインを押せば獲得の履歴で読み返せます'
      + '（章クリアのお知らせは、次の章へ進む導線があるので表示しないときも出ます）。</p>'
      + '  </section>'
      + '  <section class="settings-group" id="settingsInfo">' + settingsInfoHtml() + '</section>'
      + '  <section class="settings-group">'
      + '    <h3 class="settings-h">学習データ</h3>'
      + '    <div class="settings-rows" id="settingsStore">' + settingsStoreHtml() + '</div>'
      + '    <p class="settings-note">★・書いたコード・復習の記録・カフェの資産は、'
      + 'この端末の progress.json だけに入っています。どこにも送信されません。</p>'
      + '    <button class="ghost-btn settings-reset" id="resetBtn" type="button">進捗をリセット</button>'
      + '  </section>'
      + '</div>';
  }

  /** 進捗のリセットのように、パネルの外の処理から閉じるための口。 */
  function closeSettings() {
    var pop = document.getElementById('settingsPop');
    var btn = document.getElementById('settingsBtn');
    if (pop) { pop.hidden = true; }
    if (btn) { btn.setAttribute('aria-expanded', 'false'); }
  }

  /**
   * 歯車を押したときの開閉。箱は body 直下にあるので、位置は自分で測って
   * ボタンの真下へ寄せる（コインの履歴 setupCoinLog と同じ作り）。
   */
  function setupSettings() {
    var btn = document.getElementById('settingsBtn');
    var pop = document.getElementById('settingsPop');
    if (!btn || !pop) { return; }

    function isOpen() { return !pop.hidden; }

    function place() {
      var r = btn.getBoundingClientRect();
      pop.style.top = Math.round(r.bottom + 8) + 'px';
      pop.style.left = 'auto';
      // 右端をボタンの右端にそろえ、画面外に出ないよう最低8pxは残す
      pop.style.right = Math.max(8, Math.round(window.innerWidth - r.right)) + 'px';
    }

    function open() {
      closeCoinLog();          // 同じ列から2枚が重なって出ないように
      loadEnvInfo();           // 起動時に取れていなかったときの取り直し
      pop.innerHTML = settingsHtml();
      pop.hidden = false;
      btn.setAttribute('aria-expanded', 'true');
      place();
      var close = pop.querySelector('[data-role="close"]');
      if (close) { close.focus(); }
    }

    function close(focusBack) {
      if (!isOpen()) { return; }
      closeSettings();
      if (focusBack) { btn.focus(); }
    }

    btn.addEventListener('click', function (e) {
      e.stopPropagation();   // 直後の document クリックで閉じてしまわないように
      if (isOpen()) { close(false); } else { open(); }
    });

    pop.addEventListener('click', function (e) {
      var hit = e.target.closest ? e.target.closest('[data-role="close"]') : null;
      if (hit) { close(true); return; }
      var toastOpt = e.target.closest ? e.target.closest('[data-toast-choice]') : null;
      // 報酬の通知も選んでも閉じない（見比べる相手は無いが、明るさと同じ手触りにする）
      if (toastOpt) {
        setRewardToastOn(toastOpt.dataset.toastChoice === '1');
        repaintRewardToastChoices(pop);
        return;
      }
      var opt = e.target.closest ? e.target.closest('[data-theme-choice]') : null;
      // 明るさは選んでも閉じない。3つを見比べながら決められるようにする
      if (opt && window.JQTheme) { window.JQTheme.set(opt.dataset.themeChoice); return; }
      var reset = e.target.closest ? e.target.closest('#resetBtn') : null;
      if (reset) { resetProgress(); }
    });

    // 外側クリックと Esc で閉じる。中を触っても閉じないよう、箱の中は除く。
    document.addEventListener('click', function (e) {
      if (isOpen() && !pop.contains(e.target) && !btn.contains(e.target)) { close(false); }
    });
    document.addEventListener('keydown', function (e) {
      if (!isOpen()) { return; }
      if (e.key === 'Escape') { e.preventDefault(); close(true); return; }
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') { return; }
      // 3択・2択の中にいるときだけ、矢印キーで行き来する。範囲はいま居る区画に限る
      // （パネル全部から集めると、明るさの最後から報酬の通知へ飛び移ってしまう）。
      var group = document.activeElement && document.activeElement.closest
        ? document.activeElement.closest('[role="radiogroup"]') : null;
      if (!group || !pop.contains(group)) { return; }
      var opts = Array.prototype.slice.call(group.querySelectorAll('.theme-opt'));
      var at = opts.indexOf(document.activeElement);
      if (at < 0) { return; }
      e.preventDefault();
      var to = opts[(at + (e.key === 'ArrowDown' ? 1 : -1) + opts.length) % opts.length];
      if (to) { to.focus(); }
    });

    // 画面を動かすと測った位置がずれるので、開いたまま置いていかない
    window.addEventListener('resize', function () { if (isOpen()) { place(); } });

    // 明るさが変わったとき、および「システム」追従でOS側が変わったとき。
    // ✓の位置を合わせ直すだけ（店内の絵はCSS変数を見ていないので触らなくてよい）。
    //
    // ここで innerHTML を作り替えてはいけない。押されたボタンがその場でDOMから消え、
    // 続いて document まで上がってくるクリックが「パネルの外を押した」と判定されて
    // 勝手に閉じてしまう（3つを見比べながら決められなくなる）。印だけ書き換える。
    if (window.JQTheme) {
      window.JQTheme.onChange(function () {
        if (!isOpen()) { return; }
        var pref = window.JQTheme.get();
        var opts = pop.querySelectorAll('.theme-opt');
        for (var i = 0; i < opts.length; i++) {
          opts[i].setAttribute('aria-checked',
            opts[i].dataset.themeChoice === pref ? 'true' : 'false');
        }
      });
    }
  }

  setupSettings();
  setupCoinLog();

  function resetProgress() {
    if (!window.confirm('★・書いたコード・復習の記録・ブックマーク・カフェのコイン・店舗・設備・アイテムがすべて消えます。本当にリセットしますか？')) { return; }
    api('reset', {})
      .then(function (data) {
        // 設定パネルの中から呼ばれるので、先に閉じる。開いたままだと、下で描き直した
        // 学習ホームの上にパネルだけが残る（もう用のない状態で画面を覆ってしまう）。
        closeSettings();
        setState(data);
        sideExpanded = {};
        sideQuery = '';
        sideHitIndex = -1;
        reviewSession = null;
        reviewSummary = null;
        reviewRun = null;
        clearReviewRun();   // 出題の元（クリア済み）が無くなるので、途中のセットも成り立たない
        try { localStorage.removeItem('jq-last-lesson'); } catch (e) { /* 同上 */ }
        clearCoinLog();
        goHome();
        toast('進捗をリセットしました');
      })
      .catch(toastError);
  }

  window.addEventListener('hashchange', function () {
    var route = routeFromHash();
    if (route.view === currentView && route.id === currentId
        && route.taskId === reviewTaskId) {
      return;
    }
    applyRoute(route);
    render();
  });
  window.addEventListener('resize', function () {
    requestAnimationFrame(repositionOnboardingTour);
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
