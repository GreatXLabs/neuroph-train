package com.neuroph.train;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.server.heuristic.EvolutionaryStrategy;
import com.neuroph.train.server.heuristic.GuidedSearchStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HeuristicStrategiesTest {

    private DatasetMetadata createDummyDataset() {
        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("ds-dummy");
        meta.setInputColumns(List.of(0, 1, 2));
        meta.setOutputColumns(List.of(3, 4));
        return meta;
    }

    private CampaignConfig createDummyCampaign() {
        CampaignConfig cfg = new CampaignConfig();
        cfg.setMinHiddenLayers(1);
        cfg.setMaxHiddenLayers(2);
        cfg.setMinNeuronsPerLayer(4);
        cfg.setMaxNeuronsPerLayer(16);
        cfg.setPopulationOrBatchSize(4);
        cfg.setMaxTotalTasks(12);
        return cfg;
    }

    @Test
    public void testGuidedSearchStrategyEvolution() {
        GuidedSearchStrategy strategy = new GuidedSearchStrategy();
        DatasetMetadata meta = createDummyDataset();
        CampaignConfig cfg = createDummyCampaign();

        List<TrainingTask> initial = strategy.initializeCampaign(cfg, meta);
        assertEquals(4, initial.size());
        assertEquals(3, initial.get(0).getNetworkConfig().getInputNeurons());
        assertEquals(2, initial.get(0).getNetworkConfig().getOutputNeurons());

        // Simular resultados
        List<TaskResult> history = new ArrayList<>();
        for (int i = 0; i < initial.size(); i++) {
            TrainingTask t = initial.get(i);
            TaskResult r = new TaskResult();
            r.setTaskId(t.getTaskId());
            r.setNetworkConfig(t.getNetworkConfig());
            r.setSuccess(true);
            EvaluationMetrics m = new EvaluationMetrics();
            m.setAccuracy(80.0 + i * 5.0);
            m.setRmse(0.2 - i * 0.03);
            r.setMetrics(m);
            history.add(r);
        }

        List<TrainingTask> nextBatch = strategy.onTasksCompleted(cfg, meta, history, history);
        assertEquals(4, nextBatch.size());
        assertEquals(1, nextBatch.get(0).getGeneration());
    }

    @Test
    public void testEvolutionaryStrategyElitismAndCrossover() {
        EvolutionaryStrategy strategy = new EvolutionaryStrategy();
        DatasetMetadata meta = createDummyDataset();
        CampaignConfig cfg = createDummyCampaign();

        List<TrainingTask> pop0 = strategy.initializeCampaign(cfg, meta);
        assertEquals(4, pop0.size());

        List<TaskResult> history = new ArrayList<>();
        for (int i = 0; i < pop0.size(); i++) {
            TrainingTask t = pop0.get(i);
            TaskResult r = new TaskResult();
            r.setTaskId(t.getTaskId());
            r.setNetworkConfig(t.getNetworkConfig());
            r.setSuccess(true);
            EvaluationMetrics m = new EvaluationMetrics();
            m.setAccuracy(70.0 + i * 8.0);
            m.setRmse(0.3 - i * 0.05);
            r.setMetrics(m);
            history.add(r);
        }

        List<TrainingTask> pop1 = strategy.onTasksCompleted(cfg, meta, history, history);
        assertEquals(4, pop1.size());
        assertEquals(1, pop1.get(0).getGeneration());
    }
}
