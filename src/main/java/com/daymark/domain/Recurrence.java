package com.daymark.domain;

import java.time.LocalDate;

/**
 * How often a task repeats. When a repeating task is marked done, the service
 * creates the next one using nextDueDate().
 */
public enum Recurrence {
    NONE("Never"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly");

    private final String label;

    Recurrence(String label) {
        this.label = label;
    }

    /**
     * The next due date after the given one. If the task was finished late and that
     * date has already passed, keeps stepping forward until it lands on today or later,
     * so you never get a brand new task that is already overdue.
     */
    public LocalDate nextDueDate(LocalDate dueDate, LocalDate today) {
        if (this == NONE) {
            return dueDate;
        }
        LocalDate base = dueDate == null ? today : dueDate;
        // always step from the original date (base + 1, base + 2, ...) so a monthly
        // task due on the 31st doesn't drift to the 28th forever after February
        int steps = 1;
        LocalDate next = step(base, steps);
        while (next.isBefore(today)) {
            steps++;
            next = step(base, steps);
        }
        return next;
    }

    private LocalDate step(LocalDate base, int steps) {
        switch (this) {
            case DAILY:
                return base.plusDays(steps);
            case WEEKLY:
                return base.plusWeeks(steps);
            case MONTHLY:
                return base.plusMonths(steps);
            default:
                return base;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
