package com.neuroph.train.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Representa un proyecto de investigación o aplicación que agrupa múltiples datasets
 * (por ejemplo: dataset original, variantes con ruido, mutaciones o distintas normalizaciones)
 * y permite comparar modelos entre ellos.
 */
public class Project {

    private String id;
    private String name;
    private long createdAt;
    private List<String> datasetIds = new ArrayList<>();

    public Project() {
        this.id = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
    }

    public Project(String name) {
        this();
        this.name = name;
    }

    public Project(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getDatasetIds() {
        if (datasetIds == null) {
            datasetIds = new ArrayList<>();
        }
        return datasetIds;
    }

    public void setDatasetIds(List<String> datasetIds) {
        this.datasetIds = datasetIds;
    }

    public void addDatasetId(String datasetId) {
        if (datasetIds == null) {
            datasetIds = new ArrayList<>();
        }
        if (!datasetIds.contains(datasetId)) {
            datasetIds.add(datasetId);
        }
    }

    @Override
    public String toString() {
        return name != null && !name.isEmpty() ? name : id;
    }
}
