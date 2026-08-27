package cafe.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 領収書の1行を作る。
 *
 * いまの実装は「自分の環境では正しく見える」書き方です。
 * 2つの TODO を直して、**どの環境で動かしても同じ1行**になるようにしてください。
 */
public class Receipt {

    public static String line(LocalDate date, String name, long amount) {
        // TODO ① 曜日の言語を固定する（日本語で出す）
        String day = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd(E)"));

        // TODO ② 大文字化と桁区切りも、実行環境に左右されない形にする
        return day + " " + name.toUpperCase() + " " + String.format("%,d", amount) + "円";
    }
}
