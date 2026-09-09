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
import java.util.List;

/**
 * Orquestador principal del ciclo de vida de campañas y evolución heurística de modelos.
 */
public class CampaignOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CampaignOrchestrator.class);

    private final StorageManager storageManager;
    private final TaskManager taskManager;

    private volatile CampaignConfig activeCampaign;
    private volatile DatasetMetadata activeDataset;
    private HeuristicStrategy strategy;
    private volatile boolean isRunning = false;

    public CampaignOrchestrator(StorageManager storageManager, TaskManager taskManager) {
        this.storageManager = storageManager;
        this.taskManager = taskManager;
    }

    public synchronized void startCampaign(CampaignConfig campaignConfig) throws IOException {
        DatasetMetadata meta = storageManager.getDataset(campaignConfig.getDatasetId());
        if (meta == null) {
            throw new IllegalArgumentException("Dataset no encontrado: " + campaignConfig.getDatasetId());
        }

        this.activeCampaign = campaignConfig;
        this.activeDataset = meta;
        this.activeCampaign.setStatus("RUNNING");
        this.isRunning = true;

        // Seleccionar estrategia heurística
        if (campaignConfig.getHeuristicType() == HeuristicType.EVOLUTIONARY) {
            this.strategy = new EvolutionaryStrategy();
        } else {
            this.strategy = new GuidedSearchStrategy();
        }

        storageManager.saveCampaign(campaignConfig);

        log.info("Iniciando campaña [{}] con estrategia {} sobre dataset [{}]",
                campaignConfig.getName(), campaignConfig.getHeuristicType(), meta.getName());

        // Generar lote inicial de tareas
        List<TrainingTask> initialTasks = strategy.initializeCampaign(campaignConfig, meta);
        prepareTasks(initialTasks, campaignConfig, meta);
        taskManager.enqueueTasks(initialTasks);
        log.info("Encoladas {} tareas iniciales de exploración", initialTasks.size());
    }

    public synchronized void onTaskCompleted(TaskResult result) {
        if (!isRunning || activeCampaign == null) {
            return;
        }

        try {
            storageManager.saveModelResult(result);
        } catch (IOException e) {
            log.error("Error guardando resultado del modelo {}: {}", result.getTaskId(), e.getMessage());
        }

        List<TaskResult> campaignHistory = taskManager.getResultsForCampaign(activeCampaign.getCampaignId());
        int totalCompleted = campaignHistory.size();

        log.info("Tarea {} completada por [{}] | Metrics: {} | Total completadas: {}/{}",
                result.getTaskId(), result.getWorkerName(),
                result.getMetrics() != null ? result.getMetrics().toString() : "sin métricas",
                totalCompleted, activeCampaign.getMaxTotalTasks());

        // Comprobar criterio de parada: target error o límite de tareas
        boolean targetReached = false;
        if (result.getMetrics() != null && result.getMetrics().getRmse() <= activeCampaign.getTargetError()) {
            targetReached = true;
            log.info("¡Objetivo de error alcanzado! RMSE={} <= {}",
                    result.getMetrics().getRmse(), activeCampaign.getTargetError());
        }

        if (totalCompleted >= activeCampaign.getMaxTotalTasks() || targetReached) {
            completeCampaign();
            return;
        }

        // Si la cola de tareas pendientes tiene pocas tareas (< 3), pedirle a la heurística el siguiente lote
        if (taskManager.getPendingCount() <= 2) {
            List<TrainingTask> nextBatch = strategy.onTasksCompleted(
                    activeCampaign, activeDataset, List.of(result), campaignHistory);

            // Filtrar para no exceder maxTotalTasks
            int remaining = activeCampaign.getMaxTotalTasks() - (totalCompleted + taskManager.getRunningCount() + taskManager.getPendingCount());
            if (remaining > 0 && !nextBatch.isEmpty()) {
                int toAdd = Math.min(remaining, nextBatch.size());
                List<TrainingTask> batchToAdd = nextBatch.subList(0, toAdd);
                prepareTasks(batchToAdd, activeCampaign, activeDataset);
                taskManager.enqueueTasks(batchToAdd);
                log.info("Heurística generó nuevo lote de {} tareas", toAdd);
            }
        }
    }

    private void prepareTasks(List<TrainingTask> tasks, CampaignConfig campaign, DatasetMetadata dataset) {
        if (tasks == null) return;
        for (TrainingTask task : tasks) {
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
        java.util.List<Integer> layerSizes = new java.util.ArrayList<>();
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

    public synchronized void pauseCampaign() {
        if (activeCampaign != null) {
            activeCampaign.setStatus("PAUSED");
            this.isRunning = false;
            try {
                storageManager.saveCampaign(activeCampaign);
            } catch (IOException ignored) {}
            log.info("Campaña [{}] pausada", activeCampaign.getName());
        }
    }

    public synchronized void resumeCampaign() {
        if (activeCampaign != null) {
            activeCampaign.setStatus("RUNNING");
            this.isRunning = true;
            try {
                storageManager.saveCampaign(activeCampaign);
            } catch (IOException ignored) {}
            log.info("Campaña [{}] reanudada", activeCampaign.getName());
        }
    }

    public synchronized void stopCampaign() {
        if (activeCampaign != null) {
            activeCampaign.setStatus("STOPPED");
            this.isRunning = false;
            try {
                storageManager.saveCampaign(activeCampaign);
            } catch (IOException ignored) {}
            taskManager.clear();
            log.info("Campaña [{}] detenida manualmente", activeCampaign.getName());
        }
    }

    private synchronized void completeCampaign() {
        if (activeCampaign != null) {
            activeCampaign.setStatus("COMPLETED");
            this.isRunning = false;
            try {
                storageManager.saveCampaign(activeCampaign);
            } catch (IOException ignored) {}
            log.info("¡Campaña [{}] FINALIZADA exitosamente!", activeCampaign.getName());
        }
    }

    public CampaignConfig getActiveCampaign() {
        return activeCampaign;
    }

    public boolean isRunning() {
        return isRunning;
    }
}
