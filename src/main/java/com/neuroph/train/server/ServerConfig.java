package com.neuroph.train.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuración del servidor cargada desde variables de entorno o archivo .env.
 */
public class ServerConfig {

    private int port = 9000;
    private File storageDir = new File("./storage");
    private String adminToken = "neuroph-admin-secret-2026";
    private int heartbeatTimeoutSeconds = 15;

    public static ServerConfig load() {
        ServerConfig config = new ServerConfig();
        Map<String, String> envMap = loadDotEnv();

        // 1. Puerto
        String pStr = getEnvOrDot(envMap, "SERVER_PORT");
        if (pStr != null) {
            try {
                config.port = Integer.parseInt(pStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        // 2. Directorio de almacenamiento
        String sStr = getEnvOrDot(envMap, "STORAGE_DIR");
        if (sStr != null && !sStr.trim().isEmpty()) {
            config.storageDir = new File(sStr.trim());
        }

        // 3. Token de administración
        String tStr = getEnvOrDot(envMap, "ADMIN_TOKEN");
        if (tStr != null && !tStr.trim().isEmpty()) {
            config.adminToken = tStr.trim();
        }

        // 4. Timeout de Heartbeat
        String hStr = getEnvOrDot(envMap, "HEARTBEAT_TIMEOUT");
        if (hStr != null) {
            try {
                config.heartbeatTimeoutSeconds = Integer.parseInt(hStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        return config;
    }

    private static String getEnvOrDot(Map<String, String> dotEnv, String key) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.trim().isEmpty()) {
            return sysEnv;
        }
        return dotEnv.get(key);
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> map = new HashMap<>();
        File dotEnv = new File(".env");
        if (dotEnv.exists() && dotEnv.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(dotEnv))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                            v = v.substring(1, v.length() - 1);
                        }
                        map.put(k, v);
                    }
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    // Getters
    public int getPort() {
        return port;
    }

    public File getStorageDir() {
        return storageDir;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public int getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }
}
