import jq.progress.ProgressStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 初回オンボーディングの判定・保存・旧セーブ互換を確かめる。 */
public final class OnboardingCheck {

    private static final ProgressStore.CafeLearningProgress ZERO =
            new ProgressStore.CafeLearningProgress(0, 0);

    private static void ok(String label, boolean condition) {
        if (!condition) {
            throw new IllegalStateException("NG " + label);
        }
        System.out.println("OK  " + label);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jq-onboarding-");
        try {
            Path freshFile = dir.resolve("fresh.json");
            ProgressStore fresh = new ProgressStore(freshFile);
            ok("セーブデータが無ければ初回案内を表示", fresh.isOnboardingRequired());
            ok("初期状態は未完了", !fresh.isOnboardingCompleted());
            ok("初回判定を画面用stateへ渡す", clientFlag(fresh, "onboardingRequired"));

            fresh.completeOnboarding();
            fresh.flushNow();
            ok("完了状態をセーブデータへ保存",
                    Files.readString(freshFile).contains("\"onboardingCompleted\":true"));

            ProgressStore completed = new ProgressStore(freshFile);
            ok("再起動後は初回案内を表示しない", !completed.isOnboardingRequired());
            ok("再起動後も完了状態を維持", completed.isOnboardingCompleted());
            ok("完了後の画面用stateでは案内不要", !clientFlag(completed, "onboardingRequired"));

            completed.resetAll();
            completed.flushNow();
            ProgressStore reset = new ProgressStore(freshFile);
            ok("進捗リセット後は初回状態へ戻る", reset.isOnboardingRequired());

            Path emptyFile = dir.resolve("empty.json");
            Files.writeString(emptyFile, "{}");
            ProgressStore empty = new ProgressStore(emptyFile);
            ok("空のセーブデータも初回扱い", empty.isOnboardingRequired());

            Path legacyFile = dir.resolve("legacy.json");
            ProgressStore legacySeed = new ProgressStore(legacyFile);
            legacySeed.markCleared("1-1#1");
            legacySeed.flushNow();
            String legacyJson = Files.readString(legacyFile)
                    .replace("\"onboardingCompleted\":false,", "");
            Files.writeString(legacyFile, legacyJson);

            ProgressStore legacy = new ProgressStore(legacyFile);
            ok("学習進捗がある旧セーブは初回扱いにしない", !legacy.isOnboardingRequired());
            ok("旧セーブは完了済みとして移行", legacy.isOnboardingCompleted());

            System.out.println("\nONBOARDING OK: 初回判定と完了状態の保存を確認しました");
        } finally {
            deleteTree(dir);
        }
    }

    /** 一時ディレクトリをまるごと消す。中のファイル名を数え上げないのは、進捗の作りが
     *  増えたとき（控えが1つ増えるなど）に後始末だけが落ちるのを避けるため。 */
    private static void deleteTree(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 一時ディレクトリなので消し残っても構わない
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean clientFlag(ProgressStore progress, String key) {
        Map<String, Object> client = (Map<String, Object>) progress.toClientJson(ZERO);
        return client.get(key) instanceof Boolean value && value;
    }
}
