package com.daymark.io;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IcsExporterTest {

    private final Instant now = Instant.parse("2025-03-12T15:00:00Z");

    private Task task(int id, String title, String description, LocalDate due, boolean done, Recurrence recurrence) {
        return new Task(id, title, description, due, Priority.HIGH, done, LocalDateTime.of(2025, 3, 1, 10, 0), recurrence);
    }

    @Test
    void testTaskBecomesAnAllDayEvent() {
        String ics = IcsExporter.toIcs(List.of(
                task(7, "Finish lab 4", "due friday", LocalDate.of(2025, 3, 14), false, Recurrence.NONE)), now);

        assertEquals("BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Daymark//Daymark Task Manager//EN\r\n"
                + "CALSCALE:GREGORIAN\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:daymark-task-7-20250314@daymark\r\n"
                + "DTSTAMP:20250312T150000Z\r\n"
                + "DTSTART;VALUE=DATE:20250314\r\n"
                + "DTEND;VALUE=DATE:20250315\r\n"
                + "SUMMARY:Finish lab 4\r\n"
                + "DESCRIPTION:due friday\r\n"
                + "PRIORITY:1\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n", ics);
    }

    @Test
    void testRepeatingTaskGetsARepeatRule() {
        String ics = IcsExporter.toIcs(List.of(
                task(1, "Pay rent", null, LocalDate.of(2025, 4, 1), false, Recurrence.MONTHLY)), now);

        assertTrue(ics.contains("RRULE:FREQ=MONTHLY\r\n"));
        assertFalse(ics.contains("DESCRIPTION"));
    }

    @Test
    void testFinishedAndUndatedTasksAreLeftOut() {
        List<Task> tasks = List.of(
                task(1, "Has a date", null, LocalDate.of(2025, 3, 14), false, Recurrence.NONE),
                task(2, "Already done", null, LocalDate.of(2025, 3, 14), true, Recurrence.NONE),
                task(3, "No date", null, null, false, Recurrence.NONE));

        String ics = IcsExporter.toIcs(tasks, now);

        assertEquals(1, IcsExporter.exportable(tasks).size());
        assertTrue(ics.contains("SUMMARY:Has a date"));
        assertFalse(ics.contains("Already done"));
        assertFalse(ics.contains("No date"));
    }

    @Test
    void testSpecialCharactersAreEscaped() {
        String ics = IcsExporter.toIcs(List.of(
                task(1, "Email Dr. Lee, re: lab; ask", "line one\nline two", LocalDate.of(2025, 3, 14),
                        false, Recurrence.NONE)), now);

        assertTrue(ics.contains("SUMMARY:Email Dr. Lee\\, re: lab\\; ask\r\n"));
        assertTrue(ics.contains("DESCRIPTION:line one\\nline two\r\n"));
    }

    @Test
    void testLongLinesAreFolded() {
        String longTitle = "Finish the really long group project writeup — ".repeat(4).trim();
        String ics = IcsExporter.toIcs(List.of(
                task(1, longTitle, null, LocalDate.of(2025, 3, 14), false, Recurrence.NONE)), now);

        // no line may be longer than 75 bytes
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, "too long: " + line);
        }
        // and unfolding (removing line break + space) gives the title back in one piece
        assertTrue(ics.replace("\r\n ", "").contains("SUMMARY:" + longTitle));
    }

    @Test
    void testNoTasksStillGivesAValidEmptyCalendar() {
        String ics = IcsExporter.toIcs(List.of(), now);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertFalse(ics.contains("VEVENT"));
    }
}
