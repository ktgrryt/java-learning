import java.util.ArrayDeque;
import java.util.Deque;

public class AllocationDemo {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("pid=" + ProcessHandle.current().pid());
        Deque<byte[]> retained = new ArrayDeque<>();

        long deadline = System.nanoTime() + 60_000_000_000L;
        int rounds = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 1_000; i++) {
                byte[] value = new byte[8 * 1024];
                value[0] = (byte) i;
                if (i % 100 == 0) {
                    retained.addLast(value);
                }
            }
            while (retained.size() > 2_000) {
                retained.removeFirst();
            }
            rounds++;
            if (rounds % 20 == 0) {
                System.out.println("rounds=" + rounds + " retained=" + retained.size());
            }
            Thread.sleep(20);
        }
    }
}
