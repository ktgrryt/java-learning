import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * パスワードの保存と照合。
 *
 * いまはSHA-256を1回だけ計算している。ソルトも無く、方式や設定の版も残していない。
 * 照合は動くので気づきにくいが、漏えい時に総当たりで戻されやすい。
 *
 * TODO 1: hash は PBKDF2（`PBKDF2WithHmacSHA256`）で導出し、利用者ごとのソルトを付ける。
 *         保存値は `pbkdf2-sha256$<反復回数>$<Base64のsalt>$<Base64の導出値>` の形にする。
 *         反復回数は100000以上にする。
 * TODO 2: verify は新形式に加え、旧形式 `sha256$<hex>` も照合できるようにする。
 *         移行の途中でも既存利用者がログインできなくならないようにするため。
 * TODO 3: needsRehash は、旧形式か、反復回数が100000未満の新形式なら true を返す。
 *         ログインに成功した利用者だけを新形式へ入れ替えるために使う。
 */
public final class PasswordHasher {
    static final String LEGACY_ID = "sha256";
    static final String ID = "pbkdf2-sha256";
    static final int MINIMUM_ITERATIONS = 100_000;

    private PasswordHasher() {
    }

    public static String hash(char[] password) {
        return LEGACY_ID + "$" + hex(sha256(password));
    }

    public static boolean verify(char[] password, String stored) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 2 || !LEGACY_ID.equals(parts[0])) {
            return false;
        }
        return MessageDigest.isEqual(
                hex(sha256(password)).getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8));
    }

    public static boolean needsRehash(String stored) {
        return false;
    }

    /** 旧形式の保存値。移行前のデータを再現するために使う。 */
    static byte[] sha256(char[] password) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(new String(password).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }
}
