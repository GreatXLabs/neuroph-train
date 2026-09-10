package com.neuroph.train.common.model;

import java.util.UUID;

/**
 * Tarea individual de entrenamiento despachada por el servidor a un worker.
 */
public class TrainingTask implements Comparable<TrainingTask> {

    private String taskId;
    private String campaignId;
    private String projectId = "default-project";
    private String datasetId;
    private String datasetName;
    private NetworkConfig networkConfig;
    private double trainRatio = 0.70;
    private long splitSeed = 42L;
    private int generation = 0;
    private long createdAt;
    private String assignedWorkerId;
    private String assignedWorkerName;

    // Early Stopping y Data Augmentation
    private int patience = 80;
    private boolean enableAugmentation = false;
    private int augmentationFactor = 1;
    private double augmentationNoise = 0.03;

    // Estimación computacional para Despacho Inteligente (FLOPs aproximados)
    private double estimatedComplexity = 0.0;

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

    public String getProjectId() {
        return projectId != null ? projectId : "default-project";
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getDatasetName() {
        return datasetName;
    }

    public void setDatasetName(String datasetName) {
        this.datasetName = datasetName;
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

    public int getPatience() {
        return patience;
    }

    public void setPatience(int patience) {
        this.patience = patience;
    }

    public boolean isEnableAugmentation() {
        return enableAugmentation;
    }

    public void setEnableAugmentation(boolean enableAugmentation) {
        this.enableAugmentation = enableAugmentation;
    }

    public int getAugmentationFactor() {
        return augmentationFactor;
    }

    public void setAugmentationFactor(int augmentationFactor) {
        this.augmentationFactor = augmentationFactor;
    }

    public double getAugmentationNoise() {
        return augmentationNoise;
    }

    public void setAugmentationNoise(double augmentationNoise) {
        this.augmentationNoise = augmentationNoise;
    }

    public double getEstimatedComplexity() {
        return estimatedComplexity;
    }

    public void setEstimatedComplexity(double estimatedComplexity) {
        this.estimatedComplexity = estimatedComplexity;
    }

    @Override
    public int compareTo(TrainingTask o) {
        if (o == null) return -1;
        // Orden descendente por coste computacional estimado (LPT)
        int c = Double.compare(o.estimatedComplexity, this.estimatedComplexity);
        if (c != 0) return c;
        // En caso de empate, orden de llegada FIFO
        return Long.compare(this.createdAt, o.createdAt);
    }

    @Override
    public String toString() {
        return "TrainingTask{" +
                "taskId='" + taskId + '\'' +
                ", generation=" + generation +
                ", complexity=" + String.format("%.0f", estimatedComplexity) +
                ", config=" + (networkConfig != null ? networkConfig.getTopologySummary() : "null") +
                '}';
    }
}
