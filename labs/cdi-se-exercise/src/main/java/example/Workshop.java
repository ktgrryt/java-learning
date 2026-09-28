package example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import java.lang.annotation.*;
import java.util.ArrayList;
import java.util.List;

public class Workshop {
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
    public @interface Mail {}

    public interface Sender { String send(String text); }
    public record Sent(String result) {}

    @Dependent
    public static class SmsSender implements Sender {
        public String send(String text) { return "sms:" + text; }
    }

    @Dependent
    public static class Producers {
        // TODO: Producerと用途の名札を付ける
        public Sender mailSender() { return text -> "mail:" + text; }
    }

    @Dependent
    public static class NotificationService {
        // TODO: Mail用SenderをCDIから注入する
        Sender sender;
        @Inject Event<Sent> events;
        public String notify(String text) {
            String result = sender.send(text);
            // TODO: 成功結果のイベントを発火する
            return result;
        }
    }

    @ApplicationScoped
    public static class ReceiptObserver {
        private final List<String> receipts = new ArrayList<>();
        public void on(/* TODO: 購読の注釈 */ Sent event) { receipts.add(event.result()); }
        public List<String> receipts() { return List.copyOf(receipts); }
    }
}
