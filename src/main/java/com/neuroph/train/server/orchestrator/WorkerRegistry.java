package com.neuroph.train.server.orchestrator;

import com.neuroph.train.server.queue.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registro central de workers conectados con monitoreo activo de Heartbeat (Watchdog).
 */
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

    private final Map<String, WorkerSession> workers = new ConcurrentHashMap<>();
    private final TaskManager taskManager;
    private final long heartbeatTimeoutMs;
    private final ScheduledExecutorService watchdogExecutor;

    public interface RegistryListener {
        void onWorkerAvailable();
        void onWorkerDisconnected(String workerId, String workerName);
    }

    private RegistryListener listener;

    public WorkerRegistry(TaskManager taskManager, long heartbeatTimeoutSeconds) {
        this.taskManager = taskManager;
        this.heartbeatTimeoutMs = heartbeatTimeoutSeconds * 1000L;
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Heartbeat-Watchdog");
            t.setDaemon(true);
            return t;
        });

        startWatchdog();
    }

    public void setListener(RegistryListener listener) {
        this.listener = listener;
    }

    private void startWatchdog() {
        watchdogExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            List<String> deadWorkers = new ArrayList<>();

            for (Map.Entry<String, WorkerSession> entry : workers.entrySet()) {
                WorkerSession session = entry.getValue();
                if (now - session.getLastHeartbeat() > heartbeatTimeoutMs) {
                    deadWorkers.add(entry.getKey());
                }
            }

            for (String deadId : deadWorkers) {
                WorkerSession session = workers.remove(deadId);
                if (session != null) {
                    log.warn("Worker [{}] ({}) desconectado por timeout de Heartbeat (inactivo por > {}s)",
                            session.getWorkerName(), deadId, heartbeatTimeoutMs / 1000);
                    taskManager.requeueTasksForWorker(deadId);
                    if (session.getClientHandler() != null) {
                        session.getClientHandler().close();
                    }
                    if (listener != null) {
                        listener.onWorkerDisconnected(deadId, session.getWorkerName());
                    }
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    public void register(WorkerSession session) {
        workers.put(session.getWorkerId(), session);
        log.info("Worker registrado: [{}] ID={} | Slots={}",
                session.getWorkerName(), session.getWorkerId(), session.getAllocatedSlots());
        if (listener != null) {
            listener.onWorkerAvailable();
        }
    }

    public void unregister(String workerId) {
        WorkerSession session = workers.remove(workerId);
        if (session != null) {
            log.info("Worker desregistrado: [{}] ID={}", session.getWorkerName(), workerId);
            taskManager.requeueTasksForWorker(workerId);
            if (listener != null) {
                listener.onWorkerDisconnected(workerId, session.getWorkerName());
            }
        }
    }

    /**
     * Actualización caliente de recursos asignados (Hot Re-allocation).
     */
    public void updateResources(String workerId, int newSlots) {
        WorkerSession session = workers.get(workerId);
        if (session != null) {
            int oldSlots = session.getAllocatedSlots();
            session.setAllocatedSlots(newSlots);
            log.info("Actualización en caliente de recursos para [{}]: {} -> {} slots",
                    session.getWorkerName(), oldSlots, newSlots);
            if (session.hasAvailableSlot() && listener != null) {
                listener.onWorkerAvailable();
            }
        }
    }

    public void recordHeartbeat(String workerId) {
        WorkerSession session = workers.get(workerId);
        if (session != null) {
            session.updateHeartbeat();
        }
    }

    public WorkerSession get(String workerId) {
        return workers.get(workerId);
    }

    public List<WorkerSession> getAvailableWorkers() {
        List<WorkerSession> available = new ArrayList<>();
        for (WorkerSession s : workers.values()) {
            if (s.hasAvailableSlot()) {
                available.add(s);
            }
        }
        return available;
    }

    public List<WorkerSession> getAllWorkers() {
        return new ArrayList<>(workers.values());
    }

    public int getTotalActiveSlots() {
        int sum = 0;
        for (WorkerSession s : workers.values()) {
            sum += s.getAllocatedSlots();
        }
        return sum;
    }

    public int getTotalBusySlots() {
        int sum = 0;
        for (WorkerSession s : workers.values()) {
            sum += s.getBusySlots();
        }
        return sum;
    }

    public void shutdown() {
        watchdogExecutor.shutdownNow();
    }
}
