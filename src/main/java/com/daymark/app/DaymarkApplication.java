package com.daymark.app;

import com.daymark.persistence.DatabaseManager;
import com.daymark.persistence.SQLiteTaskRepository;
import com.daymark.service.TaskService;
import com.daymark.ui.TaskBrowser;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class DaymarkApplication extends Application {

    private DatabaseManager db;

    @Override
    public void start(Stage stage) {
        db = new DatabaseManager();
        SQLiteTaskRepository repo = new SQLiteTaskRepository(db);
        TaskService service = new TaskService(repo);

        TaskBrowser browser = new TaskBrowser(service);
        Scene scene = new Scene(browser, 900, 600);
        scene.getStylesheets().add(getClass().getResource("/com/daymark/ui/daymark.css").toExternalForm());

        stage.setTitle("Daymark");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        db.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
