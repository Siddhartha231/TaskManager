package com.daymark.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the public startup path in a fresh JVM, with its own home and working directory. */
public class DatabaseStartupTest {
    private static final String CHILD_MODE = "database-startup-child";
    private static final String CHILD_OUTPUT = "startup-ok|legacy-row|NONE";

    @Test
    void migratesLegacyDatabaseAndReopensItFromAnotherWorkingDirectory(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectory(temp.resolve("home"));
        Path firstLaunch = Files.createDirectory(temp.resolve("first-launch"));
        Path secondLaunch = Files.createDirectory(temp.resolve("second-launch"));
        Path selectedData = Files.createDirectory(temp.resolve("selected-data"));
        Path legacy = firstLaunch.resolve("daymark.db");
        createLegacyDatabase(legacy);
        byte[] original = Files.readAllBytes(legacy);

        runChild(firstLaunch, home, selectedData);

        Path migrated = dataDirectory(home, selectedData).resolve("daymark.db");
        assertTrue(Files.isRegularFile(migrated), "startup should put the database in the platform data directory");
        assertArrayEquals(original, Files.readAllBytes(legacy), "migration must leave the source database unchanged");
        assertEquals(2, countDatabases(temp), "only the original and selected database should exist");

        runChild(secondLaunch, home, selectedData);

        assertFalse(Files.exists(secondLaunch.resolve("daymark.db")), "reopening must not create a database in the new working directory");
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertEquals(2, countDatabases(temp));
    }

    /** Child entry point: this deliberately invokes the real no-argument constructor. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !CHILD_MODE.equals(args[0])) {
            throw new IllegalArgumentException("Expected child mode");
        }
        try (DatabaseManager database = new DatabaseManager();
             Statement statement = database.getConnection().createStatement();
             var row = statement.executeQuery("SELECT title, recurrence FROM tasks WHERE id = 1")) {
            if (!row.next() || !"legacy-row".equals(row.getString(1)) || !"NONE".equals(row.getString(2)) || row.next()) {
                throw new IllegalStateException("Unexpected database contents after startup");
            }
            System.out.println(CHILD_OUTPUT);
        }
    }

    private static void runChild(Path workingDirectory, Path home, Path dataRoot) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Duser.home=" + home,
                "-cp", absoluteClassPath(), DatabaseStartupTest.class.getName(), CHILD_MODE);
        builder.directory(workingDirectory.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        if (isWindows()) {
            builder.environment().put("LOCALAPPDATA", dataRoot.toString());
            builder.environment().put("APPDATA", home.resolve("roaming-data").toString());
            builder.environment().put("XDG_DATA_HOME", home.resolve("xdg-data").toString());
            builder.environment().put("USERPROFILE", home.toString());
        } else {
            builder.environment().put("XDG_DATA_HOME", dataRoot.toString());
            builder.environment().remove("HOME");
        }

        Path childLog = workingDirectory.resolve("child.log");
        builder.redirectErrorStream(true);
        builder.redirectOutput(childLog.toFile());
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Database startup child exceeded 30 seconds");
            String output = Files.readString(childLog);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.lines().anyMatch(CHILD_OUTPUT::equals), output);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void createLegacyDatabase(Path path) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT, "
                    + "due_date TEXT, priority TEXT, done INTEGER DEFAULT 0, created_at TEXT)");
            statement.execute("INSERT INTO tasks (id, title, description, due_date, priority, done, created_at) "
                    + "VALUES (1, 'legacy-row', 'fixture', NULL, 'MEDIUM', 0, '2026-01-01T00:00:00')");
        }
    }

    private static String absoluteClassPath() {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return Arrays.stream(classPath.split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static long countDatabases(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".db")).count();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static Path dataDirectory(Path home, Path dataRoot) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return dataRoot.resolve("Daymark");
        if (os.startsWith("mac")) return home.resolve("Library/Application Support/Daymark");
        return dataRoot.resolve("daymark");
    }
}
