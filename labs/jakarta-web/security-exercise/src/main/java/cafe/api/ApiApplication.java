package cafe.api;

import jakarta.annotation.security.DeclareRoles;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * APIの入口。参照専用（この演習では編集しません）。
 *
 * <p>Basic認証の仕組みと、このアプリで使う役割の名前をここで宣言してあります。
 * 「誰がどの役割か」は{@link CafeIdentityStore}にあります。
 */
@ApplicationPath("/api")
@BasicAuthenticationMechanismDefinition(realmName = "cafe")
@DeclareRoles({ "staff", "manager" })
public class ApiApplication extends Application {
}
