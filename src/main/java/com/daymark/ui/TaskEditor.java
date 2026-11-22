package com.daymark.ui;

import com.daymark.domain.Priority;
import com.daymark.domain.Recurrence;
import com.daymark.domain.Task;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Dialog for adding or editing a task. Returns a Task object with whatever
 * the user typed - validation happens in TaskService, not here.
 */
public class TaskEditor extends Dialog<Task> {

    private final TextField titleField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final DatePicker dueDatePicker = new DatePicker();
    private final ComboBox<Priority> priorityBox = new ComboBox<>();
    private final ComboBox<Recurrence> repeatBox = new ComboBox<>();

    // pass null to create a new task
    public TaskEditor(Task existing) {
        setTitle(existing == null ? "Add Task" : "Edit Task");
        setHeaderText(existing == null ? "Add a new task" : "Edit task");

        priorityBox.getItems().addAll(Priority.values());
        priorityBox.setValue(Priority.MEDIUM);
        repeatBox.getItems().addAll(Recurrence.values());
        repeatBox.setValue(Recurrence.NONE);
        descriptionArea.setPrefRowCount(3);

        // ids so the UI tests can find the fields
        titleField.setId("titleField");
        descriptionArea.setId("descriptionArea");
        dueDatePicker.setId("dueDatePicker");
        priorityBox.setId("priorityBox");
        repeatBox.setId("repeatBox");
        titleField.setPrefWidth(300);

        if (existing != null) {
            titleField.setText(existing.title());
            descriptionArea.setText(existing.description());
            dueDatePicker.setValue(existing.dueDate());
            priorityBox.setValue(existing.priority());
            repeatBox.setValue(existing.recurrence());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));

        grid.add(new Label("Title:"), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label("Description:"), 0, 1);
        grid.add(descriptionArea, 1, 1);
        grid.add(new Label("Due date:"), 0, 2);
        grid.add(dueDatePicker, 1, 2);
        grid.add(new Label("Priority:"), 0, 3);
        grid.add(priorityBox, 1, 3);
        grid.add(new Label("Repeats:"), 0, 4);
        grid.add(repeatBox, 1, 4);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            if (existing == null) {
                return new Task(titleField.getText(), descriptionArea.getText(),
                        dueDatePicker.getValue(), priorityBox.getValue())
                        .withRecurrence(repeatBox.getValue());
            }
            // tasks are immutable, so hand back an edited copy
            return existing.withTitle(titleField.getText())
                    .withDescription(descriptionArea.getText())
                    .withDueDate(dueDatePicker.getValue())
                    .withPriority(priorityBox.getValue())
                    .withRecurrence(repeatBox.getValue());
        });
    }
}
