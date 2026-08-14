package cafe.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 注文テーブルのEntity。参照専用（変更しません）。 */
@Entity
public class Order {

    @Id
    public Long id;

    public Long customerId;

    /** NEW / PAID / CANCELLED */
    public String status;

    public int amount;
}
