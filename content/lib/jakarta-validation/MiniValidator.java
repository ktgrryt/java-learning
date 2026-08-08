import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Bean Validation の基本を標準入力で練習するための小さな検証器。
 * 制約注釈の見た目は本物と同じだが、対応する型と規則は教材で使う範囲だけ。
 */
public final class MiniValidator {
    private MiniValidator() { }

    public static List<String> validate(Object bean) {
        List<String> errors = new ArrayList<>();
        if (bean == null) {
            errors.add("bean: nullです");
            return errors;
        }
        Class<?> type = bean.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    check(component.getName(), component.getAccessor().invoke(bean), component, errors);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    check(field.getName(), field.get(bean), field, errors);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void check(String name, Object value, java.lang.reflect.AnnotatedElement element,
                              List<String> errors) {
        NotBlank notBlank = element.getAnnotation(NotBlank.class);
        if (notBlank != null && (!(value instanceof CharSequence s) || s.toString().isBlank())) {
            errors.add(name + ": " + notBlank.message());
        }
        Size size = element.getAnnotation(Size.class);
        if (size != null && value instanceof CharSequence s
                && (s.length() < size.min() || s.length() > size.max())) {
            errors.add(name + ": " + size.message());
        }
        Min min = element.getAnnotation(Min.class);
        if (min != null && value instanceof Number n && n.longValue() < min.value()) {
            errors.add(name + ": " + min.message());
        }
        Max max = element.getAnnotation(Max.class);
        if (max != null && value instanceof Number n && n.longValue() > max.value()) {
            errors.add(name + ": " + max.message());
        }
        Email email = element.getAnnotation(Email.class);
        if (email != null && value instanceof CharSequence s && !s.toString().isBlank()
                && !s.toString().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            errors.add(name + ": " + email.message());
        }
    }
}
