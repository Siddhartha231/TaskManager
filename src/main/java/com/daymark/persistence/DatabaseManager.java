package com.daymark.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final String DEFAULT_DB = "daymark.db";

    private Connection connection;

    public DatabaseManager() {
        this(DEFAULT_DB);
    }

    // path can also be ":memory:" which is handy for tests
    public DatabaseManager(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            createTable();
            addMissingColumns();
        } catch (ClassNotFoundException e) {
            System.out.println("SQLite driver not found");
            e.printStackTrace();
        } catch (SQLException e) {
            System.out.println("Could not open database " + path);
            e.printStackTrace();
        }
    }

    private void createTable() throws SQLException {
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
        Statement stmt = connection.createStatement();
        stmt.execute(sql);
        stmt.close();
    }

    // A daymark.db made by an older version won't have the newer columns, and
    // CREATE TABLE IF NOT EXISTS doesn't touch a table that already exists.
    // So check what's there and add anything missing - existing tasks are kept.
    private void addMissingColumns() throws SQLException {
        boolean hasRecurrence = false;
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(tasks)");
        while (rs.next()) {
            if (rs.getString("name").equals("recurrence")) {
                hasRecurrence = true;
            }
        }
        rs.close();
        if (!hasRecurrence) {
            stmt.execute("ALTER TABLE tasks ADD COLUMN recurrence TEXT DEFAULT 'NONE'");
        }
        stmt.close();
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
