package com.daymark.persistence;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public class DatabaseManager implements AutoCloseable {

    private static final String DATABASE_NAME = "daymark.db";

    private final Connection connection;

    public DatabaseManager() {
        this(Path.of(""), appDataDirectory());
    }

    DatabaseManager(Path launchDirectory, Path dataDirectory) {
        connection = openApplicationDatabase(launchDirectory, dataDirectory);
    }

    // path can also be ":memory:" which is handy for tests
    public DatabaseManager(String path) {
        try {
            connection = openDatabase(path);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the Daymark database at " + path, e);
        }
    }

    private static Path appDataDirectory() {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return dataRoot("LOCALAPPDATA", home.resolve("AppData/Local")).resolve("Daymark");
        }
        if (os.startsWith("mac")) {
            return home.resolve("Library/Application Support/Daymark");
        }
        return dataRoot("XDG_DATA_HOME", home.resolve(".local/share")).resolve("daymark");
    }

    private static Path dataRoot(String variable, Path fallback) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Path configured = Path.of(value);
        return configured.isAbsolute() ? configured : fallback;
    }

    private static synchronized Connection openApplicationDatabase(Path launchDirectory, Path dataDirectory) {
        Path destination = dataDirectory.toAbsolutePath().normalize().resolve(DATABASE_NAME);
        Path legacy = launchDirectory.toAbsolutePath().normalize().resolve(DATABASE_NAME);
        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                return openDatabase(destination.toString());
            }

            // Serialize migration across app processes. Keep the lock file so another process
            // cannot open a different lock file while someone is still waiting on this one.
            Path lockFile = destination.resolveSibling(DATABASE_NAME + ".migration.lock");
            try (FileChannel channel = FileChannel.open(lockFile, CREATE, WRITE);
                 var lock = channel.lock()) {
                if (!destination.equals(legacy) && Files.notExists(destination, NOFOLLOW_LINKS)
                        && !Files.notExists(legacy)) {
                    migrateLegacyDatabase(legacy, destination);
                }
                return openDatabase(destination.toString());
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not prepare the Daymark database at " + destination
                    + ". If you are upgrading, your original database stays at " + legacy + ".", e);
        }
    }

    private static void migrateLegacyDatabase(Path legacy, Path destination) throws IOException, SQLException {
        Path snapshot = Files.createTempFile(destination.getParent(), "daymark-migration-", ".db");
        try {
            SQLiteConfig readOnly = new SQLiteConfig();
            readOnly.setReadOnly(true);
            // SQLite takes a consistent snapshot, including committed rows still in a WAL file.
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + legacy.toUri(), readOnly.toProperties());
                 PreparedStatement copy = source.prepareStatement("VACUUM INTO ?")) {
                copy.setString(1, snapshot.toString());
                copy.execute();
            }
            openDatabase(snapshot.toString()).close();
            Files.move(snapshot, destination);
        } catch (IOException | SQLException e) {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
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
