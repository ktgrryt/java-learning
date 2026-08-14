package cafe.ops;

import java.util.ArrayList;
import java.util.List;

/** 待たずに、待つつもりだったミリ秒だけを順番に記録する。 */
public final class RecordingSleeper implements Sleeper {

    private final List<Long> waits = new ArrayList<>();

    @Override
    public void sleep(long millis) {
        waits.add(millis);
    }

    public List<Long> waits() {
        return List.copyOf(waits);
    }
}
