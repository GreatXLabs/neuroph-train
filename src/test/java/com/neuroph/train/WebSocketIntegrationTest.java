package com.neuroph.train;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.server.ServerConfig;
import com.neuroph.train.server.heuristic.CampaignOrchestrator;
import com.neuroph.train.server.orchestrator.TrainingServer;
import com.neuroph.train.server.orchestrator.WorkerRegistry;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketIntegrationTest {

    private TrainingServer server;
    private ServerConnection client;
    private int testPort;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        }

        ServerConfig serverConfig = new ServerConfig();
        try {
            var fPort = ServerConfig.class.getDeclaredField("port");
            fPort.setAccessible(true);
            fPort.set(serverConfig, testPort);

            var fDir = ServerConfig.class.getDeclaredField("storageDir");
            fDir.setAccessible(true);
            fDir.set(serverConfig, tempDir);

            var fToken = ServerConfig.class.getDeclaredField("adminToken");
            fToken.setAccessible(true);
            fToken.set(serverConfig, "ws-test-token");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        StorageManager sm = new StorageManager(tempDir);
        TaskManager tm = new TaskManager();
        WorkerRegistry wr = new WorkerRegistry(tm, 10);
        CampaignOrchestrator co = new CampaignOrchestrator(sm, tm);

        server = new TrainingServer(serverConfig, sm, tm, wr, co);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect("Fin test");
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testClientServerWebSocketCommunication() throws Exception {
        // Conectar usando URL WebSocket explícita ws://127.0.0.1:port/ws
        client = new ServerConnection("ws://127.0.0.1:" + testPort + "/ws", testPort, "WebSocketWorker", 2);
        client.connect();

        assertTrue(client.isConnected(), "El cliente debe estar conectado vía WebSocket");

        // Autenticar como Admin a través de WebSocket
        boolean authResult = client.authenticateAdmin("ws-test-token").get(5, TimeUnit.SECONDS);
        assertTrue(authResult, "La autenticación de Admin debe ser exitosa vía WebSocket");

        // Verificar consulta de estado a través de WebSocket
        String status = client.getStatus().get(5, TimeUnit.SECONDS);
        assertNotNull(status);
        assertTrue(status.contains("activeWorkers"), "El servidor debe responder con el estado del cluster conteniendo activeWorkers");
    }

    @Test
    void testHttpHealthCheck() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + testPort + "/"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("UP"));
        assertTrue(resp.body().contains("Neuroph Training Server"));
    }
}
