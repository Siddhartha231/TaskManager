package com.daymark.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

    @Test
    void testIsOverdue() {
        Task task = new Task("Finish lab 4", null, LocalDate.now().minusDays(1), Priority.HIGH);
        assertTrue(task.isOverdue());

        Task finished = task.withDone(true);
        assertFalse(finished.isOverdue());
    }

    @Test
    void testWithMethodsReturnCopies() {
        Task original = new Task("Finish lab 4", null, null, Priority.LOW);
        Task changed = original.withTitle("Finish lab 5").withPriority(Priority.HIGH).withDone(true);

        // the original is untouched
        assertEquals("Finish lab 4", original.title());
        assertEquals(Priority.LOW, original.priority());
        assertFalse(original.done());

        assertEquals("Finish lab 5", changed.title());
        assertEquals(Priority.HIGH, changed.priority());
        assertTrue(changed.done());
        assertEquals(original.createdAt(), changed.createdAt());
    }

    @Test
    void testNotOverdueWithoutDueDate() {
        Task task = new Task("Buy groceries", null, null, Priority.LOW);
        assertFalse(task.isOverdue());
    }

    @Test
    void testDueTodayIsNotOverdue() {
        Task task = new Task("Study for midterm", "chapters 5-7", LocalDate.now(), Priority.MEDIUM);
        assertFalse(task.isOverdue());
    }

    @Test
    void testPriorityToString() {
        assertEquals("High", Priority.HIGH.toString());
        assertEquals("Low", Priority.LOW.toString());
    }
}
