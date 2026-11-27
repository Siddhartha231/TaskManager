package com.daymark.persistence;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    private static final Task LEGACY_TASK = new Task(31, "Finish lab 4", "Review results",
            LocalDate.of(2025, 3, 14), Priority.HIGH, true,
            LocalDateTime.of(2025, 3, 1, 10, 0), Recurrence.NONE);
    private static final List<String> MIGRATION_FILES = List.of(
            "daymark.db.migrating", "daymark.db.migrating-journal",
            "daymark.db.migrating-wal", "daymark.db.migrating-shm");

    @Test
    void testWindowsDataDirectory(@TempDir Path home) {
        Path localAppData = home.resolve("local-app-data");
        assertEquals(localAppData.resolve("Daymark"),
                DatabaseManager.appDataDirectory("Windows 11", home, Map.of("LOCALAPPDATA", localAppData.toString())));
        Path fallback = home.resolve("AppData/Local/Daymark");
        assertEquals(fallback, DatabaseManager.appDataDirectory("Windows 11", home, Map.of()));
        assertEquals(fallback, DatabaseManager.appDataDirectory("Windows 11", home, Map.of("LOCALAPPDATA", "relative")));
    }

    @Test
    void testMacDataDirectory(@TempDir Path home) {
        assertEquals(home.resolve("Library/Application Support/Daymark"),
                DatabaseManager.appDataDirectory("Mac OS X", home, Map.of()));
    }

    @Test
    void testLinuxDataDirectory(@TempDir Path home) {
        Path xdgDataHome = home.resolve("xdg-data");
        assertEquals(xdgDataHome.resolve("daymark"),
                DatabaseManager.appDataDirectory("Linux", home, Map.of("XDG_DATA_HOME", xdgDataHome.toString())));
        Path fallback = home.resolve(".local/share/daymark");
        assertEquals(fallback, DatabaseManager.appDataDirectory("Linux", home, Map.of()));
        assertEquals(fallback, DatabaseManager.appDataDirectory("Linux", home, Map.of("XDG_DATA_HOME", "")));
        assertEquals(fallback, DatabaseManager.appDataDirectory("Linux", home, Map.of("XDG_DATA_HOME", "relative")));
    }

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
        Path otherLaunch = Files.createDirectory(folder.resolve("other-launch"));
        try (DatabaseManager reopened = new DatabaseManager(otherLaunch, appData)) {
            assertEquals("First task", new SQLiteTaskRepository(reopened).findAll().getFirst().title());
        }
        assertFalse(Files.exists(otherLaunch.resolve("daymark.db")));
    }

    @Test
    void testLegacyDatabaseIsCopiedAndUpgradedOnce(@TempDir Path folder) throws Exception {
        Path launch = Files.createDirectory(folder.resolve("legacy folder #1"));
        Path legacy = launch.resolve("daymark.db");
        Path appData = folder.resolve("app-data");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);

        try (DatabaseManager migrated = new DatabaseManager(launch, appData)) {
            SQLiteTaskRepository repo = new SQLiteTaskRepository(migrated);
            assertEquals(List.of(LEGACY_TASK), repo.findAll());
            repo.update(LEGACY_TASK.withTitle("Updated after migration"));
        }

        try (DatabaseManager reopened = new DatabaseManager(launch, appData)) {
            assertEquals(List.of(LEGACY_TASK.withTitle("Updated after migration")),
                    new SQLiteTaskRepository(reopened).findAll());
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void testExistingAppDataWinsWhenBothDatabasesContainTasks(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = Files.createDirectory(folder.resolve("app-data"));
        Path destination = appData.resolve("daymark.db");
        Task saved;
        try (DatabaseManager existing = new DatabaseManager(destination.toString())) {
            saved = new SQLiteTaskRepository(existing).insert(new Task("App-data task", null, null, Priority.LOW));
        }
        byte[] destinationBefore = Files.readAllBytes(destination);

        try (DatabaseManager opened = new DatabaseManager(folder, appData)) {
            assertEquals(List.of(saved), new SQLiteTaskRepository(opened).findAll());
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertArrayEquals(destinationBefore, Files.readAllBytes(destination));
    }

    @Test
    void testExistingAppDataWinsEvenWhenItHasNoTasks(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        Path appData = Files.createDirectory(folder.resolve("app-data"));
        try (DatabaseManager existing = new DatabaseManager(appData.resolve("daymark.db").toString())) {
            assertTrue(new SQLiteTaskRepository(existing).findAll().isEmpty());
        }

        try (DatabaseManager opened = new DatabaseManager(folder, appData)) {
            assertTrue(new SQLiteTaskRepository(opened).findAll().isEmpty());
        }
    }

    @Test
    void testInvalidDestinationLeavesBothFilesIntact(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = Files.createDirectory(folder.resolve("app-data"));
        Path destination = appData.resolve("daymark.db");
        Files.writeString(destination, "Invalid destination database");
        byte[] destinationBefore = Files.readAllBytes(destination);

        assertThrows(IllegalStateException.class, () -> new DatabaseManager(folder, appData));

        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertArrayEquals(destinationBefore, Files.readAllBytes(destination));
    }

    @Test
    void testLegacySnapshotIncludesCommittedWalRows(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        Path appData = folder.resolve("app-data");
        Task walTask = LEGACY_TASK.withTitle("Committed only in WAL");
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             Statement stmt = old.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA wal_autocheckpoint=0");
            writeLegacyTask(old);
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            byte[] checkpointed = Files.readAllBytes(legacy);
            old.setAutoCommit(false);
            stmt.executeUpdate("UPDATE tasks SET title = 'Committed only in WAL' WHERE id = 31");
            old.commit();
            assertArrayEquals(checkpointed, Files.readAllBytes(legacy));
            assertTrue(Files.size(folder.resolve("daymark.db-wal")) > 0);

            try (DatabaseManager migrated = new DatabaseManager(folder, appData)) {
                assertEquals(List.of(walTask), new SQLiteTaskRepository(migrated).findAll());
            }
        }
        Path otherLaunch = Files.createDirectory(folder.resolve("other-launch"));
        try (DatabaseManager reopened = new DatabaseManager(otherLaunch, appData)) {
            assertEquals(List.of(walTask), new SQLiteTaskRepository(reopened).findAll());
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
        assertNoMigrationFiles(appData);
    }

    @Test
    void testStartupDiscardsInterruptedSnapshotAndSidecars(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = Files.createDirectory(folder.resolve("app-data"));
        for (String filename : MIGRATION_FILES) {
            Files.writeString(appData.resolve(filename), "Left behind by an interrupted migration");
        }
        Path unrelated = Files.writeString(appData.resolve("unrelated.db"), "Keep this file");

        try (DatabaseManager retried = new DatabaseManager(folder, appData)) {
            assertEquals(List.of(LEGACY_TASK), new SQLiteTaskRepository(retried).findAll());
        }
        assertNoMigrationFiles(appData);
        assertEquals("Keep this file", Files.readString(unrelated));
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void testStartupRemovesStagingNameAfterInterruptedPublication(@TempDir Path folder) throws Exception {
        Path appData = Files.createDirectory(folder.resolve("app-data"));
        Path destination = appData.resolve("daymark.db");
        Task saved;
        try (DatabaseManager existing = new DatabaseManager(destination.toString())) {
            saved = new SQLiteTaskRepository(existing).insert(new Task("Published task", null, null, Priority.MEDIUM));
        }
        Files.createLink(appData.resolve("daymark.db.migrating"), destination);

        try (DatabaseManager reopened = new DatabaseManager(folder, appData)) {
            assertEquals(List.of(saved), new SQLiteTaskRepository(reopened).findAll());
        }
        assertNoMigrationFiles(appData);
    }

    @Test
    void testUnrelatedSqliteDatabaseIsNotPublished(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             Statement stmt = old.createStatement()) {
            stmt.execute("CREATE TABLE unrelated (note TEXT)");
        }
        byte[] original = Files.readAllBytes(legacy);
        Path appData = folder.resolve("app-data");

        assertThrows(IllegalStateException.class, () -> {
            try (DatabaseManager ignored = new DatabaseManager(folder, appData)) {
                // Close even if unexpected success occurred, so the fixture can be cleaned up.
            }
        });

        assertFalse(Files.exists(appData.resolve("daymark.db")));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertNoMigrationFiles(appData);
    }

    @Test
    void testPublicationFailurePreservesSourceAndAllowsRetry(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = folder.resolve("app-data");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseManager(folder, appData, new DatabaseManager.MigrationObserver() {
                    @Override
                    public void beforePublish(Path destination, Path snapshot) throws IOException {
                        assertEquals(appData.resolve("daymark.db"), destination);
                        assertTrue(Files.size(snapshot) > 0);
                        assertFalse(Files.exists(destination));
                        throw new IOException("Simulated publication failure");
                    }
                }));

        assertEquals("Simulated publication failure", error.getCause().getMessage());
        assertTrue(error.getMessage().contains(legacy.toString()));
        assertTrue(error.getMessage().contains(appData.resolve("daymark.db").toString()));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertFalse(Files.exists(appData.resolve("daymark.db")));
        assertNoMigrationFiles(appData);

        try (DatabaseManager retried = new DatabaseManager(folder, appData)) {
            assertEquals(List.of(LEGACY_TASK), new SQLiteTaskRepository(retried).findAll());
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void testPublicationKeepsDestinationCreatedDuringMigration(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = folder.resolve("app-data");
        Path competingDatabase = folder.resolve("competing.db");
        Task saved;
        try (DatabaseManager other = new DatabaseManager(competingDatabase.toString())) {
            saved = new SQLiteTaskRepository(other).insert(new Task("Other app-data task", null, null, Priority.MEDIUM));
        }
        byte[] competingBytes = Files.readAllBytes(competingDatabase);

        try (DatabaseManager opened = new DatabaseManager(folder, appData, new DatabaseManager.MigrationObserver() {
            @Override
            public void beforePublish(Path destination, Path snapshot) throws IOException {
                Files.copy(competingDatabase, destination);
            }
        })) {
            assertEquals(List.of(saved), new SQLiteTaskRepository(opened).findAll());
        }
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertArrayEquals(competingBytes, Files.readAllBytes(appData.resolve("daymark.db")));
        assertNoMigrationFiles(appData);
    }

    @Test
    void testMissingPublishedDatabaseDoesNotProduceEmptyReplacement(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);
        Path appData = folder.resolve("app-data");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
            try (DatabaseManager ignored = new DatabaseManager(folder, appData, new DatabaseManager.MigrationObserver() {
                @Override
                public void afterPublish(Path destination) throws IOException {
                    assertEquals(appData.resolve("daymark.db"), destination);
                    assertTrue(Files.isRegularFile(destination));
                    Files.delete(destination);
                }
            })) {
                // Close any unexpected connection before JUnit cleans up the fixture.
            }
        });

        assertInstanceOf(SQLException.class, error.getCause());
        assertFalse(Files.exists(appData.resolve("daymark.db")));
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertNoMigrationFiles(appData);
        try (DatabaseManager retried = new DatabaseManager(folder, appData)) {
            assertEquals(List.of(LEGACY_TASK), new SQLiteTaskRepository(retried).findAll());
        }
    }

    private void assertNoMigrationFiles(Path appData) {
        for (String filename : MIGRATION_FILES) {
            assertFalse(Files.exists(appData.resolve(filename)), filename);
        }
    }

    private void createLegacyDatabase(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            writeLegacyTask(connection);
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
            stmt.execute("INSERT INTO tasks (id, title, description, due_date, priority, done, created_at) "
                    + "VALUES (31, 'Finish lab 4', 'Review results', '2025-03-14', 'HIGH', 1, '2025-03-01T10:00:00')");
        }
    }
}
