package com.neuroph.train.client.network;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.common.protocol.Message;
import com.neuroph.train.common.protocol.MessageFraming;
import com.neuroph.train.common.protocol.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cliente de red TCP: mantiene conexión persistente saliente hacia la VPS,
 * gestiona Heartbeats automáticos y despacho de tareas/respuestas.
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    private final String host;
    private final int port;
    private final String workerName;
    private volatile int allocatedSlots;

    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = false;

    private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Client-Heartbeat");
        t.setDaemon(true);
        return t;
    });

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onTaskAssigned(TrainingTask task);
        void onLog(String line);
    }

    private Listener listener;
    private Consumer<TrainingTask> taskHandler;

    public ServerConnection(String host, int port, String workerName, int initialSlots) {
        this.host = host;
        this.port = port;
        this.workerName = workerName;
        this.allocatedSlots = initialSlots;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTaskHandler(Consumer<TrainingTask> taskHandler) {
        this.taskHandler = taskHandler;
    }

    public synchronized void connect() throws IOException {
        if (connected) return;

        log.info("Conectando a {}:{}...", host, port);
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
        connected = true;

        // Iniciar hilo de lectura
        Thread readerThread = new Thread(this::readLoop, "Client-Receiver-Loop");
        readerThread.setDaemon(true);
        readerThread.start();

        // Registrarse como Worker
        registerAsWorker();

        // Iniciar Heartbeats periódicos (cada 5 segundos)
        startHeartbeatTimer();

        if (listener != null) {
            listener.onConnected();
        }
    }

    private void registerAsWorker() throws IOException {
        Map<String, Object> regData = new HashMap<>();
        regData.put("workerName", workerName);
        regData.put("slots", allocatedSlots);
        regData.put("workerId", java.util.UUID.randomUUID().toString());

        Message msg = Message.of(MessageType.WORKER_REGISTER, JsonUtil.toJson(regData));
        sendMessage(msg);
    }

    private void startHeartbeatTimer() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (connected && socket != null && !socket.isClosed()) {
                try {
                    sendMessage(Message.of(MessageType.HEARTBEAT, "PING"));
                } catch (Exception e) {
                    log.warn("Fallo enviando Heartbeat: {}", e.getMessage());
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void readLoop() {
        try {
            while (connected && !socket.isClosed()) {
                Message message = MessageFraming.readMessage(in);
                if (message == null) {
                    break; // Servidor cerró conexión
                }
                processIncomingMessage(message);
            }
        } catch (IOException e) {
            if (connected) {
                log.warn("Conexión perdida con el servidor: {}", e.getMessage());
            }
        } finally {
            disconnect("Conexión con el servidor cerrada");
        }
    }

    private void processIncomingMessage(Message message) {
        // 1. Verificar si corresponde a una solicitud pendiente que esperaba respuesta
        CompletableFuture<Message> future = pendingRequests.remove(message.getId());
        if (future != null) {
            future.complete(message);
            return;
        }

        // Si es una respuesta de éxito/error general que responde a la última petición
        if (message.getType() == MessageType.SUCCESS_RESPONSE || message.getType() == MessageType.ERROR_RESPONSE ||
                message.getType() == MessageType.AUTH_RESPONSE || message.getType() == MessageType.SYNC_DATASET_RESPONSE) {
            for (Map.Entry<String, CompletableFuture<Message>> entry : pendingRequests.entrySet()) {
                entry.getValue().complete(message);
                pendingRequests.remove(entry.getKey());
                return;
            }
        }

        // 2. Procesamiento de mensajes según tipo
        switch (message.getType()) {
            case TASK_ASSIGN:
                TrainingTask task = JsonUtil.fromJson(message.getPayload(), TrainingTask.class);
                if (task != null && taskHandler != null) {
                    taskHandler.accept(task);
                }
                break;

            case HEARTBEAT_ACK:
                // Heartbeat respondido correctamente por el servidor
                break;

            case WORKER_REGISTER_ACK:
                if (listener != null) {
                    listener.onLog("Registrado exitosamente en el Servidor con " + allocatedSlots + " slots.");
                }
                break;

            default:
                log.debug("Mensaje entrante sin handler específico: {}", message.getType());
                break;
        }
    }

    /**
     * Actualización en Caliente de recursos asignados (Hot Resize).
     */
    public synchronized void updateResources(int newSlots) {
        this.allocatedSlots = Math.max(1, newSlots);
        if (connected) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("slots", allocatedSlots);
                sendMessage(Message.of(MessageType.WORKER_UPDATE_RESOURCES, JsonUtil.toJson(data)));
                if (listener != null) {
                    listener.onLog("Recursos actualizados a " + allocatedSlots + " hilos.");
                }
            } catch (IOException e) {
                log.error("Error enviando actualización de recursos: {}", e.getMessage());
            }
        }
    }

    public synchronized void sendTaskResult(TaskResult result) throws IOException {
        Message msg = Message.of(MessageType.TASK_RESULT, JsonUtil.toJson(result));
        sendMessage(msg);
    }

    public synchronized void sendMessage(Message message) throws IOException {
        if (out != null && !socket.isClosed()) {
            MessageFraming.writeMessage(out, message);
        }
    }

    public CompletableFuture<Message> sendRequest(Message message) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        pendingRequests.put(message.getId(), future);
        try {
            sendMessage(message);
        } catch (IOException e) {
            pendingRequests.remove(message.getId());
            future.completeExceptionally(e);
        }
        return future;
    }

    // --- Métodos de Administrador ---

    public CompletableFuture<Boolean> authenticateAdmin(String token) {
        Message msg = Message.of(MessageType.AUTH_REQUEST, token);
        return sendRequest(msg).thenApply(resp -> "AUTH_OK".equals(resp.getPayload()));
    }

    public CompletableFuture<String> uploadDataset(DatasetMetadata meta) {
        Message msg = Message.of(MessageType.ADMIN_UPLOAD_DATASET, JsonUtil.toJson(meta));
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> startCampaign(CampaignConfig config) {
        Message msg = Message.of(MessageType.ADMIN_START_CAMPAIGN, JsonUtil.toJson(config));
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> pauseCampaign() {
        Message msg = Message.of(MessageType.ADMIN_PAUSE_CAMPAIGN, "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> stopCampaign() {
        Message msg = Message.of(MessageType.ADMIN_STOP_CAMPAIGN, "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> getStatus() {
        Message msg = Message.of(MessageType.ADMIN_GET_STATUS, "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> getLeaderboard(String campaignId) {
        Message msg = Message.of(MessageType.ADMIN_GET_LEADERBOARD, campaignId != null ? campaignId : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> downloadModel(String campaignId, String taskId) {
        Map<String, String> req = Map.of("campaignId", campaignId, "taskId", taskId);
        Message msg = Message.of(MessageType.ADMIN_DOWNLOAD_MODEL, JsonUtil.toJson(req));
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<DatasetMetadata> requestDatasetSync(String datasetId) {
        Message msg = Message.of(MessageType.SYNC_DATASET_REQUEST, datasetId);
        return sendRequest(msg).thenApply(resp -> JsonUtil.fromJson(resp.getPayload(), DatasetMetadata.class));
    }

    public synchronized void disconnect(String reason) {
        if (!connected) return;
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}

        for (CompletableFuture<Message> f : pendingRequests.values()) {
            f.cancel(true);
        }
        pendingRequests.clear();

        if (listener != null) {
            listener.onDisconnected(reason);
        }
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }
}
