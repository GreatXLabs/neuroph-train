package com.neuroph.train.server.heuristic;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Estrategia de Búsqueda Evolutiva (Algoritmo Genético de Hiperparámetros y Arquitecturas).
 * Evalúa poblaciones de redes neuronales, calcula fitness penalizando complejidad excesiva (principio de parsimonia),
 * y aplica operadores genéticos de Selección por Torneo, Cruce (Crossover) y Mutación.
 */
public class EvolutionaryStrategy implements HeuristicStrategy {

    private final Random random = new Random();
    private int generation = 0;

    @Override
    public List<TrainingTask> initializeCampaign(CampaignConfig campaign, DatasetMetadata dataset) {
        generation = 0;
        List<TrainingTask> population = new ArrayList<>();
        int inSize = dataset.getInputCount();
        int outSize = dataset.getOutputCount();
        int popSize = campaign.getPopulationOrBatchSize();

        for (int i = 0; i < popSize; i++) {
            NetworkConfig config = createRandomIndividual(campaign, inSize, outSize);
            TrainingTask task = new TrainingTask(campaign.getCampaignId(), dataset.getId(), config, generation);
            task.setTrainRatio(campaign.getTrainTestRatio());
            task.setSplitSeed(campaign.getSplitSeed());
            population.add(task);
        }

        return population;
    }

    @Override
    public List<TrainingTask> onTasksCompleted(CampaignConfig campaign, DatasetMetadata dataset,
                                              List<TaskResult> recentResults, List<TaskResult> allHistory) {
        if (allHistory.isEmpty()) {
            return new ArrayList<>();
        }

        generation++;
        int inSize = dataset.getInputCount();
        int outSize = dataset.getOutputCount();
        int popSize = campaign.getPopulationOrBatchSize();

        // Asignar fitness a todos los individuos evaluados válidos
        List<ScoredIndividual> pool = new ArrayList<>();
        for (TaskResult r : allHistory) {
            if (!r.isSuccess() || r.getMetrics() == null || r.getNetworkConfig() == null) continue;
            double fitness = evaluateFitness(r);
            pool.add(new ScoredIndividual(r.getNetworkConfig(), fitness));
        }

        if (pool.isEmpty()) {
            return initializeCampaign(campaign, dataset);
        }

        // Ordenar por fitness descendente
        pool.sort((a, b) -> Double.compare(b.fitness, a.fitness));

        List<TrainingTask> nextGeneration = new ArrayList<>();

        // 1. Elitismo: conservar los mejores 2 individuos sin alterar
        int elitismCount = Math.min(2, pool.size());
        for (int i = 0; i < elitismCount; i++) {
            NetworkConfig elite = cloneConfig(pool.get(i).config);
            TrainingTask task = new TrainingTask(campaign.getCampaignId(), dataset.getId(), elite, generation);
            task.setTrainRatio(campaign.getTrainTestRatio());
            task.setSplitSeed(campaign.getSplitSeed());
            nextGeneration.add(task);
        }

        // 2. Reproducción y Cruce para completar la población
        while (nextGeneration.size() < popSize) {
            NetworkConfig parent1 = tournamentSelect(pool, 3);
            NetworkConfig parent2 = tournamentSelect(pool, 3);

            NetworkConfig offspring = crossover(parent1, parent2, campaign, inSize, outSize);
            mutate(offspring, campaign);

            TrainingTask task = new TrainingTask(campaign.getCampaignId(), dataset.getId(), offspring, generation);
            task.setTrainRatio(campaign.getTrainTestRatio());
            task.setSplitSeed(campaign.getSplitSeed());
            nextGeneration.add(task);
        }

        return nextGeneration;
    }

    private double evaluateFitness(TaskResult r) {
        double acc = r.getMetrics().getAccuracy();
        double rmse = r.getMetrics().getRmse();
        int totalNeurons = r.getNetworkConfig().getTotalHiddenNeurons();

        // Función de Fitness: recompensa precisión, penaliza error RMSE y penaliza levemente exceso de neuronas
        double score = acc - (rmse * 8.0) - (0.05 * totalNeurons);
        return score;
    }

