/* ============================================================================
   Java Café — 店舗シーンの描画（SVG）

   カフェ画面の「いまの店構え」を1枚のベクター絵として組み立てる。
   以前はdiv+CSSの箱絵だったが、パーツが増えるほど座標がCSS側に散ってしまい、
   ランクごとの見た目を揃えられなくなったため、描画をこのファイルへ集約した。

   構成は3つだけ。
     ERAS       … ランク1〜12の配色（時間帯・素材色・灯りの有無）
     STRUCTURES … ランク1〜7の建物の骨格（幅・各段の高さ）
     FEATURES   … 設備ID → 絵の中で増える要素

   ランク8以降はランク7の建物を土台に、ERASの配色と終盤演出だけを変える。
   これはゲーム側の仕様（店構えはLv.7で完成し、以降は店舗網を伸ばす）に合わせている。

   座標系は 560×344 で描き、表示するのは x 90〜470 / y 40〜344 の範囲（= viewBox）。
   建物と路上の小物はこの中に収めてある。

   器の縦横比は画面幅で決まり、こちらでは決められない。切り抜き（slice）にすると
   横長の器で足元や看板が消えてしまうので、全体を必ず収める meet を使う。
   代わりに空・歩道・車道・遠景は viewBox の外まで描き足してあり、
   器がどんな比率でも余白が出ずに通りが続いて見える（OS* 定数がその範囲）。

   例外は配達の車だけ。運転席側をわざと右へはみ出させて、通りが画面の先へ
   続いているように見せている。
   ========================================================================= */

