package cafe.logging;

import java.nio.file.Files;
import java.nio.file.Path;

public class ReportStructureCheck {
    public static void main(String[] args) throws Exception {
        String reference = Files.readString(Path.of("labs/logging-investigation/reference/REPORT.md"));
        ReportStructure.validate(reference);
        String keywords = "2026-08-11T10:02:02Z connection_timeout orders-2.4.0\n"
                + "## 概要と影響\n## 時系列と事実\n## 仮説\n## 検証方法\n## 緩和策と恒久対策\n";
        reject(keywords);
        reject(reference.replaceAll("(?s)(## 検証方法).*?(## 緩和策と恒久対策)", "$1\n\n$2"));
        reject(reference.replace("## 時系列と事実", "## 一般情報"));
        reject(reference + "\nBearer-prod-secret");
        reject(reference + "\n## 仮説\n重複の本文です。\n");
        ReportStructure.validate(reference.replace("原因候補とする。", "候補として調べる。"));
        System.out.println("報告形式検査: 模範報告・別表現・空本文・誤配置・秘密情報・重複の7ケース合格");
    }
    private static void reject(String text) {
        try { ReportStructure.validate(text); }
        catch (AssertionError expected) { return; }
        throw new AssertionError("不完全な報告が通りました");
    }
}
