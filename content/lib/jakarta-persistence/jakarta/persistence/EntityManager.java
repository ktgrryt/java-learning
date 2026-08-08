package jakarta.persistence;

public interface EntityManager {
    <T> T find(Class<T> type, Object id);
    void persist(Object entity);
    <T> T merge(T entity);
    void remove(Object entity);
    <T> TypedQuery<T> createQuery(String jpql, Class<T> type);
    void flush();
}