(function (global) {
  'use strict';

  var W = 560;          // 作図全体の幅
  var H = 344;          // 作図全体の高さ（= 車道の下端）
  var WALK = 296;       // 歩道の上端。建物の接地線でもある
  var CURB = 326;       // 縁石（歩道と車道の境目）
  var NEAR_CURB = 430;  // 手前側の縁石。縦長の器のときだけ見える
  var MIDX = 280;       // 建物の中心

  // 表示する範囲。ここが器いっぱいに収まる
  var VBX = 90;
  var VBY = 40;
  var VBW = 380;
  var VBH = H - VBY;

  // 背景の描き足し。器が極端に横長／縦長でも余白が出ないだけの広さを取る
  var OSX = -1100;
  var OSW = 2760;
  var OSY = -700;
  var OSH = 1800;

  /* ── ランクの配色 ────────────────────────────────────────────────────
     朝（Lv.1）から星空（Lv.12）へ、時間帯が進むように色を送っている。
     lights は店内の灯りを点けるかどうか。夕方以降は窓から光がこぼれる。 */
  var ERAS = [
    null,
    { // 1 屋台カフェ — 朝のやわらかい光
      skyTop: '#6ea9d2', skyMid: '#a3cde1', skyLow: '#dfece9',
      orb: '#fff7d8', orbGlow: 'rgba(255,239,180,0.55)', orbX: 448, orbY: 152, orbR: 21,
      wall: '#b98a5e', wallHi: '#d09f6d', wallLo: '#8d6543', trim: '#5c3d2d',
      roof: '#8d5340', roofHi: '#a5644c',
      awning: '#c1573f', awningAlt: '#f2e3c6',
      glass: '#8fb0b6', glassHi: '#d3e7e3', glow: '#ffd98a',
      walk: '#bab4a4', walkHi: '#cec8b7', road: '#736f68', lane: '#ddd6c1',
      city: '#9cbcce', tree: '#5f8a5a', treeLo: '#416842',
      lights: false, stars: 0
    },
    { // 2 街角のコーヒースタンド — 午前
      skyTop: '#5f9fce', skyMid: '#96c8df', skyLow: '#dcebe6',
      orb: '#fff8dd', orbGlow: 'rgba(255,242,190,0.5)', orbX: 444, orbY: 142, orbR: 20,
      wall: '#bf8f61', wallHi: '#d6a471', wallLo: '#926a46', trim: '#583a2b',
      roof: '#8a5140', roofHi: '#a3634c',
      awning: '#b95440', awningAlt: '#f4e6ca',
      glass: '#8bacb4', glassHi: '#d6e9e5', glow: '#ffdb8f',
      walk: '#bcb6a6', walkHi: '#d0cab9', road: '#706c66', lane: '#ded7c3',
      city: '#96b8cc', tree: '#5c8757', treeLo: '#3e653f',
      lights: false, stars: 0
    },
    { // 3 こだわりの小さな店 — 昼
      skyTop: '#5498c9', skyMid: '#8fc5de', skyLow: '#dcece4',
      orb: '#fffbe6', orbGlow: 'rgba(255,246,205,0.45)', orbX: 440, orbY: 132, orbR: 19,
      wall: '#c59465', wallHi: '#dcaa76', wallLo: '#976e49', trim: '#543729',
      roof: '#875040', roofHi: '#a0624c',
      awning: '#b25340', awningAlt: '#f5e8ce',
      glass: '#86a9b2', glassHi: '#d8ebe6', glow: '#ffdd95',
      walk: '#beb8a8', walkHi: '#d2ccbb', road: '#6e6a64', lane: '#e0d9c5',
      city: '#91b5cb', tree: '#598454', treeLo: '#3c623d',
      lights: false, stars: 0
    },
    { // 4 人気カフェ — 午後の暖かい光
      skyTop: '#5f9cc4', skyMid: '#9dc7d6', skyLow: '#eee0be',
      orb: '#fff4cd', orbGlow: 'rgba(255,231,163,0.5)', orbX: 436, orbY: 138, orbR: 21,
      wall: '#cb9a68', wallHi: '#e3b17b', wallLo: '#9c724c', trim: '#503528',
      roof: '#845040', roofHi: '#9d624c',
      awning: '#ac5140', awningAlt: '#f6ead2',
      glass: '#82a6b0', glassHi: '#dbede7', glow: '#ffdf9c',
      walk: '#c0baaa', walkHi: '#d4cebd', road: '#6c6862', lane: '#e2dbc7',
      city: '#8fb3c9', tree: '#578251', treeLo: '#3a603b',
      lights: false, stars: 0
    },
    { // 5 大型ロースタリー — 日が傾きはじめる
      skyTop: '#5f93bb', skyMid: '#a8c5cd', skyLow: '#f6d9a4',
      orb: '#ffeab4', orbGlow: 'rgba(255,215,140,0.55)', orbX: 432, orbY: 158, orbR: 23,
      wall: '#d0a06c', wallHi: '#e9b880', wallLo: '#a1764f', trim: '#4c3226',
      roof: '#814f40', roofHi: '#9a614c',
      awning: '#a64f40', awningAlt: '#f7ecd6',
      glass: '#7ea2ac', glassHi: '#dceee8', glow: '#ffe0a2',
      walk: '#c1bbab', walkHi: '#d5cfbe', road: '#6a6660', lane: '#e4ddc9',
      city: '#8ab0c6', tree: '#54804e', treeLo: '#385e39',
      lights: false, stars: 0
    },
    { // 6 Java Café チェーン — 夕焼け。ここから店内の灯りが入る
      skyTop: '#4e7aa8', skyMid: '#a8a2b8', skyLow: '#f3a870',
      orb: '#ffd79a', orbGlow: 'rgba(255,183,116,0.6)', orbX: 428, orbY: 186, orbR: 25,
      wall: '#c99a6a', wallHi: '#e5b47f', wallLo: '#946d4b', trim: '#45302a',
      roof: '#78493c', roofHi: '#915b48',
      awning: '#a04c40', awningAlt: '#f0e2cc',
      glass: '#5d7a8a', glassHi: '#a8c3c8', glow: '#ffcf7e',
      walk: '#a9a394', walkHi: '#bdb7a7', road: '#5e5a55', lane: '#cfc8b5',
      city: '#7793ad', tree: '#48704a', treeLo: '#2f5335',
      lights: true, stars: 0
    },
    { // 7 世界的Javaカフェ — 薄暮
      skyTop: '#33547f', skyMid: '#7c6f96', skyLow: '#dd8b6a',
      orb: '#ffc78c', orbGlow: 'rgba(255,161,102,0.55)', orbX: 424, orbY: 200, orbR: 24,
      wall: '#cfa273', wallHi: '#ecbc88', wallLo: '#957050', trim: '#3b2a27',
      roof: '#6d4338', roofHi: '#875345',
      awning: '#96473d', awningAlt: '#e7dac6',
      glass: '#41596c', glassHi: '#7e9dab', glow: '#ffdb96',
      walk: '#948e81', walkHi: '#a8a294', road: '#514e4a', lane: '#bcb6a4',
      city: '#5b7797', tree: '#3c5f42', treeLo: '#26452e',
      lights: true, stars: 12
    },
    { // 8 テック街区ロースタリー — 夜。ティール系のネオン
      skyTop: '#0f3541', skyMid: '#1c5560', skyLow: '#48918f',
      orb: '#d8f6ef', orbGlow: 'rgba(160,240,224,0.4)', orbX: 430, orbY: 138, orbR: 16,
      wall: '#c2a075', wallHi: '#e0bd8c', wallLo: '#8a7050', trim: '#2b2b2c',
      roof: '#5d4038', roofHi: '#755044',
      awning: '#7f463f', awningAlt: '#ded3c1',
      glass: '#1e3c46', glassHi: '#4f7f85', glow: '#b6f2e4',
      walk: '#6f6d66', walkHi: '#82806f', road: '#3c3d3d', lane: '#8ea59b',
      city: '#2c5b68', tree: '#2c4c3c', treeLo: '#1b3529',
      lights: true, stars: 26, neon: '#7ce6cf'
    },
    { // 9 全国Java Café連合 — 夜。青の街あかり
      skyTop: '#101f3a', skyMid: '#223a63', skyLow: '#4b6390',
      orb: '#e2ecff', orbGlow: 'rgba(190,214,255,0.38)', orbX: 434, orbY: 132, orbR: 17,
      wall: '#bd9d78', wallHi: '#dbba8f', wallLo: '#866d53', trim: '#272733',
      roof: '#573d38', roofHi: '#6f4d45',
      awning: '#75433f', awningAlt: '#d9cfc0',
      glass: '#1b2c44', glassHi: '#496a90', glow: '#c9dcff',
      walk: '#6a6a6c', walkHi: '#7d7d78', road: '#383a41', lane: '#8b98a8',
      city: '#26426d', tree: '#28453e', treeLo: '#182f2c',
      lights: true, stars: 34, neon: '#8fbdff'
    },
    { // 10 アジア太平洋チェーン — 夜。紫の空
      skyTop: '#171634', skyMid: '#2f2455', skyLow: '#5b3a72',
      orb: '#f0e6ff', orbGlow: 'rgba(214,190,255,0.36)', orbX: 438, orbY: 128, orbR: 18,
      wall: '#b8987a', wallHi: '#d6b590', wallLo: '#826a55', trim: '#241f2e',
      roof: '#523a38', roofHi: '#6a4945',
      awning: '#6e4140', awningAlt: '#d5cbbd',
      glass: '#1d2340', glassHi: '#4a5590', glow: '#ffe1a8',
      walk: '#666472', walkHi: '#79777d', road: '#35333f', lane: '#8b83a2',
      city: '#2c2354', tree: '#27403d', treeLo: '#172b2b',
      lights: true, stars: 40, neon: '#c39bff'
    },
    { // 11 世界開発者ラウンジ — 深夜。マゼンタの光
      skyTop: '#140d28', skyMid: '#2c1544', skyLow: '#5d2a5c',
      orb: '#ffe9fb', orbGlow: 'rgba(255,196,240,0.34)', orbX: 442, orbY: 124, orbR: 18,
      wall: '#b2947a', wallHi: '#d0b190', wallLo: '#7d6755', trim: '#1f1a28',
      roof: '#4d3838', roofHi: '#654745',
      awning: '#684040', awningAlt: '#d0c7ba',
      glass: '#1c1c38', glassHi: '#4b4586', glow: '#ffd9f2',
      walk: '#615f6d', walkHi: '#747279', road: '#312e3b', lane: '#8b7d9e',
      city: '#2a1748', tree: '#243b3b', treeLo: '#152728',
      lights: true, stars: 46, neon: '#ff9de2'
    },
    { // 12 Java Café 殿堂 — 星空と金
      skyTop: '#080b1e', skyMid: '#181a3c', skyLow: '#39305c',
      orb: '#fff6d5', orbGlow: 'rgba(255,232,160,0.4)', orbX: 446, orbY: 120, orbR: 19,
      wall: '#d3b485', wallHi: '#f0d3a1', wallLo: '#94795a', trim: '#191527',
      roof: '#4a3838', roofHi: '#634846',
      awning: '#6a4a3c', awningAlt: '#efe0c4',
      glass: '#17182f', glassHi: '#4a4478', glow: '#ffeeb4',
      walk: '#5f5d68', walkHi: '#726f74', road: '#2d2b36', lane: '#988c9c',
      city: '#1d1a3e', tree: '#22383a', treeLo: '#132325',
      lights: true, stars: 56, neon: '#ffd977'
    }
  ];

  /* ── 建物の骨格 ──────────────────────────────────────────────────────
     x0/x1 は正面幅、top は屋根の上端。段の指定はすべて [上, 下] の y。
     upper（上階）と cornice（軒飾り）は無い階では null。
     どのランクも接地線 WALK と土台 plinth を共有するので、育っても足元がぶれない。 */
  var STRUCTURES = [
    null,
    { kind: 'cart', x0: 218, x1: 344 },
    { kind: 'shop', x0: 214, x1: 346, top: 216, sign: [220, 238], awning: null, glassTop: 244, upper: null, cornice: null },
    { kind: 'shop', x0: 200, x1: 360, top: 190, sign: [194, 210], awning: [212, 230], glassTop: 234, upper: null, cornice: null },
    { kind: 'shop', x0: 186, x1: 374, top: 174, sign: [178, 198], awning: [200, 224], glassTop: 228, upper: null, cornice: null },
    { kind: 'shop', x0: 174, x1: 386, top: 138, sign: [176, 198], awning: [200, 224], glassTop: 228, upper: [142, 172], cornice: null },
    { kind: 'shop', x0: 162, x1: 398, top: 122, sign: [176, 198], awning: [200, 224], glassTop: 228, upper: [126, 172], cornice: null },
    { kind: 'shop', x0: 150, x1: 410, top: 106, sign: [176, 198], awning: [200, 224], glassTop: 228, upper: [124, 172], cornice: [106, 120] }
  ];

  var PLINTH = 284;     // 土台の上端（ガラス面の下端）

  /* ── 設備 → 絵の中で増える要素 ──────────────────────────────────────
     装備できるのは6系統それぞれの最上位1つだけ（下位は上位へ置き換わる）。
     つまりここに並べるIDは「その系統でいまどこまで育ったか」を表している。
     1つの設備が複数の要素に効く場合（例: hand_grinder は棚とマシンの両方）は両方に並べる。

     終盤tier（9〜12）も必ずどこかに入れてある。ここが抜けていると、
     全設備を買い切った人の店が「何も置いていない店」に戻ってしまう。
     系統ごとの絵を引き継がせて、買い進めるほど賑やかになるようにしている。
       sales  … マシン・煙突・立て看板・マット・飛行機
       cups   … 棚・マシン・ロボット・配達
       chapter… ショーケース・電飾・テラス・花火
       tips   … ショーケース・ロープ・ラウンジ
       streak … 音符・ラウンジ・立て看板
     ========================================================================= */
  var SALES_TOP = ['airport_store', 'global_brand', 'quantum_campaign', 'java_legacy'];
  var CUPS_TOP = ['smart_kitchen', 'coffee_lab', 'orbital_roastery', 'planetary_brew'];
  var CHAPTER_TOP = ['coffee_festival', 'world_expo', 'developer_summit', 'mastery_congress'];
  var TIPS_TOP = ['members_lounge', 'founders_club', 'knowledge_vault', 'hall_of_fame_counter'];
  var STREAK_TOP = ['mentor_club', 'learning_retreat', 'learning_guild', 'lifelong_academy'];

  var FEATURES = {
    plane:      SALES_TOP,
    fireworks:  CHAPTER_TOP,
    festoon:    ['weekend_event'].concat(CHAPTER_TOP),
    chimney:    ['roaster', 'factory'].concat(SALES_TOP),
    music:      ['morning_playlist'].concat(STREAK_TOP),
    shelf:      ['extra_mugs', 'hand_grinder', 'dripper'].concat(CUPS_TOP, CHAPTER_TOP),
    machine:    ['hand_grinder', 'grinder', 'brew_station', 'espresso', 'roaster']
                  .concat(SALES_TOP, CUPS_TOP),
    machinePro: ['espresso', 'roaster'].concat(SALES_TOP, CUPS_TOP),
    robot:      ['robot_barista'].concat(CUPS_TOP),
    showcase:   ['cookie_plate', 'showcase', 'dessert_pairing', 'tasting_flight']
                  .concat(TIPS_TOP, CHAPTER_TOP),
    lounge:     ['window_seat', 'study_table', 'seats'].concat(TIPS_TOP, STREAK_TOP),
    loungePro:  TIPS_TOP,
    menuBoard:  ['signboard', 'loyalty_board', 'daily_roast_log'].concat(SALES_TOP, STREAK_TOP),
    welcomeMat: ['welcome_mat'].concat(SALES_TOP),
    patio:      ['seats', 'terrace'].concat(TIPS_TOP, CHAPTER_TOP),
    delivery:   ['delivery', 'catering'].concat(CUPS_TOP),
    vipRope:    ['vip_counter', 'concierge'].concat(TIPS_TOP)
  };

  /* ------------------------------------------------------------------ 小道具 */

  function clamp(value, low, high) {
    return value < low ? low : (value > high ? high : value);
  }

  function round(value) {
    return Math.round(value * 10) / 10;
  }

  /** 装備IDの集合から、絵に出す要素のフラグを組む。 */
  function featureFlags(structure, interior, ids) {
    var owned = {};
    (ids || []).forEach(function (id) { owned[id] = true; });

    var flags = {};
    Object.keys(FEATURES).forEach(function (key) {
      flags[key] = FEATURES[key].some(function (id) { return owned[id]; });
    });

    // ランクだけで増える分。設備を買っていなくても店構えは育つ
    if (structure >= 6) { flags.lounge = true; flags.patio = true; }

    // 内装レベルで増える分。旧CSSの cafe-furnish-* / cafe-interior-* と同じ段。
    flags.planter = interior >= 2;
    flags.wallLamp = interior >= 3;
    flags.pendant = interior >= 4;
    if (interior >= 5) { flags.festoon = true; }
    flags.roofMark = structure >= 7;

    return flags;
  }

  /** 装備状況を1本の文字列にする。これが同じなら絵は描き直さない。 */
  function signature(view) {
    return [view.level, view.structure, view.interior, view.storeCount || 1]
      .concat((view.equippedIds || []).slice().sort()).join('|');
  }

  /* --------------------------------------------------------------- パーツ描画 */

  /** コーヒーカップの記章。看板やテーブルの上に置く。 */
  function cupMark(x, y, size, body, dark, steam) {
    var s = size / 22;
    return '<g transform="translate(' + round(x) + ',' + round(y) + ') scale(' + round(s) + ')">'
      + (steam
        ? '<g class="cs-steam" stroke="' + steam + '" stroke-width="1.6" stroke-linecap="round" fill="none" opacity="0.75">'
          + '<path d="M-3.5 -12 q3.2 -3.4 0 -6.8"/><path d="M2.5 -13 q3.2 -3.4 0 -6.8"/></g>'
        : '')
      + '<ellipse cy="10.4" rx="12.6" ry="2.6" fill="' + dark + '" opacity="0.55"/>'
      + '<path d="M-8.4 -8 H7.6 L6 6.2 Q5.6 8.6 3.2 8.6 H-5.6 Q-8 8.6 -8.4 6.2 Z" fill="' + body + '"/>'
      + '<path d="M-8.4 -8 H7.6 L7.2 -4.6 H-8.1 Z" fill="' + dark + '" opacity="0.28"/>'
      + '<path d="M8 -4.6 q5.4 0.4 5.2 4.6 q-0.2 4.2 -5.6 4.4" fill="none" stroke="' + body
      + '" stroke-width="2.4" stroke-linecap="round"/>'
      + '</g>';
  }

  /** 人物。feet が接地点。kind で服の色分けと小物を変える。 */
  function person(x, feet, height, kind, era) {
    var s = height / 34;
    var skin = kind === 'barista' ? '#e8b98e' : (kind === 'guestB' ? '#c98d64' : '#e2b189');
    var shirt = kind === 'barista' ? '#f4efe4'
      : kind === 'guestA' ? '#4f7fa8'
        : kind === 'guestB' ? '#a8574f' : '#6a6f8c';
    var lower = kind === 'barista' ? '#3f4550' : '#3a3f4a';
    var hair = kind === 'guestB' ? '#5a3f33' : '#2f2a29';

    return '<g transform="translate(' + round(x) + ',' + round(feet) + ') scale(' + round(s) + ')">'
      + '<ellipse cy="1.6" rx="7.4" ry="2.2" fill="#000" opacity="0.2"/>'
      + '<rect x="-4.8" y="-13" width="3.9" height="13" rx="1.6" fill="' + lower + '"/>'
      + '<rect x="0.9" y="-13" width="3.9" height="13" rx="1.6" fill="' + lower + '"/>'
      + '<path d="M-5.6 -24.4 Q0 -26.2 5.6 -24.4 L6.6 -12.4 H-6.6 Z" fill="' + shirt + '"/>'
      + (kind === 'barista'
        ? '<path d="M-4.4 -19.4 H4.4 L5 -12.4 H-5 Z" fill="' + era.awningAlt + '"/>'
          + '<path d="M-4.4 -19.4 H4.4" stroke="' + era.trim + '" stroke-width="0.9" opacity="0.45"/>'
        : '')
      + '<rect x="-7.6" y="-24" width="3" height="11" rx="1.5" fill="' + shirt + '"/>'
      + '<rect x="4.6" y="-24" width="3" height="11" rx="1.5" fill="' + shirt + '"/>'
      + '<circle cy="-28.4" r="4.9" fill="' + skin + '"/>'
      + '<path d="M-4.9 -29.6 Q-4.2 -34.2 0 -34.2 Q4.2 -34.2 4.9 -29.6 Q2.4 -31.4 -4.9 -29.6 Z" fill="' + hair + '"/>'
      + (kind === 'guestA'
        ? '<rect x="5.4" y="-17" width="5.6" height="6.4" rx="1.4" fill="' + era.trim + '" opacity="0.8"/>'
        : '')
      + '</g>';
  }

  /** ロボットバリスタ。robot_barista 以降のカウンターに立つ。 */
  function robot(x, feet, height, era) {
    var s = height / 34;
    var glow = era.neon || era.glow;
    return '<g transform="translate(' + round(x) + ',' + round(feet) + ') scale(' + round(s) + ')">'
      + '<ellipse cy="1.6" rx="7.6" ry="2.2" fill="#000" opacity="0.2"/>'
      + '<rect x="-5.4" y="-13" width="4.4" height="13" rx="1.8" fill="#7d838e"/>'
      + '<rect x="1" y="-13" width="4.4" height="13" rx="1.8" fill="#7d838e"/>'
      + '<rect x="-6.4" y="-24.6" width="12.8" height="12.6" rx="3" fill="#d7dbe0"/>'
      + '<rect x="-6.4" y="-24.6" width="12.8" height="12.6" rx="3" fill="none" stroke="#8f959e" stroke-width="0.8"/>'
      + '<rect x="-3.2" y="-20.6" width="6.4" height="3.4" rx="1.2" fill="' + glow + '" opacity="0.9"/>'
      + '<rect x="-8.6" y="-24" width="2.8" height="10.6" rx="1.4" fill="#9aa0a9"/>'
      + '<rect x="5.8" y="-24" width="2.8" height="10.6" rx="1.4" fill="#9aa0a9"/>'
      + '<rect x="-5.6" y="-33.4" width="11.2" height="8.6" rx="3.2" fill="#e6e9ed"/>'
      + '<rect x="-4" y="-31" width="8" height="3.6" rx="1.8" fill="#2b3038"/>'
      + '<circle cx="-1.6" cy="-29.2" r="1" fill="' + glow + '"/>'
      + '<circle cx="1.6" cy="-29.2" r="1" fill="' + glow + '"/>'
      + '<path d="M0 -33.4 V-36.4" stroke="#9aa0a9" stroke-width="1.1"/>'
      + '<circle cy="-37.2" r="1.5" fill="' + glow + '" class="cs-blink"/>'
      + '</g>';
  }

  /* ------------------------------------------------------------------ 背景層 */

  function skyLayer(era, flags, level) {
    // 空は viewBox の外まで敷く。空色のグラデーションは userSpaceOnUse で
    // y 0〜344 に固定してあるので、広げても色の出方は変わらない
    var out = '<rect x="' + OSX + '" y="' + OSY + '" width="' + OSW + '" height="' + OSH
      + '" fill="url(#cs-sky)"/>';

    // 星。夜のランクだけ、決め打ちの座標で散らす（毎回同じ位置に出したい）
    if (era.stars) {
      var seeds = [
        [58, 46], [104, 88], [148, 34], [196, 112], [232, 58], [286, 30], [318, 96],
        [366, 52], [404, 108], [452, 40], [492, 84], [524, 128], [82, 132], [172, 152],
        [252, 140], [340, 148], [428, 158], [512, 46], [126, 62], [212, 82], [300, 116],
        [388, 78], [468, 132], [538, 92], [40, 104], [66, 168], [158, 108], [230, 30],
        [274, 168], [352, 122], [420, 60], [478, 96], [546, 150], [96, 26], [190, 46],
        [264, 100], [312, 62], [396, 132], [440, 88], [500, 116], [30, 66], [136, 176],
        [222, 158], [330, 44], [412, 172], [486, 60], [530, 34], [72, 92], [180, 130],
        [258, 52], [346, 92], [434, 116], [462, 176], [518, 168], [50, 140], [116, 44]
      ];
      out += '<g fill="#fff">';
      for (var i = 0; i < Math.min(era.stars, seeds.length); i++) {
        var r = 0.7 + (i % 3) * 0.42;
        out += '<circle cx="' + seeds[i][0] + '" cy="' + seeds[i][1] + '" r="' + round(r)
          + '" opacity="' + (0.34 + (i % 4) * 0.16) + '"'
          + (i % 5 === 0 ? ' class="cs-twinkle"' : '') + '/>';
      }
      out += '</g>';
    }

    // 太陽（昼）／月（夜）。にじみはぼかしフィルタではなく放射グラデで作る
    out += '<circle cx="' + era.orbX + '" cy="' + era.orbY + '" r="' + (era.orbR * 3.4)
      + '" fill="url(#cs-orb-glow)"/>'
      + '<circle cx="' + era.orbX + '" cy="' + era.orbY + '" r="' + era.orbR + '" fill="' + era.orb + '"'
      + (era.stars >= 26 ? ' mask="url(#cs-moon)"' : '') + '/>';
    // 月は mask で欠けさせる（空の色で塗り潰すと、雲や星と重なった時に破綻する）

    // 雲。夜は薄くする
    var cloudAlpha = era.lights ? (era.stars ? 0.16 : 0.3) : 0.5;
    out += '<g fill="#fff" opacity="' + cloudAlpha + '">'
      + cloud(126, 122, 1.15) + cloud(392, 96, 0.86) + cloud(238, 62, 0.66)
      + '</g>';

    // 飛行機。空港ラウンジ店・グローバルブランド、またはLv.10以降
    if (flags.plane || level >= 10) {
      out += '<g transform="translate(150,132) rotate(-8)" opacity="0.9">'
        + '<path d="M0 0 L26 -1.6 L34 0 L26 1.6 Z" fill="#f2f5f8"/>'
        + '<path d="M12 0 L4 -9 L9 -9 L18 -0.8 Z" fill="#dde4ea"/>'
        + '<path d="M12 0 L4 9 L9 9 L18 0.8 Z" fill="#c9d3db"/>'
        + '<path d="M-34 2 H-2" stroke="#fff" stroke-width="1.4" opacity="0.35" stroke-linecap="round"/>'
        + '</g>';
    }

    // 打ち上げ花火。都市コーヒーフェス・ワールドEXPO、またはLv.11以降。
    // 明るい空では花火は目立たないので、昼のランクでは薄くしておく
    if (flags.fireworks || level >= 11) {
      out += '<g opacity="' + (era.lights ? 1 : 0.5) + '">'
        + firework(388, 128, '#ffe6a4', 1) + firework(452, 168, '#ff9dd2', 0.72)
        + firework(330, 158, '#8fd8ef', 0.58) + '</g>';
    }
    return out;
  }

  function cloud(x, y, scale) {
    return '<g transform="translate(' + x + ',' + y + ') scale(' + scale + ')">'
      + '<ellipse rx="34" ry="9"/><ellipse cx="-16" cy="2" rx="18" ry="6.4"/>'
      + '<ellipse cx="8" cy="-5" rx="20" ry="9"/><ellipse cx="24" cy="1" rx="16" ry="6"/>'
      + '</g>';
  }

  function firework(x, y, color, scale) {
    var rays = '';
    for (var i = 0; i < 12; i++) {
      var a = (Math.PI * 2 / 12) * i;
      var inner = 5;
      var outer = 17;
      rays += '<line x1="' + round(Math.cos(a) * inner) + '" y1="' + round(Math.sin(a) * inner)
        + '" x2="' + round(Math.cos(a) * outer) + '" y2="' + round(Math.sin(a) * outer) + '"/>';
    }
    return '<g class="cs-spark" transform="translate(' + x + ',' + y + ') scale(' + scale + ')" '
      + 'stroke="' + color + '" stroke-width="1.7" stroke-linecap="round" opacity="0.9">'
      + rays + '<circle r="2.4" fill="' + color + '" stroke="none"/></g>';
  }

  /* 遠景のビル。[左端x, 高さ, 幅] を接地線 WALK から立ち上げる。 */
  var CITY_BLOCKS = [
    [-6, 88, 66], [66, 116, 48], [110, 70, 58], [164, 132, 42], [202, 96, 52],
    [250, 148, 40], [286, 108, 56], [338, 138, 46], [380, 82, 62], [438, 124, 50],
    [484, 92, 58], [536, 130, 44]
  ];

  /* ランクごとに出すビルの番号。増やす順はここで決め打ちにして、
     育っても既にあったビルが消えないようにしている。 */
  var CITY_KEEP = {
    3: [0, 3, 4, 7, 9, 11],
    4: [0, 2, 4, 6, 8, 9, 10, 11],
    5: [0, 2, 3, 4, 6, 7, 8, 9, 10, 11],
    6: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
  };

  /**
   * 遠景の街並み。ランクが上がるほど数が増え、夜は窓に灯りが入る。
   * 器が横長のときに空だけの帯ができないよう、同じ並びを左右にも繰り返す。
   */
  function cityLayer(era, structure) {
    if (structure < 3) { return ''; }
    var keep = CITY_KEEP[clamp(structure, 3, 6)];
    var body = '';
    var windows = '';

    [-1120, -560, 0, 560, 1120].forEach(function (shift) {
      keep.forEach(function (index) {
        var block = CITY_BLOCKS[index];
        var x = block[0] + shift;
        var height = block[1];
        var width = block[2];
        var y = WALK - height;
        body += '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height + '"/>';

        // 屋上の塔屋。同じ高さのビルが並ぶと単調なので、1つ飛ばしで載せる
        if (index % 2 === 0) {
          body += '<rect x="' + (x + width * 0.32) + '" y="' + (y - 9) + '" width="'
            + round(width * 0.36) + '" height="9"/>';
        }

        // 窓。灯りのあるランクだけ、格子状に散らす
        if (!era.lights) { return; }
        for (var row = 0; row < Math.floor(height / 17); row++) {
          for (var col = 0; col < Math.floor(width / 15); col++) {
            if ((row * 7 + col * 3 + index) % 4 !== 0) { continue; }
            windows += '<rect x="' + (x + 6 + col * 15) + '" y="' + (y + 10 + row * 17)
              + '" width="5" height="7"/>';
          }
        }
      });
    });

    return '<g fill="' + era.city + '" opacity="' + (era.lights ? 0.72 : 0.5) + '">' + body + '</g>'
      + (windows ? '<g fill="' + era.glow + '" opacity="0.5">' + windows + '</g>' : '');
  }

  /** 街路樹。ランク3で1本、ランク6で2本目が生える。 */
  function treeLayer(era, structure) {
    if (structure < 3) { return ''; }
    var out = tree(112, WALK, 1, era);
    if (structure >= 6) { out += tree(446, WALK, 0.84, era); }
    return out;
  }

  function tree(x, feet, scale, era) {
    return '<g transform="translate(' + x + ',' + feet + ') scale(' + scale + ')">'
      + '<ellipse cy="1" rx="20" ry="4" fill="#000" opacity="0.2"/>'
      + '<path d="M-3.4 0 L-2.2 -34 H2.2 L3.4 0 Z" fill="' + era.treeLo + '"/>'
      + '<path d="M-2.2 -22 L-11 -30" stroke="' + era.treeLo + '" stroke-width="2.4" fill="none"/>'
      + '<path d="M2.2 -26 L10 -33" stroke="' + era.treeLo + '" stroke-width="2.4" fill="none"/>'
      + '<g fill="' + era.tree + '">'
      + '<ellipse cx="-13" cy="-38" rx="17" ry="14"/><ellipse cx="12" cy="-41" rx="18" ry="15"/>'
      + '<ellipse cy="-54" rx="20" ry="16"/>'
      + '</g>'
      + '<g fill="' + era.treeLo + '" opacity="0.55">'
      + '<ellipse cx="-15" cy="-32" rx="12" ry="8"/><ellipse cx="14" cy="-34" rx="12" ry="8"/>'
      + '</g>'
      + '</g>';
  }

  /**
   * 歩道・縁石・車道。どのランクでも同じ位置なので、育っても足元がぶれない。
   * 横は viewBox の外まで、下は器が縦長でも余白が出ないところまで伸ばす。
   */
  function groundLayer(era) {
    var bottom = OSY + OSH;
    var out = '<rect x="' + OSX + '" y="' + WALK + '" width="' + OSW + '" height="' + (bottom - WALK)
      + '" fill="' + era.walk + '"/>'
      + '<rect x="' + OSX + '" y="' + WALK + '" width="' + OSW + '" height="3" fill="' + era.walkHi + '"/>';

    // 敷石の目地
    out += '<g stroke="' + era.walkHi + '" stroke-width="1" opacity="0.4">';
    for (var x = OSX + 18; x < OSX + OSW; x += 46) {
      out += '<line x1="' + x + '" y1="' + (WALK + 3) + '" x2="' + (x - 5) + '" y2="' + CURB + '"/>';
    }
    out += '<line x1="' + OSX + '" y1="' + (WALK + 15) + '" x2="' + (OSX + OSW) + '" y2="'
      + (WALK + 15) + '"/></g>';

    // 縁石と車道
    out += '<rect x="' + OSX + '" y="' + CURB + '" width="' + OSW + '" height="4" fill="' + era.walkHi
      + '" opacity="0.75"/>'
      + '<rect x="' + OSX + '" y="' + (CURB + 4) + '" width="' + OSW + '" height="' + (NEAR_CURB - CURB - 4)
      + '" fill="' + era.road + '"/>'
      + '<g stroke="' + era.lane + '" stroke-width="3" stroke-linecap="round" opacity="0.7">';
    for (var lane = OSX + 12; lane < OSX + OSW; lane += 62) {
      out += '<line x1="' + lane + '" y1="' + (H - 6) + '" x2="' + (lane + 30) + '" y2="' + (H - 6) + '"/>';
    }
    out += '</g>';

    // 手前側の縁石と歩道。器が縦長のときに車道だけの帯が残らないようにする。
    // 通常の比率では viewBox の下端（y=344）より下なので見えない。
    out += '<rect x="' + OSX + '" y="' + NEAR_CURB + '" width="' + OSW + '" height="4" fill="' + era.walkHi
      + '" opacity="0.75"/>'
      + '<rect x="' + OSX + '" y="' + (NEAR_CURB + 4) + '" width="' + OSW + '" height="'
      + (bottom - NEAR_CURB - 4) + '" fill="' + era.walk + '"/>'
      + '<line x1="' + OSX + '" y1="' + (NEAR_CURB + 22) + '" x2="' + (OSX + OSW) + '" y2="'
      + (NEAR_CURB + 22) + '" stroke="' + era.walkHi + '" stroke-width="1" opacity="0.4"/>';
    return out;
  }

  /* ------------------------------------------------------------------- 建物 */

  /** ランク1の屋台。台車と日除けだけの、いちばん小さな店。 */
  function cartLayer(era, flags) {
    var x0 = STRUCTURES[1].x0;
    var x1 = STRUCTURES[1].x1;
    var mid = (x0 + x1) / 2;
    var deck = 252;         // 天板の高さ
    var canopy = 196;       // 日除けの下端

    var out = '<ellipse cx="' + mid + '" cy="' + (WALK + 2) + '" rx="82" ry="7" fill="#000" opacity="0.22"/>';

    // 支柱と日除け
    out += '<g stroke="' + era.trim + '" stroke-width="4" stroke-linecap="round">'
      + '<line x1="' + (x0 + 10) + '" y1="' + canopy + '" x2="' + (x0 + 10) + '" y2="' + deck + '"/>'
      + '<line x1="' + (x1 - 10) + '" y1="' + canopy + '" x2="' + (x1 - 10) + '" y2="' + deck + '"/></g>'
      + awning(era, x0 - 6, x1 + 6, canopy - 26, canopy, 'cs-cart-awn');

    // バリスタは天板の後ろに立たせる
    out += person(mid + 26, deck + 4, 44, 'barista', era);

    // 台車本体
    out += '<rect x="' + x0 + '" y="' + deck + '" width="' + (x1 - x0) + '" height="8" rx="2" fill="'
      + era.wallHi + '"/>'
      + '<rect x="' + (x0 + 6) + '" y="' + (deck + 8) + '" width="' + (x1 - x0 - 12) + '" height="30" fill="'
      + era.wall + '"/>'
      + '<rect x="' + (x0 + 6) + '" y="' + (deck + 8) + '" width="' + (x1 - x0 - 12) + '" height="30" fill="none" stroke="'
      + era.trim + '" stroke-width="2"/>';

    // 前板の羽目板
    out += '<g stroke="' + era.wallLo + '" stroke-width="1.4" opacity="0.7">';
    for (var p = x0 + 16; p < x1 - 12; p += 12) {
      out += '<line x1="' + p + '" y1="' + (deck + 10) + '" x2="' + p + '" y2="' + (deck + 36) + '"/>';
    }
    out += '</g>';

    // 車輪
    out += '<g fill="' + era.trim + '">'
      + '<circle cx="' + (x0 + 26) + '" cy="' + (WALK - 8) + '" r="10"/>'
      + '<circle cx="' + (x1 - 26) + '" cy="' + (WALK - 8) + '" r="10"/></g>'
      + '<g fill="' + era.walkHi + '" opacity="0.8">'
      + '<circle cx="' + (x0 + 26) + '" cy="' + (WALK - 8) + '" r="3.4"/>'
      + '<circle cx="' + (x1 - 26) + '" cy="' + (WALK - 8) + '" r="3.4"/></g>';

    // 天板の上のもの
    out += cupMark(mid - 34, deck - 4, 20, era.awningAlt, era.trim, '#ffffff');
    if (flags.machine) { out += machine(era, mid + 2, deck, 0.85, flags.machinePro); }

    // 小さな立て看板
    out += '<g transform="translate(' + (x0 - 24) + ',' + WALK + ')">'
      + '<path d="M-11 0 L-4 -34 H4 L11 0 Z" fill="' + era.roof + '"/>'
      + '<rect x="-9" y="-31" width="18" height="24" rx="1.5" fill="#26201d"/>'
      + '<g stroke="' + era.awningAlt + '" stroke-width="1.2" opacity="0.85">'
      + '<line x1="-5" y1="-25" x2="5" y2="-25"/><line x1="-5" y1="-21" x2="3" y2="-21"/>'
      + '<line x1="-5" y1="-17" x2="5" y2="-17"/><line x1="-5" y1="-13" x2="1" y2="-13"/></g>'
      + '</g>';

    return out;
  }

  /** ランク2〜7の店舗。骨格 st の各段を上から順に積んでいく。 */
  function shopLayer(era, st, flags, structure) {
    var x0 = st.x0;
    var x1 = st.x1;
    var w = x1 - x0;
    var out = '<ellipse cx="' + MIDX + '" cy="' + (WALK + 2) + '" rx="' + (w / 2 + 26)
      + '" ry="8" fill="#000" opacity="0.24"/>';

    // 壁面と、控えめな石目
    out += '<rect x="' + x0 + '" y="' + st.top + '" width="' + w + '" height="' + (WALK - st.top)
      + '" fill="url(#cs-wall)"/>'
      + '<g stroke="' + era.wallLo + '" stroke-width="1" opacity="0.22">';
    for (var band = st.top + 14; band < PLINTH; band += 14) {
      out += '<line x1="' + x0 + '" y1="' + band + '" x2="' + x1 + '" y2="' + band + '"/>';
    }
    out += '</g>';

    // 両端の付け柱。建物の輪郭をはっきりさせる
    out += '<rect x="' + x0 + '" y="' + st.top + '" width="7" height="' + (WALK - st.top)
      + '" fill="' + era.trim + '" opacity="0.85"/>'
      + '<rect x="' + (x1 - 7) + '" y="' + st.top + '" width="7" height="' + (WALK - st.top)
      + '" fill="' + era.trim + '" opacity="0.85"/>';

    // 軒飾り（ランク7のみ）
    if (st.cornice) {
      var ch = st.cornice[1] - st.cornice[0];
      out += '<rect x="' + (x0 - 4) + '" y="' + st.cornice[0] + '" width="' + (w + 8) + '" height="' + ch
        + '" fill="' + era.roof + '"/>'
        + '<rect x="' + (x0 - 4) + '" y="' + st.cornice[0] + '" width="' + (w + 8) + '" height="3" fill="'
        + era.roofHi + '"/>'
        + '<g fill="' + era.trim + '" opacity="0.5">';
      for (var dent = x0 + 4; dent < x1 - 8; dent += 13) {
        out += '<rect x="' + dent + '" y="' + (st.cornice[1] - 5) + '" width="6" height="5"/>';
      }
      out += '</g>';
    }

    // 屋根。軒を左右に出して、壁より一段前に見せる
    var roofTop = st.top - 13;
    out += '<rect x="' + (x0 - 11) + '" y="' + roofTop + '" width="' + (w + 22) + '" height="13" rx="2" fill="'
      + era.roof + '"/>'
      + '<rect x="' + (x0 - 11) + '" y="' + roofTop + '" width="' + (w + 22) + '" height="4" rx="2" fill="'
      + era.roofHi + '"/>'
      + '<rect x="' + x0 + '" y="' + st.top + '" width="' + w + '" height="4" fill="#000" opacity="0.18"/>';

    if (flags.chimney) { out += chimney(era, x0 + 34, roofTop); }
    if (flags.roofMark) { out += roofEmblem(era, MIDX, roofTop); }

    // 上階の窓
    if (st.upper) { out += upperFloor(era, st, w); }

    // 看板
    out += signBoard(era, st, flags, structure);

    // 日除け
    if (st.awning) {
      out += awning(era, x0 - 8, x1 + 8, st.awning[0], st.awning[1], 'cs-shop-awn');
    }

    // 壁付けの灯り
    if (flags.wallLamp) {
      var lampY = st.sign[0] - 8;
      out += wallLamp(era, x0 + 24, lampY) + wallLamp(era, x1 - 24, lampY);
    }

    // 1階のガラス面と入口
    var front = frontage(era, st, flags, structure);
    out += front.markup;

    // 土台
    out += '<rect x="' + x0 + '" y="' + PLINTH + '" width="' + w + '" height="' + (WALK - PLINTH)
      + '" fill="' + era.trim + '"/>'
      + '<rect x="' + x0 + '" y="' + PLINTH + '" width="' + w + '" height="2" fill="' + era.wallHi
      + '" opacity="0.5"/>';

    return { markup: out, doorX0: front.doorX0, doorX1: front.doorX1 };
  }

  /** 上階の窓。幅に応じて3〜5枚に割る。 */
  function upperFloor(era, st, w) {
    var y0 = st.upper[0];
    var height = st.upper[1] - st.upper[0];
    var count = clamp(Math.round(w / 62), 3, 5);
    var span = w - 34;
    var pitch = span / count;
    var pane = Math.min(pitch - 12, 38);
    var out = '';

    for (var i = 0; i < count; i++) {
      var x = st.x0 + 17 + pitch * i + (pitch - pane) / 2;
      out += '<rect x="' + round(x) + '" y="' + y0 + '" width="' + round(pane) + '" height="' + height
        + '" rx="1.5" fill="url(#cs-glass)"/>'
        + (era.lights
          ? '<rect x="' + round(x) + '" y="' + y0 + '" width="' + round(pane) + '" height="' + height
            + '" rx="1.5" fill="' + era.glow + '" opacity="0.32"/>'
          : '')
        + '<rect x="' + round(x) + '" y="' + y0 + '" width="' + round(pane) + '" height="' + height
        + '" rx="1.5" fill="none" stroke="' + era.trim + '" stroke-width="3"/>'
        + '<line x1="' + round(x + pane / 2) + '" y1="' + y0 + '" x2="' + round(x + pane / 2) + '" y2="'
        + (y0 + height) + '" stroke="' + era.trim + '" stroke-width="2"/>'
        + '<line x1="' + round(x) + '" y1="' + (y0 + height * 0.42) + '" x2="' + round(x + pane)
        + '" y2="' + (y0 + height * 0.42) + '" stroke="' + era.trim + '" stroke-width="2"/>'
        // 窓台
        + '<rect x="' + round(x - 3) + '" y="' + (y0 + height) + '" width="' + round(pane + 6)
        + '" height="3" fill="' + era.roof + '"/>';
    }
    return out;
  }

  /** 看板。ランクが上がるほど大きく、終盤はネオンの縁が付く。 */
  function signBoard(era, st, flags, structure) {
    var y0 = st.sign[0];
    var height = st.sign[1] - st.sign[0];
    var x0 = st.x0 + 16;
    var width = st.x1 - 16 - x0;
    var mid = st.x0 + (st.x1 - st.x0) / 2;
    var big = height >= 20;

    var out = '<rect x="' + x0 + '" y="' + y0 + '" width="' + width + '" height="' + height
      + '" rx="3" fill="#241b18"/>'
      + '<rect x="' + x0 + '" y="' + y0 + '" width="' + width + '" height="' + round(height * 0.45)
      + '" rx="3" fill="#ffffff" opacity="0.06"/>'
      + '<rect x="' + x0 + '" y="' + y0 + '" width="' + width + '" height="' + height
      + '" rx="3" fill="none" stroke="' + (era.neon || '#d8ad65') + '" stroke-width="2"/>';

    // 終盤はネオンの外側ににじみを足す
    if (era.neon && structure >= 6) {
      out += '<rect x="' + (x0 - 2) + '" y="' + (y0 - 2) + '" width="' + (width + 4) + '" height="'
        + (height + 4) + '" rx="5" fill="none" stroke="' + era.neon
        + '" stroke-width="3" opacity="0.28"/>';
    }

    var textY = y0 + height / 2 + (big ? -1 : 4);
    out += cupMark(x0 + 20, y0 + height / 2 + 4, big ? 17 : 14, era.awningAlt, '#241b18', null)
      + '<text x="' + round(mid + 10) + '" y="' + round(textY)
      + '" text-anchor="middle" fill="#f4d89d" font-family="' + FONT
      + '" font-size="' + (big ? 13 : 11) + '" font-weight="700" letter-spacing="1.6">JAVA CAFÉ</text>';
    if (big) {
      out += '<text x="' + round(mid + 10) + '" y="' + round(y0 + height - 5)
        + '" text-anchor="middle" fill="#d9b97e" font-family="' + FONT
        + '" font-size="5.5" letter-spacing="1.4">COFFEE &amp; CODE</text>';
    }

    // 音符（朝のプレイリスト）
    if (flags.music) {
      out += '<g class="cs-music" transform="translate(' + (st.x1 - 26) + ',' + (y0 + 5) + ')" fill="'
        + (era.neon || era.glow) + '">'
        + '<ellipse cx="-3.4" cy="0" rx="3.4" ry="2.6" transform="rotate(-18 -3.4 0)"/>'
        + '<path d="M-0.4 -0.8 L-0.4 -11 L5 -13 L5 -10 L1.6 -8.6 L1.6 -0.8 Z"/></g>';
    }
    return out;
  }

  /** 縞の日除け。下端はスカラップ（波形）にして布らしく見せる。 */
  function awning(era, x0, x1, y0, y1, clipId) {
    var width = x1 - x0;
    var drop = 6;
    var inset = 6;
    var bumps = Math.max(4, Math.round((width - inset * 2) / 17));
    var step = (width - inset * 2) / bumps;

    var edge = 'M' + x0 + ' ' + y0 + ' H' + x1 + ' L' + (x1 - inset) + ' ' + y1;
    for (var i = 0; i < bumps; i++) {
      edge += ' q' + round(-step / 2) + ' ' + drop + ' ' + round(-step) + ' 0';
    }
    edge += ' L' + x0 + ' ' + y0 + ' Z';

    var stripes = '';
    var count = Math.max(4, Math.round(width / 25));
    var pitch = width / count;
    for (var s = 0; s < count; s++) {
      stripes += '<rect x="' + round(x0 + pitch * s) + '" y="' + (y0 - 2) + '" width="' + round(pitch + 1)
        + '" height="' + (y1 - y0 + drop + 4) + '" fill="' + (s % 2 ? era.awning : era.awningAlt) + '"/>';
    }

    return '<clipPath id="' + clipId + '"><path d="' + edge + '"/></clipPath>'
      + '<g clip-path="url(#' + clipId + ')">' + stripes
      + '<rect x="' + x0 + '" y="' + y0 + '" width="' + width + '" height="' + (y1 - y0 + drop)
      + '" fill="url(#cs-cloth)"/></g>'
      + '<path d="' + edge + '" fill="none" stroke="' + era.trim + '" stroke-width="2"/>'
      + '<rect x="' + x0 + '" y="' + (y0 - 3) + '" width="' + width + '" height="4" rx="1.5" fill="'
      + era.trim + '"/>';
  }

  /** 1階のガラス面・ラウンジ・入口。戻り値の doorX* は路上の小物の基準に使う。 */
  function frontage(era, st, flags, structure) {
    var gx0 = st.x0 + 9;
    var gx1 = st.x1 - 9;
    var gy0 = st.glassTop;
    var height = PLINTH - gy0;
    var doorWidth = gx1 - gx0 > 140 ? 48 : 42;
    var doorX0 = gx1 - doorWidth;
    var out = '';

    // ガラス面（入口の左側）を、ラウンジがあるときは2枚に割る
    var paneX1 = doorX0 - 7;
    var paneWidth = paneX1 - gx0;
    var lounge = flags.lounge && paneWidth >= 132;
    var mainWidth = lounge ? round(paneWidth * 0.58) : paneWidth;

    out += mainWindow(era, gx0, gy0, mainWidth, height, flags, 0);
    if (lounge) {
      out += loungeWindow(era, gx0 + mainWidth + 7, gy0, paneWidth - mainWidth - 7, height, flags, structure);
    }
    out += door(era, doorX0, gy0, doorWidth, height, flags);

    return { markup: out, doorX0: doorX0, doorX1: gx1 };
  }

  /** 窓わく・映り込み・室内灯の共通部分。中身は inner に描いてもらう。 */
  function glassPane(era, x, y, width, height, id, inner) {
    return '<clipPath id="' + id + '"><rect x="' + x + '" y="' + y + '" width="' + width
      + '" height="' + height + '" rx="2"/></clipPath>'
      + '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height
      + '" rx="2" fill="url(#cs-glass)"/>'
      + '<g clip-path="url(#' + id + ')">'
      + (era.lights
        ? '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height
          + '" fill="url(#cs-inner)"/>'
        : '')
      + inner
      // 映り込み。斜めの帯を2本流す
      + '<g fill="#ffffff" opacity="0.1">'
      + '<path d="M' + (x + width * 0.1) + ' ' + (y + height) + ' L' + (x + width * 0.42) + ' ' + y
      + ' h' + round(width * 0.13) + ' L' + (x + width * 0.23) + ' ' + (y + height) + ' Z"/>'
      + '<path d="M' + (x + width * 0.55) + ' ' + (y + height) + ' L' + (x + width * 0.8) + ' ' + y
      + ' h' + round(width * 0.07) + ' L' + (x + width * 0.62) + ' ' + (y + height) + ' Z"/>'
      + '</g></g>'
      + '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height
      + '" rx="2" fill="none" stroke="' + era.trim + '" stroke-width="5"/>'
      + '<line x1="' + x + '" y1="' + (y + 13) + '" x2="' + (x + width) + '" y2="' + (y + 13)
      + '" stroke="' + era.trim + '" stroke-width="3"/>';
  }

  /** カウンターのある主役の窓。設備はここに増えていく。 */
  function mainWindow(era, x, y, width, height, flags, index) {
    var floor = y + height;
    var counterY = floor - 17;
    var mid = x + width / 2;
    var inner = '';

    // 奥の壁と床
    inner += '<rect x="' + x + '" y="' + (floor - 30) + '" width="' + width + '" height="30" fill="'
      + era.trim + '" opacity="0.28"/>';

    // 吊り下げ照明
    if (flags.pendant) {
      inner += pendant(era, mid - width * 0.24, y + 13) + pendant(era, mid + width * 0.24, y + 13);
    }

    // 棚とカップ
    if (flags.shelf) {
      var shelfY = y + 30;
      var shelfX0 = x + 10;
      var shelfX1 = x + width - 10;
      inner += '<rect x="' + shelfX0 + '" y="' + shelfY + '" width="' + (shelfX1 - shelfX0)
        + '" height="3" fill="' + era.roof + '"/>';
      var mugs = Math.max(3, Math.floor((shelfX1 - shelfX0) / 22));
      for (var m = 0; m < mugs; m++) {
        var mx = shelfX0 + (shelfX1 - shelfX0) * (m + 0.5) / mugs;
        inner += '<rect x="' + round(mx - 4) + '" y="' + (shelfY - 9) + '" width="8" height="9" rx="1.5" fill="'
          + (m % 2 ? era.awningAlt : era.glassHi) + '"/>'
          + '<path d="M' + round(mx + 4.6) + ' ' + (shelfY - 6.5) + ' q2.6 1.6 0 3.6" fill="none" stroke="'
          + (m % 2 ? era.awningAlt : era.glassHi) + '" stroke-width="1.3"/>';
      }
    }

    // カウンターの上は左からショーケース → バリスタ → マシンの順に並べる。
    // 同じあたりに置くと重なって何の設備か分からなくなるので、両端に振り分けている。
    var hasCase = flags.showcase && width >= 92;

    inner += flags.robot
      ? robot(mid, counterY + 5, 46, era)
      : person(mid, counterY + 5, 46, 'barista', era);
    if (hasCase) { inner += showcase(era, x + 10, counterY); }
    if (flags.machine) {
      // 狭い窓では等倍だとマシンだけが目立ってしまうので、窓幅に合わせて縮める
      var scale = Math.min(1, width / 92);
      inner += machine(era, x + width - 26 * scale, counterY, round(scale), flags.machinePro);
    }

    // カウンター
    inner += '<rect x="' + x + '" y="' + counterY + '" width="' + width + '" height="17" fill="'
      + era.roof + '"/>'
      + '<rect x="' + x + '" y="' + counterY + '" width="' + width + '" height="4" fill="' + era.roofHi + '"/>'
      + '<rect x="' + x + '" y="' + (counterY + 4) + '" width="' + width + '" height="13" fill="#000" opacity="0.18"/>'
      + cupMark(x + (hasCase ? 66 : 18), counterY + 1, 15, era.awningAlt, era.trim,
        era.lights ? '#ffffff' : null);

    return glassPane(era, x, y, width, height, 'cs-pane-' + index, inner);
  }

  /** くつろぎスペースの窓。ソファとローテーブルを置く。 */
  function loungeWindow(era, x, y, width, height, flags, structure) {
    var floor = y + height;
    var mid = x + width / 2;
    var sofaColor = flags.loungePro ? '#78597a' : '#4f7369';
    var sofaEdge = flags.loungePro ? '#4b384e' : '#334e49';
    var inner = '';

    inner += '<rect x="' + x + '" y="' + (floor - 26) + '" width="' + width + '" height="26" fill="'
      + era.trim + '" opacity="0.28"/>';

    if (flags.pendant) { inner += pendant(era, mid, y + 13); }

    // 壁のアート
    inner += '<rect x="' + round(mid - 15) + '" y="' + (y + 22) + '" width="30" height="20" rx="1.5" fill="'
      + era.awningAlt + '"/>'
      + '<rect x="' + round(mid - 15) + '" y="' + (y + 22) + '" width="30" height="20" rx="1.5" fill="none" stroke="'
      + era.trim + '" stroke-width="2"/>'
      + '<text x="' + round(mid) + '" y="' + (y + 36) + '" text-anchor="middle" fill="' + era.trim
      + '" font-family="' + FONT + '" font-size="9" font-weight="700">&lt;/&gt;</text>';

    // 客（ランク6以上）。頭が中桟より上に出ると、棒に刺さったように見えるので低く座らせる
    if (structure >= 6) { inner += person(mid + width * 0.26, floor - 8, 30, 'guestC', era); }

    // ソファとローテーブル。背もたれ・肘掛け・座面を描き分けないと、
    // ただの色の板に見えてしまうので3段に分けている
    var sofaX = x + 8;
    var sofaW = width - 16;
    inner += '<rect x="' + sofaX + '" y="' + (floor - 26) + '" width="' + sofaW + '" height="13" rx="4" fill="'
      + sofaColor + '"/>'
      + '<rect x="' + sofaX + '" y="' + (floor - 15) + '" width="' + sofaW + '" height="8" rx="3" fill="'
      + sofaEdge + '"/>'
      + '<rect x="' + sofaX + '" y="' + (floor - 20) + '" width="6" height="14" rx="3" fill="' + sofaEdge + '"/>'
      + '<rect x="' + (sofaX + sofaW - 6) + '" y="' + (floor - 20) + '" width="6" height="14" rx="3" fill="'
      + sofaEdge + '"/>'
      + '<line x1="' + round(sofaX + sofaW / 2) + '" y1="' + (floor - 25) + '" x2="' + round(sofaX + sofaW / 2)
      + '" y2="' + (floor - 15) + '" stroke="' + sofaEdge + '" stroke-width="1.4" opacity="0.7"/>'
      + '<rect x="' + (sofaX + 2) + '" y="' + (floor - 7) + '" width="' + (sofaW - 4) + '" height="5" rx="2" fill="'
      + era.trim + '"/>'
      + '<ellipse cx="' + round(mid) + '" cy="' + (floor - 4) + '" rx="15" ry="5" fill="' + era.roof + '"/>'
      + cupMark(round(mid), floor - 7, 11, era.awningAlt, era.trim, null);

    if (flags.loungePro) {
      // 会員ラウンジはフロアランプを1本足す
      inner += '<line x1="' + (x + 12) + '" y1="' + (floor - 2) + '" x2="' + (x + 12) + '" y2="' + (floor - 34)
        + '" stroke="' + era.trim + '" stroke-width="2"/>'
        + '<path d="M' + (x + 5) + ' ' + (floor - 34) + ' H' + (x + 19) + ' L' + (x + 16) + ' ' + (floor - 44)
        + ' H' + (x + 8) + ' Z" fill="' + era.glow + '"/>';
    }

    return glassPane(era, x, y, width, height, 'cs-pane-lounge', inner);
  }

  /** 入口。灯りのあるランクでは、光が歩道へこぼれる。 */
  function door(era, x, y, width, height, flags) {
    var mid = x + width / 2;
    var out = '';

    // 歩道へこぼれる光。建物より先に敷く
    if (era.lights) {
      out += '<path d="M' + (x + 4) + ' ' + WALK + ' L' + (x - 16) + ' ' + (CURB + 6) + ' H'
        + (x + width + 22) + ' L' + (x + width - 4) + ' ' + WALK + ' Z" fill="' + era.glow
        + '" opacity="0.16"/>';
    }

    // ランクによって扉の高さが違うので、蹴込み板とOPENの札は高さに対する割合で置く。
    // 固定値だと、背の低いランク2〜3で札が蹴込み板に乗ってしまう。
    var tall = WALK - y;
    var kick = round(Math.min(22, tall * 0.3));
    var plateY = round(y + Math.max(22, tall * 0.42));

    out += '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + tall
      + '" rx="2" fill="url(#cs-glass)"/>'
      + (era.lights
        ? '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + tall + '" fill="'
          + era.glow + '" opacity="0.24"/>'
        : '')
      // 上部の明かり窓
      + '<rect x="' + (x + 5) + '" y="' + (y + 5) + '" width="' + (width - 10) + '" height="14" rx="1.5" fill="'
      + era.glassHi + '" opacity="0.55"/>'
      + '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + tall
      + '" rx="2" fill="none" stroke="' + era.trim + '" stroke-width="5"/>'
      // 下部の蹴込み板
      + '<rect x="' + (x + 4) + '" y="' + (WALK - kick) + '" width="' + (width - 8) + '" height="' + (kick - 4)
      + '" fill="' + era.roof + '"/>'
      // OPEN の札
      + '<rect x="' + round(mid - 17) + '" y="' + plateY + '" width="34" height="13" rx="2" fill="#241b18"/>'
      + '<text x="' + round(mid) + '" y="' + (plateY + 9) + '" text-anchor="middle" fill="'
      + (era.neon || '#f6e9bd') + '" font-family="' + FONT
      + '" font-size="7.5" font-weight="700" letter-spacing="1">OPEN</text>'
      // 取っ手
      + '<rect x="' + round(mid + width * 0.28) + '" y="' + round(y + height * 0.52) + '" width="3" height="14" rx="1.5" fill="'
      + (era.neon || '#f1cb70') + '"/>';

    if (flags.welcomeMat) {
      out += '<g transform="translate(' + round(mid) + ',' + (WALK + 9) + ')">'
        + '<ellipse rx="30" ry="8" fill="' + era.roof + '"/>'
        + '<ellipse rx="24" ry="5.4" fill="none" stroke="' + era.awningAlt + '" stroke-width="1.2" opacity="0.7"/>'
        + '<text y="2.6" text-anchor="middle" fill="' + era.awningAlt + '" font-family="' + FONT
        + '" font-size="6.5" font-weight="700" letter-spacing="1.2">WELCOME</text></g>';
    }
    return out;
  }

  /* ------------------------------------------------------------- 設備のパーツ */

  function pendant(era, x, y) {
    return '<g transform="translate(' + round(x) + ',' + round(y) + ')">'
      + '<line y2="12" stroke="' + era.trim + '" stroke-width="1.4"/>'
      + '<path d="M-7 22 Q0 10 7 22 Z" fill="' + era.trim + '"/>'
      + '<ellipse cy="22" rx="7" ry="2.2" fill="' + era.glow + '"/>'
      + (era.lights
        ? '<ellipse cy="30" rx="15" ry="12" fill="' + era.glow + '" opacity="0.2"/>'
        : '')
      + '</g>';
  }

  /** 壁付けの灯り。笠は上向きに開かせ、下から電球が覗くようにする
      （下向きの台形だと、この寸法では黒い矢印に見えてしまう）。 */
  function wallLamp(era, x, y) {
    return '<g transform="translate(' + round(x) + ',' + round(y) + ')">'
      + (era.lights ? '<circle cy="12" r="22" fill="url(#cs-lamp)"/>' : '')
      + '<path d="M-6 -10 H6" stroke="' + era.trim + '" stroke-width="2" stroke-linecap="round"/>'
      + '<path d="M0 -10 V-2" stroke="' + era.trim + '" stroke-width="2"/>'
      + '<path d="M-8 6 L-4.4 -2 H4.4 L8 6 Z" fill="' + era.roof + '"/>'
      + '<path d="M-4.4 -2 H4.4 L5.6 1 H-5.6 Z" fill="' + era.roofHi + '"/>'
      + '<ellipse cy="6" rx="8" ry="2.4" fill="' + era.glow + '"/>'
      + '<circle cy="8.4" r="2.6" fill="' + era.glow + '"/>'
      + '</g>';
  }

  function chimney(era, x, top) {
    return '<rect x="' + x + '" y="' + (top - 26) + '" width="16" height="27" fill="' + era.roof + '"/>'
      + '<rect x="' + (x - 3) + '" y="' + (top - 30) + '" width="22" height="6" rx="1.5" fill="' + era.trim + '"/>'
      + '<g class="cs-smoke" fill="#ffffff" opacity="0.3">'
      + '<circle cx="' + (x + 8) + '" cy="' + (top - 38) + '" r="5"/>'
      + '<circle cx="' + (x + 14) + '" cy="' + (top - 50) + '" r="7"/>'
      + '<circle cx="' + (x + 7) + '" cy="' + (top - 63) + '" r="9"/>'
      + '</g>';
  }

  /** 屋上の記章。宙に浮かないよう、屋根まで届く支柱を付ける。 */
  function roofEmblem(era, x, top) {
    var ring = era.neon || '#e8c77c';
    return '<g transform="translate(' + x + ',' + top + ')">'
      + '<rect x="-9" y="-6" width="18" height="7" rx="2" fill="' + era.trim + '"/>'
      + '<rect x="-2.4" y="-16" width="4.8" height="11" fill="' + era.trim + '"/>'
      + '<circle cy="-30" r="16" fill="#241b18"/>'
      + '<circle cy="-30" r="16" fill="none" stroke="' + ring + '" stroke-width="2"/>'
      + (era.neon ? '<circle cy="-30" r="19" fill="none" stroke="' + ring + '" stroke-width="3" opacity="0.22"/>' : '')
      + cupMark(0, -24, 18, era.awningAlt, '#241b18', null)
      + '</g>';
  }

  function machine(era, x, counterY, scale, pro) {
    var body = pro ? '#d3d7dc' : '#8c8f95';
    var trim = pro ? '#9aa0a8' : '#6a6d73';
    return '<g transform="translate(' + round(x) + ',' + round(counterY) + ') scale(' + scale + ')">'
      + '<rect x="-15" y="-30" width="30" height="30" rx="2.5" fill="' + body + '"/>'
      + '<rect x="-15" y="-30" width="30" height="7" rx="2.5" fill="' + trim + '"/>'
      + '<rect x="-11" y="-21" width="22" height="7" rx="1.4" fill="#2f3238"/>'
      + '<text y="-15.4" text-anchor="middle" fill="' + (era.neon || '#f0d078') + '" font-family="' + FONT
      + '" font-size="5" font-weight="700" letter-spacing="0.6">JAVA</text>'
      + '<rect x="-9" y="-11" width="18" height="3" rx="1.5" fill="' + trim + '"/>'
      + '<rect x="-6" y="-8" width="4" height="5" fill="' + trim + '"/>'
      + '<rect x="2" y="-8" width="4" height="5" fill="' + trim + '"/>'
      + (pro
        ? '<rect x="-19" y="-34" width="38" height="5" rx="2" fill="' + trim + '"/>'
          + '<circle cx="-19" cy="-19" r="3.4" fill="' + trim + '"/>'
          + '<circle cx="19" cy="-19" r="3.4" fill="' + trim + '"/>'
          + '<g class="cs-steam" stroke="#ffffff" stroke-width="1.4" stroke-linecap="round" fill="none" opacity="0.5">'
          + '<path d="M-4 -36 q3 -3.4 0 -6.8"/><path d="M4 -37 q3 -3.4 0 -6.8"/></g>'
        : '')
      + '</g>';
  }

  function showcase(era, x, counterY) {
    return '<g transform="translate(' + round(x) + ',' + round(counterY) + ')">'
      + '<rect y="-24" width="44" height="24" rx="2" fill="' + era.glassHi + '" opacity="0.45"/>'
      + '<rect y="-24" width="44" height="24" rx="2" fill="none" stroke="' + era.trim + '" stroke-width="2"/>'
      + '<line y1="-13" x2="44" y2="-13" stroke="' + era.trim + '" stroke-width="1.4"/>'
      + '<g fill="#c67b50">'
      + '<circle cx="10" cy="-17" r="3.2"/><circle cx="22" cy="-17" r="3.2"/><circle cx="34" cy="-17" r="3.2"/>'
      + '<circle cx="12" cy="-6" r="3.2"/><circle cx="26" cy="-6" r="3.2"/></g>'
      + '</g>';
  }

  /* ---------------------------------------------------------------- 路上の小物 */

  /** 歩道と車道に置くもの。建物より手前に描く。 */
  function streetLayer(era, st, flags, structure, doorX0, doorX1) {
    var out = '';

    if (flags.planter) {
      out += planter(era, st.x0 + 16, WALK + 4) + planter(era, doorX1 + 14, WALK + 4);
    }
    if (flags.patio) { out += patio(era, st, doorX0); }
    if (flags.menuBoard) { out += menuBoard(era, doorX0 - 30, WALK + 8); }
    if (flags.vipRope) { out += vipRope(era, doorX0 - 6, doorX1 + 6, WALK + 4); }
    if (flags.delivery) { out += deliveryVan(era); }

    out += crowd(era, st, structure, doorX0);
    return out;
  }

  function planter(era, x, feet) {
    return '<g transform="translate(' + round(x) + ',' + round(feet) + ')">'
      + '<ellipse cy="1" rx="13" ry="3.4" fill="#000" opacity="0.2"/>'
      + '<path d="M-11 -14 H11 L9 0 H-9 Z" fill="' + era.roof + '"/>'
      + '<rect x="-12" y="-17" width="24" height="4" rx="1.4" fill="' + era.roofHi + '"/>'
      + '<g fill="' + era.tree + '">'
      + '<ellipse cx="-5" cy="-21" rx="7.4" ry="6"/><ellipse cx="5" cy="-23" rx="8" ry="6.4"/>'
      + '<ellipse cy="-29" rx="6.6" ry="5.4"/></g>'
      + '</g>';
  }

  /**
   * テラス席。真横から見た絵なので、日傘を立てると必ず看板やガラス面を覆ってしまう。
   * そのため日傘は使わず、低い手すりで席の範囲を示す方式に統一している。
   * 右端は立て看板の手前で止め、小物同士が重ならないようにしてある。
   */
  function patio(era, st, doorX0) {
    var x0 = st.x0 + 12;
    var x1 = Math.min(st.x0 + 96, doorX0 - 46);
    if (x1 - x0 < 44) { return ''; }
    var mid = (x0 + x1) / 2;

    // 手すり
    var out = '<g stroke="' + era.trim + '" stroke-width="2.6" stroke-linecap="round">'
      + '<line x1="' + x0 + '" y1="' + (WALK - 22) + '" x2="' + x1 + '" y2="' + (WALK - 22) + '"/>'
      + '<line x1="' + x0 + '" y1="' + (WALK - 22) + '" x2="' + x0 + '" y2="' + (WALK + 4) + '"/>'
      + '<line x1="' + round(mid) + '" y1="' + (WALK - 22) + '" x2="' + round(mid) + '" y2="' + (WALK + 4) + '"/>'
      + '<line x1="' + x1 + '" y1="' + (WALK - 22) + '" x2="' + x1 + '" y2="' + (WALK + 4) + '"/></g>';

    // 丸テーブルと椅子
    out += chair(era, x0 + 9, WALK + 2, -1)
      + chair(era, x1 - 9, WALK + 2, 1)
      + '<g transform="translate(' + round(mid) + ',' + (WALK + 2) + ')">'
      + '<ellipse cy="1" rx="17" ry="4" fill="#000" opacity="0.18"/>'
      + '<rect x="-1.8" y="-22" width="3.6" height="22" fill="' + era.trim + '"/>'
      + '<ellipse cy="-22" rx="19" ry="5.4" fill="' + era.roof + '"/>'
      + '<ellipse cy="-23.6" rx="19" ry="5.4" fill="' + era.roofHi + '"/></g>'
      + cupMark(round(mid), WALK - 24, 12, era.awningAlt, era.trim, era.lights ? '#ffffff' : null);
    return out;
  }

  function chair(era, x, feet, facing) {
    return '<g transform="translate(' + round(x) + ',' + round(feet) + ') scale(' + facing + ',1)">'
      + '<ellipse cy="0.6" rx="9" ry="2.6" fill="#000" opacity="0.16"/>'
      + '<rect x="-7" y="-12" width="14" height="3" rx="1.4" fill="' + era.roof + '"/>'
      + '<rect x="-6" y="-9" width="2.4" height="9" fill="' + era.trim + '"/>'
      + '<rect x="3.6" y="-9" width="2.4" height="9" fill="' + era.trim + '"/>'
      + '<path d="M4 -12 L6.4 -27 H8.8 L7 -12 Z" fill="' + era.trim + '"/>'
      + '<rect x="3" y="-26" width="6" height="2.4" rx="1.2" fill="' + era.roof + '"/>'
      + '</g>';
  }

  function menuBoard(era, x, feet) {
    return '<g transform="translate(' + round(x) + ',' + round(feet) + ')">'
      + '<ellipse cy="1" rx="16" ry="3.4" fill="#000" opacity="0.2"/>'
      + '<path d="M-13 0 L-5 -42 H5 L13 0 Z" fill="' + era.roof + '"/>'
      + '<rect x="-11" y="-39" width="22" height="30" rx="1.5" fill="#26201d"/>'
      + '<text y="-32" text-anchor="middle" fill="' + era.awningAlt + '" font-family="' + FONT
      + '" font-size="5.5" font-weight="700" letter-spacing="0.8">TODAY</text>'
      + '<g stroke="' + era.awningAlt + '" stroke-width="1.2" opacity="0.75">'
      + '<line x1="-7" y1="-27" x2="7" y2="-27"/><line x1="-7" y1="-23" x2="4" y2="-23"/>'
      + '<line x1="-7" y1="-19" x2="7" y2="-19"/><line x1="-7" y1="-15" x2="2" y2="-15"/></g>'
      + '</g>';
  }

  function vipRope(era, x0, x1, feet) {
    var post = function (x) {
      return '<g transform="translate(' + round(x) + ',' + round(feet) + ')">'
        + '<ellipse cy="0.6" rx="6" ry="2" fill="#000" opacity="0.18"/>'
        + '<rect x="-2" y="-30" width="4" height="30" fill="url(#cs-brass)"/>'
        + '<circle cy="-32" r="3.4" fill="url(#cs-brass)"/></g>';
    };
    return post(x0) + post(x1)
      + '<path d="M' + round(x0) + ' ' + (feet - 28) + ' Q' + round((x0 + x1) / 2) + ' ' + (feet - 14)
      + ' ' + round(x1) + ' ' + (feet - 28) + '" fill="none" stroke="#7f2f37" stroke-width="3.4"'
      + ' stroke-linecap="round"/>';
  }

  /**
   * 配達の車。車道に停める。
   * 運転席側はセーフエリアの外へわざとはみ出させている。通りが画面の先へ
   * 続いているように見えるので、幅を詰めて全体を収めるより自然に見える。
   */
  function deliveryVan(era) {
    return '<g transform="translate(404,' + (H - 2) + ')">'
      + '<ellipse cx="44" cy="0" rx="50" ry="4" fill="#000" opacity="0.22"/>'
      + '<rect x="0" y="-54" width="62" height="46" rx="3" fill="' + era.awningAlt + '"/>'
      + '<rect x="0" y="-54" width="62" height="46" rx="3" fill="none" stroke="' + era.trim + '" stroke-width="2"/>'
      + '<path d="M62 -42 H82 L94 -24 V-8 H62 Z" fill="' + era.awning + '"/>'
      + '<path d="M67 -38 H79 L88 -26 H67 Z" fill="' + era.glassHi + '" opacity="0.7"/>'
      + '<rect x="0" y="-30" width="62" height="4" fill="' + era.awning + '"/>'
      + cupMark(31, -31, 22, era.awning, era.trim, null)
      + '<text x="31" y="-14" text-anchor="middle" fill="' + era.trim + '" font-family="' + FONT
      + '" font-size="7" font-weight="700" letter-spacing="1">DELIVERY</text>'
      + '<g fill="#27292a"><circle cx="16" cy="-8" r="9.5"/><circle cx="78" cy="-8" r="9.5"/></g>'
      + '<g fill="' + era.walkHi + '"><circle cx="16" cy="-8" r="3.8"/><circle cx="78" cy="-8" r="3.8"/></g>'
      + '</g>';
  }

  /**
   * 通りの人。ランクが上がるほど賑わう。
   *
   * 立ち位置は建物の幅から出すが、席・立て看板・配達の車とぶつかると
   * 一気に安っぽくなるので、それらが使わない区画だけを候補にしている。
   *   建物の左外 … 常に空いている
   *   席と看板の間 … 幅が足りるランクだけ
   *   建物の右外 … 車の手前（x=410）で止める
   */
  function crowd(era, st, structure, doorX0) {
    var count = structure >= 6 ? 3 : structure >= 4 ? 2 : 1;
    var slots = [st.x0 - 22];

    var gap0 = st.x0 + 100;
    var gap1 = doorX0 - 44;
    if (gap1 - gap0 >= 34) { slots.push((gap0 + gap1) / 2); }
    slots.push(Math.min(st.x1 + 26, 400));

    var kinds = ['guestB', 'guestC', 'guestA'];
    var heights = [53, 46, 50];
    var feet = [WALK + 24, WALK + 14, WALK + 20];
    var out = '';
    for (var i = 0; i < Math.min(count, slots.length); i++) {
      out += person(slots[i], feet[i], heights[i], kinds[i], era);
    }
    return out;
  }

  /** 軒先の電飾。設備または内装レベル5以上で灯る。 */
  function festoonLayer(era, st) {
    var x0 = st.x0 - 14;
    var x1 = st.x1 + 14;
    var y = st.top + 4;
    var sag = 22;
    var out = '<path d="M' + x0 + ' ' + y + ' Q' + MIDX + ' ' + (y + sag * 2) + ' ' + x1 + ' ' + y
      + '" fill="none" stroke="' + era.trim + '" stroke-width="1.4" opacity="0.8"/>';

    var bulbs = 9;
    for (var i = 1; i < bulbs; i++) {
      var t = i / bulbs;
      // 二次ベジェ上の点
      var bx = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * MIDX + t * t * x1;
      var by = (1 - t) * (1 - t) * y + 2 * (1 - t) * t * (y + sag * 2) + t * t * y;
      var color = i % 2 ? '#ffd578' : '#ee8f7f';
      out += '<g class="cs-bulb" style="animation-delay:' + round(i * 0.22) + 's">'
        + '<circle cx="' + round(bx) + '" cy="' + round(by + 6) + '" r="9" fill="' + color + '" opacity="0.22"/>'
        + '<line x1="' + round(bx) + '" y1="' + round(by) + '" x2="' + round(bx) + '" y2="' + round(by + 3)
        + '" stroke="' + era.trim + '" stroke-width="1.2"/>'
        + '<circle cx="' + round(bx) + '" cy="' + round(by + 6) + '" r="3.4" fill="' + color + '"/></g>';
    }
    return out;
  }

  /* ------------------------------------------------------------------- defs */

  var FONT = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';

  function defsLayer(era) {
    var glassStops = era.lights
      ? '<stop offset="0" stop-color="' + era.glassHi + '"/>'
        + '<stop offset="0.55" stop-color="' + era.glow + '"/>'
        + '<stop offset="1" stop-color="' + era.glass + '"/>'
      : '<stop offset="0" stop-color="' + era.glassHi + '"/>'
        + '<stop offset="1" stop-color="' + era.glass + '"/>';

    return '<defs>'
      // 空色は y 0〜344 に固定する。userSpaceOnUse にしておかないと、
      // viewBox の外まで敷いた分だけグラデーションが伸びて色が変わってしまう
      + '<linearGradient id="cs-sky" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="' + H + '">'
      + '<stop offset="0" stop-color="' + era.skyTop + '"/>'
      + '<stop offset="0.5" stop-color="' + era.skyMid + '"/>'
      + '<stop offset="0.86" stop-color="' + era.skyLow + '"/></linearGradient>'

      + '<radialGradient id="cs-orb-glow">'
      + '<stop offset="0" stop-color="' + era.orbGlow + '"/>'
      + '<stop offset="1" stop-color="' + era.orbGlow.replace(/[\d.]+\)$/, '0)') + '"/></radialGradient>'

      + '<mask id="cs-moon">'
      + '<circle cx="' + era.orbX + '" cy="' + era.orbY + '" r="' + era.orbR + '" fill="#fff"/>'
      + '<circle cx="' + round(era.orbX + era.orbR * 0.62) + '" cy="' + round(era.orbY - era.orbR * 0.42)
      + '" r="' + round(era.orbR * 0.86) + '" fill="#000"/></mask>'

      + '<linearGradient id="cs-wall" x1="0" y1="0" x2="0" y2="1">'
      + '<stop offset="0" stop-color="' + era.wallHi + '"/>'
      + '<stop offset="0.55" stop-color="' + era.wall + '"/>'
      + '<stop offset="1" stop-color="' + era.wallLo + '"/></linearGradient>'

      + '<linearGradient id="cs-glass" x1="0" y1="0" x2="0" y2="1">' + glassStops + '</linearGradient>'

      + '<radialGradient id="cs-inner" cx="0.5" cy="0.85" r="0.75">'
      + '<stop offset="0" stop-color="' + era.glow + '" stop-opacity="0.5"/>'
      + '<stop offset="1" stop-color="' + era.glow + '" stop-opacity="0"/></radialGradient>'

      + '<radialGradient id="cs-lamp">'
      + '<stop offset="0" stop-color="' + era.glow + '" stop-opacity="0.5"/>'
      + '<stop offset="1" stop-color="' + era.glow + '" stop-opacity="0"/></radialGradient>'

      // 布の陰影。日除けと日傘に薄く重ねる
      + '<linearGradient id="cs-cloth" x1="0" y1="0" x2="0" y2="1">'
      + '<stop offset="0" stop-color="#000" stop-opacity="0.16"/>'
      + '<stop offset="0.45" stop-color="#000" stop-opacity="0"/>'
      + '<stop offset="1" stop-color="#000" stop-opacity="0.2"/></linearGradient>'

      + '<linearGradient id="cs-brass" x1="0" y1="0" x2="1" y2="0">'
      + '<stop offset="0" stop-color="#a8792f"/><stop offset="0.5" stop-color="#f1d27e"/>'
      + '<stop offset="1" stop-color="#9a6a28"/></linearGradient>'

      // 四隅を落とす。こちらも userSpaceOnUse で建物の中心に合わせておく
      + '<radialGradient id="cs-vignette" gradientUnits="userSpaceOnUse" cx="' + MIDX
      + '" cy="180" r="460">'
      + '<stop offset="0.5" stop-color="#000" stop-opacity="0"/>'
      + '<stop offset="1" stop-color="#000" stop-opacity="0.22"/></radialGradient>'
      + '</defs>';
  }

  /* ------------------------------------------------------------------ 組み立て */

  /** 表示に必要な値だけを取り出し、範囲外の値を丸める。 */
  function normalize(view) {
    var level = clamp(Math.round(Number(view.level) || 1), 1, ERAS.length - 1);
    return {
      level: level,
      // 店構えはランク7で完成。以降は配色と終盤演出だけが変わる
      structure: clamp(Math.round(Number(view.structure) || level), 1, STRUCTURES.length - 1),
      interior: clamp(Math.round(Number(view.interior) || 0), 0, 8),
      storeCount: Math.max(1, Math.round(Number(view.storeCount) || 1)),
      equippedIds: view.equippedIds || []
    };
  }

  /** シーン1枚ぶんのSVGを組み立てる。 */
  function markup(view) {
    var v = normalize(view);
    var era = ERAS[v.level];
    var st = STRUCTURES[v.structure];
    var flags = featureFlags(v.structure, v.interior, v.equippedIds);

    var body = defsLayer(era)
      + skyLayer(era, flags, v.level)
      + cityLayer(era, v.structure)
      + treeLayer(era, v.structure)
      + groundLayer(era);

    if (st.kind === 'cart') {
      body += cartLayer(era, flags);
      body += person(392, WALK + 18, 48, 'guestA', era);
    } else {
      var shop = shopLayer(era, st, flags, v.structure);
      body += shop.markup
        + streetLayer(era, st, flags, v.structure, shop.doorX0, shop.doorX1);
      if (flags.festoon) { body += festoonLayer(era, st); }
    }

    body += '<rect x="' + OSX + '" y="' + OSY + '" width="' + OSW + '" height="' + OSH
      + '" fill="url(#cs-vignette)"/>';

    var svg = '<svg class="cs-svg" viewBox="' + VBX + ' ' + VBY + ' ' + VBW + ' ' + VBH + '"'
      + ' preserveAspectRatio="xMidYMid meet"'
      + ' xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">' + body + '</svg>';

    // idは書き出すたびに変える。同じidが2つあると url(#…) は先に出てきた方を拾うので、
    // 1ページに2枚並べた瞬間に後ろの絵が前の絵の配色になってしまう。
    // class名（cs-steam など）には触らないよう、id と url(#…) だけを狙って置き換える。
    var uid = 'cs' + (++sequence) + '-';
    return svg.replace(/id="cs-/g, 'id="' + uid).replace(/\(#cs-/g, '(#' + uid);
  }

  var sequence = 0;

  /**
   * シーンを container に描く。
   * 装備状況が前回と同じなら描き直さない（毎回作り直すとアニメが頭に戻る）。
   * @return {boolean} 描き直したか
   */
  function render(container, view) {
    if (!container) { return false; }
    var sig = signature(normalize(view));
    if (container.getAttribute('data-cs') === sig) { return false; }
    container.setAttribute('data-cs', sig);
    container.innerHTML = markup(view);
    return true;
  }

  /* --------------------------------------------------------------- 店舗網の地図 */

  var NW = 380;         // 地図の viewBox 幅
  var NH = 210;         // 同 高さ

  /* 店舗を置く座標。先頭が本店。増える順は固定なので、
     出店しても既にある店の位置が動かない。 */
  var NODES = [
    [190, 106],
    [128, 84], [252, 86], [96, 132], [286, 130], [166, 62],
    [216, 150], [66, 98], [318, 98], [140, 148], [244, 56],
    [78, 158], [306, 162], [190, 44], [112, 56], [268, 112],
    [50, 128], [336, 128], [158, 176], [230, 176], [92, 176],
    [296, 62], [60, 66], [326, 46]
  ];

  /**
   * 店舗ネットワークの地図。
   * @param {{storeCount:number, locked:boolean, maximum:boolean}} view
   */
  function networkMap(view) {
    var stores = Math.max(1, Math.round(Number(view.storeCount) || 1));
    var shown = Math.min(stores, NODES.length);
    var hidden = stores - shown;
    var accent = view.maximum ? '#ffd977' : (view.locked ? '#8ea3b5' : '#e8a33d');
    var flag = NODES[0];

    var out = '<defs>'
      + '<radialGradient id="csn-bg" cx="0.5" cy="0.42" r="0.75">'
      + '<stop offset="0" stop-color="#2c4048"/><stop offset="1" stop-color="#172126"/></radialGradient>'
      + '<radialGradient id="csn-node">'
      + '<stop offset="0" stop-color="' + accent + '" stop-opacity="0.55"/>'
      + '<stop offset="1" stop-color="' + accent + '" stop-opacity="0"/></radialGradient>'
      + '</defs>'
      + '<rect width="' + NW + '" height="' + NH + '" fill="url(#csn-bg)"/>';

    // 経緯線。地球の上に置いているように見せる
    out += '<g fill="none" stroke="#ffffff" stroke-width="1" opacity="0.1">'
      + '<ellipse cx="190" cy="105" rx="168" ry="88"/>'
      + '<ellipse cx="190" cy="105" rx="112" ry="88"/>'
      + '<ellipse cx="190" cy="105" rx="52" ry="88"/>'
      + '<line x1="22" y1="105" x2="358" y2="105"/>'
      + '<path d="M32 62 Q190 40 348 62"/><path d="M32 148 Q190 170 348 148"/></g>';

    // 本店から各店への航路
    out += '<g fill="none" stroke="' + accent + '" stroke-width="1.2" opacity="0.4">';
    for (var i = 1; i < shown; i++) {
      var node = NODES[i];
      var cx = (flag[0] + node[0]) / 2 + (node[1] - flag[1]) * 0.2;
      var cy = (flag[1] + node[1]) / 2 - Math.abs(node[0] - flag[0]) * 0.16;
      out += '<path d="M' + flag[0] + ' ' + flag[1] + ' Q' + round(cx) + ' ' + round(cy)
        + ' ' + node[0] + ' ' + node[1] + '"/>';
    }
    out += '</g>';

    // 各店
    for (var n = 1; n < shown; n++) {
      var p = NODES[n];
      out += '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="11" fill="url(#csn-node)"/>'
        + '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="4.2" fill="' + accent + '"/>'
        + '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="4.2" fill="none" stroke="#172126" stroke-width="1"/>';
    }

    // 本店
    out += '<circle class="csn-pulse" cx="' + flag[0] + '" cy="' + flag[1] + '" r="15" fill="none" stroke="'
      + accent + '" stroke-width="1.6"/>'
      + '<circle cx="' + flag[0] + '" cy="' + flag[1] + '" r="24" fill="url(#csn-node)"/>'
      + '<circle cx="' + flag[0] + '" cy="' + flag[1] + '" r="13" fill="#241b18"/>'
      + '<circle cx="' + flag[0] + '" cy="' + flag[1] + '" r="13" fill="none" stroke="' + accent + '" stroke-width="2"/>'
      + cupMark(flag[0], flag[1] + 5, 15, accent, '#241b18', null);

    if (hidden > 0) {
      out += '<rect x="' + (NW - 84) + '" y="' + (NH - 30) + '" width="70" height="20" rx="10" fill="#241b18"'
        + ' fill-opacity="0.86" stroke="' + accent + '" stroke-width="1" stroke-opacity="0.5"/>'
        + '<text x="' + (NW - 49) + '" y="' + (NH - 16) + '" text-anchor="middle" fill="' + accent
        + '" font-family="' + FONT + '" font-size="10" font-weight="700">+' + hidden + '</text>';
    }

    return '<svg class="csn-svg" viewBox="0 0 ' + NW + ' ' + NH + '" preserveAspectRatio="xMidYMid meet"'
      + ' xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">' + out + '</svg>';
  }

  global.CafeScene = {
    markup: markup,
    render: render,
    networkMap: networkMap,
    maxStructure: STRUCTURES.length - 1,
    maxEra: ERAS.length - 1
  };
}(window));
