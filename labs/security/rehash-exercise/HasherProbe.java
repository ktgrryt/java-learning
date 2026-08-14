/**
 * 採点で使う検査。学習者は編集しない。
 *
 * 保存値の形、ソルトの有無、照合、旧形式の受け入れ、再ハッシュ判定を、
 * 実際に PasswordHasher を呼んで確かめる。
 */
public class HasherProbe {
    private static final String PASSWORD = "correct horse battery staple";
    private static final String OTHER = "wrong horse battery staple";

    private static boolean failed;

    public static void main(String[] args) {
        String stored;
        try {
            stored = PasswordHasher.hash(PASSWORD.toCharArray());
        } catch (RuntimeException e) {
            String reason = summarize(e);
            for (String id : new String[] {"hash-format", "hash-salted", "hash-verify",
                    "hash-legacy-verify", "hash-needs-rehash"}) {
                report(id, false, "", "hashが例外で失敗しました: " + reason);
            }
            System.exit(1);
            return;
        }

        // 1. 保存値に方式と反復回数が入り、平文は入らない
        String[] parts = stored.split("\\$", -1);
        int iterations = -1;
        if (parts.length == 4 && parts[0].equals(PasswordHasher.ID)) {
            try {
                iterations = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                iterations = -1;
            }
        }
        boolean formatOk = iterations >= PasswordHasher.MINIMUM_ITERATIONS
                && !stored.contains(PASSWORD);
        report("hash-format", formatOk,
                "保存値が" + PasswordHasher.ID + "形式で、反復回数" + iterations + "を含みます",
                "保存値は pbkdf2-sha256$<反復回数>$<salt>$<導出値> の形にし、反復回数は"
                        + PasswordHasher.MINIMUM_ITERATIONS + "以上にしてください（実際: "
                        + shorten(stored) + "）");

        // 2. 同じパスワードでも保存値が変わる（利用者ごとのソルト）
        String again = PasswordHasher.hash(PASSWORD.toCharArray());
        report("hash-salted", !stored.equals(again),
                "同じパスワードでも保存値が毎回変わりました",
                "同じパスワードから同じ保存値ができています。利用者ごとのソルトを付けてください");

        // 3. 正しいパスワードだけ一致する
        boolean verifyOk = PasswordHasher.verify(PASSWORD.toCharArray(), stored)
                && !PasswordHasher.verify(OTHER.toCharArray(), stored)
                && PasswordHasher.verify(PASSWORD.toCharArray(), again);
        report("hash-verify", verifyOk,
                "正しいパスワードだけが一致しました",
                "自分が作った保存値と正しいパスワードが一致し、違うパスワードは一致しないようにしてください");

        // 4. 旧形式でも照合できる（移行中も止めない）
        String legacy = PasswordHasher.LEGACY_ID + "$"
                + PasswordHasher.hex(PasswordHasher.sha256(PASSWORD.toCharArray()));
        boolean legacyOk = PasswordHasher.verify(PASSWORD.toCharArray(), legacy)
                && !PasswordHasher.verify(OTHER.toCharArray(), legacy);
        report("hash-legacy-verify", legacyOk,
                "旧形式の保存値でも照合できました",
                "移行の途中でも既存利用者がログインできるよう、旧形式 sha256$<hex> も照合してください");

        // 5. 入れ替えが必要な保存値を見分ける
        String weak = PasswordHasher.ID + "$1000$"
                + java.util.Base64.getEncoder().encodeToString(new byte[16]) + "$"
                + java.util.Base64.getEncoder().encodeToString(new byte[32]);
        boolean rehashOk = PasswordHasher.needsRehash(legacy)
                && PasswordHasher.needsRehash(weak)
                && !PasswordHasher.needsRehash(stored);
        report("hash-needs-rehash", rehashOk,
                "旧形式と反復回数不足だけを入れ替え対象と判定しました",
                "needsRehashは、旧形式と反復回数が" + PasswordHasher.MINIMUM_ITERATIONS
                        + "未満の保存値でtrue、いま作った保存値ではfalseにしてください");

        if (failed) System.exit(1);
    }

    private static void report(String id, boolean pass, String passMessage, String failMessage) {
        System.out.printf("JQ_CHECK\t%s\t%s\t%s%n", pass ? "PASS" : "FAIL", id,
                pass ? passMessage : failMessage);
        if (!pass) failed = true;
    }

    private static String shorten(String value) {
        String single = value.replaceAll("[\\r\\n\\t]", " ");
        return single.length() <= 60 ? single : single.substring(0, 60) + "...";
    }

    private static String summarize(Throwable error) {
        Throwable deepest = error;
        while (deepest.getCause() != null) deepest = deepest.getCause();
        return shorten(deepest.getClass().getSimpleName()
                + (deepest.getMessage() == null ? "" : ": " + deepest.getMessage()));
    }
}
