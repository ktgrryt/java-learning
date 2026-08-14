import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 注文1件の要約を出す。データファイルはUTF-8で保存されている。
 *
 * このclass fileはどのJVMでも動く。しかし出力は動かす環境の既定値で変わる。
 * 大文字化、数値の書式、時刻の解釈、バイト列の復号が、どれも既定値を見ている。
 *
 * TODO: どの環境で動かしても同じ出力になるよう、環境に依存している4か所を直す。
 *       注文の記録時刻はUTCで扱う。金額の桁区切りは `1,234.50` の形にする。
 */
public class OrderReport {
    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(args[0]));
        String[] lines = new String(raw).split("\n");

        String code = lines[0].strip();
        double total = Double.parseDouble(lines[1].strip());
        Instant recordedAt = Instant.parse(lines[2].strip());
        String label = lines[3].strip();

        System.out.println("code=" + code.toUpperCase());
        System.out.println("total=" + String.format("%,.2f", total));
        System.out.println("recorded=" + recordedAt.atZone(ZoneId.systemDefault()).toLocalDate());
        System.out.println("label=" + label);
    }
}
