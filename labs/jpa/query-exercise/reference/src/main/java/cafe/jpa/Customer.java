package cafe.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * 顧客（模範解答）。
 *
 * <p>{@code @Version}を付けると、JPAは更新のたびに
 * {@code UPDATE ... WHERE id = ? AND version = ?} を発行し、更新できた行数を見ます。
 * 0行なら「読んだあとに他の誰かが変えた」ということなので、
 * {@link jakarta.persistence.OptimisticLockException}になります。
 *
 * <p>行をロックして待たせる（悲観ロック）のではなく、<b>ぶつかったときに気づく</b>方式です。
 * 待たないので同時実行性は落ちませんが、呼び出し側に「やり直す」処理が必要になります。
 * 列があるだけでは何も守られず、この宣言が判断の分かれ目です。
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "budget_yen", nullable = false)
    private int budgetYen;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CustomerOrder> orders = new ArrayList<>();

    protected Customer() {
    }

    public Customer(String name, int budgetYen) {
        this.name = name;
        this.budgetYen = budgetYen;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getBudgetYen() {
        return budgetYen;
    }

    public void setBudgetYen(int budgetYen) {
        this.budgetYen = budgetYen;
    }

    public int getVersion() {
        return version;
    }

    public List<CustomerOrder> getOrders() {
        return orders;
    }

    public void addOrder(String item) {
        orders.add(new CustomerOrder(this, item));
    }
}
