package com.daymark.persistence;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class SQLiteTaskRepository implements TaskRepository {

    private final Connection conn;

    public SQLiteTaskRepository(DatabaseManager db) {
        this.conn = db.getConnection();
    }

    @Override
    public Task insert(Task task) {
        String sql = "INSERT INTO tasks (title, description, due_date, priority, done, created_at, recurrence) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, task.title());
            ps.setString(2, task.description());
            // sqlite has no date type, just store the ISO string
            ps.setString(3, task.dueDate() == null ? null : task.dueDate().toString());
            ps.setString(4, task.priority().name());
            ps.setInt(5, task.done() ? 1 : 0);
            ps.setString(6, task.createdAt().toString());
            ps.setString(7, task.recurrence().name());
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            Task saved = task;
            if (keys.next()) {
                saved = task.withId(keys.getInt(1));
            }
            keys.close();
            ps.close();
            return saved;
        } catch (SQLException e) {
            e.printStackTrace();
            return task;
        }
    }

    @Override
    public void update(Task task) {
        String sql = "UPDATE tasks SET title = ?, description = ?, due_date = ?, "
                + "priority = ?, done = ?, recurrence = ? WHERE id = ?";
        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, task.title());
            ps.setString(2, task.description());
            ps.setString(3, task.dueDate() == null ? null : task.dueDate().toString());
            ps.setString(4, task.priority().name());
            ps.setInt(5, task.done() ? 1 : 0);
            ps.setString(6, task.recurrence().name());
            ps.setInt(7, task.id());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Task findById(int id) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks WHERE id = ?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            Task task = null;
            if (rs.next()) {
                task = rowToTask(rs);
            }
            rs.close();
            ps.close();
            return task;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public List<Task> findAll() {
        List<Task> tasks = new ArrayList<>();
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM tasks ORDER BY id");
            while (rs.next()) {
                tasks.add(rowToTask(rs));
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tasks;
    }

    @Override
    public void delete(int id) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?");
            ps.setInt(1, id);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private Task rowToTask(ResultSet rs) throws SQLException {
        String due = rs.getString("due_date");
        String created = rs.getString("created_at");
        return new Task(
                rs.getInt("id"),
                rs.getString("title"),
                rs.getString("description"),
                parseDueDate(due),
                Priority.valueOf(rs.getString("priority")),
                rs.getInt("done") == 1,
                parseCreatedAt(created),
                parseRecurrence(rs.getString("recurrence"))
        );
    }

    // same idea as the dates below - anything unexpected just means "doesn't repeat"
    private Recurrence parseRecurrence(String text) {
        if (text == null) {
            return Recurrence.NONE;
        }
        try {
            return Recurrence.valueOf(text);
        } catch (IllegalArgumentException e) {
            System.out.println("Ignoring unknown recurrence in database: " + text);
            return Recurrence.NONE;
        }
    }

    // If the stored text isn't a real date (file edited by hand, older version, etc.)
    // load the task without a due date instead of crashing the whole list.
    private LocalDate parseDueDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            System.out.println("Ignoring malformed due date in database: " + text);
            return null;
        }
    }

    private LocalDateTime parseCreatedAt(String text) {
        if (text == null) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            System.out.println("Ignoring malformed created date in database: " + text);
            return LocalDateTime.now();
        }
    }
}
