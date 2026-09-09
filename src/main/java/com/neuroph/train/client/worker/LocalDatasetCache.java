package com.neuroph.train.client.worker;

import com.neuroph.train.common.model.DatasetMetadata;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache local de datasets en el cliente para evitar descargas repetidas de datos en cada tarea.
 */
public class LocalDatasetCache {

    private final Map<String, DatasetMetadata> memoryCache = new ConcurrentHashMap<>();
    private final File cacheDir;

    public LocalDatasetCache() {
        this.cacheDir = new File("./client-cache/datasets");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public DatasetMetadata get(String datasetId) {
        return memoryCache.get(datasetId);
    }

    public void put(String datasetId, DatasetMetadata metadata) {
        if (datasetId != null && metadata != null) {
            memoryCache.put(datasetId, metadata);
        }
    }

    public boolean contains(String datasetId) {
        return memoryCache.containsKey(datasetId);
    }

    public void clear() {
        memoryCache.clear();
    }
}
