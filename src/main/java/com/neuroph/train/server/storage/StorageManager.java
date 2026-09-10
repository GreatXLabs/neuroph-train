package com.neuroph.train.server.storage;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.protocol.JsonUtil;
import com.neuroph.train.common.util.NetworkSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor de persistencia en disco del servidor (Datasets, Modelos .nnet, Campañas y Métricas).
 */
public class StorageManager {

    private final File baseDir;
    private final File projectsDir;
    private final File datasetsDir;
    private final File modelsDir;
    private final File campaignsDir;

    private final Map<String, com.neuroph.train.common.model.Project> projectsCache = new ConcurrentHashMap<>();
    private final Map<String, DatasetMetadata> datasetsCache = new ConcurrentHashMap<>();
    private final Map<String, CampaignConfig> campaignsCache = new ConcurrentHashMap<>();

    public StorageManager(File baseDir) {
        this.baseDir = baseDir;
        this.projectsDir = new File(baseDir, "projects");
        this.datasetsDir = new File(baseDir, "datasets");
        this.modelsDir = new File(baseDir, "models");
        this.campaignsDir = new File(baseDir, "campaigns");

        projectsDir.mkdirs();
        datasetsDir.mkdirs();
        modelsDir.mkdirs();
        campaignsDir.mkdirs();

        loadExistingMetadata();
    }

