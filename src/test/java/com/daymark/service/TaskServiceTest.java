package com.daymark.service;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import com.daymark.persistence.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {

    private TaskService service;

    // Wednesday March 12 2025 - the service gets a clock stuck on this day, so tests
    // about "today" and "this Friday" give the same answer no matter when they run
    private static final LocalDate TODAY = LocalDate.of(2025, 3, 12);

    // the service only needs a TaskRepository, so these tests don't touch a database at all
    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                TODAY.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new TaskService(new InMemoryTaskRepository(), fixedClock);
    }

    @Test
    void testEmptyTitleThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask("", null, null, Priority.LOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask("   ", null, null, Priority.LOW));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(null, null, null, Priority.LOW));
    }

    @Test
    void testTitleTooLong() {
        String longTitle = "a".repeat(101);
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(longTitle, null, null, Priority.LOW));
    }

    @Test
    void testTitleTrimmed() {
        Task task = service.createTask("  Finish lab 4  ", "  notes  ", null, Priority.HIGH);
        assertEquals("Finish lab 4", task.title());
        assertEquals("notes", task.description());
    }

    @Test
    void testBlankDescriptionBecomesNull() {
        Task task = service.createTask("Buy groceries", "   ", null, Priority.LOW);
        assertNull(task.description());
    }

    @Test
    void testMarkDoneAndUndone() {
        Task task = service.createTask("Study for midterm", null, null, Priority.MEDIUM);
        assertFalse(task.done());

        service.markDone(task.id());
        assertTrue(service.getAllTasks().get(0).done());

        service.markUndone(task.id());
        assertFalse(service.getAllTasks().get(0).done());
    }

    @Test
    void testDelete() {
        Task task = service.createTask("Buy groceries", null, null, Priority.LOW);
        service.deleteTask(task.id());
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testUpdateValidatesAndCleans() {
        Task task = service.createTask("Buy groceries", null, null, Priority.LOW);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(task.withTitle("   ")));
        // the failed update must not have changed what is stored
        assertEquals("Buy groceries", service.getAllTasks().get(0).title());

        service.updateTask(task.withTitle("  Buy groceries and milk  "));
        assertEquals("Buy groceries and milk", service.getAllTasks().get(0).title());
    }

    @Test
    void testQuickAdd() {
        Task task = service.quickAdd("essay due fri !high");

        assertEquals("essay", task.title());
        assertEquals(LocalDate.of(2025, 3, 14), task.dueDate());
        assertEquals(Priority.HIGH, task.priority());
        assertEquals(1, service.getAllTasks().size());
    }

    @Test
    void testQuickAddWithNoTitleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.quickAdd("due fri !high"));
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testFinishingRepeatingTaskCreatesTheNextOne() {
        Task rent = service.createTask("Pay rent", "landlord portal", LocalDate.of(2025, 3, 14),
                Priority.HIGH, Recurrence.MONTHLY);

        service.markDone(rent.id());

        List<Task> all = service.getAllTasks();
        assertEquals(2, all.size());
        assertTrue(all.get(0).done());

        Task next = all.get(1);
        assertFalse(next.done());
        assertEquals("Pay rent", next.title());
        assertEquals("landlord portal", next.description());
        assertEquals(LocalDate.of(2025, 4, 14), next.dueDate());
        assertEquals(Priority.HIGH, next.priority());
        assertEquals(Recurrence.MONTHLY, next.recurrence());
    }

    @Test
    void testFinishingNormalTaskDoesNotCreateAnother() {
        Task task = service.createTask("Finish lab 4", null, LocalDate.of(2025, 3, 14), Priority.LOW);
        service.markDone(task.id());
        assertEquals(1, service.getAllTasks().size());
    }

    @Test
    void testMarkingDoneTwiceOnlyCreatesOneNextTask() {
        Task task = service.createTask("Water plants", null, TODAY, Priority.LOW, Recurrence.DAILY);
        service.markDone(task.id());
        service.markDone(task.id());
        assertEquals(2, service.getAllTasks().size());
    }

    @Test
    void testUndoDeleteBringsTheTaskBack() {
        Task task = service.createTask("Finish lab 4", "due friday", LocalDate.of(2025, 3, 14),
                Priority.HIGH, Recurrence.WEEKLY);
        assertFalse(service.canUndoDelete());

        service.deleteTask(task.id());
        assertTrue(service.getAllTasks().isEmpty());
        assertTrue(service.canUndoDelete());

        Task restored = service.undoDelete();

        assertEquals(1, service.getAllTasks().size());
        // same task as before except for the id
        assertEquals(task.withId(restored.id()), restored);
        // and there is nothing left to undo
        assertFalse(service.canUndoDelete());
        assertNull(service.undoDelete());
        assertEquals(1, service.getAllTasks().size());
    }

    @Test
    void testUndoOnlyRestoresTheMostRecentDelete() {
        Task first = service.createTask("Finish lab 4", null, null, Priority.LOW);
        Task second = service.createTask("Buy groceries", null, null, Priority.LOW);

        service.deleteTask(first.id());
        service.deleteTask(second.id());
        service.undoDelete();

        assertEquals(1, service.getAllTasks().size());
        assertEquals("Buy groceries", service.getAllTasks().get(0).title());
    }

    @Test
    void testDeletingTaskThatDoesNotExistLeavesNothingToUndo() {
        service.deleteTask(999);
        assertFalse(service.canUndoDelete());
    }

    @Test
    void testImportSkipsTasksWithBadTitles() {
        List<Task> fromFile = List.of(
                new Task("  Finish lab 4  ", "  ", null, Priority.HIGH),
                new Task("   ", null, null, Priority.LOW),
                new Task("x".repeat(101), null, null, Priority.LOW),
                new Task("Buy groceries", null, null, Priority.LOW).withDone(true));

        int imported = service.importTasks(fromFile);

        assertEquals(2, imported);
        List<Task> all = service.getAllTasks();
        assertEquals(2, all.size());
        assertEquals("Finish lab 4", all.get(0).title());
        assertNull(all.get(0).description());
        // a finished task stays finished
        assertTrue(all.get(1).done());
    }
}
