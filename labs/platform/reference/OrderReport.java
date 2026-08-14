import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * 注文1件の要約を出す。データファイルはUTF-8で保存されている。
 *
 * 環境の既定値に任せる代わりに、文字集合・ロケール・タイムゾーンを明示している。
 * だからこのclass fileは、どのJVMのどの既定値のもとでも同じ出力になる。
 */
public class OrderReport {
    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(args[0]));
        String[] lines = new String(raw, StandardCharsets.UTF_8).split("\n");

        String code = lines[0].strip();
        double total = Double.parseDouble(lines[1].strip());
        Instant recordedAt = Instant.parse(lines[2].strip());
        String label = lines[3].strip();

        System.out.println("code=" + code.toUpperCase(Locale.ROOT));
        System.out.println("total=" + String.format(Locale.ROOT, "%,.2f", total));
        System.out.println("recorded=" + recordedAt.atZone(ZoneOffset.UTC).toLocalDate());
        System.out.println("label=" + label);
    }
}
