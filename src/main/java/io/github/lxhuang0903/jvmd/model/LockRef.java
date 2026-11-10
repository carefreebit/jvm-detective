package io.github.lxhuang0903.jvmd.model;

/** A monitor / lock reference from a thread dump, e.g. {@code <0x00000000d6c0a1b8> (a java.lang.Object)}. */
public record LockRef(String id, String className) {

    public String shortClass() {
        if (className == null || className.isEmpty()) {
            return "object";
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    public String label() {
        return id + " (" + shortClass() + ")";
    }
}
