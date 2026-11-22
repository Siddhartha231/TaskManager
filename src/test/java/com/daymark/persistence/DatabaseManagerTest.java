package com.daymark.persistence;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    // A database file created before repeating tasks existed has no recurrence column.
    // Opening it with the current code should add the column and keep the old tasks.
    @Test
    void testOldDatabaseFileIsUpgraded(@TempDir Path folder) throws SQLException {
        String path = folder.resolve("old-daymark.db").toString();

        Connection old = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement stmt = old.createStatement();
        stmt.execute("CREATE TABLE tasks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "description TEXT,"
                + "due_date TEXT,"
                + "priority TEXT,"
                + "done INTEGER DEFAULT 0,"
                + "created_at TEXT"
                + ")");
        stmt.execute("INSERT INTO tasks (title, due_date, priority, done, created_at) "
                + "VALUES ('Finish lab 4', '2025-03-14', 'HIGH', 0, '2025-03-01T10:00:00')");
        stmt.close();
        old.close();

        DatabaseManager db = new DatabaseManager(path);
        SQLiteTaskRepository repo = new SQLiteTaskRepository(db);

        List<Task> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("Finish lab 4", all.get(0).title());
        assertEquals(LocalDate.of(2025, 3, 14), all.get(0).dueDate());
        assertEquals(Recurrence.NONE, all.get(0).recurrence());

        // and new repeating tasks can be saved into the upgraded file
        Task rent = repo.insert(new Task("Pay rent", null, null, Priority.MEDIUM).withRecurrence(Recurrence.MONTHLY));
        assertEquals(Recurrence.MONTHLY, repo.findById(rent.id()).recurrence());
        db.close();

        // opening it a second time must not try to add the column again
        DatabaseManager again = new DatabaseManager(path);
        assertEquals(2, new SQLiteTaskRepository(again).findAll().size());
        again.close();
    }
}
