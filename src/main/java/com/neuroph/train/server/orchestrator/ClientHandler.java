package com.neuroph.train.server.orchestrator;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.common.protocol.Message;
import com.neuroph.train.common.protocol.MessageFraming;
import com.neuroph.train.common.protocol.MessageType;
import com.neuroph.train.server.ServerConfig;
import com.neuroph.train.server.heuristic.CampaignOrchestrator;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manejador individual de la conexión de un cliente (Worker o Admin) a través de TCP.
 */
public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final ServerConfig config;
    private final WorkerRegistry workerRegistry;
    private final TaskManager taskManager;
    private final StorageManager storageManager;
    private final CampaignOrchestrator campaignOrchestrator;
    private final Runnable triggerDispatchCallback;

    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean running = true;
    private String registeredWorkerId;
    private boolean isAdminAuthenticated = false;

    public ClientHandler(Socket socket,
                         ServerConfig config,
                         WorkerRegistry workerRegistry,
                         TaskManager taskManager,
                         StorageManager storageManager,
                         CampaignOrchestrator campaignOrchestrator,
                         Runnable triggerDispatchCallback) {
        this.socket = socket;
        this.config = config;
        this.workerRegistry = workerRegistry;
        this.taskManager = taskManager;
        this.storageManager = storageManager;
        this.campaignOrchestrator = campaignOrchestrator;
        this.triggerDispatchCallback = triggerDispatchCallback;
    }

    @Override
    public void run() {
        try {
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            while (running && !socket.isClosed()) {
                Message message = MessageFraming.readMessage(in);
                if (message == null) {
                    break; // EOF
                }
                handleIncomingMessage(message);
            }
        } catch (IOException e) {
            if (running) {
                log.debug("Socket cerrado para cliente {}: {}", registeredWorkerId, e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void handleIncomingMessage(Message message) throws IOException {
        switch (message.getType()) {
            case AUTH_REQUEST:
                handleAuth(message);
                break;

            case WORKER_REGISTER:
                handleWorkerRegister(message);
                break;

            case WORKER_UPDATE_RESOURCES:
                handleWorkerUpdateResources(message);
                break;

            case HEARTBEAT:
                handleHeartbeat(message);
                break;

            case SYNC_DATASET_REQUEST:
                handleSyncDataset(message);
                break;

            case TASK_RESULT:
                handleTaskResult(message);
                break;

            case ADMIN_UPLOAD_DATASET:
                handleUploadDataset(message);
                break;

            case ADMIN_START_CAMPAIGN:
                handleStartCampaign(message);
                break;

            case ADMIN_PAUSE_CAMPAIGN:
                handlePauseCampaign(message);
                break;

            case ADMIN_STOP_CAMPAIGN:
                handleStopCampaign(message);
                break;

            case ADMIN_GET_STATUS:
                handleGetStatus(message);
                break;

            case ADMIN_GET_LEADERBOARD:
                handleGetLeaderboard(message);
                break;

            case ADMIN_DOWNLOAD_MODEL:
                handleDownloadModel(message);
                break;

            default:
                log.warn("Mensaje no reconocido: {}", message.getType());
                sendMessage(Message.error("Tipo de mensaje no reconocido: " + message.getType()));
                break;
        }
    }

    private void handleAuth(Message message) throws IOException {
        String token = message.getPayload();
        if (config.getAdminToken().equals(token)) {
            isAdminAuthenticated = true;
            log.info("Cliente autenticado exitosamente como Administrador desde {}", socket.getRemoteSocketAddress());
            sendMessage(Message.of(MessageType.AUTH_RESPONSE, "AUTH_OK"));
        } else {
            log.warn("Intento fallido de autenticación de Administrador desde {}", socket.getRemoteSocketAddress());
            sendMessage(Message.of(MessageType.AUTH_RESPONSE, "AUTH_FAILED"));
        }
    }

    private void handleWorkerRegister(Message message) throws IOException {
        Map<String, Object> data = JsonUtil.fromJson(message.getPayload(), Map.class);
        String name = (String) data.getOrDefault("workerName", "Worker-" + socket.getPort());
        int slots = ((Number) data.getOrDefault("slots", 2)).intValue();
        String id = (String) data.getOrDefault("workerId", java.util.UUID.randomUUID().toString());

        this.registeredWorkerId = id;
        WorkerSession session = new WorkerSession(id, name, this, slots);
        workerRegistry.register(session);

        Map<String, Object> resp = new HashMap<>();
        resp.put("workerId", id);
        resp.put("status", "REGISTERED");
        sendMessage(Message.of(MessageType.WORKER_REGISTER_ACK, JsonUtil.toJson(resp)));

        triggerDispatch();
    }

    private void handleWorkerUpdateResources(Message message) {
        if (registeredWorkerId != null) {
            Map<String, Object> data = JsonUtil.fromJson(message.getPayload(), Map.class);
            int newSlots = ((Number) data.getOrDefault("slots", 2)).intValue();
            workerRegistry.updateResources(registeredWorkerId, newSlots);
            triggerDispatch();
        }
    }

    private void handleHeartbeat(Message message) throws IOException {
        if (registeredWorkerId != null) {
            workerRegistry.recordHeartbeat(registeredWorkerId);
        }
        sendMessage(Message.of(MessageType.HEARTBEAT_ACK, "ACK"));
    }

    private void handleSyncDataset(Message message) throws IOException {
        String datasetId = message.getPayload();
        DatasetMetadata meta = storageManager.getDataset(datasetId);
        if (meta != null) {
            sendMessage(Message.of(MessageType.SYNC_DATASET_RESPONSE, JsonUtil.toJson(meta)));
        } else {
            sendMessage(Message.error("Dataset no encontrado: " + datasetId));
        }
    }

    private void handleTaskResult(Message message) {
        TaskResult result = JsonUtil.fromJson(message.getPayload(), TaskResult.class);
        if (result != null) {
            if (registeredWorkerId != null) {
                WorkerSession session = workerRegistry.get(registeredWorkerId);
                if (session != null) {
                    session.decrementBusy();
                }
            }
            taskManager.completeTask(result);
            campaignOrchestrator.onTaskCompleted(result);
            triggerDispatch();
        }
    }

    private void handleUploadDataset(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.error("No autorizado. Requiere ADMIN_TOKEN"));
            return;
        }

        DatasetMetadata meta = JsonUtil.fromJson(message.getPayload(), DatasetMetadata.class);
        if (meta == null || meta.getCsvContent() == null) {
            sendMessage(Message.error("Dataset o CSV inválido"));
            return;
        }

        storageManager.saveDataset(meta, meta.getCsvContent());
        log.info("Nuevo dataset subido por Admin: [{}] ({} filas, {} entradas, {} salidas)",
                meta.getName(), meta.getNumRows(), meta.getInputCount(), meta.getOutputCount());

        sendMessage(Message.success("Dataset " + meta.getId() + " guardado exitosamente"));
    }

    private void handleStartCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.error("No autorizado"));
            return;
        }

        CampaignConfig campaignConfig = JsonUtil.fromJson(message.getPayload(), CampaignConfig.class);
        campaignOrchestrator.startCampaign(campaignConfig);
        triggerDispatch();
        sendMessage(Message.success("Campaña iniciada"));
    }

    private void handlePauseCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.error("No autorizado"));
            return;
        }
        campaignOrchestrator.pauseCampaign();
        sendMessage(Message.success("Campaña pausada"));
    }

    private void handleStopCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.error("No autorizado"));
            return;
        }
        campaignOrchestrator.stopCampaign();
        sendMessage(Message.success("Campaña detenida"));
    }

    private void handleGetStatus(Message message) throws IOException {
        Map<String, Object> status = new HashMap<>();
        status.put("activeWorkers", workerRegistry.getAllWorkers().size());
        status.put("totalSlots", workerRegistry.getTotalActiveSlots());
        status.put("busySlots", workerRegistry.getTotalBusySlots());
        status.put("pendingTasks", taskManager.getPendingCount());
        status.put("runningTasks", taskManager.getRunningCount());
        status.put("completedTasks", taskManager.getCompletedCount());
        status.put("activeCampaign", campaignOrchestrator.getActiveCampaign());
        status.put("datasets", storageManager.listDatasets());

        sendMessage(Message.of(MessageType.SUCCESS_RESPONSE, JsonUtil.toJson(status)));
    }

    private void handleGetLeaderboard(Message message) throws IOException {
        String campaignId = message.getPayload();
        if (campaignId == null && campaignOrchestrator.getActiveCampaign() != null) {
            campaignId = campaignOrchestrator.getActiveCampaign().getCampaignId();
        }

        List<TaskResult> lb = (campaignId != null) ? storageManager.getLeaderboard(campaignId) : List.of();
        sendMessage(Message.of(MessageType.SUCCESS_RESPONSE, JsonUtil.toJson(lb)));
    }

    private void handleDownloadModel(Message message) throws IOException {
        Map<String, String> req = JsonUtil.fromJson(message.getPayload(), Map.class);
        String campId = req.get("campaignId");
        String taskId = req.get("taskId");

        try {
            byte[] nnetBytes = storageManager.getModelNnetBytes(campId, taskId);
            String b64 = Base64.getEncoder().encodeToString(nnetBytes);
            sendMessage(Message.of(MessageType.SUCCESS_RESPONSE, b64));
        } catch (IOException e) {
            sendMessage(Message.error("No se pudo cargar el archivo .nnet: " + e.getMessage()));
        }
    }

    private void triggerDispatch() {
        if (triggerDispatchCallback != null) {
            triggerDispatchCallback.run();
        }
    }

    public synchronized void sendMessage(Message message) throws IOException {
        if (out != null && !socket.isClosed()) {
            MessageFraming.writeMessage(out, message);
        }
    }

    public synchronized void close() {
        running = false;
        if (registeredWorkerId != null) {
            workerRegistry.unregister(registeredWorkerId);
            registeredWorkerId = null;
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
