package io.github.carefreebit.jvmd.model;

/**
 * A single stack frame, parsed from a HotSpot thread dump line such as
 * {@code at java.lang.Object.wait(java.base@17.0.2/Native Method)}.
 */
public record StackFrame(String className, String methodName, String fileName, int line, String raw) {

    public static StackFrame parse(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("at ")) {
            trimmed = trimmed.substring(3);
        }
        int open = trimmed.indexOf('(');
        int close = trimmed.lastIndexOf(')');
        if (open < 0 || close < open) {
            return new StackFrame(trimmed, "", "", -1, raw.trim());
        }
        String method = trimmed.substring(0, open);
        String location = trimmed.substring(open + 1, close);

        // java.base@17.0.2/Native Method  ->  Native Method
        int slash = location.lastIndexOf('/');
        if (slash >= 0) {
            location = location.substring(slash + 1);
        }

        String file = location;
        int line = -1;
        int colon = location.lastIndexOf(':');
        if (colon > 0) {
            String tail = location.substring(colon + 1);
            if (tail.chars().allMatch(Character::isDigit)) {
                try {
                    line = Integer.parseInt(tail);
                    file = location.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // keep the whole location
                }
            }
        }

        int dot = method.lastIndexOf('.');
        String className = dot > 0 ? method.substring(0, dot) : method;
        String methodName = dot > 0 ? method.substring(dot + 1) : "";

        return new StackFrame(className, methodName, file, line, raw.trim());
    }

    /** Short, readable location such as {@code ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1061)}. */
    public String display() {
        String simple = simpleClassName(className);
        String loc = line > 0 ? fileName + ":" + line : (fileName.isEmpty() ? "?" : fileName);
        return simple + "." + methodName + "(" + loc + ")";
    }

    /** Class name without the package, and without lambda suffixes. */
    public String simpleClassName() {
        return simpleClassName(className);
    }

    public static String simpleClassName(String fqcn) {
        String name = fqcn;
        int slash = name.indexOf('/');
        if (slash > 0) {
            name = name.substring(0, slash);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name;
    }

    public boolean isNative() {
        return line <= 0;
    }
}
