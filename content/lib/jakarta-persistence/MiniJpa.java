import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.TypedQuery;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** EntityManagerの基本操作を外部DBなしで練習するための最小実装。 */
public final class MiniJpa {
    private MiniJpa() { }
    public static EntityManager open() { return new MemoryEntityManager(); }

    private static final class MemoryEntityManager implements EntityManager {
        private final Map<Class<?>, LinkedHashMap<Object, Object>> tables = new LinkedHashMap<>();
        private long sequence = 1;

        public <T> T find(Class<T> type, Object id) {
            Object entity = table(type).get(id);
            return entity == null ? null : type.cast(entity);
        }
        public void persist(Object entity) {
            Field idField = idField(entity.getClass());
            try {
                idField.setAccessible(true);
                Object id = idField.get(entity);
                if (id == null || id instanceof Number n && n.longValue() == 0) {
                    if (idField.getType() == Long.class || idField.getType() == long.class) idField.set(entity, sequence++);
                    else if (idField.getType() == Integer.class || idField.getType() == int.class) idField.set(entity, (int) sequence++);
                    else throw new IllegalStateException("自動採番できるID型はLong/long/Integer/intです");
                    id = idField.get(entity);
                }
                if (table(entity.getClass()).containsKey(id)) throw new IllegalStateException("同じIDが既にあります: " + id);
                table(entity.getClass()).put(id, entity);
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        public <T> T merge(T entity) {
            Object id = idValue(entity);
            table(entity.getClass()).put(id, entity);
            return entity;
        }
        public void remove(Object entity) { table(entity.getClass()).remove(idValue(entity)); }
        public <T> TypedQuery<T> createQuery(String jpql, Class<T> type) { return new MemoryQuery<>(jpql, type, this); }
        public void flush() { }

        private LinkedHashMap<Object, Object> table(Class<?> type) {
            return tables.computeIfAbsent(type, key -> new LinkedHashMap<>());
        }
        private static Field idField(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields()) if (f.isAnnotationPresent(Id.class)) return f;
            throw new IllegalStateException(type.getSimpleName() + " に@Idがありません");
        }
        private static Object idValue(Object entity) {
            try { Field f = idField(entity.getClass()); f.setAccessible(true); return f.get(entity); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    private static final class MemoryQuery<T> implements TypedQuery<T> {
        private final String jpql;
        private final Class<T> type;
        private final MemoryEntityManager em;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        MemoryQuery(String jpql, Class<T> type, MemoryEntityManager em) { this.jpql = jpql; this.type = type; this.em = em; }
        public TypedQuery<T> setParameter(String name, Object value) { parameters.put(name, value); return this; }
        public List<T> getResultList() {
            List<T> all = em.table(type).values().stream().map(type::cast).toList();
            String normalized = jpql.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            int where = normalized.indexOf(" where ");
            if (where < 0) return all;
            String clause = normalized.substring(where + 7);
            String[] sides = clause.split("=", 2);
            if (sides.length != 2 || !sides[1].strip().startsWith(":")) throw new IllegalStateException("MiniJpaは `e.field = :name` だけ対応します");
            String fieldName = sides[0].strip().substring(sides[0].strip().indexOf('.') + 1);
            Object expected = parameters.get(sides[1].strip().substring(1));
            List<T> out = new ArrayList<>();
            for (T entity : all) {
                try {
                    Field field = entity.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (java.util.Objects.equals(field.get(entity), expected)) out.add(entity);
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            return out;
        }
        public T getSingleResult() {
            List<T> list = getResultList();
            if (list.size() != 1) throw new IllegalStateException("結果が1件ではありません: " + list.size());
            return list.get(0);
        }
    }
}
