#!/usr/bin/env bash
#
# Dock / Launchpad / Spotlight から1クリックで遊べる「Java Quest.app」を作る（macOS専用）。
#
#   tools/make-app.sh                        … ~/Applications に作る
#   tools/make-app.sh --dest /Applications   … 場所を指定する
#   tools/make-app.sh --name "Java道場"       … アプリの名前を変える
#   tools/make-app.sh --jdk /path/to/jdk     … アプリが使うJDKを指定する
#
# 中身は tools/launch.sh を呼ぶだけの薄い入れ物なので、レッスンやコードを直したあとに
# 作り直す必要はない。作り直しが必要なのはリポジトリを別の場所に移したときだけ。
#
# 普通のアプリと同じように「起動中はDockに残り、右クリック→終了でサーバも止まる」形に
# したいので、AppleScript の stay-open アプレット（osacompile -s）として作る。
# シェルスクリプトを直接 CFBundleExecutable にすると、Quit の Apple Event を誰も
# 受け取らないため「応答しません → 強制終了」になってしまう。
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

DEST="$HOME/Applications"
NAME="Java Quest"
JDK_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dest) DEST="${2:?--dest には場所が必要です}"; shift 2 ;;
    --name) NAME="${2:?--name には名前が必要です}"; shift 2 ;;
    --jdk) JDK_OVERRIDE="${2:?--jdk には場所が必要です}"; shift 2 ;;
    *) echo "使えないオプションです: $1" >&2; exit 1 ;;
  esac
done

APP="$DEST/$NAME.app"

# ---- 使うJDKをいま決めて焼き込む --------------------------------------------
# Finder から起動されたアプリの PATH は /usr/bin:/bin:/usr/sbin:/sbin しかないので、
# Homebrew や sdkman のJDKは見つけられない。さらに、ターミナルとアプリで違うJDKを
# 拾うと build.sh がそのたびに作り直すことになる。だから、いまターミナルで解決できる
# JDKをアプリに書き込んで、どちらから起動しても同じJDKを使うようにする。
#
# ただし JAVA_HOME は既定で無視する。エディタの統合ターミナルでは JAVA_HOME が
# エディタ同梱のJDK（拡張機能のバージョン入りパス）を指していることがあり、
# 拡張機能が更新されると消えてしまう。PATH の javac や /usr/libexec/java_home の方が
# 場所が動かないので、そちらを焼き込む。
if [[ -n "$JDK_OVERRIDE" ]]; then
  export JQ_JAVA_HOME="$JDK_OVERRIDE"
else
  unset JAVA_HOME
fi
source tools/build.sh
JDK_HOME="$(cd "$(dirname "$JQ_JAVAC")/.." && pwd)"
echo "使うJDK: $JQ_JDK_LABEL"
echo "         $JDK_HOME"

# ---- 中身（AppleScript）を組み立てる ---------------------------------------
# パスを AppleScript の文字列リテラルに埋め込むので、壊れる文字が入っていないか確かめる
case "$ROOT$JDK_HOME" in
  *'"'* | *'\'*) echo "エラー: パスに \" か \\ が含まれていると埋め込めません: $ROOT" >&2; exit 1 ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

{
  echo '-- tools/make-app.sh が生成。直したいときは tools/make-app.sh を編集する。'
  printf 'property launcher : "%s"\n' "$ROOT/tools/launch.sh"
  printf 'property jdkHome : "%s"\n' "$JDK_HOME"
  cat <<'APPLESCRIPT'

-- 起動 / 停止はどちらも tools/launch.sh に任せる。
-- launch.sh はサーバをバックグラウンドに置いてすぐ戻るので、アプレットは
-- 「Dockに残ってQuitを待つ係」に専念できる。
on callLauncher(extraArgs)
	set env to "JQ_GUI=1 JQ_JAVA_HOME=" & quoted form of jdkHome & " "
	with timeout of 300 seconds
		do shell script env & quoted form of launcher & extraArgs
	end timeout
end callLauncher

on startServer()
	try
		do shell script "test -x " & quoted form of launcher
	on error
		display dialog "Java Quest のファイルが見つかりません。" & return & return & ¬
			"フォルダを移動した場合は、移動先で tools/make-app.sh を実行し直してください。" ¬
			with title "Java Quest" buttons {"OK"} default button 1 with icon stop
		quit
		return
	end try

	try
		callLauncher("")
	on error
		-- 失敗の内容は launch.sh がダイアログで見せているので、ここでは黙って畳む
		quit
	end try
end startServer

on run
	startServer()
end run

-- 起動中にDockアイコンを押したとき。launch.sh がすでに動いているのを見つけて
-- ブラウザを開くだけで戻ってくる
on reopen
	startServer()
end reopen

-- 右クリック→終了 / ⌘Q / ログアウト。サーバもここで止める
on quit
	try
		callLauncher(" --stop")
	end try
	continue quit
end quit
APPLESCRIPT
} > "$WORK/main.applescript"

