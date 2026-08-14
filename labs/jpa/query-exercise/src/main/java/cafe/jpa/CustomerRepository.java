package cafe.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 顧客の読み出し。
 *
 * <p>いまの実装は「動くけれど遅い」「閉じたあとに壊れる」の両方を持っています。
 * どちらもコードを読むだけでは気づきにくく、実DBへ当てて<b>発行されたSQLの本数</b>と
 * <b>例外</b>を見ると分かります。
 *
 * <p>メソッドの名前・引数・戻り値は採点の足場が呼ぶので変えないこと。
 */
public class CustomerRepository {

    private final EntityManagerFactory factory;

    public CustomerRepository(EntityManagerFactory factory) {
        this.factory = factory;
    }

    /**
     * 全顧客と、その注文の品名を返す。
     *
     * <p>採点は発行されたSQLの本数を数えます。顧客が5人でも<b>2本以内</b>にしてください。
     */
    public List<CustomerSummary> findAllWithOrders() {
        EntityManager manager = factory.createEntityManager();
        try {
            List<Customer> customers = manager
                    .createQuery("SELECT c FROM Customer c ORDER BY c.id", Customer.class)
                    .getResultList();

            List<CustomerSummary> summaries = new ArrayList<>();
            for (Customer customer : customers) {
                // TODO: ここで注文を触るたびに、追加のSELECTが1本ずつ飛んでいる
                summaries.add(toSummary(customer));
            }
            return summaries;
        } finally {
            manager.close();
        }
    }

    /**
     * 1人の顧客と、その注文の品名を返す。
     *
     * <p>採点は返ってきた値を使うだけです。いまは例外になります。
     */
    public CustomerSummary findOne(long id) {
        EntityManager manager = factory.createEntityManager();
        Customer customer;
        try {
            customer = manager.find(Customer.class, id);
        } finally {
            manager.close();
        }
        // TODO: 閉じたあとに注文を触っている。必要な値は閉じる前にそろえる
        return toSummary(customer);
    }

    private static CustomerSummary toSummary(Customer customer) {
        List<String> items = new ArrayList<>();
        for (CustomerOrder order : customer.getOrders()) {
            items.add(order.getItem());
        }
        return new CustomerSummary(customer.getName(), customer.getBudgetYen(), items);
    }
}
