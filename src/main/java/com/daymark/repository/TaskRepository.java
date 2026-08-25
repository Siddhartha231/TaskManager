package com.daymark.repository;

import com.daymark.domain.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;  //The unique id that is assigned to each task

//An interface to describe the options for the UI
public interface TaskRepository {
    //inserting a task
    Task insert(Task task);
    //updates a task
    Task update(Task task);
    //finds a task its unique id
    Optional<Task> findById(UUID id);
    //returns ALL tasks
    List<Task> findAll();
    //deletes a task by its unique id
    void deleteById(UUID id);
}
