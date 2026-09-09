package com.neuroph.train.server.heuristic;

import com.neuroph.train.common.model.CampaignConfig;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;

import java.util.List;

/**
 * Interfaz para algoritmos de búsqueda heurística de arquitecturas e hiperparámetros.
 */
public interface HeuristicStrategy {

    /**
     * Genera el lote inicial de tareas para comenzar la exploración.
     */
    List<TrainingTask> initializeCampaign(CampaignConfig campaign, DatasetMetadata dataset);

    /**
     * Evalúa los resultados obtenidos y genera el siguiente lote de tareas de entrenamiento.
     */
    List<TrainingTask> onTasksCompleted(CampaignConfig campaign, DatasetMetadata dataset,
                                         List<TaskResult> recentResults, List<TaskResult> allHistory);
}
