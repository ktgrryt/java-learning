package cafe.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/** 最低限の報告形式と根拠の配置を検証する。原因推論の正しさは判定しない。 */
final class ReportStructure {
    static void validate(String report) {
        require(!report.contains("TODO"), "REPORT.mdのTODOを置き換えてください");
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : report.lines().toList()) {
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                require(!sections.containsKey(current), "同じ見出しを重複させないでください: " + current);
                sections.put(current, new StringBuilder());
            } else if (current != null && !line.stripLeading().startsWith("#")) {
                sections.get(current).append(line).append('\n');
            }
        }
        for (String heading : new String[] {"概要と影響", "時系列と事実", "仮説", "検証方法", "緩和策と恒久対策"}) {
            String body = sections.getOrDefault(heading, new StringBuilder()).toString().strip();
            require(!body.isEmpty(), "「" + heading + "」に本文を書いてください");
            require(body.contains("。") || body.matches("(?s).*[.!?](?:\\s|$).*"),
                    "「" + heading + "」はキーワードだけでなく、少なくとも1文で記述してください");
        }
        String facts = sections.get("時系列と事実").toString();
        require(facts.contains("2026-08-11T10:02:02Z") && facts.contains("connection_timeout"),
                "時系列と事実に最初の異常の時刻とイベントを書いてください");
        require(sections.get("仮説").toString().contains("orders-2.4.0"),
                "仮説に直前の配備を原因候補として書いてください");
        require(!report.contains("Bearer-prod-secret") && !report.contains("転居先を秘密にしたい"),
                "報告へ秘密情報や顧客の自由入力を転記してはいけません");
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
