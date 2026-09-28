package api;

public interface Service<T extends Model> {
    T process(String input);
    void validate(T model) throws ValidationException;
    default String describe() { return getClass().getSimpleName(); }
}
