package com.daymark.persistence;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteTaskRepositoryTest {

    private DatabaseManager db;
    private SQLiteTaskRepository repo;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(":memory:");
        repo = new SQLiteTaskRepository(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void testInsertAndFind() {
        Task task = repo.insert(new Task("Finish lab 4", "due friday", LocalDate.of(2025, 3, 14), Priority.HIGH));

        assertTrue(task.id() > 0);

        Task found = repo.findById(task.id());
        assertNotNull(found);
        assertEquals("Finish lab 4", found.title());
        assertEquals("due friday", found.description());
        assertEquals(LocalDate.of(2025, 3, 14), found.dueDate());
        assertEquals(Priority.HIGH, found.priority());
        assertFalse(found.done());
    }

    @Test
    void testFindByIdMissing() {
        assertNull(repo.findById(999));
    }

    @Test
    void testFindAllEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void testFindAll() {
        repo.insert(new Task("Buy groceries", null, null, Priority.LOW));
        repo.insert(new Task("Study for midterm", null, null, Priority.MEDIUM));

        List<Task> all = repo.findAll();
        assertEquals(2, all.size());
        assertEquals("Buy groceries", all.get(0).title());
        assertEquals("Study for midterm", all.get(1).title());
    }

    @Test
    void testUpdate() {
        Task task = repo.insert(new Task("Buy groceries", null, null, Priority.LOW));

        repo.update(task.withTitle("Buy groceries and milk").withDone(true).withPriority(Priority.HIGH));

        Task found = repo.findById(task.id());
        assertEquals("Buy groceries and milk", found.title());
        assertTrue(found.done());
        assertEquals(Priority.HIGH, found.priority());
    }

    @Test
    void testDelete() {
        Task task = repo.insert(new Task("Study for midterm", null, null, Priority.MEDIUM));
        repo.delete(task.id());

        assertNull(repo.findById(task.id()));
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void testRecurrenceIsSavedAndUpdated() {
        Task task = repo.insert(new Task("Pay rent", null, LocalDate.of(2025, 4, 1), Priority.HIGH)
                .withRecurrence(Recurrence.MONTHLY));
        assertEquals(Recurrence.MONTHLY, repo.findById(task.id()).recurrence());

        repo.update(task.withRecurrence(Recurrence.WEEKLY));
        assertEquals(Recurrence.WEEKLY, repo.findById(task.id()).recurrence());
    }

    @Test
    void testUnknownRecurrenceLoadsAsNotRepeating() throws SQLException {
        insertRawRow("Finish lab 4", "2025-03-14", "2025-03-01T10:00:00");
        db.getConnection().createStatement().execute("UPDATE tasks SET recurrence = 'FORTNIGHTLY'");

        assertEquals(Recurrence.NONE, repo.findById(1).recurrence());
    }

    // These go around the repository and write rows with raw SQL, the way a
    // hand-edited or corrupted database file would look.

    @Test
    void testMalformedDueDateLoadsWithoutDueDate() throws SQLException {
        insertRawRow("Finish lab 4", "not-a-date", "2025-03-01T10:00:00");

        List<Task> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("Finish lab 4", all.get(0).title());
        assertNull(all.get(0).dueDate());
    }

    @Test
    void testImpossibleDueDateLoadsWithoutDueDate() throws SQLException {
        // right format, but there is no February 30th
        insertRawRow("Buy groceries", "2025-02-30", "2025-03-01T10:00:00");

        Task found = repo.findById(1);
        assertNotNull(found);
        assertNull(found.dueDate());
    }

    @Test
    void testMalformedCreatedAtStillLoads() throws SQLException {
        insertRawRow("Study for midterm", "2025-03-14", "03/01/2025 10am");

        Task found = repo.findById(1);
        assertNotNull(found);
        assertEquals(LocalDate.of(2025, 3, 14), found.dueDate());
        assertNotNull(found.createdAt());
    }

    @Test
    void testOneBadRowDoesNotHideGoodRows() throws SQLException {
        repo.insert(new Task("Good task", null, LocalDate.of(2025, 3, 14), Priority.HIGH));
        insertRawRow("Bad task", "14/03/2025", "garbage");
        repo.insert(new Task("Another good task", null, null, Priority.LOW));

        List<Task> all = repo.findAll();
        assertEquals(3, all.size());
        assertEquals(LocalDate.of(2025, 3, 14), all.get(0).dueDate());
        assertEquals("Bad task", all.get(1).title());
        assertNull(all.get(1).dueDate());
        assertEquals("Another good task", all.get(2).title());
    }

    @Test
    void testTaskWithMalformedDateCanStillBeUpdated() throws SQLException {
        insertRawRow("Finish lab 4", "not-a-date", "2025-03-01T10:00:00");

        Task loaded = repo.findById(1);
        repo.update(loaded.withDueDate(LocalDate.of(2025, 4, 1)));

        assertEquals(LocalDate.of(2025, 4, 1), repo.findById(1).dueDate());
    }

    private void insertRawRow(String title, String dueDate, String createdAt) throws SQLException {
        PreparedStatement ps = db.getConnection().prepareStatement(
                "INSERT INTO tasks (title, due_date, priority, done, created_at) VALUES (?, ?, 'MEDIUM', 0, ?)");
        ps.setString(1, title);
        ps.setString(2, dueDate);
        ps.setString(3, createdAt);
        ps.executeUpdate();
        ps.close();
    }
}
