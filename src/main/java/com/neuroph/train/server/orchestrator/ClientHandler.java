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
    private volatile boolean isWebSocket = false;
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

            // Detectar si la conexión entrante es HTTP / WebSocket
            if (com.neuroph.train.common.protocol.WebSocketFraming.isHttpRequest(in)) {
                com.neuroph.train.common.protocol.WebSocketFraming.HttpRequest req =
                        com.neuroph.train.common.protocol.WebSocketFraming.readHttpRequest(in);
                if (req.isWebSocketUpgrade()) {
                    com.neuroph.train.common.protocol.WebSocketFraming.sendWebSocketHandshakeResponse(out, req.getWebSocketKey());
                    isWebSocket = true;
                    log.info("Conexión WebSocket establecida desde {}", socket.getRemoteSocketAddress());
                } else {
                    // Petición HTTP simple (ej: health check de Dokploy/Traefik o consulta desde navegador)
                    String body = "{\"status\":\"UP\",\"service\":\"Neuroph Training Server\",\"version\":\"1.0.0\"}\n";
                    com.neuroph.train.common.protocol.WebSocketFraming.sendHttpResponse(
                            out, 200, "OK", "application/json; charset=utf-8", body);
                    return;
                }
            }

            while (running && !socket.isClosed()) {
                Message message = isWebSocket ?
                        com.neuroph.train.common.protocol.WebSocketFraming.readWebSocketMessage(in, out) :
                        MessageFraming.readMessage(in);
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

            case ADMIN_RESUME_CAMPAIGN:
                handleResumeCampaign(message);
                break;

            case ADMIN_STOP_CAMPAIGN:
                handleStopCampaign(message);
                break;

            case ADMIN_CREATE_PROJECT:
                handleCreateProject(message);
                break;

            case ADMIN_LIST_PROJECTS:
                handleListProjects(message);
                break;

            case ADMIN_GET_PROJECT_LEADERBOARD:
                handleGetProjectLeaderboard(message);
                break;

            case ADMIN_GET_DATASET_LEADERBOARD:
                handleGetDatasetLeaderboard(message);
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
            sendMessage(Message.responseTo(message, MessageType.AUTH_RESPONSE, "AUTH_OK"));
        } else {
            log.warn("Intento fallido de autenticación de Administrador desde {}", socket.getRemoteSocketAddress());
            sendMessage(Message.responseTo(message, MessageType.AUTH_RESPONSE, "AUTH_FAILED"));
        }
    }

    private void handleWorkerRegister(Message message) throws IOException {
        Map<String, Object> data = JsonUtil.fromJson(message.getPayload(), Map.class);
        String name = (String) data.getOrDefault("workerName", "Worker-" + socket.getPort());
        int slots = ((Number) data.getOrDefault("slots", 2)).intValue();
        String id = (String) data.getOrDefault("workerId", java.util.UUID.randomUUID().toString());
        double benchmarkScore = ((Number) data.getOrDefault("benchmarkScore", 1000.0)).doubleValue();

        this.registeredWorkerId = id;
        WorkerSession session = new WorkerSession(id, name, this, slots, benchmarkScore);
        workerRegistry.register(session);

        Map<String, Object> resp = new HashMap<>();
        resp.put("workerId", id);
        resp.put("status", "REGISTERED");
        sendMessage(Message.responseTo(message, MessageType.WORKER_REGISTER_ACK, JsonUtil.toJson(resp)));

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
        sendMessage(Message.responseTo(message, MessageType.HEARTBEAT_ACK, "ACK"));
    }

    private void handleSyncDataset(Message message) throws IOException {
        String datasetId = message.getPayload();
        DatasetMetadata meta = storageManager.getDataset(datasetId);
        if (meta != null) {
            sendMessage(Message.responseTo(message, MessageType.SYNC_DATASET_RESPONSE, JsonUtil.toJson(meta)));
        } else {
            sendMessage(Message.errorTo(message, "Dataset no encontrado: " + datasetId));
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
            sendMessage(Message.errorTo(message, "No autorizado. Requiere ADMIN_TOKEN"));
            return;
        }

        DatasetMetadata meta = JsonUtil.fromJson(message.getPayload(), DatasetMetadata.class);
        if (meta == null || meta.getCsvContent() == null) {
            sendMessage(Message.errorTo(message, "Dataset o CSV inválido"));
            return;
        }

        storageManager.saveDataset(meta, meta.getCsvContent());
        log.info("Nuevo dataset subido por Admin: [{}] ({} filas, {} entradas, {} salidas)",
                meta.getName(), meta.getNumRows(), meta.getInputCount(), meta.getOutputCount());

        sendMessage(Message.successTo(message, "Dataset " + meta.getId() + " guardado exitosamente"));
    }

    private void handleStartCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.errorTo(message, "No autorizado"));
            return;
        }

        CampaignConfig campaignConfig = JsonUtil.fromJson(message.getPayload(), CampaignConfig.class);
        campaignOrchestrator.startCampaign(campaignConfig);
        triggerDispatch();
        sendMessage(Message.successTo(message, "Campaña iniciada"));
    }

    private void handlePauseCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.errorTo(message, "No autorizado"));
            return;
        }
        String campaignId = (message.getPayload() != null && !message.getPayload().isBlank()) ? message.getPayload().trim() : null;
        campaignOrchestrator.pauseCampaign(campaignId);
        sendMessage(Message.successTo(message, "Campaña pausada"));
    }

    private void handleResumeCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.errorTo(message, "No autorizado"));
            return;
        }
        String campaignId = (message.getPayload() != null && !message.getPayload().isBlank()) ? message.getPayload().trim() : null;
        campaignOrchestrator.resumeCampaign(campaignId);
        triggerDispatch();
        sendMessage(Message.successTo(message, "Campaña reanudada"));
    }

    private void handleStopCampaign(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.errorTo(message, "No autorizado"));
            return;
        }
        String campaignId = (message.getPayload() != null && !message.getPayload().isBlank()) ? message.getPayload().trim() : null;
        campaignOrchestrator.stopCampaign(campaignId);
        sendMessage(Message.successTo(message, "Campaña detenida"));
    }

    private void handleCreateProject(Message message) throws IOException {
        if (!isAdminAuthenticated) {
            sendMessage(Message.errorTo(message, "No autorizado"));
            return;
        }
        String name = message.getPayload();
        if (name == null || name.trim().isEmpty()) {
            sendMessage(Message.errorTo(message, "El nombre del proyecto no puede estar vacío"));
            return;
        }
        com.neuroph.train.common.model.Project proj = storageManager.getOrCreateProject(name.trim());
        log.info("Proyecto registrado o consultado: [{}] ({})", proj.getName(), proj.getId());
        sendMessage(Message.successTo(message, JsonUtil.toJson(proj)));
    }

    private void handleListProjects(Message message) throws IOException {
        List<com.neuroph.train.common.model.Project> list = storageManager.listProjects();
        sendMessage(Message.successTo(message, JsonUtil.toJson(list)));
    }

    private void handleGetProjectLeaderboard(Message message) throws IOException {
        String projectId = (message.getPayload() != null && !message.getPayload().isBlank()) ? message.getPayload().trim() : null;
        List<TaskResult> lb = storageManager.getLeaderboardByProject(projectId);
        sendMessage(Message.successTo(message, JsonUtil.toJson(lb)));
    }

    private void handleGetDatasetLeaderboard(Message message) throws IOException {
        String datasetId = (message.getPayload() != null && !message.getPayload().isBlank()) ? message.getPayload().trim() : null;
        List<TaskResult> lb = storageManager.getLeaderboardByDataset(datasetId);
        sendMessage(Message.successTo(message, JsonUtil.toJson(lb)));
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
        status.put("activeCampaigns", campaignOrchestrator.getActiveCampaigns());
        status.put("projects", storageManager.listProjects());
        status.put("datasets", storageManager.listDatasets());

        sendMessage(Message.successTo(message, JsonUtil.toJson(status)));
    }

    private void handleGetLeaderboard(Message message) throws IOException {
        String campaignId = message.getPayload();
        if (campaignId == null && campaignOrchestrator.getActiveCampaign() != null) {
            campaignId = campaignOrchestrator.getActiveCampaign().getCampaignId();
        }

        List<TaskResult> lb = (campaignId != null) ? storageManager.getLeaderboard(campaignId) : List.of();
        sendMessage(Message.successTo(message, JsonUtil.toJson(lb)));
    }

    private void handleDownloadModel(Message message) throws IOException {
        Map<String, String> req = JsonUtil.fromJson(message.getPayload(), Map.class);
        String campId = req.get("campaignId");
        String taskId = req.get("taskId");

        try {
            byte[] nnetBytes = storageManager.getModelNnetBytes(campId, taskId);
            String b64 = Base64.getEncoder().encodeToString(nnetBytes);
            sendMessage(Message.successTo(message, b64));
        } catch (IOException e) {
            sendMessage(Message.errorTo(message, "No se pudo cargar el archivo .nnet: " + e.getMessage()));
        }
    }

    private void triggerDispatch() {
        if (triggerDispatchCallback != null) {
            triggerDispatchCallback.run();
        }
    }

    public synchronized void sendMessage(Message message) throws IOException {
        if (out != null && !socket.isClosed()) {
            if (isWebSocket) {
                com.neuroph.train.common.protocol.WebSocketFraming.writeWebSocketMessage(out, message);
            } else {
                MessageFraming.writeMessage(out, message);
            }
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