    private NetworkConfig tournamentSelect(List<ScoredIndividual> pool, int tournamentSize) {
        ScoredIndividual best = null;
        for (int i = 0; i < tournamentSize; i++) {
            ScoredIndividual candidate = pool.get(random.nextInt(pool.size()));
            if (best == null || candidate.fitness > best.fitness) {
                best = candidate;
            }
        }
        return best != null ? best.config : pool.get(0).config;
    }

    private NetworkConfig crossover(NetworkConfig p1, NetworkConfig p2, CampaignConfig campaign, int inSize, int outSize) {
        // Combinar número de capas
        int layers1 = p1.getHiddenNeurons().size();
        int layers2 = p2.getHiddenNeurons().size();
        int numLayers = random.nextBoolean() ? layers1 : layers2;

        List<Integer> hidden = new ArrayList<>();
        for (int l = 0; l < numLayers; l++) {
            int n1 = (l < layers1) ? p1.getHiddenNeurons().get(l) : campaign.getMinNeuronsPerLayer();
            int n2 = (l < layers2) ? p2.getHiddenNeurons().get(l) : campaign.getMinNeuronsPerLayer();
            int blended = random.nextBoolean() ? n1 : (n1 + n2) / 2;
            hidden.add(Math.min(campaign.getMaxNeuronsPerLayer(),
                    Math.max(campaign.getMinNeuronsPerLayer(), blended)));
        }

        // Función de transferencia de uno de los padres
        String tf = random.nextBoolean() ? p1.getTransferFunction() : p2.getTransferFunction();

        // Learning rate y Momentum promedio / recombinado
        double lr = (p1.getLearningRate() + p2.getLearningRate()) / 2.0;
        double mom = (p1.getMomentum() + p2.getMomentum()) / 2.0;

        return new NetworkConfig(inSize, hidden, outSize, tf, lr, mom,
                campaign.getMaxIterations(), campaign.getTargetError());
    }

    private void mutate(NetworkConfig config, CampaignConfig campaign) {
        // Mutación de neuronas (probabilidad 35%)
        if (random.nextDouble() < 0.35 && !config.getHiddenNeurons().isEmpty()) {
            int layerIdx = random.nextInt(config.getHiddenNeurons().size());
            int delta = random.nextBoolean() ? 1 : -1;
            int mutated = Math.min(campaign.getMaxNeuronsPerLayer(),
                    Math.max(campaign.getMinNeuronsPerLayer(), config.getHiddenNeurons().get(layerIdx) + delta));
            config.getHiddenNeurons().set(layerIdx, mutated);
        }

        // Mutación de Learning Rate (probabilidad 25%)
        if (random.nextDouble() < 0.25) {
            double factor = 0.85 + (random.nextDouble() * 0.3); // 0.85 a 1.15
            double mutatedLr = Math.min(campaign.getLearningRateMax(),
                    Math.max(campaign.getLearningRateMin(), config.getLearningRate() * factor));
            config.setLearningRate(mutatedLr);
        }

        // Mutación de Función de Transferencia (probabilidad 15%)
        if (random.nextDouble() < 0.15 && campaign.getAllowedTransferFunctions().size() > 1) {
            String newTf = campaign.getAllowedTransferFunctions().get(
                    random.nextInt(campaign.getAllowedTransferFunctions().size()));
            config.setTransferFunction(newTf);
        }
    }

    private NetworkConfig createRandomIndividual(CampaignConfig campaign, int inSize, int outSize) {
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

    private NetworkConfig cloneConfig(NetworkConfig src) {
        return new NetworkConfig(
                src.getInputNeurons(),
                new ArrayList<>(src.getHiddenNeurons()),
                src.getOutputNeurons(),
                src.getTransferFunction(),
                src.getLearningRate(),
                src.getMomentum(),
                src.getMaxIterations(),
                src.getMaxError()
        );
    }

    private static class ScoredIndividual {
        final NetworkConfig config;
        final double fitness;

        ScoredIndividual(NetworkConfig config, double fitness) {
            this.config = config;
            this.fitness = fitness;
        }
    }
}
