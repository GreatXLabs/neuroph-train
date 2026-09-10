package com.neuroph.train.common.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Configuración completa de una campaña de entrenamiento y búsqueda heurística.
 */
public class CampaignConfig {

    private String campaignId;
    private String name;
    private String projectId = "default-project";
    private String datasetId;
    private String datasetName;
    private HeuristicType heuristicType = HeuristicType.GUIDED;

    // Rango de búsqueda de topología
    private int minHiddenLayers = 1;
    private int maxHiddenLayers = 2;
    private int minNeuronsPerLayer = 4;
    private int maxNeuronsPerLayer = 32;
    private List<String> allowedTransferFunctions = new ArrayList<>(Arrays.asList("SIGMOID", "TANH"));

    // Rango de hiperparámetros
    private double learningRateMin = 0.05;
    private double learningRateMax = 0.30;
    private double momentumMin = 0.10;
    private double momentumMax = 0.70;

    // Criterios de entrenamiento, parada y Early Stopping
    private int maxIterations = 1000;
    private double targetError = 0.01;
    private int patience = 80; // Épocas sin mejora para Early Stopping generoso
    private int populationOrBatchSize = 8;
    private int maxTotalTasks = 40;
    private double trainTestRatio = 0.70;
    private long splitSeed = 42L;

    // Data Augmentation (Generación de datos sintéticos con ruido de sensores)
    private boolean enableAugmentation = false;
    private int augmentationFactor = 1; // Cantidad de copias sintéticas por muestra
    private double augmentationNoise = 0.03; // Nivel de ruido gaussiano (3%)

    private long createdAt;
    private String status = "PENDING"; // PENDING, RUNNING, PAUSED, COMPLETED

    public CampaignConfig() {
        this.campaignId = UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
    }

    // Getters y Setters
    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public HeuristicType getHeuristicType() {
        return heuristicType;
    }

    public void setHeuristicType(HeuristicType heuristicType) {
        this.heuristicType = heuristicType;
    }

    public int getMinHiddenLayers() {
        return minHiddenLayers;
    }

    public void setMinHiddenLayers(int minHiddenLayers) {
        this.minHiddenLayers = minHiddenLayers;
    }

    public int getMaxHiddenLayers() {
        return maxHiddenLayers;
    }

    public void setMaxHiddenLayers(int maxHiddenLayers) {
        this.maxHiddenLayers = maxHiddenLayers;
    }

    public int getMinNeuronsPerLayer() {
        return minNeuronsPerLayer;
    }

    public void setMinNeuronsPerLayer(int minNeuronsPerLayer) {
        this.minNeuronsPerLayer = minNeuronsPerLayer;
    }

    public int getMaxNeuronsPerLayer() {
        return maxNeuronsPerLayer;
    }

    public void setMaxNeuronsPerLayer(int maxNeuronsPerLayer) {
        this.maxNeuronsPerLayer = maxNeuronsPerLayer;
    }

    public List<String> getAllowedTransferFunctions() {
        return allowedTransferFunctions;
    }

    public void setAllowedTransferFunctions(List<String> allowedTransferFunctions) {
        this.allowedTransferFunctions = allowedTransferFunctions;
    }

    public double getLearningRateMin() {
        return learningRateMin;
    }

    public void setLearningRateMin(double learningRateMin) {
        this.learningRateMin = learningRateMin;
    }

    public double getLearningRateMax() {
        return learningRateMax;
    }

    public void setLearningRateMax(double learningRateMax) {
        this.learningRateMax = learningRateMax;
    }

    public double getMomentumMin() {
        return momentumMin;
    }

    public void setMomentumMin(double momentumMin) {
        this.momentumMin = momentumMin;
    }

    public double getMomentumMax() {
        return momentumMax;
    }

    public void setMomentumMax(double momentumMax) {
        this.momentumMax = momentumMax;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public double getTargetError() {
        return targetError;
    }

    public void setTargetError(double targetError) {
        this.targetError = targetError;
    }

    public int getPatience() {
        return patience;
    }

    public void setPatience(int patience) {
        this.patience = patience;
    }

    public int getPopulationOrBatchSize() {
        return populationOrBatchSize;
    }

    public void setPopulationOrBatchSize(int populationOrBatchSize) {
        this.populationOrBatchSize = populationOrBatchSize;
    }

    public int getMaxTotalTasks() {
        return maxTotalTasks;
    }

    public void setMaxTotalTasks(int maxTotalTasks) {
        this.maxTotalTasks = maxTotalTasks;
    }

    public double getTrainTestRatio() {
        return trainTestRatio;
    }

    public void setTrainTestRatio(double trainTestRatio) {
        this.trainTestRatio = trainTestRatio;
    }

    public long getSplitSeed() {
        return splitSeed;
    }

    public void setSplitSeed(long splitSeed) {
        this.splitSeed = splitSeed;
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
