package com.neuroph.train.server.heuristic;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.HeuristicType;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.server.queue.TaskManager;
import com.neuroph.train.server.storage.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquestador principal multientrenamiento: gestiona el ciclo de vida independiente
 * de múltiples campañas heurísticas en paralelo sobre diferentes datasets.
 */
public class CampaignOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CampaignOrchestrator.class);

    private final StorageManager storageManager;
    private final TaskManager taskManager;

    public static class CampaignContext {
        private final CampaignConfig config;
        private final DatasetMetadata dataset;
        private final HeuristicStrategy strategy;
        private volatile boolean running;

        public CampaignContext(CampaignConfig config, DatasetMetadata dataset, HeuristicStrategy strategy) {
            this.config = config;
            this.dataset = dataset;
            this.strategy = strategy;
            this.running = true;
        }

        public CampaignConfig getConfig() {
            return config;
        }

        public DatasetMetadata getDataset() {
            return dataset;
        }

        public HeuristicStrategy getStrategy() {
            return strategy;
        }

        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }
    }

    private final Map<String, CampaignContext> activeCampaigns = new ConcurrentHashMap<>();

    public CampaignOrchestrator(StorageManager storageManager, TaskManager taskManager) {
        this.storageManager = storageManager;
        this.taskManager = taskManager;
    }

    public synchronized void startCampaign(CampaignConfig campaignConfig) throws IOException {
        DatasetMetadata meta = storageManager.getDataset(campaignConfig.getDatasetId());
        if (meta == null) {
            throw new IllegalArgumentException("Dataset no encontrado: " + campaignConfig.getDatasetId());
        }

        // Asociar metadatos del dataset y proyecto a la campaña
        campaignConfig.setDatasetName(meta.getName());
        if (campaignConfig.getProjectId() == null || "default-project".equals(campaignConfig.getProjectId())) {
            campaignConfig.setProjectId(meta.getProjectId());
        }
        campaignConfig.setStatus("RUNNING");

        // Seleccionar estrategia heurística
        HeuristicStrategy strategy;
        if (campaignConfig.getHeuristicType() == HeuristicType.EVOLUTIONARY) {
            strategy = new EvolutionaryStrategy();
        } else {
            strategy = new GuidedSearchStrategy();
        }

        storageManager.saveCampaign(campaignConfig);

        CampaignContext ctx = new CampaignContext(campaignConfig, meta, strategy);
        activeCampaigns.put(campaignConfig.getCampaignId(), ctx);

        log.info("Iniciando campaña [{}] (ID: {}) para dataset [{}] (Proyecto: {}) con {}",
                campaignConfig.getName(), campaignConfig.getCampaignId(), meta.getName(),
                campaignConfig.getProjectId(), campaignConfig.getHeuristicType());

        // Generar lote inicial de tareas
        List<TrainingTask> initialTasks = strategy.initializeCampaign(campaignConfig, meta);
        prepareTasks(initialTasks, campaignConfig, meta);
        taskManager.enqueueTasks(initialTasks);
        log.info("Campaña [{}] encoló {} tareas iniciales de exploración", campaignConfig.getName(), initialTasks.size());
    }

    public synchronized void onTaskCompleted(TaskResult result) {
        String campId = result.getCampaignId();
        CampaignContext ctx = activeCampaigns.get(campId);

        try {
            storageManager.saveModelResult(result);
        } catch (IOException e) {
            log.error("Error guardando resultado del modelo {}: {}", result.getTaskId(), e.getMessage());
        }

        if (ctx == null || !ctx.isRunning()) {
            return;
        }

        List<TaskResult> campaignHistory = taskManager.getResultsForCampaign(campId);
        int totalCompleted = campaignHistory.size();

        log.info("Tarea {} (Campaña: {}) completada por [{}] | Metrics: {} | Total completadas: {}/{}",
                result.getTaskId(), campId, result.getWorkerName(),
                result.getMetrics() != null ? result.getMetrics().toString() : "sin métricas",
                totalCompleted, ctx.config.getMaxTotalTasks());

        // Comprobar criterio de parada: target error o límite de tareas
        boolean targetReached = false;
        if (result.getMetrics() != null && result.getMetrics().getRmse() <= ctx.config.getTargetError()) {
            targetReached = true;
            log.info("¡Objetivo de error alcanzado para campaña [{}]! RMSE={} <= {}",
                    ctx.config.getName(), result.getMetrics().getRmse(), ctx.config.getTargetError());
        }

        if (totalCompleted >= ctx.config.getMaxTotalTasks() || targetReached) {
            completeCampaign(campId);
            return;
        }

        // Si la cola de tareas pendientes para esta campaña tiene pocas tareas (<= 2), pedir el siguiente lote
        if (taskManager.getPendingCount(campId) <= 2) {
            List<TrainingTask> nextBatch = ctx.strategy.onTasksCompleted(
                    ctx.config, ctx.dataset, List.of(result), campaignHistory);

            int remaining = ctx.config.getMaxTotalTasks() - (totalCompleted + taskManager.getRunningCount(campId) + taskManager.getPendingCount(campId));
            if (remaining > 0 && !nextBatch.isEmpty()) {
                int toAdd = Math.min(remaining, nextBatch.size());
                List<TrainingTask> batchToAdd = nextBatch.subList(0, toAdd);
                prepareTasks(batchToAdd, ctx.config, ctx.dataset);
                taskManager.enqueueTasks(batchToAdd);
                log.info("Campaña [{}] generó nuevo lote de {} tareas", ctx.config.getName(), toAdd);
            }
        }
    }

    private void prepareTasks(List<TrainingTask> tasks, CampaignConfig campaign, DatasetMetadata dataset) {
        if (tasks == null) return;
        for (TrainingTask task : tasks) {
            task.setCampaignId(campaign.getCampaignId());
            task.setProjectId(campaign.getProjectId());
            task.setDatasetId(campaign.getDatasetId());
            task.setDatasetName(campaign.getDatasetName());
            task.setPatience(campaign.getPatience());
            task.setEnableAugmentation(campaign.isEnableAugmentation());
            task.setAugmentationFactor(campaign.getAugmentationFactor());
            task.setAugmentationNoise(campaign.getAugmentationNoise());
            task.setEstimatedComplexity(calculateTaskComplexity(task, dataset));
        }
    }

    private double calculateTaskComplexity(TrainingTask task, DatasetMetadata dataset) {
        if (task.getNetworkConfig() == null) {
            return 1000.0;
        }

        var config = task.getNetworkConfig();
        List<Integer> layerSizes = new ArrayList<>();
        layerSizes.add(config.getInputNeurons());
        if (config.getHiddenNeurons() != null) {
            layerSizes.addAll(config.getHiddenNeurons());
        }
        layerSizes.add(config.getOutputNeurons());

        double totalConnections = 0;
        for (int i = 0; i < layerSizes.size() - 1; i++) {
            totalConnections += (double) layerSizes.get(i) * layerSizes.get(i + 1);
        }

        int maxIter = Math.max(1, config.getMaxIterations());
        int rows = (dataset != null && dataset.getNumRows() > 0) ? dataset.getNumRows() : 100;

        return totalConnections * maxIter * rows;
    }

    public synchronized void pauseCampaign(String campaignId) {
        CampaignContext ctx = resolveContext(campaignId);
        if (ctx != null) {
            ctx.setRunning(false);
            ctx.getConfig().setStatus("PAUSED");
            try {
                storageManager.saveCampaign(ctx.getConfig());
            } catch (IOException ignored) {}
            log.info("Campaña [{}] pausada", ctx.getConfig().getName());
        }
    }

    public synchronized void pauseCampaign() {
        pauseCampaign(null);
    }

    public synchronized void resumeCampaign(String campaignId) {
        CampaignContext ctx = resolveContext(campaignId);
        if (ctx != null) {
            ctx.setRunning(true);
            ctx.getConfig().setStatus("RUNNING");
            try {
                storageManager.saveCampaign(ctx.getConfig());
            } catch (IOException ignored) {}
            log.info("Campaña [{}] reanudada", ctx.getConfig().getName());
        }
    }

    public synchronized void resumeCampaign() {
        resumeCampaign(null);
    }

    public synchronized void stopCampaign(String campaignId) {
        CampaignContext ctx = resolveContext(campaignId);
        if (ctx != null) {
            ctx.setRunning(false);
            ctx.getConfig().setStatus("STOPPED");
            try {
                storageManager.saveCampaign(ctx.getConfig());
            } catch (IOException ignored) {}
            taskManager.cancelTasksForCampaign(ctx.getConfig().getCampaignId());
            activeCampaigns.remove(ctx.getConfig().getCampaignId());
            log.info("Campaña [{}] detenida manualmente", ctx.getConfig().getName());
        }
    }

    public synchronized void stopCampaign() {
        stopCampaign(null);
    }

    public synchronized void completeCampaign(String campaignId) {
        CampaignContext ctx = activeCampaigns.get(campaignId);
        if (ctx != null) {
            ctx.setRunning(false);
            ctx.getConfig().setStatus("COMPLETED");
            try {
                storageManager.saveCampaign(ctx.getConfig());
            } catch (IOException ignored) {}
            taskManager.cancelTasksForCampaign(campaignId);
            activeCampaigns.remove(campaignId);
            log.info("¡Campaña [{}] FINALIZADA exitosamente!", ctx.getConfig().getName());
        }
    }

    private CampaignContext resolveContext(String campaignId) {
        if (campaignId != null && !campaignId.isEmpty() && activeCampaigns.containsKey(campaignId)) {
            return activeCampaigns.get(campaignId);
        }
        if (!activeCampaigns.isEmpty()) {
            return activeCampaigns.values().iterator().next();
        }
        return null;
    }

    public CampaignConfig getActiveCampaign() {
        for (CampaignContext ctx : activeCampaigns.values()) {
            if (ctx.isRunning()) {
                return ctx.getConfig();
            }
        }
        if (!activeCampaigns.isEmpty()) {
            return activeCampaigns.values().iterator().next().getConfig();
        }
        return null;
    }

    public List<CampaignConfig> getActiveCampaigns() {
        List<CampaignConfig> list = new ArrayList<>();
        for (CampaignContext ctx : activeCampaigns.values()) {
            list.add(ctx.getConfig());
        }
        return list;
    }

    public boolean isRunning() {
        for (CampaignContext ctx : activeCampaigns.values()) {
            if (ctx.isRunning()) return true;
        }
        return false;
    }
}
