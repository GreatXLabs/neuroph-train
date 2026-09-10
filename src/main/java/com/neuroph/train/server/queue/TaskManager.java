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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor de colas de tareas con balanceo equitativo multientrenamiento (Fair-Share Round-Robin)
 * y despacho prioritario por complejidad computacional (Greedy LPT).
 * 
 * Permite que múltiples campañas de distintos datasets compartan el cluster sin monopolización.
 */
public class TaskManager {

    private final Map<String, Queue<TrainingTask>> campaignPendingQueues = new ConcurrentHashMap<>();
    private final List<String> roundRobinCampaignOrder = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    private final Map<String, TrainingTask> runningTasks = new ConcurrentHashMap<>();
    private final List<TaskResult> completedResults = new CopyOnWriteArrayList<>();

    public synchronized void enqueueTasks(List<TrainingTask> tasks) {
        if (tasks != null) {
            for (TrainingTask task : tasks) {
                enqueueTask(task);
            }
        }
    }

    public synchronized void enqueueTask(TrainingTask task) {
        if (task == null) return;
        String campId = task.getCampaignId() != null ? task.getCampaignId() : "default";
        Queue<TrainingTask> q = campaignPendingQueues.computeIfAbsent(campId, k -> {
            if (!roundRobinCampaignOrder.contains(k)) {
                roundRobinCampaignOrder.add(k);
            }
            return new PriorityBlockingQueue<>();
        });
        q.offer(task);
        if (!roundRobinCampaignOrder.contains(campId)) {
            roundRobinCampaignOrder.add(campId);
        }
    }

    /**
     * Extrae la siguiente tarea aplicando Round-Robin entre las campañas activas
     * y Greedy LPT (Longest Processing Time) dentro de la campaña seleccionada.
     */
    public synchronized TrainingTask pollTask() {
        if (roundRobinCampaignOrder.isEmpty()) {
            return null;
        }

        int attempts = roundRobinCampaignOrder.size();
        for (int i = 0; i < attempts; i++) {
            if (roundRobinCampaignOrder.isEmpty()) break;
            int idx = Math.abs(roundRobinIndex.getAndIncrement() % roundRobinCampaignOrder.size());
            String campId = roundRobinCampaignOrder.get(idx);
            Queue<TrainingTask> q = campaignPendingQueues.get(campId);
            if (q != null) {
                TrainingTask task = q.poll();
                if (task != null) {
                    return task;
                } else {
                    campaignPendingQueues.remove(campId);
                    roundRobinCampaignOrder.remove(campId);
                }
            } else {
                roundRobinCampaignOrder.remove(campId);
            }
        }
        return null;
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

    public synchronized void failAndRequeue(String taskId) {
        TrainingTask task = runningTasks.remove(taskId);
        if (task != null) {
            task.setAssignedWorkerId(null);
            task.setAssignedWorkerName(null);
            enqueueTask(task);
        }
    }

    public synchronized List<TrainingTask> requeueTasksForWorker(String workerId) {
        List<TrainingTask> requeued = new ArrayList<>();
        for (Map.Entry<String, TrainingTask> entry : runningTasks.entrySet()) {
            TrainingTask task = entry.getValue();
            if (workerId.equals(task.getAssignedWorkerId())) {
                runningTasks.remove(entry.getKey());
                task.setAssignedWorkerId(null);
                task.setAssignedWorkerName(null);
                enqueueTask(task);
                requeued.add(task);
            }
        }
        return requeued;
    }

    public synchronized void cancelTasksForCampaign(String campaignId) {
        if (campaignId == null) return;
        campaignPendingQueues.remove(campaignId);
        roundRobinCampaignOrder.remove(campaignId);
        runningTasks.entrySet().removeIf(e -> campaignId.equals(e.getValue().getCampaignId()));
    }

    public synchronized int getPendingCount() {
        int total = 0;
        for (Queue<TrainingTask> q : campaignPendingQueues.values()) {
            total += q.size();
        }
        return total;
    }

    public synchronized int getPendingCount(String campaignId) {
        Queue<TrainingTask> q = campaignPendingQueues.get(campaignId);
        return q != null ? q.size() : 0;
    }

    public int getRunningCount() {
        return runningTasks.size();
    }

    public int getRunningCount(String campaignId) {
        int count = 0;
        for (TrainingTask t : runningTasks.values()) {
            if (campaignId != null && campaignId.equals(t.getCampaignId())) {
                count++;
            }
        }
        return count;
    }

    public int getCompletedCount() {
        return completedResults.size();
    }

    public int getCompletedCount(String campaignId) {
        int count = 0;
        for (TaskResult r : completedResults) {
            if (campaignId != null && campaignId.equals(r.getCampaignId())) {
                count++;
            }
        }
        return count;
    }

    public List<TaskResult> getAllResults() {
        return Collections.unmodifiableList(completedResults);
    }

    public List<TaskResult> getResultsForCampaign(String campaignId) {
        List<TaskResult> res = new ArrayList<>();
        for (TaskResult r : completedResults) {
            if (campaignId != null && campaignId.equals(r.getCampaignId())) {
                res.add(r);
            }
        }
        return res;
    }
}
