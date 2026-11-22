package com.daymark.io;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes tasks as an iCalendar (.ics) file that Google Calendar, Outlook and Apple
 * Calendar can import. Each task becomes an all-day event on its due date, and a
 * repeating task becomes a repeating event.
 *
 * Only unfinished tasks that have a due date are included - a task with no date has
 * nowhere to go on a calendar.
 */
public class IcsExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    // the tasks from the list that will actually end up in the calendar
    public static List<Task> exportable(List<Task> tasks) {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) {
            if (!task.done() && task.dueDate() != null) {
                result.add(task);
            }
        }
        return result;
    }

    // now is passed in (instead of calling Instant.now() here) so tests get the same output every time
    public static String toIcs(List<Task> tasks, Instant now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//Daymark//Daymark Task Manager//EN");
        line(sb, "CALSCALE:GREGORIAN");

        for (Task task : exportable(tasks)) {
            LocalDate due = task.dueDate();
            line(sb, "BEGIN:VEVENT");
            // the calendar uses the UID to tell events apart
            line(sb, "UID:daymark-task-" + task.id() + "-" + DATE.format(due) + "@daymark");
            line(sb, "DTSTAMP:" + STAMP.format(now));
            // all-day event: the end date is the day after, that's how the format wants it
            line(sb, "DTSTART;VALUE=DATE:" + DATE.format(due));
            line(sb, "DTEND;VALUE=DATE:" + DATE.format(due.plusDays(1)));
            line(sb, "SUMMARY:" + escape(task.title()));
            if (task.description() != null) {
                line(sb, "DESCRIPTION:" + escape(task.description()));
            }
            line(sb, "PRIORITY:" + icsPriority(task.priority()));
            if (task.recurrence() != Recurrence.NONE) {
                line(sb, "RRULE:FREQ=" + task.recurrence().name());
            }
            line(sb, "END:VEVENT");
        }

        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    // iCalendar priorities go from 1 (highest) to 9 (lowest)
    private static int icsPriority(Priority priority) {
        switch (priority) {
            case HIGH:
                return 1;
            case LOW:
                return 9;
            default:
                return 5;
        }
    }

    // backslash, comma and semicolon mean something in the format so they need a backslash in front
    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    // The format says lines end with \r\n and can't be longer than 75 bytes. A longer line
    // gets continued on the next line, which has to start with a space.
    private static void line(StringBuilder sb, String text) {
        int lineBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            int size = ch.getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes + size > 75) {
                sb.append("\r\n ");
                lineBytes = 1;
            }
            sb.append(ch);
            lineBytes += size;
            i += ch.length();
        }
        sb.append("\r\n");
    }
}
