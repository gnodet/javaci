package impl;

public class Helper {
    public String normalize(String input) {
        if (input == null) return "";
        return input.strip().toLowerCase();
    }

    public int computeValue(String input) {
        return input.hashCode() * 31;
    }
}
