package com.daymark.service;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one line of text into a task, e.g.
 *
 *   essay due fri !high            -> "essay", due this Friday, high priority
 *   call mom due tomorrow          -> "call mom", due tomorrow
 *   pay rent due 12/1 every month  -> "pay rent", due Dec 1, repeats monthly
 *   water plants every day         -> "water plants", due today, repeats daily
 *
 * What it understands:
 *   !high !medium !low (or !h !m !l)                      priority
 *   due today / tomorrow / fri / next week / in 3 days /
 *       2026-03-14 / 3/14 / mar 14                        due date
 *   every day / week / month / fri                        repeating
 *
 * Anything it doesn't recognize is left in the title, so "rent is due" or
 * "wow!" don't lose words. It only parses - the title is validated by TaskService.
 */
public class QuickAddParser {

    private static final String[] MONTHS = {
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    };

    // today is passed in so the tests can use a fixed date
    public Task parse(String text, LocalDate today) {
        String[] words = text == null ? new String[0] : text.trim().split("\\s+");

        List<String> titleWords = new ArrayList<>();
        Priority priority = Priority.MEDIUM;
        LocalDate dueDate = null;
        Recurrence recurrence = Recurrence.NONE;
        DayOfWeek repeatDay = null;

        int i = 0;
        while (i < words.length) {
            String word = words[i].toLowerCase();

            if (word.startsWith("!") && parsePriority(word.substring(1)) != null) {
                priority = parsePriority(word.substring(1));
                i++;
                continue;
            }

            if (word.equals("due")) {
                // a date can be up to 3 words ("in 3 days"), try the longest match first
                boolean found = false;
                for (int length = 3; length >= 1 && !found; length--) {
                    LocalDate date = parseDate(words, i + 1, length, today);
                    if (date != null) {
                        dueDate = date;
                        i += 1 + length;
                        found = true;
                    }
                }
                if (found) {
                    continue;
                }
            }

            if (word.equals("every") && i + 1 < words.length) {
                String unit = words[i + 1].toLowerCase();
                Recurrence parsed = null;
                if (unit.equals("day")) {
                    parsed = Recurrence.DAILY;
                } else if (unit.equals("week")) {
                    parsed = Recurrence.WEEKLY;
                } else if (unit.equals("month")) {
                    parsed = Recurrence.MONTHLY;
                } else if (parseDayOfWeek(unit) != null) {
                    parsed = Recurrence.WEEKLY;
                    repeatDay = parseDayOfWeek(unit);
                }
                if (parsed != null) {
                    recurrence = parsed;
                    i += 2;
                    continue;
                }
            }

            titleWords.add(words[i]);
            i++;
        }

        // a repeating task needs a date to repeat from
        if (recurrence != Recurrence.NONE && dueDate == null) {
            if (repeatDay != null) {
                dueDate = today.with(TemporalAdjusters.nextOrSame(repeatDay));
            } else {
                dueDate = today;
            }
        }

        String title = String.join(" ", titleWords);
        return new Task(title, null, dueDate, priority).withRecurrence(recurrence);
    }

    private Priority parsePriority(String text) {
        switch (text) {
            case "high":
            case "h":
                return Priority.HIGH;
            case "medium":
            case "med":
            case "m":
                return Priority.MEDIUM;
            case "low":
            case "l":
                return Priority.LOW;
            default:
                return null;
        }
    }

    // Tries to read exactly `length` words starting at `start` as a date. Returns null if they aren't one.
    private LocalDate parseDate(String[] words, int start, int length, LocalDate today) {
        if (start + length > words.length) {
            return null;
        }
        String first = words[start].toLowerCase();

        if (length == 1) {
            if (first.equals("today") || first.equals("tod")) {
                return today;
            }
            if (first.equals("tomorrow") || first.equals("tmrw") || first.equals("tmr") || first.equals("tom")) {
                return today.plusDays(1);
            }
            DayOfWeek day = parseDayOfWeek(first);
            if (day != null) {
                // "due fri" typed on a Friday means today
                return today.with(TemporalAdjusters.nextOrSame(day));
            }
            return parseNumericDate(first, today);
        }

        String second = words[start + 1].toLowerCase();

        if (length == 2) {
            if (first.equals("next") && second.equals("week")) {
                return today.plusWeeks(1);
            }
            if (first.equals("next") && second.equals("month")) {
                return today.plusMonths(1);
            }
            // "mar 14" or "march 14"
            int month = parseMonth(first);
            if (month != -1 && second.matches("\\d{1,2}")) {
                return thisYearOrNext(month, Integer.parseInt(second), today);
            }
            return null;
        }

        // length 3: "in 3 days" / "in 2 weeks"
        String third = words[start + 2].toLowerCase();
        if (first.equals("in") && second.matches("\\d{1,3}")) {
            int amount = Integer.parseInt(second);
            if (third.equals("day") || third.equals("days")) {
                return today.plusDays(amount);
            }
            if (third.equals("week") || third.equals("weeks")) {
                return today.plusWeeks(amount);
            }
        }
        return null;
    }

    // 2026-03-14, 3/14, 3/14/26 or 3/14/2026
    private LocalDate parseNumericDate(String text, LocalDate today) {
        try {
            if (text.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] parts = text.split("-");
                return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            }
            if (text.matches("\\d{1,2}/\\d{1,2}")) {
                String[] parts = text.split("/");
                return thisYearOrNext(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), today);
            }
            if (text.matches("\\d{1,2}/\\d{1,2}/(\\d{2}|\\d{4})")) {
                String[] parts = text.split("/");
                int year = Integer.parseInt(parts[2]);
                if (year < 100) {
                    year += 2000;
                }
                return LocalDate.of(year, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (DateTimeException e) {
            // something like 2/30 - not a real date, so it's not a date
        }
        return null;
    }

    // a date with no year means the next time that date comes around
    private LocalDate thisYearOrNext(int month, int day, LocalDate today) {
        try {
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            if (date.isBefore(today)) {
                date = LocalDate.of(today.getYear() + 1, month, day);
            }
            return date;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private DayOfWeek parseDayOfWeek(String text) {
        switch (text) {
            case "mon": case "monday":
                return DayOfWeek.MONDAY;
            case "tue": case "tues": case "tuesday":
                return DayOfWeek.TUESDAY;
            case "wed": case "wednesday":
                return DayOfWeek.WEDNESDAY;
            case "thu": case "thur": case "thurs": case "thursday":
                return DayOfWeek.THURSDAY;
            case "fri": case "friday":
                return DayOfWeek.FRIDAY;
            case "sat": case "saturday":
                return DayOfWeek.SATURDAY;
            case "sun": case "sunday":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    // 1-12, or -1 if the word isn't a month name
    private int parseMonth(String text) {
        if (text.length() < 3) {
            return -1;
        }
        for (int m = 0; m < MONTHS.length; m++) {
            String full = Month.of(m + 1).name().toLowerCase();
            if (text.equals(MONTHS[m]) || text.equals(full)) {
                return m + 1;
            }
        }
        return -1;
    }
}
