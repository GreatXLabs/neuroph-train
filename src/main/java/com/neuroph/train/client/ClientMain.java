package com.neuroph.train.client;

import com.formdev.flatlaf.FlatDarkLaf;
import com.neuroph.train.client.gui.ClientMainWindow;
import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.client.worker.WorkerEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.util.Arrays;

/**
 * Punto de entrada del cliente Neuroph-Train.
 * Soporta modo gráfico Swing y modo Headless (CLI) para servidores o terminales.
 */
public class ClientMain {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    public static void main(String[] args) {
        boolean headless = false;
        String cliHost = null;
        Integer cliPort = null;
        Integer cliCores = null;
        String cliName = null;

        if (args != null) {
            for (String arg : args) {
                if ("--headless".equalsIgnoreCase(arg) || "-headless".equalsIgnoreCase(arg)) {
                    headless = true;
                } else if (arg.startsWith("--host=")) {
                    cliHost = arg.substring(7);
                } else if (arg.startsWith("--port=")) {
                    cliPort = Integer.parseInt(arg.substring(7));
                } else if (arg.startsWith("--cores=")) {
                    cliCores = Integer.parseInt(arg.substring(8));
                } else if (arg.startsWith("--name=")) {
                    cliName = arg.substring(7);
                }
            }
        }

        ClientConfig config = ClientConfig.load();
        if (cliHost != null) config.setServerHost(cliHost);
        if (cliPort != null) config.setServerPort(cliPort);
        if (cliCores != null) config.setAllocatedCores(cliCores);
        if (cliName != null) config.setWorkerName(cliName);

        if (headless) {
            runHeadless(config);
        } else {
            runGui(config);
        }
    }

    private static void runHeadless(ClientConfig config) {
        log.info("Iniciando Worker en modo HEADLESS (Consola)...");
        log.info("Servidor: {}:{} | Worker: [{}] | Cores: {}",
                config.getServerHost(), config.getServerPort(), config.getWorkerName(), config.getAllocatedCores());

        ServerConnection connection = new ServerConnection(
                config.getServerHost(),
                config.getServerPort(),
                config.getWorkerName(),
                config.getAllocatedCores()
        );

        WorkerEngine engine = new WorkerEngine(connection, config.getAllocatedCores());

        connection.setListener(new ServerConnection.Listener() {
            @Override
            public void onConnected() {
                log.info("¡Conectado exitosamente al Servidor Orquestador!");
            }

            @Override
            public void onDisconnected(String reason) {
                log.warn("Desconectado del servidor: {}", reason);
            }

            @Override
            public void onTaskAssigned(com.neuroph.train.common.model.TrainingTask task) {
                log.info("Tarea recibida: [{}] Topología: {}",
                        task.getTaskId(), task.getNetworkConfig().getTopologySummary());
            }

            @Override
            public void onLog(String line) {
                log.info("[Server] {}", line);
            }
        });

        engine.setListener(new WorkerEngine.WorkerListener() {
            @Override
            public void onTaskStarted(String taskId, String topology) {
                log.info("Entrenando tarea [{}]...", taskId);
            }

            @Override
            public void onTaskProgress(String taskId, int epoch, double error) {
                if (epoch % 100 == 0) {
                    log.debug("Tarea [{}] Época {} | Error: {}", taskId, epoch, error);
                }
            }

            @Override
            public void onTaskFinished(String taskId, com.neuroph.train.common.model.EvaluationMetrics metrics, boolean success) {
                if (success) {
                    log.info("Tarea [{}] finalizada con éxito. {}", taskId, metrics);
                } else {
                    log.error("Fallo en tarea [{}]", taskId);
                }
            }

            @Override
            public void onLog(String message) {
                log.info("[Worker] {}", message);
            }
        });

        try {
            connection.connect();
        } catch (IOException e) {
            log.error("Error conectando al servidor: {}", e.getMessage());
            System.exit(1);
        }

        // Mantener hilo vivo
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException ignored) {}
        }
    }

    private static void runGui(ClientConfig config) {
        // Configurar tema moderno FlatLaf
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            log.warn("No se pudo inicializar FlatLaf, usando tema del sistema: {}", e.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
        }

        SwingUtilities.invokeLater(() -> {
            ServerConnection connection = new ServerConnection(
                    config.getServerHost(),
                    config.getServerPort(),
                    config.getWorkerName(),
                    config.getAllocatedCores()
            );

            WorkerEngine engine = new WorkerEngine(connection, config.getAllocatedCores());

            ClientMainWindow window = new ClientMainWindow(config, connection, engine);
            window.setVisible(true);

            log.info("Cliente Neuroph-Train iniciado en modo GUI.");
        });
    }
}
