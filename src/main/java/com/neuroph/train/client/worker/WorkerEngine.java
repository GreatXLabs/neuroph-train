package com.neuroph.train.client.worker;

import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.common.model.DatasetMetadata;
import com.neuroph.train.common.model.EvaluationMetrics;
import com.neuroph.train.common.model.TaskResult;
import com.neuroph.train.common.model.TrainingTask;
import com.neuroph.train.common.util.DatasetParser;
import com.neuroph.train.common.util.NetworkSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Motor local de ejecución de tareas de entrenamiento con reasignación en caliente de hilos.
 */
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);

    private final ServerConnection connection;
    private final LocalDatasetCache datasetCache = new LocalDatasetCache();
    private final NeurophTrainer trainer = new NeurophTrainer();

    private final ThreadPoolExecutor threadPool;
    private volatile int currentCores;
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);

    public interface WorkerListener {
        void onTaskStarted(String taskId, String topology);
        void onTaskProgress(String taskId, int epoch, double error);
        void onTaskFinished(String taskId, EvaluationMetrics metrics, boolean success);
        void onLog(String message);
    }

    private WorkerListener listener;

    public WorkerEngine(ServerConnection connection, int initialCores) {
        this.connection = connection;
        this.currentCores = Math.max(1, initialCores);

        this.threadPool = new ThreadPoolExecutor(
                currentCores, currentCores,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "Neuroph-Worker-Thread");
                    t.setDaemon(true);
                    return t;
                }
        );

        // Conectar el despachador de tareas de la conexión con este motor
        this.connection.setTaskHandler(this::executeTask);
    }

    public void setListener(WorkerListener listener) {
        this.listener = listener;
    }

    /**
     * Reasignación en Caliente de recursos (Cores de CPU / Hilos).
     */
    public synchronized void setAllocatedCores(int newCores) {
        int safeCores = Math.max(1, newCores);
        if (safeCores == currentCores) return;

        this.currentCores = safeCores;
        if (safeCores > threadPool.getMaximumPoolSize()) {
            threadPool.setMaximumPoolSize(safeCores);
            threadPool.setCorePoolSize(safeCores);
        } else {
            threadPool.setCorePoolSize(safeCores);
            threadPool.setMaximumPoolSize(safeCores);
        }

        log.info("Pool de entrenamiento redimensionado a {} hilos", safeCores);
        if (listener != null) {
            listener.onLog("Asignación de CPU actualizada a " + safeCores + " núcleos.");
        }

        // Notificar al servidor el nuevo cupo de slots
        connection.updateResources(safeCores);
    }

    public int getAllocatedCores() {
        return currentCores;
    }

    public int getActiveTaskCount() {
        return activeTaskCount.get();
    }

    /**
     * Procesa una tarea recibida desde el Servidor.
     */
    public void executeTask(TrainingTask task) {
        threadPool.submit(() -> {
            activeTaskCount.incrementAndGet();
            String taskId = task.getTaskId();
            String topology = task.getNetworkConfig().getTopologySummary();

            if (listener != null) {
                listener.onTaskStarted(taskId, topology);
                listener.onLog("Iniciando entrenamiento de tarea [" + taskId + "] Topología: " + topology);
            }

            try {
                // 1. Obtener Dataset (usar cache o pedir al server)
                DatasetMetadata meta = datasetCache.get(task.getDatasetId());
                if (meta == null || meta.getCsvContent() == null) {
                    if (listener != null) {
                        listener.onLog("Descargando dataset [" + task.getDatasetId() + "] desde el servidor...");
                    }
                    meta = connection.requestDatasetSync(task.getDatasetId()).get(15, TimeUnit.SECONDS);
                    if (meta == null || meta.getCsvContent() == null) {
                        throw new IllegalStateException("No se pudo obtener el dataset " + task.getDatasetId());
                    }
                    datasetCache.put(task.getDatasetId(), meta);
                }

                // 2. Particionar datos 70% entrenamiento / 30% prueba (con Data Augmentation opcional)
                DatasetParser.SplitResult split = DatasetParser.parseAndSplit(
                        meta.getCsvContent(), meta, task.getTrainRatio(), task.getSplitSeed(),
                        task.isEnableAugmentation(), task.getAugmentationFactor(), task.getAugmentationNoise()
                );

                // 3. Entrenar red neuronal con Neuroph
                NeurophTrainer.TrainingResult trainingResult = trainer.train(
                        task, split.getTrainSet(),
                        (epoch, currentError) -> {
                            if (listener != null) {
                                listener.onTaskProgress(taskId, epoch, currentError);
                            }
                        }
                );

                // 4. Evaluar exhaustivamente sobre el 30% de prueba
                EvaluationMetrics metrics = ModelEvaluator.evaluate(
                        trainingResult.getNeuralNetwork(),
                        split.getTestSamples(),
                        meta,
                        trainingResult.getDurationSeconds(),
                        trainingResult.getIterations(),
                        trainingResult.getFinalError()
                );

                // 5. Serializar el modelo .nnet resultante en Base64
                String nnetBase64 = NetworkSerializer.toBase64(trainingResult.getNeuralNetwork());

                // 6. Construir resultado y enviar al servidor
                TaskResult result = new TaskResult();
                result.setTaskId(taskId);
                result.setCampaignId(task.getCampaignId());
                result.setProjectId(task.getProjectId());
                result.setDatasetId(task.getDatasetId());
                result.setDatasetName(task.getDatasetName());
                result.setNetworkConfig(task.getNetworkConfig());
                result.setMetrics(metrics);
                result.setNnetBase64(nnetBase64);
                result.setSuccess(true);

                connection.sendTaskResult(result);

                if (listener != null) {
                    listener.onTaskFinished(taskId, metrics, true);
                    listener.onLog("Tarea [" + taskId + "] completada con éxito. " + metrics);
                }

            } catch (Exception e) {
                log.error("Error entrenando tarea {}: {}", taskId, e.getMessage(), e);
                TaskResult failure = TaskResult.failure(taskId, task.getCampaignId(), "LocalWorker", e.getMessage());
                try {
                    connection.sendTaskResult(failure);
                } catch (Exception ignored) {}

                if (listener != null) {
                    listener.onTaskFinished(taskId, null, false);
                    listener.onLog("Error en tarea [" + taskId + "]: " + e.getMessage());
                }
            } finally {
                activeTaskCount.decrementAndGet();
            }
        });
    }

    public void shutdown() {
        threadPool.shutdownNow();
    }
}
