package com.daymark.persistence;

import com.daymark.domain.Task;

import java.util.List;

/**
 * What the service layer needs from storage. TaskService only knows about this
 * interface, so the SQLite version can be swapped for something else (a different
 * database, an in-memory list for tests) without touching the service or the UI.
 */
public interface TaskRepository {

    // returns a copy of the task with its new id
    Task insert(Task task);

    void update(Task task);

    // null if there is no task with that id
    Task findById(int id);

    List<Task> findAll();

    void delete(int id);
}
