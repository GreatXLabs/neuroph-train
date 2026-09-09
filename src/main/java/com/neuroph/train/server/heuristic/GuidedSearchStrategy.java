package com.neuroph.train.server.heuristic;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Estrategia de Búsqueda Guiada (Adaptive Hill-Climbing con Random Restarts).
 * Explora el vecindario del mejor modelo actual ajustando capas, neuronas y learning rates,
 * y aplica reinicios aleatorios ante estancamientos en mínimos locales.
 */
public class GuidedSearchStrategy implements HeuristicStrategy {

    private final Random random = new Random();
    private int generation = 0;
    private int consecutiveStalls = 0;
    private double bestScore = Double.NEGATIVE_INFINITY;

    @Override
    public List<TrainingTask> initializeCampaign(CampaignConfig campaign, DatasetMetadata dataset) {
        generation = 0;
        consecutiveStalls = 0;
        bestScore = Double.NEGATIVE_INFINITY;

        List<TrainingTask> initialTasks = new ArrayList<>();
        int inSize = dataset.getInputCount();
        int outSize = dataset.getOutputCount();
        int batchSize = campaign.getPopulationOrBatchSize();

        // Generar un conjunto diverso de semillas iniciales
        for (int i = 0; i < batchSize; i++) {
            List<Integer> hidden = new ArrayList<>();
            // Distribuir neuronas entre min y max
            double ratio = (double) i / Math.max(1, batchSize - 1);
            int neurons1 = campaign.getMinNeuronsPerLayer() +
                    (int) Math.round(ratio * (campaign.getMaxNeuronsPerLayer() - campaign.getMinNeuronsPerLayer()));
            hidden.add(Math.max(1, neurons1));

            if (campaign.getMaxHiddenLayers() >= 2 && i % 2 == 1) {
                hidden.add(Math.max(1, neurons1 / 2));
            }

            String tf = campaign.getAllowedTransferFunctions().get(i % campaign.getAllowedTransferFunctions().size());
            double lr = campaign.getLearningRateMin() +
                    ratio * (campaign.getLearningRateMax() - campaign.getLearningRateMin());
            double momentum = campaign.getMomentumMin() +
                    0.5 * (campaign.getMomentumMax() - campaign.getMomentumMin());

            NetworkConfig config = new NetworkConfig(
                    inSize, hidden, outSize, tf, lr, momentum,
                    campaign.getMaxIterations(), campaign.getTargetError()
            );

            TrainingTask task = new TrainingTask(campaign.getCampaignId(), dataset.getId(), config, generation);
            task.setTrainRatio(campaign.getTrainTestRatio());
            task.setSplitSeed(campaign.getSplitSeed());
            initialTasks.add(task);
        }

        return initialTasks;
    }

    @Override
    public List<TrainingTask> onTasksCompleted(CampaignConfig campaign, DatasetMetadata dataset,
                                              List<TaskResult> recentResults, List<TaskResult> allHistory) {
        if (allHistory.isEmpty()) {
            return new ArrayList<>();
        }

        generation++;

        // Encontrar el mejor resultado global
        TaskResult globalBest = null;
        double currentBestScore = Double.NEGATIVE_INFINITY;

        for (TaskResult r : allHistory) {
            if (!r.isSuccess() || r.getMetrics() == null) continue;
            double score = computeScore(r);
            if (score > currentBestScore) {
                currentBestScore = score;
                globalBest = r;
            }
        }

        if (globalBest == null) {
            return initializeCampaign(campaign, dataset);
        }

        if (currentBestScore > bestScore + 0.001) {
            bestScore = currentBestScore;
            consecutiveStalls = 0;
        } else {
            consecutiveStalls++;
        }

        List<TrainingTask> nextTasks = new ArrayList<>();
        int inSize = dataset.getInputCount();
        int outSize = dataset.getOutputCount();
        int batchSize = campaign.getPopulationOrBatchSize();

        NetworkConfig base = globalBest.getNetworkConfig();

        // Si llevamos 3 estancamientos consecutivos, aplicamos Random Restart en la mitad del lote
        boolean triggerRestart = (consecutiveStalls >= 3);

        for (int i = 0; i < batchSize; i++) {
            NetworkConfig newConfig;

            if (triggerRestart && i >= batchSize / 2) {
                // Mutación aleatoria drástica para salir de mínimo local
                newConfig = generateRandomConfig(campaign, inSize, outSize);
            } else {
                // Exploración en el vecindario del mejor modelo
                newConfig = mutateNeighborhood(base, campaign, inSize, outSize);
            }

            TrainingTask task = new TrainingTask(campaign.getCampaignId(), dataset.getId(), newConfig, generation);
            task.setTrainRatio(campaign.getTrainTestRatio());
            task.setSplitSeed(campaign.getSplitSeed());
            nextTasks.add(task);
        }

        return nextTasks;
    }

