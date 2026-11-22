package com.daymark.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class RecurrenceTest {

    private final LocalDate today = LocalDate.of(2025, 3, 12);

    @Test
    void testNextDueDateForEachKind() {
        LocalDate due = LocalDate.of(2025, 3, 14);
        assertEquals(LocalDate.of(2025, 3, 15), Recurrence.DAILY.nextDueDate(due, today));
        assertEquals(LocalDate.of(2025, 3, 21), Recurrence.WEEKLY.nextDueDate(due, today));
        assertEquals(LocalDate.of(2025, 4, 14), Recurrence.MONTHLY.nextDueDate(due, today));
        assertEquals(due, Recurrence.NONE.nextDueDate(due, today));
    }

    @Test
    void testNoDueDateStartsFromToday() {
        assertEquals(LocalDate.of(2025, 3, 13), Recurrence.DAILY.nextDueDate(null, today));
        assertEquals(LocalDate.of(2025, 3, 19), Recurrence.WEEKLY.nextDueDate(null, today));
    }

    @Test
    void testFinishedLateSkipsDatesThatAlreadyPassed() {
        // weekly task that was due three weeks ago: the next one should not be born overdue
        LocalDate due = LocalDate.of(2025, 2, 19);
        assertEquals(LocalDate.of(2025, 3, 12), Recurrence.WEEKLY.nextDueDate(due, today));

        LocalDate oldDaily = LocalDate.of(2025, 3, 1);
        assertEquals(today, Recurrence.DAILY.nextDueDate(oldDaily, today));
    }

    @Test
    void testMonthlyOnThe31stDoesNotDrift() {
        LocalDate jan31 = LocalDate.of(2025, 1, 31);
        // February has no 31st so it lands on the 28th...
        assertEquals(LocalDate.of(2025, 2, 28), Recurrence.MONTHLY.nextDueDate(jan31, LocalDate.of(2025, 1, 31)));
        // ...but skipping ahead to March still gives the 31st, not the 28th
        assertEquals(LocalDate.of(2025, 3, 31), Recurrence.MONTHLY.nextDueDate(jan31, LocalDate.of(2025, 3, 5)));
    }
}
