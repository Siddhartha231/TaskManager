package com.daymark.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A task is immutable - to change one you make a copy with the with...() methods
 * and save the copy. That way nothing can modify a task behind the service's back.
 */
public record Task(int id, String title, String description, LocalDate dueDate,
                   Priority priority, boolean done, LocalDateTime createdAt, Recurrence recurrence) {

    // for new tasks, the real id comes back from the repository after insert
    public Task(String title, String description, LocalDate dueDate, Priority priority) {
        this(0, title, description, dueDate, priority, false, LocalDateTime.now(), Recurrence.NONE);
    }

    public boolean isOverdue() {
        if (done || dueDate == null) {
            return false;
        }
        return dueDate.isBefore(LocalDate.now());
    }

    public Task withId(int newId) {
        return new Task(newId, title, description, dueDate, priority, done, createdAt, recurrence);
    }

    public Task withTitle(String newTitle) {
        return new Task(id, newTitle, description, dueDate, priority, done, createdAt, recurrence);
    }

    public Task withDescription(String newDescription) {
        return new Task(id, title, newDescription, dueDate, priority, done, createdAt, recurrence);
    }

    public Task withDueDate(LocalDate newDueDate) {
        return new Task(id, title, description, newDueDate, priority, done, createdAt, recurrence);
    }

    public Task withPriority(Priority newPriority) {
        return new Task(id, title, description, dueDate, newPriority, done, createdAt, recurrence);
    }

    public Task withDone(boolean newDone) {
        return new Task(id, title, description, dueDate, priority, newDone, createdAt, recurrence);
    }

    public Task withRecurrence(Recurrence newRecurrence) {
        return new Task(id, title, description, dueDate, priority, done, createdAt, newRecurrence);
    }

    @Override
    public String toString() {
        return "Task #" + id + " " + title + " (" + priority + ", due " + dueDate + ")";
    }
}
