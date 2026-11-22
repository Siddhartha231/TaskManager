package com.daymark.service;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import com.daymark.persistence.TaskRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sits between the UI and the repository. The UI should never talk to
 * the repository directly - all the rules about what a valid task is
 * live here so the tests and the dialog go through the same checks.
 */
public class TaskService {

    private final TaskRepository repository;
    private final QuickAddParser quickAddParser = new QuickAddParser();

    // "what day is it" comes from a clock so the tests can pin it to a fixed date
    private final Clock clock;

    // the most recently deleted task, kept around so the delete can be undone
    private Task lastDeleted;

    public TaskService(TaskRepository repository) {
        this(repository, Clock.systemDefaultZone());
    }

    public TaskService(TaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Task> getAllTasks() {
        return repository.findAll();
    }

    public Task createTask(String title, String description, LocalDate dueDate, Priority priority) {
        return createTask(title, description, dueDate, priority, Recurrence.NONE);
    }

    public Task createTask(String title, String description, LocalDate dueDate, Priority priority,
                           Recurrence recurrence) {
        String cleanTitle = checkTitle(title);
        if (priority == null) {
            priority = Priority.MEDIUM;
        }
        if (recurrence == null) {
            recurrence = Recurrence.NONE;
        }
        Task task = new Task(cleanTitle, cleanDescription(description), dueDate, priority)
                .withRecurrence(recurrence);
        return repository.insert(task);
    }

    // Creates a task from one line of text like "essay due fri !high".
    // See QuickAddParser for what it understands.
    public Task quickAdd(String text) {
        Task parsed = quickAddParser.parse(text, LocalDate.now(clock));
        return createTask(parsed.title(), parsed.description(), parsed.dueDate(),
                parsed.priority(), parsed.recurrence());
    }

    /**
     * Saves tasks that came from a file. Each one goes through the same title check as
     * everything else, and the ones that fail are skipped instead of stopping the import.
     * Returns how many were saved.
     */
    public int importTasks(List<Task> tasks) {
        int imported = 0;
        for (Task task : tasks) {
            try {
                Task clean = task.withId(0)
                        .withTitle(checkTitle(task.title()))
                        .withDescription(cleanDescription(task.description()));
                repository.insert(clean);
                imported++;
            } catch (IllegalArgumentException e) {
                // bad title, skip this row
            }
        }
        return imported;
    }

    public Task updateTask(Task task) {
        Task clean = task.withTitle(checkTitle(task.title()))
                .withDescription(cleanDescription(task.description()));
        repository.update(clean);
        return clean;
    }

    public void markDone(int id) {
        Task task = repository.findById(id);
        if (task == null || task.done()) {
            return;
        }
        repository.update(task.withDone(true));

        // a repeating task comes back as a new task with the next due date
        if (task.recurrence() != Recurrence.NONE) {
            LocalDate next = task.recurrence().nextDueDate(task.dueDate(), LocalDate.now(clock));
            repository.insert(new Task(0, task.title(), task.description(), next, task.priority(),
                    false, LocalDateTime.now(clock), task.recurrence()));
        }
    }

    public void markUndone(int id) {
        Task task = repository.findById(id);
        if (task != null) {
            repository.update(task.withDone(false));
        }
    }

    public void deleteTask(int id) {
        Task task = repository.findById(id);
        if (task == null) {
            return;
        }
        repository.delete(id);
        lastDeleted = task;
    }

    public boolean canUndoDelete() {
        return lastDeleted != null;
    }

    // Puts the most recently deleted task back. Returns it, or null if there is nothing to undo.
    // (It gets a new id, everything else is the same as before.)
    public Task undoDelete() {
        if (lastDeleted == null) {
            return null;
        }
        Task restored = repository.insert(lastDeleted);
        lastDeleted = null;
        return restored;
    }

    private String checkTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title cannot be empty");
        }
        String clean = title.trim();
        if (clean.length() > 100) {
            throw new IllegalArgumentException("Title is too long (max 100 characters)");
        }
        return clean;
    }

    private String cleanDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }
        return description.trim();
    }
}
