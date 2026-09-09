package com.neuroph.train.common.model;

/**
 * Resultado devuelto por un worker tras completar el entrenamiento y evaluación de un modelo.
 */
public class TaskResult {

    private String taskId;
    private String campaignId;
    private String workerId;
    private String workerName;
    private NetworkConfig networkConfig;
    private EvaluationMetrics metrics;
    private String nnetBase64; // Modelo serializado en Base64
    private boolean success;
    private String errorMessage;
    private long completedAt;
    private double fitness;

    public TaskResult() {
        this.completedAt = System.currentTimeMillis();
    }

    public static TaskResult failure(String taskId, String campaignId, String workerName, String error) {
        TaskResult res = new TaskResult();
        res.setTaskId(taskId);
        res.setCampaignId(campaignId);
        res.setWorkerName(workerName);
        res.setSuccess(false);
        res.setErrorMessage(error);
        return res;
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

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public NetworkConfig getNetworkConfig() {
        return networkConfig;
    }

    public void setNetworkConfig(NetworkConfig networkConfig) {
        this.networkConfig = networkConfig;
    }

    public EvaluationMetrics getMetrics() {
        return metrics;
    }

    public void setMetrics(EvaluationMetrics metrics) {
        this.metrics = metrics;
    }

    public String getNnetBase64() {
        return nnetBase64;
    }

    public void setNnetBase64(String nnetBase64) {
        this.nnetBase64 = nnetBase64;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }

    public double getFitness() {
        return fitness;
    }

    public void setFitness(double fitness) {
        this.fitness = fitness;
    }
}
