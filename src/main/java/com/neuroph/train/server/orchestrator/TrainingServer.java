package com.neuroph.train.server.orchestrator;

import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.common.protocol.Message;
import com.neuroph.train.common.protocol.MessageType;
import com.neuroph.train.server.ServerConfig;
import com.neuroph.train.server.heuristic.CampaignOrchestrator;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor principal de sockets TCP: escucha conexiones entrantes y despacha tareas a los workers disponibles.
 */
public class TrainingServer {

    private static final Logger log = LoggerFactory.getLogger(TrainingServer.class);

    private final ServerConfig config;
    private final StorageManager storageManager;
    private final TaskManager taskManager;
    private final WorkerRegistry workerRegistry;
    private final CampaignOrchestrator campaignOrchestrator;

    private ServerSocket serverSocket;
    private final ExecutorService clientThreadPool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public TrainingServer(ServerConfig config,
                          StorageManager storageManager,
                          TaskManager taskManager,
                          WorkerRegistry workerRegistry,
                          CampaignOrchestrator campaignOrchestrator) {
        this.config = config;
        this.storageManager = storageManager;
        this.taskManager = taskManager;
        this.workerRegistry = workerRegistry;
        this.campaignOrchestrator = campaignOrchestrator;

        this.workerRegistry.setListener(new WorkerRegistry.RegistryListener() {
            @Override
            public void onWorkerAvailable() {
                dispatchTasks();
            }

            @Override
            public void onWorkerDisconnected(String workerId, String workerName) {
                dispatchTasks();
            }
        });
    }

    public synchronized void start() throws IOException {
        serverSocket = new ServerSocket(config.getPort());
        running = true;

        log.info("Servidor Orquestador de Neuroph iniciado en el puerto {}", config.getPort());
        log.info("Directorio de almacenamiento: {}", config.getStorageDir().getAbsolutePath());

        Thread acceptThread = new Thread(this::acceptConnections, "TCP-Accept-Loop");
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    private void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);

                ClientHandler handler = new ClientHandler(
                        clientSocket, config, workerRegistry, taskManager,
                        storageManager, campaignOrchestrator, this::dispatchTasks
                );
                clientThreadPool.submit(handler);
            } catch (IOException e) {
                if (running) {
                    log.error("Error en accept(): {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Bucle de despacho concurrente: empareja tareas pendientes de la cola con slots libres en los workers.
     */
    public synchronized void dispatchTasks() {
        if (!campaignOrchestrator.isRunning()) {
            return;
        }

        List<WorkerSession> availableWorkers = workerRegistry.getAvailableWorkers();
        if (availableWorkers.isEmpty() || taskManager.getPendingCount() == 0) {
            return;
        }

        for (WorkerSession worker : availableWorkers) {
            while (worker.hasAvailableSlot()) {
                TrainingTask task = taskManager.pollTask();
                if (task == null) {
                    return; // No hay más tareas pendientes
                }

                worker.incrementBusy();
                taskManager.markRunning(task, worker.getWorkerId(), worker.getWorkerName());

                try {
                    Message msg = Message.of(MessageType.TASK_ASSIGN, JsonUtil.toJson(task));
                    worker.sendMessage(msg);
                    log.info("Despachada tarea [{}] a [{}] (slots en uso: {}/{})",
                            task.getTaskId(), worker.getWorkerName(),
                            worker.getBusySlots(), worker.getAllocatedSlots());
                } catch (IOException e) {
                    log.error("Fallo al enviar tarea {} al worker [{}]: {}",
                            task.getTaskId(), worker.getWorkerName(), e.getMessage());
                    worker.decrementBusy();
                    taskManager.failAndRequeue(task.getTaskId());
                    break;
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        clientThreadPool.shutdownNow();
        workerRegistry.shutdown();
        log.info("Servidor detenido");
    }
}
