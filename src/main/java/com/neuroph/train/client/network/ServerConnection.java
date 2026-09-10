package com.neuroph.train.client.network;

import com.neuroph.train.client.worker.BenchmarkUtil;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cliente de red TCP / WebSocket: mantiene conexión persistente saliente hacia la VPS,
 * gestiona Heartbeats automáticos y despacho de tareas/respuestas.
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    private volatile String host;
    private volatile int port;
    private final String workerName;
    private volatile int allocatedSlots;

    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private WebSocket webSocket;
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
        setTarget(host, port);
        this.workerName = workerName;
        this.allocatedSlots = initialSlots;
    }

    public synchronized void setTarget(String rawHost, int rawPort) {
        String clean = (rawHost != null) ? rawHost.trim() : "neuroph.aguilucho.ar";
        int cleanPort = rawPort;

        if (clean.startsWith("https://")) {
            clean = clean.substring(8);
            if (cleanPort == 9000 || cleanPort == 80) cleanPort = 443;
        } else if (clean.startsWith("http://")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("wss://")) {
            clean = clean.substring(6);
            if (cleanPort == 9000 || cleanPort == 80) cleanPort = 443;
        } else if (clean.startsWith("ws://")) {
            clean = clean.substring(5);
        }

        if (clean.endsWith("/ws")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }

        if (clean.contains(":")) {
            String[] parts = clean.split(":");
            clean = parts[0];
            try {
                cleanPort = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }

        this.host = clean;
        this.port = cleanPort;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTaskHandler(Consumer<TrainingTask> taskHandler) {
        this.taskHandler = taskHandler;
    }

    private boolean isWebSocketTarget() {
        return port == 443 || host.endsWith(".ar") || host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".org");
    }

    public synchronized void connect() throws IOException {
        if (connected) return;

        if (isWebSocketTarget()) {
            connectWebSocket();
        } else {
            connectTcpSocket();
        }

        connected = true;

        // Registrarse como Worker
        registerAsWorker();

        // Iniciar Heartbeats periódicos (cada 5 segundos)
        startHeartbeatTimer();

        if (listener != null) {
            listener.onConnected();
        }
    }

    private URI buildWebSocketUri() {
        boolean isSsl = (port == 443 || host.endsWith(".ar") || host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".org"));
        String scheme = isSsl ? "wss" : "ws";
        if (isSsl && port == 443) {
            return URI.create("wss://" + host + "/ws");
        } else if (!isSsl && port == 80) {
            return URI.create("ws://" + host + "/ws");
        } else {
            return URI.create(scheme + "://" + host + ":" + port + "/ws");
        }
    }

    private void connectWebSocket() throws IOException {
        URI wsUri = buildWebSocketUri();
        log.info("Conectando vía WebSocket seguro a {}...", wsUri);

        WebSocket.Listener wsListener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String fullJson = buffer.toString();
                    buffer.setLength(0);
                    try {
                        Message msg = JsonUtil.fromJson(fullJson, Message.class);
                        if (msg != null) {
                            processIncomingMessage(msg);
                        }
                    } catch (Exception e) {
                        log.error("Error procesando mensaje WebSocket entrante: {}", e.getMessage());
                    }
                }
                ws.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                disconnect("Conexión WebSocket cerrada (" + statusCode + "): " + reason);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                disconnect("Error en conexión WebSocket: " + error.getMessage());
            }
        };

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            this.webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(wsUri, wsListener)
                    .get(10, TimeUnit.SECONDS);

            log.info("WebSocket conectado exitosamente a {}", wsUri);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("Fallo al conectar WebSocket con " + wsUri + ": " + cause.getMessage(), cause);
        }
    }

    private void connectTcpSocket() throws IOException {
        log.info("Conectando vía Socket TCP a {}:{}...", host, port);
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());

        // Iniciar hilo de lectura TCP
        Thread readerThread = new Thread(this::readLoop, "Client-Receiver-Loop");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void registerAsWorker() throws IOException {
        double benchmarkScore = BenchmarkUtil.runBenchmark();
        log.info("Micro-benchmark de CPU ejecutado: {} MFLOPS", benchmarkScore);

        Map<String, Object> regData = new HashMap<>();
        regData.put("workerName", workerName);
        regData.put("slots", allocatedSlots);
        regData.put("workerId", java.util.UUID.randomUUID().toString());
        regData.put("benchmarkScore", benchmarkScore);

        Message msg = Message.of(MessageType.WORKER_REGISTER, JsonUtil.toJson(regData));
        sendMessage(msg);
    }

    private void startHeartbeatTimer() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (isConnected()) {
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
        if (webSocket != null) {
            String json = JsonUtil.toJson(message);
            webSocket.sendText(json, true);
        } else if (out != null && socket != null && !socket.isClosed()) {
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

    public CompletableFuture<String> createProject(String projectName) {
        Message msg = Message.of(MessageType.ADMIN_CREATE_PROJECT, projectName != null ? projectName : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> listProjects() {
        Message msg = Message.of(MessageType.ADMIN_LIST_PROJECTS, "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> getProjectLeaderboard(String projectId) {
        Message msg = Message.of(MessageType.ADMIN_GET_PROJECT_LEADERBOARD, projectId != null ? projectId : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> getDatasetLeaderboard(String datasetId) {
        Message msg = Message.of(MessageType.ADMIN_GET_DATASET_LEADERBOARD, datasetId != null ? datasetId : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> pauseCampaign() {
        return pauseCampaign(null);
    }

    public CompletableFuture<String> pauseCampaign(String campaignId) {
        Message msg = Message.of(MessageType.ADMIN_PAUSE_CAMPAIGN, campaignId != null ? campaignId : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> resumeCampaign(String campaignId) {
        Message msg = Message.of(MessageType.ADMIN_RESUME_CAMPAIGN, campaignId != null ? campaignId : "");
        return sendRequest(msg).thenApply(Message::getPayload);
    }

    public CompletableFuture<String> stopCampaign() {
        return stopCampaign(null);
    }

    public CompletableFuture<String> stopCampaign(String campaignId) {
        Message msg = Message.of(MessageType.ADMIN_STOP_CAMPAIGN, campaignId != null ? campaignId : "");
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
        return sendRequest(msg).thenApply(resp -> {
            if (resp.getType() == MessageType.ERROR_RESPONSE || resp.getType() != MessageType.SYNC_DATASET_RESPONSE) {
                log.warn("Error o respuesta no esperada en sincronización de dataset: {}", resp.getPayload());
                return null;
            }
            try {
                return JsonUtil.fromJson(resp.getPayload(), DatasetMetadata.class);
            } catch (Exception e) {
                log.error("Error parseando DatasetMetadata: {}", e.getMessage());
                return null;
            }
        });
    }

    public synchronized void disconnect(String reason) {
        if (!connected) return;
        connected = false;

        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Cierre por cliente");
            } catch (Exception ignored) {}
            webSocket = null;
        }

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
        return connected && (webSocket != null || (socket != null && !socket.isClosed()));
    }
}
