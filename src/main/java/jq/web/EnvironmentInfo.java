package jq.web;

import jq.progress.ProgressStore;

import javax.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 設定パネルに出す「このアプリ」と「いま動いているJDK」の情報。
 *
 * <p>ここが見せるのは <b>教材を動かしているJVMそのもの</b>である。PATHの `java` を
 * 別に測って出すことはしない。学習者のコードは {@code java.home} の道具でコンパイル・
 * 実行されるので（{@link jq.runner.JavaRunner}）、PATH側の版を出すと「画面に21と
 * 出ているのに17向けの動きをする」という食い違いが起きる。
 *
 * <p>子プロセスは起こさない。設定を開くだけの操作で `java --version` を待たせたくないし、
 * 事前確認（{@link jq.runner.PreflightRunner}）と違ってここは実測が要る話ではない。
 * 例外はコンパイラの有無で、これは {@code ToolProvider} を引くだけ（プロセスは起きない）。
 * JREで起動されると教材が1問も解けないので、その場合だけは設定からも分かるようにする。
 */
public final class EnvironmentInfo {

    /**
     * アプリの版。画面に出る版はここが正で、起動時の表示（{@code App}）と設定パネルの
     * 両方がこれを読む。画面側で2箇所に書くと、片方だけ上げた版が画面に出てしまう。
     *
     * <p>ただしリポジトリには、もう1箇所だけ版が書いてある ― README の末尾（配布物の目印）。
     * 手で上げると片方を忘れるので、上げるときは {@code ./tools/bump-version.sh} を使う。
     * そろっているかは {@code ./tools/check-version.sh} が見張る。
     */
    public static final String APP_VERSION = "1.2.0";

    private EnvironmentInfo() {
    }

    public static Map<String, Object> of(ProgressStore progress) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("appVersion", APP_VERSION);
        // Runtime.version() は "21.0.5+11-LTS" のように build まで出る。java.version だけだと
        // patch までしか分からず、security baseline を見るときに足りない。
        info.put("javaVersion", Runtime.version().toString());
        info.put("javaVendor", vendor());
        info.put("vmName", property("java.vm.name"));
        info.put("vmVersion", property("java.vm.version"));
        info.put("javaHome", property("java.home"));
        info.put("compilerAvailable", ToolProvider.getSystemJavaCompiler() != null);
        info.put("osName", property("os.name"));
        info.put("osVersion", property("os.version"));
        info.put("osArch", property("os.arch"));
        info.put("progressFile", progress.location().toAbsolutePath().toString());
        return info;
    }

    /**
     * 配布元。`java.vendor` は "Homebrew" や "Eclipse Adoptium" のように配布物を
     * 見分ける手がかりになるので、版が同じでも別物だと分かるように版番号まで添える。
     */
    private static String vendor() {
        String vendor = property("java.vendor");
        String vendorVersion = property("java.vendor.version");
        if (vendorVersion.isEmpty() || vendor.contains(vendorVersion)) {
            return vendor;
        }
        return vendor + "（" + vendorVersion + "）";
    }

    /** 読めない項目は空にする。設定パネル側で「不明」と出す。 */
    private static String property(String key) {
        String value = System.getProperty(key);
        return value == null ? "" : value;
    }
}