    private double computeScore(TaskResult r) {
        if (r.getMetrics() == null) return Double.NEGATIVE_INFINITY;
        // Priorizar Accuracy (%) y penalizar RMSE
        double acc = r.getMetrics().getAccuracy();
        double rmse = r.getMetrics().getRmse();
        return acc - (rmse * 10.0);
    }

    private NetworkConfig mutateNeighborhood(NetworkConfig base, CampaignConfig campaign, int inSize, int outSize) {
        List<Integer> hidden = new ArrayList<>();
        if (base.getHiddenNeurons() != null && !base.getHiddenNeurons().isEmpty()) {
            for (int n : base.getHiddenNeurons()) {
                int delta = (random.nextInt(5) - 2); // -2, -1, 0, +1, +2
                int mutated = Math.min(campaign.getMaxNeuronsPerLayer(),
                        Math.max(campaign.getMinNeuronsPerLayer(), n + delta));
                hidden.add(mutated);
            }
        } else {
            hidden.add(campaign.getMinNeuronsPerLayer() + random.nextInt(8));
        }

        // Posibilidad de agregar o quitar una capa oculta si los límites lo permiten
        if (campaign.getMaxHiddenLayers() > 1 && random.nextDouble() < 0.20) {
            if (hidden.size() == 1 && campaign.getMaxHiddenLayers() >= 2) {
                hidden.add(Math.max(campaign.getMinNeuronsPerLayer(), hidden.get(0) / 2));
            } else if (hidden.size() > 1 && campaign.getMinHiddenLayers() == 1) {
                hidden.remove(hidden.size() - 1);
            }
        }

        // Variar learning rate (+/- 20%)
        double lrFactor = 0.8 + (random.nextDouble() * 0.4); // 0.8 a 1.2
        double newLr = Math.min(campaign.getLearningRateMax(),
                Math.max(campaign.getLearningRateMin(), base.getLearningRate() * lrFactor));

        // Variar momentum (+/- 15%)
        double momFactor = 0.85 + (random.nextDouble() * 0.3);
        double newMom = Math.min(campaign.getMomentumMax(),
                Math.max(campaign.getMomentumMin(), base.getMomentum() * momFactor));

        // Función de transferencia (80% mantener, 20% cambiar)
        String tf = base.getTransferFunction();
        if (random.nextDouble() < 0.20 && campaign.getAllowedTransferFunctions().size() > 1) {
            tf = campaign.getAllowedTransferFunctions().get(
                    random.nextInt(campaign.getAllowedTransferFunctions().size()));
        }

        return new NetworkConfig(inSize, hidden, outSize, tf, newLr, newMom,
                campaign.getMaxIterations(), campaign.getTargetError());
    }

    private NetworkConfig generateRandomConfig(CampaignConfig campaign, int inSize, int outSize) {
        int numLayers = campaign.getMinHiddenLayers() +
                random.nextInt(Math.max(1, campaign.getMaxHiddenLayers() - campaign.getMinHiddenLayers() + 1));
        List<Integer> hidden = new ArrayList<>();
        for (int l = 0; l < numLayers; l++) {
            int neurons = campaign.getMinNeuronsPerLayer() +
                    random.nextInt(Math.max(1, campaign.getMaxNeuronsPerLayer() - campaign.getMinNeuronsPerLayer() + 1));
            hidden.add(neurons);
        }

        String tf = campaign.getAllowedTransferFunctions().get(
                random.nextInt(campaign.getAllowedTransferFunctions().size()));

        double lr = campaign.getLearningRateMin() +
                random.nextDouble() * (campaign.getLearningRateMax() - campaign.getLearningRateMin());
        double mom = campaign.getMomentumMin() +
                random.nextDouble() * (campaign.getMomentumMax() - campaign.getMomentumMin());

        return new NetworkConfig(inSize, hidden, outSize, tf, lr, mom,
                campaign.getMaxIterations(), campaign.getTargetError());
    }
}