# ---- アプレットにする ------------------------------------------------------
mkdir -p "$DEST"
rm -rf "$APP"
# -s = stay-open。これがないと run ハンドラが終わった時点でアプリが終了してしまい、
# Dockに残らない（＝右クリックで終了できない）
osacompile -s -o "$APP" "$WORK/main.applescript"

# osacompile が CFBundleName / CFBundleIconFile / OSAAppletStayOpen までは書いてくれる。
# 足りないのはバンドルIDだけ（Dockへの固定や Spotlight のためにあった方がいい）
SLUG="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//; s/-*$//')"
[[ -n "$SLUG" ]] || SLUG="app"
BUNDLE_ID="local.javaquest.$SLUG"
/usr/libexec/PlistBuddy -c "Add :CFBundleIdentifier string $BUNDLE_ID" "$APP/Contents/Info.plist" >/dev/null 2>&1 \
  || /usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $BUNDLE_ID" "$APP/Contents/Info.plist" >/dev/null

# ---- アイコン（☕を描いて .icns にする） ------------------------------------
# 失敗しても起動には関係ないので、素のアイコンのまま進める
make_icon() {
  local work png
  work="$WORK/icon"
  mkdir -p "$work"
  png="$work/icon.png"

  cat > "$work/icon.js" <<'JXA'
// 濃い角丸の上に ☕（Apple Color Emoji）を描いて PNG に書き出す。
// 使い方: osascript -l JavaScript icon.js <出力先.png>
ObjC.import('Foundation');
ObjC.import('AppKit');

function run(argv) {
  var out = argv[0];
  var size = 1024;

  var img = $.NSImage.alloc.initWithSize({ width: size, height: size });
  img.lockFocus;
  $.NSColor.colorWithSRGBRedGreenBlueAlpha(0.15, 0.11, 0.09, 1.0).set;
  $.NSBezierPath.bezierPathWithRoundedRectXRadiusYRadius($.NSMakeRect(0, 0, size, size), 224, 224).fill;

  var attrs = $.NSMutableDictionary.dictionary;
  attrs.setObjectForKey($.NSFont.fontWithNameSize('Apple Color Emoji', 660), $.NSFontAttributeName);
  var cup = $.NSString.stringWithString('☕');
  var box = cup.sizeWithAttributes(attrs);
  cup.drawAtPointWithAttributes({ x: (size - box.width) / 2, y: (size - box.height) / 2 }, attrs);
  img.unlockFocus;

  var rep = $.NSBitmapImageRep.imageRepWithData(img.TIFFRepresentation);
  rep.setSize({ width: size, height: size });
  rep.representationUsingTypeProperties(4, $.NSDictionary.dictionary)  // 4 = PNG
     .writeToFileAtomically(out, true);
}
JXA

  osascript -l JavaScript "$work/icon.js" "$png" >/dev/null 2>&1 || return 1
  [[ -s "$png" ]] || return 1

  mkdir -p "$work/AppIcon.iconset"
  local sz double
  for sz in 16 32 128 256 512; do
    double=$((sz * 2))
    sips -z "$sz" "$sz" "$png" --out "$work/AppIcon.iconset/icon_${sz}x${sz}.png" >/dev/null 2>&1 || return 1
    sips -z "$double" "$double" "$png" --out "$work/AppIcon.iconset/icon_${sz}x${sz}@2x.png" >/dev/null 2>&1 || return 1
  done

  # アプレットの Info.plist は CFBundleIconFile が applet なので、そこに上書きする
  iconutil -c icns "$work/AppIcon.iconset" -o "$APP/Contents/Resources/applet.icns" >/dev/null 2>&1 || return 1
}

if ! make_icon; then
  echo "注意: アイコンを作れませんでした（起動には影響しません）。" >&2
fi

# ---- 仕上げ ----------------------------------------------------------------
# アイコンを差し替えるとアプレットの署名と合わなくなる。macOS に「壊れている」と
# 言われないよう、自己署名で付け直す
codesign --force --sign - "$APP" >/dev/null 2>&1 || true
# Finder が古いアイコンを覚え込まないように更新時刻を触る
touch "$APP"

echo
echo "  ☕  $APP を作りました"
echo
echo "      Finder で開いてダブルクリック、または Dock にドラッグしてください。"
echo "      Spotlight（⌘スペース）で「${NAME}」でも起動できます。"
echo "      止めるときは Dock アイコンを右クリック →「終了」。"
echo
open -R "$APP" 2>/dev/null || true
