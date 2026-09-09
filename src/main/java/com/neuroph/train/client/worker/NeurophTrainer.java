package com.neuroph.train.client.worker;

import com.neuroph.train.common.model.NetworkConfig;
import com.neuroph.train.common.model.TrainingTask;
import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.events.LearningEvent;
import org.neuroph.core.events.LearningEventListener;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.MomentumBackpropagation;
import org.neuroph.util.TransferFunctionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Ejecutor local del entrenamiento de redes neuronales utilizando la biblioteca Neuroph.
 */
public class NeurophTrainer {

    public static class TrainingResult {
        private final NeuralNetwork<?> neuralNetwork;
        private final double durationSeconds;
        private final int iterations;
        private final double finalError;

        public TrainingResult(NeuralNetwork<?> neuralNetwork, double durationSeconds, int iterations, double finalError) {
            this.neuralNetwork = neuralNetwork;
            this.durationSeconds = durationSeconds;
            this.iterations = iterations;
            this.finalError = finalError;
        }

        public NeuralNetwork<?> getNeuralNetwork() {
            return neuralNetwork;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }

        public int getIterations() {
            return iterations;
        }

        public double getFinalError() {
            return finalError;
        }
    }

    public interface ProgressCallback {
        void onProgress(int epoch, double currentError);
    }

    /**
     * Entrena un MultiLayerPerceptron en Neuroph según la tarea especificada.
     */
    public TrainingResult train(TrainingTask task, DataSet trainSet, ProgressCallback progressCallback) {
        NetworkConfig config = task.getNetworkConfig();

        // Construir la lista de neuronas por capa: [Entrada, Oculta1, Oculta2..., Salida]
        List<Integer> layers = new ArrayList<>();
        layers.add(config.getInputNeurons());
        if (config.getHiddenNeurons() != null) {
            layers.addAll(config.getHiddenNeurons());
        }
        layers.add(config.getOutputNeurons());

        TransferFunctionType tfType = parseTransferFunction(config.getTransferFunction());

        // Instanciar MultiLayerPerceptron con la función de activación deseada
        MultiLayerPerceptron mlp = new MultiLayerPerceptron(layers, tfType);

        // Configurar la regla de aprendizaje MomentumBackpropagation
        MomentumBackpropagation learningRule = (MomentumBackpropagation) mlp.getLearningRule();
        learningRule.setLearningRate(config.getLearningRate());
        learningRule.setMomentum(config.getMomentum());
        learningRule.setMaxIterations(config.getMaxIterations());
        learningRule.setMaxError(config.getMaxError());

        if (progressCallback != null) {
            learningRule.addListener(new LearningEventListener() {
                private long lastNotifyTime = 0;

                @Override
                public void handleLearningEvent(LearningEvent event) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotifyTime >= 500) { // Notificar como máximo cada 500ms
                        lastNotifyTime = now;
                        progressCallback.onProgress(learningRule.getCurrentIteration(), learningRule.getTotalNetworkError());
                    }
                }
            });
        }

        long startTime = System.currentTimeMillis();
        mlp.learn(trainSet);
        long endTime = System.currentTimeMillis();

        double durationSeconds = (endTime - startTime) / 1000.0;
        int iterations = learningRule.getCurrentIteration();
        double finalError = learningRule.getTotalNetworkError();

        return new TrainingResult(mlp, durationSeconds, iterations, finalError);
    }

    private TransferFunctionType parseTransferFunction(String name) {
        if (name == null) return TransferFunctionType.SIGMOID;
        switch (name.toUpperCase().trim()) {
            case "TANH":
                return TransferFunctionType.TANH;
            case "RECTIFIED_LINEAR":
            case "RECTIFIED":
            case "RELU":
                return TransferFunctionType.RECTIFIED;
            case "LINEAR":
                return TransferFunctionType.LINEAR;
            case "SIGMOID":
            default:
                return TransferFunctionType.SIGMOID;
        }
    }
}
