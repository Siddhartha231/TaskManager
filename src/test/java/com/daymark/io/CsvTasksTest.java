package com.daymark.io;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvTasksTest {

    @Test
    void testExportThenImportGivesTheSameTasks() {
        Task plain = new Task(1, "Finish lab 4", "due friday", LocalDate.of(2025, 3, 14), Priority.HIGH,
                false, LocalDateTime.of(2025, 3, 1, 10, 0), Recurrence.NONE);
        Task repeating = new Task(2, "Pay rent", null, LocalDate.of(2025, 4, 1), Priority.MEDIUM,
                true, LocalDateTime.of(2025, 3, 2, 9, 30), Recurrence.MONTHLY);

        List<Task> back = CsvTasks.fromCsv(CsvTasks.toCsv(List.of(plain, repeating)));

        assertEquals(2, back.size());
        // ids aren't exported (the database hands out new ones), everything else should match
        assertEquals(plain.withId(0), back.get(0));
        assertEquals("Pay rent", back.get(1).title());
        assertEquals("", back.get(1).description());
        assertEquals(LocalDate.of(2025, 4, 1), back.get(1).dueDate());
        assertTrue(back.get(1).done());
        assertEquals(Recurrence.MONTHLY, back.get(1).recurrence());
        assertEquals(LocalDateTime.of(2025, 3, 2, 9, 30), back.get(1).createdAt());
    }

    @Test
    void testCommasQuotesAndLineBreaksSurvive() {
        Task tricky = new Task("Email Dr. Lee, re: \"late\" lab", "line one\nline two, with a comma",
                null, Priority.LOW);

        String csv = CsvTasks.toCsv(List.of(tricky));
        List<Task> back = CsvTasks.fromCsv(csv);

        assertEquals(1, back.size());
        assertEquals("Email Dr. Lee, re: \"late\" lab", back.get(0).title());
        assertEquals("line one\nline two, with a comma", back.get(0).description());
    }

    @Test
    void testImportSpreadsheetWithOnlySomeColumns() {
        String csv = "Due Date,Title\n"
                + "3/14/2025,Finish lab 4\n"
                + ",Buy groceries\n";

        List<Task> tasks = CsvTasks.fromCsv(csv);

        assertEquals(2, tasks.size());
        assertEquals("Finish lab 4", tasks.get(0).title());
        assertEquals(LocalDate.of(2025, 3, 14), tasks.get(0).dueDate());
        assertEquals(Priority.MEDIUM, tasks.get(0).priority());
        assertFalse(tasks.get(0).done());
        assertNull(tasks.get(1).dueDate());
    }

    @Test
    void testBadValuesFallBackToDefaults() {
        String csv = "title,due_date,priority,repeats,done\r\n"
                + "Study for midterm,not-a-date,URGENT,sometimes,maybe\r\n";

        Task task = CsvTasks.fromCsv(csv).get(0);

        assertEquals("Study for midterm", task.title());
        assertNull(task.dueDate());
        assertEquals(Priority.MEDIUM, task.priority());
        assertEquals(Recurrence.NONE, task.recurrence());
        assertFalse(task.done());
    }

    @Test
    void testExcelByteOrderMarkAndBlankLinesAreIgnored() {
        String csv = "﻿title,priority\n\nFinish lab 4,high\n\n";

        List<Task> tasks = CsvTasks.fromCsv(csv);

        assertEquals(1, tasks.size());
        assertEquals(Priority.HIGH, tasks.get(0).priority());
    }

    @Test
    void testMissingTitleColumnIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> CsvTasks.fromCsv("name,due\nFinish lab 4,\n"));
    }

    @Test
    void testEmptyFileGivesNoTasks() {
        assertTrue(CsvTasks.fromCsv("").isEmpty());
        assertTrue(CsvTasks.fromCsv("title,priority\n").isEmpty());
    }
}
