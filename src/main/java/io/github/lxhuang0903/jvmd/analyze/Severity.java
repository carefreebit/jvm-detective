package io.github.lxhuang0903.jvmd.analyze;

/** Ordered incident severity. */
public enum Severity {
    CRITICAL(4, "Critical", "#ff4d4f"),
    HIGH(3, "High", "#ff7a45"),
    MEDIUM(2, "Medium", "#f5b942"),
    LOW(1, "Low", "#4dabf7"),
    INFO(0, "Info", "#8b93a7");

    private final int rank;
    private final String label;
    private final String color;

    Severity(int rank, String label, String color) {
        this.rank = rank;
        this.label = label;
        this.color = color;
    }

    public int rank() {
        return rank;
    }

    public String label() {
        return label;
    }

    public String color() {
        return color;
    }
}
