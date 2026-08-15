package cafe.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;

import java.util.Map;
import java.util.Set;

/**
 * 利用者と役割の登録先。参照専用（この演習では編集しません）。
 *
 * <p>この演習で使える利用者:
 *
 * <table border="1">
 *   <caption>利用者と役割</caption>
 *   <tr><th>利用者</th><th>パスワード</th><th>役割</th></tr>
 *   <tr><td>aki</td><td>aki-pass</td><td>staff</td></tr>
 *   <tr><td>mgr</td><td>mgr-pass</td><td>staff, manager</td></tr>
 *   <tr><td>bob</td><td>bob-pass</td><td>（役割なし）</td></tr>
 * </table>
 *
 * <p>本番でパスワードを平文で持つことはありません（Web・Jakarta EE編『Jakarta EE 11アップデート』で保存方式を扱います）。
 * ここは認可の練習に集中するための固定データです。
 */
@ApplicationScoped
public class CafeIdentityStore implements IdentityStore {

    private static final Map<String, String> PASSWORDS = Map.of(
            "aki", "aki-pass",
            "mgr", "mgr-pass",
            "bob", "bob-pass");

    private static final Map<String, Set<String>> ROLES = Map.of(
            "aki", Set.of("staff"),
            "mgr", Set.of("staff", "manager"),
            "bob", Set.of());

    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof UsernamePasswordCredential password)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }
        String caller = password.getCaller();
        String expected = PASSWORDS.get(caller);
        if (expected == null || !expected.equals(password.getPasswordAsString())) {
            return CredentialValidationResult.INVALID_RESULT;
        }
        return new CredentialValidationResult(caller, ROLES.get(caller));
    }
}
