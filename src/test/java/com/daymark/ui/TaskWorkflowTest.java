package com.daymark.ui;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import com.daymark.persistence.DatabaseManager;
import com.daymark.persistence.SQLiteTaskRepository;
import com.daymark.service.TaskService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.NodeQueryUtils;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real window the way a user would - clicking buttons and typing -
 * on top of the real service and an in-memory SQLite database. So each test
 * goes through every layer: UI -> service -> repository -> database.
 */
@ExtendWith(ApplicationExtension.class)
class TaskWorkflowTest {

    private DatabaseManager db;
    private TaskService service;
    private Stage stage;
    private TaskBrowser browser;

    @Start
    private void start(Stage stage) {
        db = new DatabaseManager(":memory:");
        service = new TaskService(new SQLiteTaskRepository(db));

        browser = new TaskBrowser(service);
        Scene scene = new Scene(browser, 900, 600);
        scene.getStylesheets().add(getClass().getResource("/com/daymark/ui/daymark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        stage.toFront();
        this.stage = stage;
    }

    // don't start clicking until the window is actually up and has focus,
    // otherwise the first click of a test can land before the window is ready
    @BeforeEach
    void waitForWindow() throws TimeoutException {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> stage.isShowing() && stage.isFocused());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void testAddTaskShowsUpInTable(FxRobot robot) {
        addTask(robot, "Finish lab 4");

        TableView<Task> table = table(robot);
        assertEquals(1, table.getItems().size());
        assertEquals("Finish lab 4", table.getItems().get(0).title());

        // and it really got saved, not just displayed
        assertEquals(1, service.getAllTasks().size());
    }

    @Test
    void testAddTaskWithEmptyTitleShowsError(FxRobot robot) {
        robot.clickOn("Add Task");
        waitFor(robot, "#titleField");
        clickDialogButton(robot, "OK");

        // the error alert from the service's validation
        waitFor(robot, "Title cannot be empty");
        clickDialogButton(robot, "OK");

        assertTrue(table(robot).getItems().isEmpty());
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testEditTaskChangesTitle(FxRobot robot) {
        addTask(robot, "Buy groceries");

        robot.clickOn("Buy groceries");
        robot.clickOn("Edit");
        waitFor(robot, "#titleField");
        TextField titleField = robot.lookup("#titleField").queryAs(TextField.class);
        robot.interact(titleField::clear);
        robot.clickOn("#titleField").write("Buy groceries and milk");
        clickDialogButton(robot, "OK");

        assertEquals("Buy groceries and milk", table(robot).getItems().get(0).title());
        assertEquals("Buy groceries and milk", service.getAllTasks().get(0).title());
    }

    @Test
    void testMarkDoneAndUndone(FxRobot robot) {
        addTask(robot, "Study for midterm");

        robot.clickOn("Study for midterm");
        robot.clickOn("#doneButton");
        assertTrue(service.getAllTasks().get(0).done());

        // selecting a finished task flips the button label
        robot.clickOn("Study for midterm");
        assertEquals("Mark Undone", robot.lookup("#doneButton").queryAs(Button.class).getText());
        robot.clickOn("#doneButton");
        assertFalse(service.getAllTasks().get(0).done());
    }

    @Test
    void testDeleteTaskAfterConfirming(FxRobot robot) {
        addTask(robot, "Buy groceries");

        robot.clickOn("Buy groceries");
        robot.clickOn("Delete");
        waitFor(robot, "OK");
        clickDialogButton(robot, "OK");

        assertTrue(table(robot).getItems().isEmpty());
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testCancelDeleteKeepsTask(FxRobot robot) {
        addTask(robot, "Buy groceries");

        robot.clickOn("Buy groceries");
        robot.clickOn("Delete");
        waitFor(robot, "Cancel");
        clickDialogButton(robot, "Cancel");

        assertEquals(1, table(robot).getItems().size());
    }

    @Test
    void testSearchFiltersTable(FxRobot robot) {
        addTask(robot, "Finish lab 4");
        addTask(robot, "Buy groceries");
        assertEquals(2, table(robot).getItems().size());

        robot.clickOn("#searchField").write("lab");

        assertEquals(1, table(robot).getItems().size());
        assertEquals("Finish lab 4", table(robot).getItems().get(0).title());
    }

    @Test
    void testQuickAddParsesTheLine(FxRobot robot) {
        robot.clickOn("#quickAddField").write("essay due fri !high");
        robot.push(KeyCode.ENTER);

        assertEquals(1, service.getAllTasks().size());
        Task task = service.getAllTasks().get(0);
        assertEquals("essay", task.title());
        assertEquals(LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)), task.dueDate());
        assertEquals(Priority.HIGH, task.priority());

        // it shows up in the table and the box is ready for the next one
        assertEquals("essay", table(robot).getItems().get(0).title());
        assertEquals("", robot.lookup("#quickAddField").queryAs(TextField.class).getText());
    }

    @Test
    void testQuickAddWithOnlyKeywordsShowsError(FxRobot robot) {
        robot.clickOn("#quickAddField").write("due fri !high");
        robot.push(KeyCode.ENTER);

        waitFor(robot, "Title cannot be empty");
        clickDialogButton(robot, "OK");
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testUndoButtonRestoresDeletedTask(FxRobot robot) {
        addTask(robot, "Buy groceries");
        Button undo = robot.lookup("#undoButton").queryAs(Button.class);
        assertFalse(undo.isVisible());

        robot.clickOn("Buy groceries");
        robot.clickOn("Delete");
        waitFor(robot, "OK");
        clickDialogButton(robot, "OK");
        assertTrue(table(robot).getItems().isEmpty());
        assertTrue(undo.isVisible());

        robot.clickOn("#undoButton");

        assertEquals("Buy groceries", table(robot).getItems().get(0).title());
        assertEquals(1, service.getAllTasks().size());
        assertFalse(undo.isVisible());
    }

    @Test
    void testFinishingRepeatingTaskAddsTheNextOne(FxRobot robot) {
        robot.clickOn("#quickAddField").write("water plants every day");
        robot.push(KeyCode.ENTER);

        robot.clickOn("water plants");
        robot.clickOn("#doneButton");

        // the finished one plus a new one due tomorrow
        assertEquals(2, table(robot).getItems().size());
        Task next = service.getAllTasks().get(1);
        assertFalse(next.done());
        assertEquals(LocalDate.now().plusDays(1), next.dueDate());
    }

    // ---- keyboard shortcuts ----

    @Test
    void testCtrlNOpensNewTaskDialog(FxRobot robot) {
        robot.clickOn("#taskTable");
        robot.push(KeyCode.CONTROL, KeyCode.N);
        waitFor(robot, "#titleField");
        robot.clickOn("#titleField").write("Finish lab 4");
        robot.push(KeyCode.ENTER);

        assertEquals("Finish lab 4", table(robot).getItems().get(0).title());
    }

    @Test
    void testKeysOnTheSelectedTask(FxRobot robot) {
        addTask(robot, "Study for midterm");
        robot.clickOn("Study for midterm");

        // Ctrl+D marks it done
        robot.push(KeyCode.CONTROL, KeyCode.D);
        assertTrue(service.getAllTasks().get(0).done());

        // Space toggles it back
        robot.clickOn("Study for midterm");
        robot.push(KeyCode.SPACE);
        assertFalse(service.getAllTasks().get(0).done());

        // Enter opens the editor
        robot.clickOn("Study for midterm");
        robot.push(KeyCode.ENTER);
        waitFor(robot, "#titleField");
        clickDialogButton(robot, "Cancel");

        // Ctrl+E does too
        robot.clickOn("Study for midterm");
        robot.push(KeyCode.CONTROL, KeyCode.E);
        waitFor(robot, "#titleField");
        clickDialogButton(robot, "Cancel");

        // Delete asks first, then Ctrl+Z brings it back
        robot.clickOn("Study for midterm");
        robot.push(KeyCode.DELETE);
        waitFor(robot, "OK");
        clickDialogButton(robot, "OK");
        assertTrue(service.getAllTasks().isEmpty());

        robot.clickOn("#taskTable");
        robot.push(KeyCode.CONTROL, KeyCode.Z);
        assertEquals(1, service.getAllTasks().size());
        assertEquals("Study for midterm", table(robot).getItems().get(0).title());
    }

    @Test
    void testDeleteKeyWhileTypingDoesNotDeleteATask(FxRobot robot) {
        addTask(robot, "Buy groceries");
        robot.clickOn("Buy groceries");

        robot.clickOn("#searchField").write("groc");
        robot.push(KeyCode.BACK_SPACE);
        robot.push(KeyCode.DELETE);

        assertEquals(1, service.getAllTasks().size());
        assertEquals("gro", robot.lookup("#searchField").queryAs(TextField.class).getText());
    }

    @Test
    void testFocusAndViewShortcuts(FxRobot robot) {
        addTask(robot, "Finish lab 4");
        robot.clickOn("#taskTable");

        robot.push(KeyCode.CONTROL, KeyCode.F);
        assertTrue(robot.lookup("#searchField").queryAs(TextField.class).isFocused());
        robot.write("zzz");
        assertTrue(table(robot).getItems().isEmpty());

        // Escape clears the search and goes back to the list
        robot.push(KeyCode.ESCAPE);
        assertEquals("", robot.lookup("#searchField").queryAs(TextField.class).getText());
        assertEquals(1, table(robot).getItems().size());

        robot.push(KeyCode.CONTROL, KeyCode.L);
        assertTrue(robot.lookup("#quickAddField").queryAs(TextField.class).isFocused());

        robot.clickOn("#taskTable");
        robot.push(KeyCode.CONTROL, KeyCode.DIGIT3);
        assertEquals("Completed", robot.lookup("#viewBox").queryAs(ComboBox.class).getValue());
        assertTrue(table(robot).getItems().isEmpty());
        robot.push(KeyCode.CONTROL, KeyCode.DIGIT2);
        assertEquals(1, table(robot).getItems().size());
    }

    @Test
    void testF1ShowsShortcutList(FxRobot robot) {
        robot.clickOn("#taskTable");
        robot.push(KeyCode.F1);
        waitFor(robot, "Keyboard shortcuts");
        clickDialogButton(robot, "OK");
    }

    @Test
    void testDarkModeShortcutSwitchesTheme(FxRobot robot) {
        boolean before = Theme.isDark();
        try {
            robot.clickOn("#taskTable");
            robot.push(KeyCode.CONTROL, KeyCode.SHIFT, KeyCode.D);
            assertEquals(!before, browser.getStyleClass().contains("dark"));
            // the choice is saved for next time
            assertEquals(!before, Theme.isDark());

            // dialogs follow the theme too
            robot.clickOn("Add Task");
            waitFor(robot, "#titleField");
            assertEquals(!before, robot.lookup(".dialog-pane").query().getStyleClass().contains("dark"));
            clickDialogButton(robot, "Cancel");

            robot.clickOn("#taskTable");
            robot.push(KeyCode.CONTROL, KeyCode.SHIFT, KeyCode.D);
            assertEquals(before, browser.getStyleClass().contains("dark"));
        } finally {
            // don't leave the tester's own setting changed
            Theme.setDark(before);
        }
    }

    // ---- import / export ----
    // (calls the browser's methods directly because a test can't click through the
    // operating system's file dialog)

    @Test
    void testExportThenImportCsv(FxRobot robot, @TempDir Path folder) throws IOException {
        addTask(robot, "Finish lab 4");
        robot.clickOn("#quickAddField").write("pay rent due 12/1 every month !high");
        robot.push(KeyCode.ENTER);

        File file = folder.resolve("tasks.csv").toFile();
        robot.interact(() -> browser.exportCsv(file));
        assertTrue(Files.readString(file.toPath()).contains("pay rent"));

        // importing the same file again doubles the list
        robot.interact(() -> browser.importCsv(file));
        assertEquals(4, table(robot).getItems().size());
        assertEquals("Imported 2 tasks", robot.lookup("#statusLabel").queryAs(Label.class).getText());
        assertEquals(Recurrence.MONTHLY, service.getAllTasks().get(3).recurrence());
    }

    @Test
    void testImportFileWithoutTitleColumnShowsError(FxRobot robot, @TempDir Path folder) throws IOException {
        File file = folder.resolve("wrong.csv").toFile();
        Files.writeString(file.toPath(), "name,when\nFinish lab 4,friday\n");

        // runLater instead of interact: the error dialog blocks until it is closed
        Platform.runLater(() -> browser.importCsv(file));
        waitFor(robot, "The file needs a header row with a \"title\" column");
        clickDialogButton(robot, "OK");
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test
    void testExportCalendarFile(FxRobot robot, @TempDir Path folder) throws IOException {
        robot.clickOn("#quickAddField").write("essay due 2030-03-14 !high");
        robot.push(KeyCode.ENTER);
        addTask(robot, "No due date");

        File file = folder.resolve("tasks.ics").toFile();
        robot.interact(() -> browser.exportIcs(file));

        String ics = Files.readString(file.toPath());
        assertTrue(ics.contains("SUMMARY:essay"));
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20300314"));
        assertFalse(ics.contains("No due date"));
    }

    private void addTask(FxRobot robot, String title) {
        robot.clickOn("Add Task");
        waitFor(robot, "#titleField");
        robot.clickOn("#titleField").write(title);
        clickDialogButton(robot, "OK");
    }

    private void clickDialogButton(FxRobot robot, String text) {
        waitFor(robot, text);
        robot.clickOn(text);
    }

    // dialogs take a moment to open, so wait until something in them is actually on screen
    private void waitFor(FxRobot robot, String query) {
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> robot.lookup(query).match(NodeQueryUtils.isVisible()).tryQuery().isPresent());
        } catch (TimeoutException e) {
            fail("Timed out waiting for " + query + " to appear");
        }
    }

    @SuppressWarnings("unchecked")
    private TableView<Task> table(FxRobot robot) {
        return robot.lookup("#taskTable").queryAs(TableView.class);
    }
}
