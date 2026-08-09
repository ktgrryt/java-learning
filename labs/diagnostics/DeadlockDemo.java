public class DeadlockDemo {
    private static final Object FIRST = new Object();
    private static final Object SECOND = new Object();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("pid=" + ProcessHandle.current().pid());

        Thread left = new Thread(() -> lockInOrder(FIRST, SECOND), "left-first-second");
        Thread right = new Thread(() -> lockInOrder(SECOND, FIRST), "right-second-first");
        left.start();
        right.start();
        left.join();
        right.join();
    }

    private static void lockInOrder(Object first, Object second) {
        synchronized (first) {
            sleep(300);
            synchronized (second) {
                System.out.println(Thread.currentThread().getName() + " acquired both");
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
