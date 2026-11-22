package com.daymark.domain;

public enum Priority {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    // so combo boxes and the table show "High" instead of "HIGH"
    @Override
    public String toString() {
        return label;
    }
}