    private void loadExistingMetadata() {
        // Cargar proyectos previos
        File[] pFiles = projectsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (pFiles != null) {
            for (File f : pFiles) {
                try {
                    String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    com.neuroph.train.common.model.Project proj = JsonUtil.fromJson(json, com.neuroph.train.common.model.Project.class);
                    if (proj != null && proj.getId() != null) {
                        projectsCache.put(proj.getId(), proj);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Si no hay proyectos, crear el proyecto por defecto
        if (projectsCache.isEmpty()) {
            com.neuroph.train.common.model.Project def = new com.neuroph.train.common.model.Project("default-project", "Proyecto Principal");
            try {
                saveProject(def);
            } catch (IOException ignored) {
            }
        }

        // Cargar datasets previos
        File[] dFiles = datasetsDir.listFiles((dir, name) -> name.endsWith("_meta.json"));
        if (dFiles != null) {
            for (File f : dFiles) {
                try {
                    String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    DatasetMetadata meta = JsonUtil.fromJson(json, DatasetMetadata.class);
                    if (meta != null && meta.getId() != null) {
                        if (meta.getProjectId() == null || meta.getProjectId().isEmpty()) {
                            meta.setProjectId("default-project");
                        }
                        datasetsCache.put(meta.getId(), meta);

                        // Asociar al proyecto en cache
                        com.neuroph.train.common.model.Project p = projectsCache.get(meta.getProjectId());
                        if (p != null) {
                            p.addDatasetId(meta.getId());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Cargar campañas previas
        File[] cFiles = campaignsDir.listFiles((dir, name) -> name.endsWith(".json") && !name.contains("_"));
        if (cFiles != null) {
            for (File f : cFiles) {
                try {
                    String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    CampaignConfig cfg = JsonUtil.fromJson(json, CampaignConfig.class);
                    if (cfg != null && cfg.getCampaignId() != null) {
                        campaignsCache.put(cfg.getCampaignId(), cfg);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    // --- Proyectos ---

    public synchronized void saveProject(com.neuroph.train.common.model.Project project) throws IOException {
        projectsCache.put(project.getId(), project);
        File file = new File(projectsDir, project.getId() + ".json");
        Files.writeString(file.toPath(), JsonUtil.toPrettyJson(project), StandardCharsets.UTF_8);
    }

    public com.neuroph.train.common.model.Project getProject(String projectId) {
        return projectsCache.get(projectId);
    }

    public List<com.neuroph.train.common.model.Project> listProjects() {
        return new ArrayList<>(projectsCache.values());
    }

    public synchronized com.neuroph.train.common.model.Project getOrCreateProject(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            name = "Proyecto Principal";
        }
        for (com.neuroph.train.common.model.Project p : projectsCache.values()) {
            if (name.trim().equalsIgnoreCase(p.getName().trim())) {
                return p;
            }
        }
        com.neuroph.train.common.model.Project newProj = new com.neuroph.train.common.model.Project(name.trim());
        saveProject(newProj);
        return newProj;
    }

    public List<DatasetMetadata> listDatasetsByProject(String projectId) {
        List<DatasetMetadata> list = new ArrayList<>();
        for (DatasetMetadata d : datasetsCache.values()) {
            if (projectId == null || projectId.isEmpty() || projectId.equals(d.getProjectId())) {
                list.add(d);
            }
        }
        return list;
    }

    // --- Datasets ---

    public synchronized void saveDataset(DatasetMetadata meta, String csvContent) throws IOException {
        if (meta.getProjectId() == null || meta.getProjectId().isEmpty()) {
            meta.setProjectId("default-project");
        }
        com.neuroph.train.common.model.Project p = projectsCache.get(meta.getProjectId());
        if (p != null) {
            p.addDatasetId(meta.getId());
            meta.setProjectName(p.getName());
            saveProject(p);
        }

        datasetsCache.put(meta.getId(), meta);

        File metaFile = new File(datasetsDir, meta.getId() + "_meta.json");
        Files.writeString(metaFile.toPath(), JsonUtil.toPrettyJson(meta), StandardCharsets.UTF_8);

        if (csvContent != null) {
            File csvFile = new File(datasetsDir, meta.getId() + ".csv");
            Files.writeString(csvFile.toPath(), csvContent, StandardCharsets.UTF_8);
        }
    }

    public DatasetMetadata getDataset(String datasetId) {
        DatasetMetadata meta = datasetsCache.get(datasetId);
        if (meta != null && meta.getCsvContent() == null) {
            File csvFile = new File(datasetsDir, datasetId + ".csv");
            if (csvFile.exists()) {
                try {
                    meta.setCsvContent(Files.readString(csvFile.toPath(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            }
        }
        return meta;
    }

    public List<DatasetMetadata> listDatasets() {
        return new ArrayList<>(datasetsCache.values());
    }

    // --- Campañas ---

    public synchronized void saveCampaign(CampaignConfig config) throws IOException {
        campaignsCache.put(config.getCampaignId(), config);
        File file = new File(campaignsDir, config.getCampaignId() + ".json");
        Files.writeString(file.toPath(), JsonUtil.toPrettyJson(config), StandardCharsets.UTF_8);
    }

    public CampaignConfig getCampaign(String campaignId) {
        return campaignsCache.get(campaignId);
    }

    public List<CampaignConfig> listCampaigns() {
        return new ArrayList<>(campaignsCache.values());
    }

    // --- Modelos .nnet y Resultados ---

    public synchronized void saveModelResult(TaskResult result) throws IOException {
        String campaignId = result.getCampaignId() != null ? result.getCampaignId() : "default";
        CampaignConfig camp = campaignsCache.get(campaignId);
        if (camp != null) {
            if (result.getProjectId() == null || "default-project".equals(result.getProjectId())) {
                result.setProjectId(camp.getProjectId());
            }
            if (result.getDatasetId() == null) {
                result.setDatasetId(camp.getDatasetId());
            }
            if (result.getDatasetName() == null) {
                result.setDatasetName(camp.getDatasetName());
            }
        }

        File campModelsDir = new File(modelsDir, campaignId);
        campModelsDir.mkdirs();

        // Guardar métricas JSON
        File metricsFile = new File(campModelsDir, "model_" + result.getTaskId() + "_metrics.json");
        Files.writeString(metricsFile.toPath(), JsonUtil.toPrettyJson(result), StandardCharsets.UTF_8);

        // Guardar archivo binario .nnet de Neuroph si viene adjunto
        if (result.getNnetBase64() != null && !result.getNnetBase64().isEmpty()) {
            File nnetFile = new File(campModelsDir, "model_" + result.getTaskId() + ".nnet");
            NetworkSerializer.saveBase64ToFile(result.getNnetBase64(), nnetFile);
        }

        // Actualizar leaderboard de la campaña
        updateCampaignLeaderboard(campaignId, result);
    }

    private synchronized void updateCampaignLeaderboard(String campaignId, TaskResult result) {
        try {
            File lbFile = new File(campaignsDir, campaignId + "_leaderboard.json");
            List<TaskResult> list = new ArrayList<>();
            if (lbFile.exists()) {
                String json = Files.readString(lbFile.toPath(), StandardCharsets.UTF_8);
                TaskResult[] arr = JsonUtil.fromJson(json, TaskResult[].class);
                if (arr != null) {
                    for (TaskResult r : arr) {
                        list.add(r);
                    }
                }
            }

            // Omitir el blob binario en el leaderboard JSON para mantenerlo ligero
            TaskResult lightResult = JsonUtil.fromJson(JsonUtil.toJson(result), TaskResult.class);
            lightResult.setNnetBase64(null);

            list.add(lightResult);

            // Ordenar por menor RMSE o mayor Accuracy
            list.sort((a, b) -> {
                if (a.getMetrics() == null && b.getMetrics() == null) return 0;
                if (a.getMetrics() == null) return 1;
                if (b.getMetrics() == null) return -1;
                // Preferir mayor Accuracy; si empatan o no hay, menor RMSE
                int cmpAcc = Double.compare(b.getMetrics().getAccuracy(), a.getMetrics().getAccuracy());
                if (cmpAcc != 0) return cmpAcc;
                return Double.compare(a.getMetrics().getRmse(), b.getMetrics().getRmse());
            });

            Files.writeString(lbFile.toPath(), JsonUtil.toPrettyJson(list), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public List<TaskResult> getLeaderboard(String campaignId) {
        File lbFile = new File(campaignsDir, campaignId + "_leaderboard.json");
        List<TaskResult> list = new ArrayList<>();
        if (lbFile.exists()) {
            try {
                String json = Files.readString(lbFile.toPath(), StandardCharsets.UTF_8);
                TaskResult[] arr = JsonUtil.fromJson(json, TaskResult[].class);
                if (arr != null) {
                    for (TaskResult r : arr) {
                        list.add(r);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return list;
    }

    public List<TaskResult> getLeaderboardByDataset(String datasetId) {
        List<TaskResult> combined = new ArrayList<>();
        for (CampaignConfig camp : campaignsCache.values()) {
            if (datasetId != null && datasetId.equals(camp.getDatasetId())) {
                combined.addAll(getLeaderboard(camp.getCampaignId()));
            }
        }
        sortLeaderboard(combined);
        return combined;
    }

    public List<TaskResult> getLeaderboardByProject(String projectId) {
        List<TaskResult> combined = new ArrayList<>();
        for (CampaignConfig camp : campaignsCache.values()) {
            if (projectId != null && projectId.equals(camp.getProjectId())) {
                combined.addAll(getLeaderboard(camp.getCampaignId()));
            }
        }
        sortLeaderboard(combined);
        return combined;
    }

    public Map<String, TaskResult> getBestModelPerDataset(String projectId) {
        Map<String, TaskResult> bestMap = new HashMap<>();
        List<DatasetMetadata> datasets = listDatasetsByProject(projectId);
        for (DatasetMetadata d : datasets) {
            List<TaskResult> dsResults = getLeaderboardByDataset(d.getId());
            if (!dsResults.isEmpty()) {
                bestMap.put(d.getId(), dsResults.get(0));
            }
        }
        return bestMap;
    }

    private void sortLeaderboard(List<TaskResult> list) {
        list.sort((a, b) -> {
            if (a.getMetrics() == null && b.getMetrics() == null) return 0;
            if (a.getMetrics() == null) return 1;
            if (b.getMetrics() == null) return -1;
            int cmpAcc = Double.compare(b.getMetrics().getAccuracy(), a.getMetrics().getAccuracy());
            if (cmpAcc != 0) return cmpAcc;
            return Double.compare(a.getMetrics().getRmse(), b.getMetrics().getRmse());
        });
    }

    public byte[] getModelNnetBytes(String campaignId, String taskId) throws IOException {
        File nnetFile = new File(new File(modelsDir, campaignId), "model_" + taskId + ".nnet");
        if (!nnetFile.exists()) {
            throw new IOException("El archivo del modelo " + taskId + " no existe");
        }
        return Files.readAllBytes(nnetFile.toPath());
    }
}
