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

    private String serverHost = "neuroph.aguilucho.ar";
    private int serverPort = 443;
    private String workerName = "Alumno-PC";
    private int allocatedCores = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    private static final String CONFIG_FILE = "client.properties";

    public static ClientConfig load() {
        ClientConfig config = new ClientConfig();
        File file = new File(CONFIG_FILE);

        if (file.exists() && file.isFile()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
                config.setServerHost(props.getProperty("server.host", config.serverHost));
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
        if (serverHost != null) {
            String clean = serverHost.trim();
            if (clean.startsWith("https://")) clean = clean.substring(8);
            else if (clean.startsWith("http://")) clean = clean.substring(7);
            else if (clean.startsWith("wss://")) clean = clean.substring(6);
            else if (clean.startsWith("ws://")) clean = clean.substring(5);
            if (clean.endsWith("/ws")) clean = clean.substring(0, clean.length() - 3);
            while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            if (clean.isEmpty()) {
                clean = "neuroph.aguilucho.ar";
            }
            this.serverHost = clean;
        } else {
            this.serverHost = "neuroph.aguilucho.ar";
        }
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
