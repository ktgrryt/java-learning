package cafe.jpa;

import java.util.List;

/**
 * 呼び出し側へ返す形。参照専用（この演習では編集しません）。
 *
 * <p>EntityManagerを閉じたあとでも読めるように、必要な値だけを詰めた入れ物。
 */
public record CustomerSummary(String name, int budgetYen, List<String> items) {
}
