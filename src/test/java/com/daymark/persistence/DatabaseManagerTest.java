package com.daymark.persistence;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            writeLegacyTask(old);
        }

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

    @Test
    void testFreshInstallCreatesDatabaseInAppData(@TempDir Path folder) throws Exception {
        Path launch = Files.createDirectory(folder.resolve("launch"));
        Path appData = folder.resolve("app-data");

        try (DatabaseManager db = new DatabaseManager(launch, appData)) {
            SQLiteTaskRepository repo = new SQLiteTaskRepository(db);
            assertTrue(repo.findAll().isEmpty());
            repo.insert(new Task("First task", null, null, Priority.MEDIUM));
        }

        assertTrue(Files.isRegularFile(appData.resolve("daymark.db")));
        assertFalse(Files.exists(launch.resolve("daymark.db")));
        try (DatabaseManager reopened = new DatabaseManager(launch, appData)) {
            assertEquals("First task", new SQLiteTaskRepository(reopened).findAll().getFirst().title());
        }
    }

    @Test
    void testLegacyDatabaseIsCopiedAndUpgradedOnce(@TempDir Path folder) throws Exception {
        Path launch = Files.createDirectory(folder.resolve("legacy folder #1"));
        Path legacy = launch.resolve("daymark.db");
        Path appData = folder.resolve("app-data");
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + legacy)) {
            writeLegacyTask(old);
        }
        byte[] original = Files.readAllBytes(legacy);

        try (DatabaseManager migrated = new DatabaseManager(launch, appData)) {
            SQLiteTaskRepository repo = new SQLiteTaskRepository(migrated);
            Task existing = repo.findAll().getFirst();
            assertEquals("Finish lab 4", existing.title());
            assertEquals(Recurrence.NONE, existing.recurrence());
            repo.update(existing.withTitle("Updated after migration"));
        }

        try (DatabaseManager reopened = new DatabaseManager(launch, appData)) {
            assertEquals("Updated after migration", new SQLiteTaskRepository(reopened).findAll().getFirst().title());
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void testLegacySnapshotIncludesCommittedWalRows(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             Statement stmt = old.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA wal_autocheckpoint=0");
            writeLegacyTask(old);
            assertTrue(Files.size(folder.resolve("daymark.db-wal")) > 0);

            try (DatabaseManager migrated = new DatabaseManager(folder, folder.resolve("app-data"))) {
                assertEquals("Finish lab 4", new SQLiteTaskRepository(migrated).findAll().getFirst().title());
            }
        }
    }

    @Test
    void testFailedMigrationLeavesLegacyFileAndNoDestination(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        Path appData = folder.resolve("app-data");
        Files.writeString(legacy, "This is not a SQLite database.");
        byte[] original = Files.readAllBytes(legacy);

        assertThrows(IllegalStateException.class, () -> new DatabaseManager(folder, appData));

        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertFalse(Files.exists(appData.resolve("daymark.db")));
        try (var files = Files.list(appData)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("daymark-migration-")));
        }
    }

    private void writeLegacyTask(Connection old) throws SQLException {
        try (Statement stmt = old.createStatement()) {
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
        }
    }
}
