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

import java.util.ArrayList;
import java.util.List;

/**
 * 顧客。
 *
 * <p>{@code version}列はDBに<b>すでにあります</b>。しかしいまの宣言では、ただの整数の列です。
 * 同じ行を2つのトランザクションが読んで両方が書くと、<b>あとの書き込みが黙って上書きします</b>
 * （更新の喪失）。列があるだけでは何も守られません。
 *
 * <p>TODO: この列を「同時更新の検出」に使わせる宣言を1つ足してください。
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

    // TODO: ここに宣言を1つ足す
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
