import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * パスワードの保存と照合。
 *
 * 新しい保存値はPBKDF2で導出し、方式・反復回数・ソルトを保存値へ含める。
 * 旧形式（SHA-256を1回）も照合できるので、移行の途中でも既存利用者はログインできる。
 * ログインに成功したら needsRehash を見て、その利用者だけ新形式へ入れ替える。
 */
public final class PasswordHasher {
    static final String LEGACY_ID = "sha256";
    static final String ID = "pbkdf2-sha256";
    static final int MINIMUM_ITERATIONS = 100_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return ID + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    public static boolean verify(char[] password, String stored) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length == 2 && LEGACY_ID.equals(parts[0])) {
            return MessageDigest.isEqual(
                    hex(sha256(password)).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8));
        }
        if (parts.length != 4 || !ID.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean needsRehash(String stored) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length == 4 && ID.equals(parts[0])) {
            try {
                return Integer.parseInt(parts[1]) < MINIMUM_ITERATIONS;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return true;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is unavailable", e);
        } finally {
            spec.clearPassword();
        }
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
