package com.neuroph.train.server;

import com.neuroph.train.server.heuristic.CampaignOrchestrator;
import com.neuroph.train.server.orchestrator.TrainingServer;
import com.neuroph.train.server.orchestrator.WorkerRegistry;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Punto de entrada principal del Servidor Orquestador en la VPS / Dokploy.
 */
public class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) {
        log.info("==================================================");
        log.info("Iniciando Neuroph-Train Orchestrator Server");
        log.info("==================================================");

        ServerConfig config = ServerConfig.load();
        StorageManager storageManager = new StorageManager(config.getStorageDir());
        TaskManager taskManager = new TaskManager();
        WorkerRegistry workerRegistry = new WorkerRegistry(taskManager, config.getHeartbeatTimeoutSeconds());
        CampaignOrchestrator campaignOrchestrator = new CampaignOrchestrator(storageManager, taskManager);

        TrainingServer server = new TrainingServer(
                config, storageManager, taskManager, workerRegistry, campaignOrchestrator
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Cerrando servidor...");
            server.stop();
        }));

        try {
            server.start();
        } catch (IOException e) {
            log.error("Fallo crítico iniciando el servidor: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
