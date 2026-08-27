package cafe.report;

import java.time.LocalDate;

/** 固定のデータで2行出す。参照専用です。 */
public class Main {

    public static void main(String[] args) {
        System.out.println(Receipt.line(LocalDate.of(2026, 1, 1), "ishida", 1_234_567L));
        System.out.println(Receipt.line(LocalDate.of(2026, 5, 3), "kimura", 980L));
    }
}
