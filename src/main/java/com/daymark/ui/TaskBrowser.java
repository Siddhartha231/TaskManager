package com.daymark.ui;

import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import com.daymark.io.CsvTasks;
import com.daymark.io.IcsExporter;
import com.daymark.service.TaskService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaskBrowser extends BorderPane {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final TaskService service;

    private final TableView<Task> table = new TableView<>();
    private final ObservableList<Task> allTasks = FXCollections.observableArrayList();

    private final ComboBox<String> viewBox = new ComboBox<>();
    private final ComboBox<String> priorityBox = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final TextField quickAddField = new TextField();

    private final Button doneButton = new Button("Mark Done");

    // bottom right: says what just happened, with an Undo button after a delete
    private final Label statusLabel = new Label();
    private final Button undoButton = new Button("Undo");

    public TaskBrowser(TaskService service) {
        this.service = service;

        // ids so the UI tests can find things
        table.setId("taskTable");
        searchField.setId("searchField");
        viewBox.setId("viewBox");
        priorityBox.setId("priorityFilter");
        doneButton.setId("doneButton");
        quickAddField.setId("quickAddField");
        statusLabel.setId("statusLabel");
        statusLabel.getStyleClass().add("status-label");
        undoButton.setId("undoButton");

        setTop(buildTopBar());
        setCenter(buildTable());
        setBottom(buildButtonBar());

        // light or dark, whichever was used last time
        Theme.apply(this);

        loadTasks();
    }

    private VBox buildTopBar() {
        Label title = new Label("Daymark");
        title.getStyleClass().add("app-title");

        viewBox.getItems().addAll("Today", "All Tasks", "Completed");
        viewBox.setValue("All Tasks");
        viewBox.setOnAction(e -> refreshTable());

        priorityBox.getItems().addAll("All", "Low", "Medium", "High");
        priorityBox.setValue("All");
        priorityBox.setOnAction(e -> refreshTable());

        quickAddField.setPromptText("Quick add - type something like \"essay due fri !high\" and press Enter");
        quickAddField.setOnAction(e -> quickAdd());
        quickAddField.getStyleClass().add("quick-add");

        searchField.setPromptText("Search...");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshTable());

        HBox filters = new HBox(10,
                new Label("View:"), viewBox,
                new Label("Priority:"), priorityBox,
                searchField);
        filters.setAlignment(Pos.CENTER_LEFT);

        // Escape in the search box clears it and goes back to the list
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                table.requestFocus();
            }
        });

        VBox content = new VBox(10, title, quickAddField, filters);
        content.setPadding(new Insets(15, 15, 10, 15));
        return new VBox(buildMenuBar(), content);
    }

    // Every action is in a menu with its keyboard shortcut next to it.
    // SHORTCUT_DOWN means Ctrl on Windows/Linux and Cmd on a Mac.
    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(
                menuItem("Import CSV...", shortcut(KeyCode.I), this::chooseImportCsv),
                menuItem("Export CSV...", shortcut(KeyCode.S), this::chooseExportCsv),
                menuItem("Export Calendar (.ics)...", shiftShortcut(KeyCode.S), this::chooseExportIcs));

        Menu taskMenu = new Menu("Task");
        MenuItem editItem = menuItem("Edit Task", shortcut(KeyCode.E), this::editTask);
        MenuItem doneItem = menuItem("Mark Done / Undone", shortcut(KeyCode.D), this::toggleDone);
        // no accelerator here on purpose: the Delete key is handled by the table (see buildTable),
        // otherwise pressing Delete while typing in a text box would delete a task
        MenuItem deleteItem = menuItem("Delete Task", null, this::deleteTask);
        editItem.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        doneItem.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteItem.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        taskMenu.getItems().addAll(
                menuItem("New Task...", shortcut(KeyCode.N), this::addTask),
                menuItem("Quick Add", shortcut(KeyCode.L), quickAddField::requestFocus),
                new SeparatorMenuItem(),
                editItem, doneItem, deleteItem,
                menuItem("Undo Delete", shortcut(KeyCode.Z), this::undoDelete));

        CheckMenuItem darkModeItem = new CheckMenuItem("Dark Mode");
        darkModeItem.setId("darkModeItem");
        darkModeItem.setAccelerator(shiftShortcut(KeyCode.D));
        darkModeItem.setSelected(Theme.isDark());
        darkModeItem.setOnAction(e -> {
            Theme.setDark(darkModeItem.isSelected());
            Theme.apply(this);
        });

        Menu viewMenu = new Menu("View");
        viewMenu.getItems().addAll(
                menuItem("Search", shortcut(KeyCode.F), searchField::requestFocus),
                new SeparatorMenuItem(),
                menuItem("Today", shortcut(KeyCode.DIGIT1), () -> viewBox.setValue("Today")),
                menuItem("All Tasks", shortcut(KeyCode.DIGIT2), () -> viewBox.setValue("All Tasks")),
                menuItem("Completed", shortcut(KeyCode.DIGIT3), () -> viewBox.setValue("Completed")),
                new SeparatorMenuItem(),
                darkModeItem);

        Menu helpMenu = new Menu("Help");
        helpMenu.getItems().add(menuItem("Keyboard Shortcuts", new KeyCodeCombination(KeyCode.F1), this::showShortcuts));

        return new MenuBar(fileMenu, taskMenu, viewMenu, helpMenu);
    }

    private MenuItem menuItem(String text, KeyCombination keys, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (keys != null) {
            item.setAccelerator(keys);
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private KeyCombination shortcut(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    private KeyCombination shiftShortcut(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private TableView<Task> buildTable() {
        TableColumn<Task, String> titleCol = new TableColumn<>("Title");
        titleCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().title()));
        titleCol.setPrefWidth(300);

        TableColumn<Task, String> dueCol = new TableColumn<>("Due Date");
        dueCol.setCellValueFactory(data -> {
            LocalDate due = data.getValue().dueDate();
            return new SimpleStringProperty(due == null ? "" : DATE_FORMAT.format(due));
        });
        dueCol.setPrefWidth(120);

        TableColumn<Task, String> priorityCol = new TableColumn<>("Priority");
        priorityCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().priority().toString()));
        priorityCol.setPrefWidth(100);

        TableColumn<Task, String> doneCol = new TableColumn<>("Done");
        doneCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().done() ? "✓" : ""));
        doneCol.setPrefWidth(70);

        TableColumn<Task, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(data -> {
            String d = data.getValue().description();
            return new SimpleStringProperty(d == null ? "" : d);
        });
        descCol.setPrefWidth(180);

        TableColumn<Task, String> repeatsCol = new TableColumn<>("Repeats");
        repeatsCol.setCellValueFactory(data -> {
            Recurrence r = data.getValue().recurrence();
            return new SimpleStringProperty(r == Recurrence.NONE ? "" : r.toString());
        });
        repeatsCol.setPrefWidth(80);

        table.getColumns().addAll(titleCol, dueCol, priorityCol, repeatsCol, doneCol, descCol);
        table.setPlaceholder(new Label("No tasks"));

        // highlight overdue rows in red
        table.setRowFactory(tv -> new TableRow<Task>() {
            @Override
            protected void updateItem(Task task, boolean empty) {
                super.updateItem(task, empty);
                getStyleClass().remove("overdue");
                if (task != null && !empty && task.isOverdue()) {
                    getStyleClass().add("overdue");
                }
            }
        });

        // the done button label depends on which task is selected
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldTask, newTask) -> {
            if (newTask != null && newTask.done()) {
                doneButton.setText("Mark Undone");
            } else {
                doneButton.setText("Mark Done");
            }
        });

        // keys that only make sense while the list has focus
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                deleteTask();
            } else if (e.getCode() == KeyCode.ENTER) {
                editTask();
            } else if (e.getCode() == KeyCode.SPACE) {
                toggleDone();
            } else {
                return;
            }
            e.consume();
        });

        BorderPane.setMargin(table, new Insets(0, 15, 0, 15));
        return table;
    }

    private HBox buildButtonBar() {
        Button addButton = new Button("Add Task");
        addButton.getStyleClass().add("add-button");
        addButton.setOnAction(e -> addTask());

        Button editButton = new Button("Edit");
        editButton.setOnAction(e -> editTask());

        doneButton.setOnAction(e -> toggleDone());

        Button deleteButton = new Button("Delete");
        deleteButton.getStyleClass().add("delete-button");
        deleteButton.setOnAction(e -> deleteTask());

        // these only make sense when a row is selected
        editButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        doneButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        addButton.setTooltip(new Tooltip("Ctrl+N"));
        editButton.setTooltip(new Tooltip("Ctrl+E, or Enter in the list"));
        doneButton.setTooltip(new Tooltip("Ctrl+D, or Space in the list"));
        deleteButton.setTooltip(new Tooltip("Delete key in the list"));
        undoButton.setTooltip(new Tooltip("Ctrl+Z"));

        undoButton.setOnAction(e -> undoDelete());
        undoButton.setVisible(false);
        undoButton.managedProperty().bind(undoButton.visibleProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, addButton, editButton, doneButton, deleteButton, spacer, statusLabel, undoButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 15, 15, 15));
        return bar;
    }

    // reload everything from the db after every change, simpler than keeping the list in sync
    private void loadTasks() {
        allTasks.setAll(service.getAllTasks());
        refreshTable();
    }

    private void refreshTable() {
        String view = viewBox.getValue();
        String priority = priorityBox.getValue();
        String search = searchField.getText().trim().toLowerCase();

        List<Task> visible = new ArrayList<>();
        for (Task task : allTasks) {
            if (view.equals("Today")) {
                if (task.done() || task.dueDate() == null || task.dueDate().isAfter(LocalDate.now())) {
                    continue;
                }
            } else if (view.equals("Completed")) {
                if (!task.done()) {
                    continue;
                }
            }

            if (!priority.equals("All") && !task.priority().toString().equals(priority)) {
                continue;
            }

            if (!search.isEmpty()) {
                boolean inTitle = task.title().toLowerCase().contains(search);
                boolean inDesc = task.description() != null
                        && task.description().toLowerCase().contains(search);
                if (!inTitle && !inDesc) {
                    continue;
                }
            }

            visible.add(task);
        }

        // tasks with a due date first, soonest at the top
        // TODO sort by priority too
        visible.sort((a, b) -> {
            if (a.dueDate() == null && b.dueDate() == null) {
                return 0;
            }
            if (a.dueDate() == null) {
                return 1;
            }
            if (b.dueDate() == null) {
                return -1;
            }
            return a.dueDate().compareTo(b.dueDate());
        });

        table.getItems().setAll(visible);
    }

    private void quickAdd() {
        String text = quickAddField.getText();
        if (text.trim().isEmpty()) {
            return;
        }
        try {
            Task created = service.quickAdd(text);
            quickAddField.clear();
            showStatus("Added \"" + created.title() + "\"");
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        }
        loadTasks();
    }

    private void addTask() {
        TaskEditor editor = new TaskEditor(null);
        Theme.apply(editor);
        Optional<Task> result = editor.showAndWait();
        if (result.isPresent()) {
            Task t = result.get();
            try {
                service.createTask(t.title(), t.description(), t.dueDate(), t.priority(), t.recurrence());
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
            }
            loadTasks();
        }
    }

    private void editTask() {
        Task selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TaskEditor editor = new TaskEditor(selected);
        Theme.apply(editor);
        Optional<Task> result = editor.showAndWait();
        if (result.isPresent()) {
            try {
                service.updateTask(result.get());
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
            }
            loadTasks();
        }
    }

    private void toggleDone() {
        Task selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (selected.done()) {
            service.markUndone(selected.id());
        } else {
            service.markDone(selected.id());
            if (selected.recurrence() != Recurrence.NONE) {
                showStatus("Done - the next \"" + selected.title() + "\" has been added");
            }
        }
        loadTasks();
    }

    private void deleteTask() {
        Task selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Task");
        confirm.setHeaderText("Delete \"" + selected.title() + "\"?");
        Theme.apply(confirm);
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            service.deleteTask(selected.id());
            loadTasks();
            showStatus("Deleted \"" + selected.title() + "\"");
            undoButton.setVisible(true);
        }
    }

    private void undoDelete() {
        Task restored = service.undoDelete();
        if (restored == null) {
            return;
        }
        loadTasks();
        showStatus("Restored \"" + restored.title() + "\"");
    }

    // ---- import / export ----
    // The choose... methods just ask for a file. The methods that take a File do the
    // work, which keeps them testable without a file dialog.

    private void chooseImportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import tasks from CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            importCsv(file);
        }
    }

    private void chooseExportCsv() {
        File file = chooseSaveFile("Export tasks to CSV", "daymark-tasks.csv", "CSV files", "*.csv");
        if (file != null) {
            exportCsv(file);
        }
    }

    private void chooseExportIcs() {
        File file = chooseSaveFile("Export tasks to a calendar file", "daymark-tasks.ics", "Calendar files", "*.ics");
        if (file != null) {
            exportIcs(file);
        }
    }

    private File chooseSaveFile(String title, String fileName, String description, String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(fileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extension));
        return chooser.showSaveDialog(getScene().getWindow());
    }

    void importCsv(File file) {
        try {
            List<Task> tasks = CsvTasks.fromCsv(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            int imported = service.importTasks(tasks);
            loadTasks();
            int skipped = tasks.size() - imported;
            if (skipped == 0) {
                showStatus("Imported " + imported + " tasks");
            } else {
                showStatus("Imported " + imported + " tasks, skipped " + skipped + " with a missing or too long title");
            }
        } catch (IOException ex) {
            showError("Could not read " + file.getName() + ": " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        }
    }

    void exportCsv(File file) {
        try {
            Files.writeString(file.toPath(), CsvTasks.toCsv(allTasks), StandardCharsets.UTF_8);
            showStatus("Exported " + allTasks.size() + " tasks to " + file.getName());
        } catch (IOException ex) {
            showError("Could not write " + file.getName() + ": " + ex.getMessage());
        }
    }

    void exportIcs(File file) {
        try {
            int exported = IcsExporter.exportable(allTasks).size();
            Files.writeString(file.toPath(), IcsExporter.toIcs(allTasks, Instant.now()), StandardCharsets.UTF_8);
            showStatus("Exported " + exported + " unfinished tasks with due dates to " + file.getName());
        } catch (IOException ex) {
            showError("Could not write " + file.getName() + ": " + ex.getMessage());
        }
    }

    private void showShortcuts() {
        Alert help = new Alert(Alert.AlertType.INFORMATION);
        help.setTitle("Keyboard Shortcuts");
        help.setHeaderText("Keyboard shortcuts");
        // two columns: the keys on the left, what they do on the right
        String[][] rows = {
                {"Ctrl+N", "New task"},
                {"Ctrl+L", "Quick add (type a task, press Enter)"},
                {"Ctrl+E", "Edit the selected task"},
                {"Ctrl+D", "Mark the selected task done / undone"},
                {"Ctrl+Z", "Undo the last delete"},
                {"Ctrl+F", "Search (Esc clears it)"},
                {"Ctrl+1 / 2 / 3", "Today / All Tasks / Completed"},
                {"Ctrl+Shift+D", "Dark mode on / off"},
                {"Ctrl+I", "Import CSV"},
                {"Ctrl+S", "Export CSV"},
                {"Ctrl+Shift+S", "Export calendar (.ics)"},
                {"F1", "This list"},
                {"", ""},
                {"In the task list:", ""},
                {"Up / Down", "Move between tasks"},
                {"Enter", "Edit"},
                {"Space", "Mark done / undone"},
                {"Delete", "Delete"},
                {"", ""},
                {"On a Mac use Cmd instead of Ctrl.", ""},
        };
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(5);
        grid.setPadding(new Insets(15));
        for (int row = 0; row < rows.length; row++) {
            Label keys = new Label(rows[row][0]);
            if (rows[row][1].isEmpty()) {
                // a note or heading, let it use the full width
                grid.add(keys, 0, row, 2, 1);
            } else {
                keys.getStyleClass().add("shortcut-keys");
                grid.add(keys, 0, row);
                grid.add(new Label(rows[row][1]), 1, row);
            }
        }
        help.getDialogPane().setContent(grid);
        Theme.apply(help);
        help.showAndWait();
    }

    // a new message replaces the old one, and only a delete shows the Undo button
    private void showStatus(String message) {
        statusLabel.setText(message);
        undoButton.setVisible(false);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        Theme.apply(alert);
        alert.showAndWait();
    }
}
