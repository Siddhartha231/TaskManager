package com.daymark.persistence;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public class DatabaseManager implements AutoCloseable {

    private static final String DATABASE_NAME = "daymark.db";
    private static final String SNAPSHOT_NAME = DATABASE_NAME + ".migrating";
    private static final MigrationObserver NO_MIGRATION_OBSERVER = new MigrationObserver() {};

    private final Connection connection;

    public DatabaseManager() {
        this(Path.of(""), appDataDirectory(System.getProperty("os.name"),
                Path.of(System.getProperty("user.home")), System.getenv()));
    }

    DatabaseManager(Path launchDirectory, Path dataDirectory) {
        this(launchDirectory, dataDirectory, NO_MIGRATION_OBSERVER);
    }

    DatabaseManager(Path launchDirectory, Path dataDirectory, MigrationObserver observer) {
        connection = openApplicationDatabase(launchDirectory, dataDirectory, observer);
    }

    interface MigrationObserver {
        default void beforePublish(Path destination, Path snapshot) throws IOException {}

        default void afterPublish(Path destination) throws IOException {}
    }

    // path can also be ":memory:" which is handy for tests
    public DatabaseManager(String path) {
        try {
            connection = openDatabase(path);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the Daymark database at " + path, e);
        }
    }

    static Path appDataDirectory(String osName, Path home, Map<String, String> environment) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return dataRoot(environment.get("LOCALAPPDATA"), home.resolve("AppData/Local")).resolve("Daymark");
        }
        if (os.startsWith("mac")) {
            return home.resolve("Library/Application Support/Daymark");
        }
        return dataRoot(environment.get("XDG_DATA_HOME"), home.resolve(".local/share")).resolve("daymark");
    }

    private static Path dataRoot(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            Path configured = Path.of(value);
            return configured.isAbsolute() ? configured : fallback;
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    private static synchronized Connection openApplicationDatabase(
            Path launchDirectory, Path dataDirectory, MigrationObserver observer) {
        Path appData = dataDirectory.toAbsolutePath().normalize();
        Path destination = appData.resolve(DATABASE_NAME);
        Path legacy = launchDirectory.toAbsolutePath().normalize().resolve(DATABASE_NAME);
        Path snapshot = appData.resolve(SNAPSHOT_NAME);
        List<Path> stagingFiles = List.of(snapshot, appData.resolve(SNAPSHOT_NAME + "-journal"),
                appData.resolve(SNAPSHOT_NAME + "-wal"), appData.resolve(SNAPSHOT_NAME + "-shm"));
        try {
            Files.createDirectories(appData);
            if (Files.exists(destination, NOFOLLOW_LINKS)
                    && stagingFiles.stream().allMatch(file -> Files.notExists(file, NOFOLLOW_LINKS))) {
                return openExistingDatabase(destination);
            }

            // Serialize migration across app processes. Keep the lock file so another process
            // cannot open a different lock file while someone is still waiting on this one.
            Path lockFile = destination.resolveSibling(DATABASE_NAME + ".migration.lock");
            try (FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE);
                 var lock = channel.lock()) {
                if (!Files.notExists(destination, NOFOLLOW_LINKS)) {
                    discardStagingFilesAfterPublication(stagingFiles);
                    return openExistingDatabase(destination);
                }
                discardStagingFiles(stagingFiles);
                if (!destination.equals(legacy) && !Files.notExists(legacy)) {
                    migrateLegacyDatabase(legacy, destination, snapshot, stagingFiles, observer);
                    return openExistingDatabase(destination);
                }

                return openDatabase(destination.toUri().toString());
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not prepare the Daymark database at " + destination
                    + ". If you are upgrading, your original database stays at " + legacy + ". "
                    + e.getMessage(), e);
        }
    }

    private static void migrateLegacyDatabase(Path legacy, Path destination, Path snapshot,
                                              List<Path> stagingFiles, MigrationObserver observer)
            throws IOException, SQLException {
        try {
            if (Files.getFileStore(snapshot.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.createFile(snapshot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(snapshot);
            }
            SQLiteConfig readOnly = new SQLiteConfig();
            readOnly.setReadOnly(true);
            // SQLite takes a consistent snapshot, including committed rows still in a WAL file.
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + legacy.toUri(), readOnly.toProperties());
                 PreparedStatement copy = source.prepareStatement("VACUUM INTO ?")) {
                copy.setString(1, snapshot.toString());
                copy.execute();
            }
            verifyAndUpgradeSnapshot(snapshot);
            try (FileChannel channel = FileChannel.open(snapshot, WRITE)) {
                channel.force(true);
            }
            publishSnapshot(destination, snapshot, observer);
        } catch (IOException | SQLException e) {
            try {
                discardStagingFiles(stagingFiles);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        discardStagingFilesAfterPublication(stagingFiles);
    }

    private static void verifyAndUpgradeSnapshot(Path snapshot) throws SQLException {
        try (Connection staged = DriverManager.getConnection("jdbc:sqlite:" + snapshot.toUri());
             Statement stmt = staged.createStatement()) {
            try (ResultSet table = stmt.executeQuery(
                    "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'tasks' COLLATE NOCASE")) {
                if (!table.next()) {
                    throw new SQLException("The legacy database does not contain a tasks table.");
                }
            }
            stmt.executeQuery("SELECT id, title, description, due_date, priority, done, created_at FROM tasks LIMIT 0").close();
            // Publish a standalone database that does not depend on a staging WAL file.
            try (ResultSet mode = stmt.executeQuery("PRAGMA journal_mode = DELETE")) {
                if (!mode.next() || !"delete".equalsIgnoreCase(mode.getString(1))) {
                    throw new SQLException("Could not prepare a standalone database snapshot.");
                }
            }
            addMissingColumns(staged);
            try (ResultSet check = stmt.executeQuery("PRAGMA quick_check")) {
                if (!check.next() || !"ok".equals(check.getString(1)) || check.next()) {
                    throw new SQLException("The database snapshot did not pass SQLite's integrity check.");
                }
            }
        }
    }

    private static void publishSnapshot(Path destination, Path snapshot, MigrationObserver observer) throws IOException {
        observer.beforePublish(destination, snapshot);
        try {
            Files.createLink(destination, snapshot);
        } catch (FileAlreadyExistsException e) {
            // A destination created during migration takes precedence too.
            if (!Files.exists(destination, NOFOLLOW_LINKS)) {
                throw e;
            }
            return;
        } catch (UnsupportedOperationException e) {
            throw new IOException("The application data directory must support hard links to publish the migration safely.", e);
        }
        observer.afterPublish(destination);
    }

    private static void discardStagingFiles(List<Path> stagingFiles) throws IOException {
        for (Path file : stagingFiles) {
            if (Files.notExists(file, NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                throw new IOException("Unexpected entry at the reserved migration path " + file);
            }
            Files.deleteIfExists(file);
        }
    }

    private static void discardStagingFilesAfterPublication(List<Path> stagingFiles) {
        try {
            discardStagingFiles(stagingFiles);
        } catch (IOException e) {
            // The destination is usable. Retry staging cleanup on the next launch.
            System.err.println("Could not clean up the Daymark migration files. " + e.getMessage());
        }
    }

    private static Connection openExistingDatabase(Path path) throws SQLException {
        // If the file disappears before SQLite opens it, startup must fail instead of creating a blank database.
        return openDatabase(path.toUri() + "?mode=rw");
    }

    private static Connection openDatabase(String path) throws SQLException {
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            createTable(opened);
            addMissingColumns(opened);
            return opened;
        } catch (SQLException e) {
            try {
                opened.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS tasks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "description TEXT,"
                + "due_date TEXT,"
                + "priority TEXT,"
                + "done INTEGER DEFAULT 0,"
                + "created_at TEXT,"
                + "recurrence TEXT DEFAULT 'NONE'"
                + ")";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    // A daymark.db made by an older version won't have the newer columns, and
    // CREATE TABLE IF NOT EXISTS doesn't touch a table that already exists.
    // So check what's there and add anything missing - existing tasks are kept.
    private static void addMissingColumns(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(tasks)")) {
                while (rs.next()) {
                    if (rs.getString("name").equals("recurrence")) {
                        return;
                    }
                }
            }
            stmt.execute("ALTER TABLE tasks ADD COLUMN recurrence TEXT DEFAULT 'NONE'");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
