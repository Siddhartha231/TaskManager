package com.daymark.persistence;

import com.daymark.domain.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TaskRepository that just keeps tasks in a map. Used by the service tests so
 * they run without a database, and it shows the service really doesn't care
 * which implementation it gets.
 */
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private int nextId = 1;

    @Override
    public Task insert(Task task) {
        Task saved = task.withId(nextId++);
        tasks.put(saved.id(), saved);
        return saved;
    }

    @Override
    public void update(Task task) {
        if (tasks.containsKey(task.id())) {
            tasks.put(task.id(), task);
        }
    }

    @Override
    public Task findById(int id) {
        return tasks.get(id);
    }

    @Override
    public List<Task> findAll() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public void delete(int id) {
        tasks.remove(id);
    }
}
