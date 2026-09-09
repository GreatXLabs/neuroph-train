package com.neuroph.train.server.queue;

import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Gestor de colas de tareas multihilo con soporte de reintento automático, tolerancia a fallos
 * y despacho prioritario por complejidad computacional (Greedy LPT).
 */
public class TaskManager {

    private final Queue<TrainingTask> pendingTasks = new PriorityBlockingQueue<>();
    private final Map<String, TrainingTask> runningTasks = new ConcurrentHashMap<>();
    private final List<TaskResult> completedResults = new CopyOnWriteArrayList<>();

    public void enqueueTasks(List<TrainingTask> tasks) {
        if (tasks != null) {
            for (TrainingTask task : tasks) {
                pendingTasks.offer(task);
            }
        }
    }

    public void enqueueTask(TrainingTask task) {
        if (task != null) {
            pendingTasks.offer(task);
        }
    }

    public TrainingTask pollTask() {
        return pendingTasks.poll();
    }

    public void markRunning(TrainingTask task, String workerId, String workerName) {
        task.setAssignedWorkerId(workerId);
        task.setAssignedWorkerName(workerName);
        runningTasks.put(task.getTaskId(), task);
    }

    public void completeTask(TaskResult result) {
        if (result != null && result.getTaskId() != null) {
            runningTasks.remove(result.getTaskId());
            completedResults.add(result);
        }
    }

    public void failAndRequeue(String taskId) {
        TrainingTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.setAssignedWorkerId(null);
            task.setAssignedWorkerName(null);
            pendingTasks.offer(task);
        }
    }

    /**
     * Re-encola todas las tareas que estaban siendo procesadas por un worker específico (por caída o desconexión).
     */
    public List<TrainingTask> requeueTasksForWorker(String workerId) {
        List<TrainingTask> requeued = new ArrayList<>();
        for (Map.Entry<String, TrainingTask> entry : runningTasks.entrySet()) {
            TrainingTask task = entry.getValue();
            if (workerId.equals(task.getAssignedWorkerId())) {
                runningTasks.remove(entry.getKey());
                task.setAssignedWorkerId(null);
                task.setAssignedWorkerName(null);
                pendingTasks.offer(task);
                requeued.add(task);
            }
        }
        return requeued;
    }

    public int getPendingCount() {
        return pendingTasks.size();
    }

    public int getRunningCount() {
        return runningTasks.size();
    }

    public int getCompletedCount() {
        return completedResults.size();
    }

    public List<TaskResult> getAllResults() {
        return Collections.unmodifiableList(completedResults);
    }

    public List<TaskResult> getResultsForCampaign(String campaignId) {
        List<TaskResult> res = new ArrayList<>();
        for (TaskResult r : completedResults) {
            if (campaignId.equals(r.getCampaignId())) {
                res.add(r);
            }
        }
        return res;
    }

    public void clear() {
        pendingTasks.clear();
        runningTasks.clear();
        completedResults.clear();
    }
}
