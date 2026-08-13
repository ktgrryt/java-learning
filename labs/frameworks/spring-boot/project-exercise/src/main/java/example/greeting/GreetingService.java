package example.greeting;

class GreetingService {
    private final String prefix;

    GreetingService(String prefix) {
        this.prefix = prefix;
    }

    String message(String name) {
        // TODO: 外側の空白を除き、設定したprefixで挨拶する
        return prefix + name;
    }
}
