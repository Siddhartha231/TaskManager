import java.sql.*;

/** SQLite fixture operations for the Windows packaged-application smoke test. */
class SmokeDatabase {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + args[1]);
             Statement s = c.createStatement()) {
            switch (args[0]) {
                case "legacy" -> {
                    s.execute("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, description TEXT, due_date TEXT, priority TEXT, done INTEGER DEFAULT 0, created_at TEXT)");
                    s.execute("INSERT INTO tasks (title, priority, done) VALUES ('Synthetic legacy task', 'MEDIUM', 0)");
                }
                case "insert" -> s.execute("INSERT INTO tasks (title, priority, done, recurrence) VALUES ('Synthetic restart task', 'MEDIUM', 0, 'NONE')");
                case "check-legacy", "check-restart" -> {
                    String title = args[0].equals("check-legacy") ? "Synthetic legacy task" : "Synthetic restart task";
                    try (ResultSet rs = s.executeQuery("SELECT title, recurrence FROM tasks WHERE title = '" + title + "'")) {
                        if (!rs.next() || !"NONE".equals(rs.getString("recurrence")) || rs.next()) throw new IllegalStateException("Missing or invalid fixture: " + title);
                    }
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
