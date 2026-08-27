package cafe.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 何も明示しない書き方の見本。参照専用で、採点には使いません。
 * 3つの環境で走らせて「同じコードなのに出力が変わる」ことを見るために置いてあります。
 */
public class Loose {

    public static void main(String[] args) {
        LocalDate date = LocalDate.of(2026, 1, 1);
        System.out.println("ロケール=" + Locale.getDefault());
        System.out.println("  " + date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd(E)"))
                + " " + "ishida".toUpperCase() + " " + String.format("%,d", 1_234_567L) + "円");
    }
}
