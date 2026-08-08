package jakarta.persistence;

import java.util.List;

public interface TypedQuery<T> {
    TypedQuery<T> setParameter(String name, Object value);
    List<T> getResultList();
    T getSingleResult();
}
