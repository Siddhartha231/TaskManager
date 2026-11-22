package com.daymark.service;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class QuickAddParserTest {

    // a Wednesday, so "fri" is two days later
    private final LocalDate today = LocalDate.of(2025, 3, 12);
    private final QuickAddParser parser = new QuickAddParser();

    @Test
    void testTitleDueDateAndPriority() {
        Task task = parser.parse("essay due fri !high", today);
        assertEquals("essay", task.title());
        assertEquals(LocalDate.of(2025, 3, 14), task.dueDate());
        assertEquals(Priority.HIGH, task.priority());
        assertEquals(Recurrence.NONE, task.recurrence());
    }

    @Test
    void testJustATitle() {
        Task task = parser.parse("Buy groceries", today);
        assertEquals("Buy groceries", task.title());
        assertNull(task.dueDate());
        assertEquals(Priority.MEDIUM, task.priority());
    }

    @Test
    void testOrderDoesNotMatter() {
        Task task = parser.parse("!low due tomorrow call mom", today);
        assertEquals("call mom", task.title());
        assertEquals(LocalDate.of(2025, 3, 13), task.dueDate());
        assertEquals(Priority.LOW, task.priority());
    }

    @Test
    void testPriorityShortForms() {
        assertEquals(Priority.HIGH, parser.parse("essay !h", today).priority());
        assertEquals(Priority.MEDIUM, parser.parse("essay !med", today).priority());
        assertEquals(Priority.LOW, parser.parse("essay !L", today).priority());
    }

    @Test
    void testRelativeDates() {
        assertEquals(today, parser.parse("essay due today", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 13), parser.parse("essay due tmrw", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 19), parser.parse("essay due next week", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 15), parser.parse("essay due in 3 days", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 26), parser.parse("essay due in 2 weeks", today).dueDate());
    }

    @Test
    void testWeekdays() {
        assertEquals(LocalDate.of(2025, 3, 17), parser.parse("essay due monday", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 13), parser.parse("essay due Thurs", today).dueDate());
        // typed on a Wednesday, "wed" means today
        assertEquals(today, parser.parse("essay due wed", today).dueDate());
    }

    @Test
    void testWrittenDates() {
        assertEquals(LocalDate.of(2025, 4, 1), parser.parse("essay due 2025-04-01", today).dueDate());
        assertEquals(LocalDate.of(2025, 3, 14), parser.parse("essay due 3/14", today).dueDate());
        assertEquals(LocalDate.of(2026, 3, 14), parser.parse("essay due 3/14/26", today).dueDate());
        assertEquals(LocalDate.of(2025, 5, 2), parser.parse("essay due may 2", today).dueDate());
        assertEquals(LocalDate.of(2025, 12, 25), parser.parse("gifts due December 25", today).dueDate());
    }

    @Test
    void testDateWithNoYearThatAlreadyPassedMeansNextYear() {
        assertEquals(LocalDate.of(2026, 1, 15), parser.parse("essay due 1/15", today).dueDate());
        assertEquals(LocalDate.of(2026, 2, 1), parser.parse("essay due feb 1", today).dueDate());
    }

    @Test
    void testRepeating() {
        Task daily = parser.parse("water plants every day", today);
        assertEquals("water plants", daily.title());
        assertEquals(Recurrence.DAILY, daily.recurrence());
        assertEquals(today, daily.dueDate());

        Task monthly = parser.parse("pay rent due 4/1 every month !high", today);
        assertEquals("pay rent", monthly.title());
        assertEquals(Recurrence.MONTHLY, monthly.recurrence());
        assertEquals(LocalDate.of(2025, 4, 1), monthly.dueDate());
        assertEquals(Priority.HIGH, monthly.priority());

        Task weekly = parser.parse("lab report every fri", today);
        assertEquals("lab report", weekly.title());
        assertEquals(Recurrence.WEEKLY, weekly.recurrence());
        assertEquals(LocalDate.of(2025, 3, 14), weekly.dueDate());
    }

    @Test
    void testWordsThatOnlyLookLikeKeywordsStayInTheTitle() {
        // "due" not followed by a date
        assertEquals("rent is due", parser.parse("rent is due", today).title());
        assertEquals("pay dues due soon", parser.parse("pay dues due soon", today).title());
        // not a real priority or date
        assertEquals("wow !important", parser.parse("wow !important", today).title());
        assertEquals("essay due 2/30", parser.parse("essay due 2/30", today).title());
        assertNull(parser.parse("essay due 2/30", today).dueDate());
        // "every" not followed by day/week/month
        assertEquals("email every professor", parser.parse("email every professor", today).title());
        assertEquals(Recurrence.NONE, parser.parse("email every professor", today).recurrence());
    }

    @Test
    void testEmptyInputGivesEmptyTitle() {
        // the service is what rejects this, the parser just reports what it found
        assertEquals("", parser.parse("   ", today).title());
        assertEquals("", parser.parse("due fri !high", today).title());
        assertEquals("", parser.parse(null, today).title());
    }

    @Test
    void testExtraSpacesAreIgnored() {
        Task task = parser.parse("  essay    due   fri  ", today);
        assertEquals("essay", task.title());
        assertEquals(LocalDate.of(2025, 3, 14), task.dueDate());
    }
}
