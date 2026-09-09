package com.neuroph.train.common.model;

import java.util.UUID;

/**
 * Tarea individual de entrenamiento despachada por el servidor a un worker.
 */
public class TrainingTask {

    private String taskId;
    private String campaignId;
    private String datasetId;
    private NetworkConfig networkConfig;
    private double trainRatio = 0.70;
    private long splitSeed = 42L;
    private int generation = 0;
    private long createdAt;
    private String assignedWorkerId;
    private String assignedWorkerName;

    public TrainingTask() {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
    }

    public TrainingTask(String campaignId, String datasetId, NetworkConfig config, int generation) {
        this();
        this.campaignId = campaignId;
        this.datasetId = datasetId;
        this.networkConfig = config;
        this.generation = generation;
    }

    // Getters y Setters
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public NetworkConfig getNetworkConfig() {
        return networkConfig;
    }

    public void setNetworkConfig(NetworkConfig networkConfig) {
        this.networkConfig = networkConfig;
    }

    public double getTrainRatio() {
        return trainRatio;
    }

    public void setTrainRatio(double trainRatio) {
        this.trainRatio = trainRatio;
    }

    public long getSplitSeed() {
        return splitSeed;
    }

    public void setSplitSeed(long splitSeed) {
        this.splitSeed = splitSeed;
    }

    public int getGeneration() {
        return generation;
    }

    public void setGeneration(int generation) {
        this.generation = generation;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getAssignedWorkerId() {
        return assignedWorkerId;
    }

    public void setAssignedWorkerId(String assignedWorkerId) {
        this.assignedWorkerId = assignedWorkerId;
    }

    public String getAssignedWorkerName() {
        return assignedWorkerName;
    }

    public void setAssignedWorkerName(String assignedWorkerName) {
        this.assignedWorkerName = assignedWorkerName;
    }

    @Override
    public String toString() {
        return "TrainingTask{" +
                "taskId='" + taskId + '\'' +
                ", generation=" + generation +
                ", config=" + (networkConfig != null ? networkConfig.getTopologySummary() : "null") +
                '}';
    }
}
