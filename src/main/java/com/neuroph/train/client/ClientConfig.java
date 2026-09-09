package com.neuroph.train.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuración local del cliente leída de client.properties o creada con valores por defecto.
 */
public class ClientConfig {

    private String serverHost = "127.0.0.1";
    private int serverPort = 9000;
    private String workerName = System.getProperty("user.name", "Alumno") + "-PC";
    private int allocatedCores = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    private static final String CONFIG_FILE = "client.properties";

    public static ClientConfig load() {
        ClientConfig config = new ClientConfig();
        File file = new File(CONFIG_FILE);

        if (file.exists() && file.isFile()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
                config.serverHost = props.getProperty("server.host", config.serverHost);
                config.serverPort = Integer.parseInt(props.getProperty("server.port", String.valueOf(config.serverPort)));
                config.workerName = props.getProperty("worker.name", config.workerName);
                config.allocatedCores = Integer.parseInt(props.getProperty("worker.cores", String.valueOf(config.allocatedCores)));
            } catch (Exception ignored) {}
        } else {
            config.save(); // Generar archivo inicial por defecto
        }

        return config;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("server.host", serverHost);
        props.setProperty("server.port", String.valueOf(serverPort));
        props.setProperty("worker.name", workerName);
        props.setProperty("worker.cores", String.valueOf(allocatedCores));

        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "Configuración del Cliente de Entrenamiento Neuroph-Train");
        } catch (IOException ignored) {}
    }

    public String getServerHost() {
        return serverHost;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public int getAllocatedCores() {
        return allocatedCores;
    }

    public void setAllocatedCores(int allocatedCores) {
        this.allocatedCores = allocatedCores;
    }
}
