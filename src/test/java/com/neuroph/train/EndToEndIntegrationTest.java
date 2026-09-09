package com.neuroph.train;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.client.worker.WorkerEngine;
import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.HeuristicType;
import com.neuroph.train.common.model.NormalizationType;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TaskType;
import com.neuroph.train.common.protocol.JsonUtil;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class EndToEndIntegrationTest {

    private int testPort;
    private TrainingServer server;
    private ServerConnection clientConnection;
    private WorkerEngine workerEngine;
    private File storageDir;

    @BeforeEach
    public void setup(@TempDir File tempDir) throws IOException {
        this.storageDir = tempDir;

        // Encontrar puerto libre
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        }

        ServerConfig serverConfig = new ServerConfig();
        // Usar reflection o setters
        try {
            var fPort = ServerConfig.class.getDeclaredField("port");
            fPort.setAccessible(true);
            fPort.set(serverConfig, testPort);

            var fDir = ServerConfig.class.getDeclaredField("storageDir");
            fDir.setAccessible(true);
            fDir.set(serverConfig, storageDir);

            var fToken = ServerConfig.class.getDeclaredField("adminToken");
            fToken.setAccessible(true);
            fToken.set(serverConfig, "test-token-123");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        StorageManager sm = new StorageManager(storageDir);
        TaskManager tm = new TaskManager();
        WorkerRegistry wr = new WorkerRegistry(tm, 10);
        CampaignOrchestrator co = new CampaignOrchestrator(sm, tm);

        server = new TrainingServer(serverConfig, sm, tm, wr, co);
        server.start();

        clientConnection = new ServerConnection("127.0.0.1", testPort, "IntegrationWorker", 2);
        workerEngine = new WorkerEngine(clientConnection, 2);
    }

    @AfterEach
    public void tearDown() {
        if (clientConnection != null) {
            clientConnection.disconnect("Fin del test");
        }
        if (workerEngine != null) {
            workerEngine.shutdown();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testFullDistributedTrainingPipeline() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch taskCompletedLatch = new CountDownLatch(1);

        clientConnection.setListener(new ServerConnection.Listener() {
            @Override
            public void onConnected() {
                connectedLatch.countDown();
            }

            @Override
            public void onDisconnected(String reason) {}

            @Override
            public void onTaskAssigned(com.neuroph.train.common.model.TrainingTask task) {}

            @Override
            public void onLog(String line) {}
        });

        workerEngine.setListener(new WorkerEngine.WorkerListener() {
            @Override
            public void onTaskStarted(String taskId, String topology) {}

            @Override
            public void onTaskProgress(String taskId, int epoch, double error) {}

            @Override
            public void onTaskFinished(String taskId, com.neuroph.train.common.model.EvaluationMetrics metrics, boolean success) {
                if (success) {
                    taskCompletedLatch.countDown();
                }
            }

            @Override
            public void onLog(String message) {}
        });

        // 1. Conectar cliente
        clientConnection.connect();
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "El cliente debió conectarse en < 5s");

        // 2. Autenticar Admin
        Boolean authResult = clientConnection.authenticateAdmin("test-token-123").get(5, TimeUnit.SECONDS);
        assertTrue(authResult, "La autenticación de admin debe ser exitosa");

        // 3. Subir Dataset sintético
        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("ds-integration");
        meta.setName("Test Sensors");
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setInputColumns(List.of(0, 1, 2));
        meta.setOutputColumns(List.of(3));
        meta.setClassLabels(List.of("LOBITO", "MURO", "OBSTACULO", "SALIDA"));
        meta.setNormalization(NormalizationType.MIN_MAX_0_1);

        // CSV sintético mínimo
        String csv = "d1,d2,d3,label\n" +
                "50,15,50,LOBITO\n" +
                "52,14,51,LOBITO\n" +
                "20,20,20,MURO\n" +
                "21,19,20,MURO\n" +
                "15,75,80,OBSTACULO\n" +
                "14,70,85,OBSTACULO\n" +
                "150,160,150,SALIDA\n" +
                "155,165,152,SALIDA\n";
        meta.setCsvContent(csv);

        String uploadResp = clientConnection.uploadDataset(meta).get(5, TimeUnit.SECONDS);
        assertNotNull(uploadResp);

        // 4. Iniciar Campaña con Búsqueda Guiada
        CampaignConfig config = new CampaignConfig();
        config.setCampaignId("camp-test");
        config.setName("Campaña de Prueba");
        config.setDatasetId("ds-integration");
        config.setHeuristicType(HeuristicType.GUIDED);
        config.setMinHiddenLayers(1);
        config.setMaxHiddenLayers(1);
        config.setMinNeuronsPerLayer(4);
        config.setMaxNeuronsPerLayer(8);
        config.setMaxIterations(50); // Pocas épocas para test rápido
        config.setMaxTotalTasks(2);
        config.setPopulationOrBatchSize(2);

        String startResp = clientConnection.startCampaign(config).get(5, TimeUnit.SECONDS);
        assertNotNull(startResp);

        // 5. Esperar que el worker entrene y complete al menos una tarea
        boolean completed = taskCompletedLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "El worker debió completar al menos una tarea en < 20s");

        // 6. Consultar Leaderboard y verificar métricas
        String lbJson = clientConnection.getLeaderboard("camp-test").get(5, TimeUnit.SECONDS);
        TaskResult[] results = JsonUtil.fromJson(lbJson, TaskResult[].class);
        assertNotNull(results);
        assertTrue(results.length > 0, "El leaderboard debe contener resultados");

        TaskResult first = results[0];
        assertNotNull(first.getMetrics(), "El modelo debe contener métricas completas");
        assertTrue(first.getMetrics().getAccuracy() >= 0.0);
        assertTrue(first.getMetrics().getRmse() >= 0.0);
        assertEquals(50, first.getMetrics().getIteraciones());

        // 7. Descargar el archivo .nnet desde el servidor
        String nnetB64 = clientConnection.downloadModel("camp-test", first.getTaskId()).get(5, TimeUnit.SECONDS);
        assertNotNull(nnetB64);
        assertFalse(nnetB64.isEmpty(), "El archivo .nnet no debe estar vacío");

        // 8. Test de reasignación en caliente de recursos
        workerEngine.setAllocatedCores(4);
        assertEquals(4, workerEngine.getAllocatedCores());
    }
}
