package cafe.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 領収書の1行を作る。
 *
 * ロケールを明示しているので、どの環境で動かしても同じ1行になります。
 */
public class Receipt {

    public static String line(LocalDate date, String name, long amount) {
        // ① 曜日の言語を Locale で固定する
        String day = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd(E)", Locale.JAPAN));

        // ② 大文字化は Locale.ROOT、桁区切りも Locale を渡す
        return day + " " + name.toUpperCase(Locale.ROOT)
                + " " + String.format(Locale.ROOT, "%,d", amount) + "円";
    }
}
