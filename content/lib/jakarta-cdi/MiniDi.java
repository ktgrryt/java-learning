import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** CDIのコンストラクタ注入とApplicationScopedを体験するための最小コンテナ。 */
public final class MiniDi {
    private final Map<Class<?>, Class<?>> bindings = new LinkedHashMap<>();
    private final Map<Class<?>, Object> applicationScope = new LinkedHashMap<>();
    private final ThreadLocal<ArrayDeque<Class<?>>> creating = ThreadLocal.withInitial(ArrayDeque::new);

    public static MiniDi container() { return new MiniDi(); }

    public <T> MiniDi bind(Class<T> contract, Class<? extends T> implementation) {
        bindings.put(contract, implementation);
        return this;
    }

    public <T> T get(Class<T> contract) {
        Class<?> implementation = bindings.getOrDefault(contract, contract);
        Object value = implementation.isAnnotationPresent(ApplicationScoped.class)
                ? applicationScope.computeIfAbsent(implementation, this::create)
                : create(implementation);
        return contract.cast(value);
    }

    private Object create(Class<?> type) {
        ArrayDeque<Class<?>> stack = creating.get();
        if (stack.contains(type)) throw new IllegalStateException("循環依存: " + stack + " -> " + type.getSimpleName());
        stack.push(type);
        try {
            Constructor<?> selected = null;
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(Inject.class)) {
                    if (selected != null) throw new IllegalStateException(type.getSimpleName() + " に@Injectコンストラクタが複数あります");
                    selected = constructor;
                }
            }
            if (selected == null) {
                try { selected = type.getDeclaredConstructor(); }
                catch (NoSuchMethodException e) { throw new IllegalStateException(type.getSimpleName() + " に@Injectコンストラクタか引数なしコンストラクタが必要です"); }
            }
            selected.setAccessible(true);
            Class<?>[] parameterTypes = selected.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < arguments.length; i++) arguments[i] = get(parameterTypes[i]);
            Object instance = selected.newInstance(arguments);
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Inject.class)) {
                        field.setAccessible(true);
                        field.set(instance, get(field.getType()));
                    }
                }
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + " を生成できません", e);
        } finally {
            stack.pop();
            if (stack.isEmpty()) creating.remove();
        }
    }
}
