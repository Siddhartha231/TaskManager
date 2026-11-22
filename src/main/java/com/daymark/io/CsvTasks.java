package com.daymark.io;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes tasks as CSV text so they can be opened in Excel / Google Sheets
 * or moved to another computer. Only deals with text - the UI handles the actual files
 * and TaskService validates and saves whatever gets imported.
 */
public class CsvTasks {

    private static final String HEADER = "title,description,due_date,priority,repeats,done,created_at";
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/yyyy");

    public static String toCsv(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\r\n");
        for (Task task : tasks) {
            sb.append(escape(task.title())).append(',');
            sb.append(escape(task.description())).append(',');
            sb.append(task.dueDate() == null ? "" : task.dueDate().toString()).append(',');
            sb.append(task.priority().name()).append(',');
            sb.append(task.recurrence().name()).append(',');
            sb.append(task.done()).append(',');
            sb.append(task.createdAt().toString());
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Parses CSV text into tasks (not saved yet, ids are all 0). The first row has to be
     * a header with at least a "title" column - the other columns are optional and can be
     * in any order, so a spreadsheet with just a list of titles works too.
     *
     * Throws IllegalArgumentException if there is no title column.
     */
    public static List<Task> fromCsv(String csv) {
        List<List<String>> rows = parseRows(csv);
        List<Task> tasks = new ArrayList<>();
        if (rows.isEmpty()) {
            return tasks;
        }

        List<String> header = rows.get(0);
        int titleCol = findColumn(header, "title");
        int descCol = findColumn(header, "description");
        int dueCol = findColumn(header, "due_date", "due date", "due");
        int priorityCol = findColumn(header, "priority");
        int repeatsCol = findColumn(header, "repeats", "recurrence");
        int doneCol = findColumn(header, "done", "completed");
        int createdCol = findColumn(header, "created_at", "created");
        if (titleCol == -1) {
            throw new IllegalArgumentException("The file needs a header row with a \"title\" column");
        }

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            // skip completely blank lines
            if (row.size() == 1 && row.get(0).trim().isEmpty()) {
                continue;
            }
            tasks.add(new Task(
                    0,
                    cell(row, titleCol),
                    cell(row, descCol),
                    parseDate(cell(row, dueCol)),
                    parsePriority(cell(row, priorityCol)),
                    parseDone(cell(row, doneCol)),
                    parseCreatedAt(cell(row, createdCol)),
                    parseRecurrence(cell(row, repeatsCol))
            ));
        }
        return tasks;
    }

    // wrap in quotes if the text has a comma, quote or line break in it (quotes get doubled)
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    // Splits the text into rows of cells. Goes one character at a time because a quoted
    // cell can contain commas and even line breaks, so splitting on "," and "\n" breaks.
    private static List<List<String>> parseRows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        if (csv == null) {
            return rows;
        }
        // Excel puts an invisible marker at the start of UTF-8 files
        if (csv.startsWith("﻿")) {
            csv = csv.substring(1);
        }

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');   // "" inside quotes is one real quote
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        // last line might not end with a line break
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static int findColumn(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase();
            for (String name : names) {
                if (h.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int col) {
        if (col == -1 || col >= row.size()) {
            return "";
        }
        return row.get(col).trim();
    }

    // Bad values don't fail the whole import, they fall back to a default
    // (same approach as the repository takes with malformed dates).

    private static LocalDate parseDate(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            // spreadsheets like to turn dates into 3/14/2025
            try {
                return LocalDate.parse(text, US_DATE);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static Priority parsePriority(String text) {
        for (Priority p : Priority.values()) {
            if (p.name().equalsIgnoreCase(text)) {
                return p;
            }
        }
        return Priority.MEDIUM;
    }

    private static Recurrence parseRecurrence(String text) {
        for (Recurrence r : Recurrence.values()) {
            if (r.name().equalsIgnoreCase(text) || r.toString().equalsIgnoreCase(text)) {
                return r;
            }
        }
        return Recurrence.NONE;
    }

    private static boolean parseDone(String text) {
        String t = text.toLowerCase();
        return t.equals("true") || t.equals("yes") || t.equals("1") || t.equals("x") || t.equals("✓");
    }

    private static LocalDateTime parseCreatedAt(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }
}
