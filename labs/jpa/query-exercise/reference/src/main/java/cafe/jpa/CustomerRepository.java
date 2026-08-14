package cafe.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 顧客の読み出し（模範解答）。
 *
 * <p>要点は2つ。
 *
 * <ul>
 *   <li><b>関連をたどるなら、たどる前にまとめて取る。</b>
 *       {@code JOIN FETCH}を書かないと、顧客を1件ずつ触るたびに追加のSELECTが飛ぶ（N+1）。
 *       件数が少ないうちは気づかず、データが増えてから遅くなる。
 *       {@code DISTINCT}が必要なのは、JOINで顧客の行が注文の数だけ複製されるため。</li>
 *   <li><b>EntityManagerを閉じる前に、必要な値をそろえる。</b>
 *       遅延読み込みは「あとで取ってくる」だけで、閉じたあとには取ってこられない
 *       （{@code LazyInitializationException}）。閉じたあとも使う値は、
 *       閉じる前に入れ物へ移しておく。</li>
 * </ul>
 *
 * <p>どちらも「1件のときは動く」ので、テストが1件だけだと本番まで残る。
 */
public class CustomerRepository {

    private final EntityManagerFactory factory;

    public CustomerRepository(EntityManagerFactory factory) {
        this.factory = factory;
    }

    /** 全顧客と、その注文の品名を返す。SQLは1本。 */
    public List<CustomerSummary> findAllWithOrders() {
        EntityManager manager = factory.createEntityManager();
        try {
            List<Customer> customers = manager.createQuery(
                            "SELECT DISTINCT c FROM Customer c LEFT JOIN FETCH c.orders ORDER BY c.id",
                            Customer.class)
                    .getResultList();

            List<CustomerSummary> summaries = new ArrayList<>();
            for (Customer customer : customers) {
                summaries.add(toSummary(customer));
            }
            return summaries;
        } finally {
            manager.close();
        }
    }

    /** 1人の顧客と、その注文の品名を返す。閉じる前に値をそろえる。 */
    public CustomerSummary findOne(long id) {
        EntityManager manager = factory.createEntityManager();
        try {
            Customer customer = manager.find(Customer.class, id);
            if (customer == null) {
                return null;
            }
            // 閉じる前に入れ物へ移す。ここで注文を触るので、遅延読み込みも間に合う
            return toSummary(customer);
        } finally {
            manager.close();
        }
    }

    private static CustomerSummary toSummary(Customer customer) {
        List<String> items = new ArrayList<>();
        for (CustomerOrder order : customer.getOrders()) {
            items.add(order.getItem());
        }
        return new CustomerSummary(customer.getName(), customer.getBudgetYen(), items);
    }
}
