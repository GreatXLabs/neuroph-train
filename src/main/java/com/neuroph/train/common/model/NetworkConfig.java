package com.neuroph.train.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuración arquitectónica e hiperparámetros de una red neuronal para Neuroph.
 */
public class NetworkConfig {

    private int inputNeurons;
    private List<Integer> hiddenNeurons = new ArrayList<>();
    private int outputNeurons;
    private String transferFunction = "SIGMOID"; // SIGMOID, TANH, RECTIFIED_LINEAR, LINEAR
    private double learningRate = 0.1;
    private double momentum = 0.2;
    private int maxIterations = 1000;
    private double maxError = 0.01;

    public NetworkConfig() {
    }

    public NetworkConfig(int inputNeurons, List<Integer> hiddenNeurons, int outputNeurons,
                         String transferFunction, double learningRate, double momentum,
                         int maxIterations, double maxError) {
        this.inputNeurons = inputNeurons;
        this.hiddenNeurons = hiddenNeurons != null ? new ArrayList<>(hiddenNeurons) : new ArrayList<>();
        this.outputNeurons = outputNeurons;
        this.transferFunction = transferFunction != null ? transferFunction : "SIGMOID";
        this.learningRate = learningRate;
        this.momentum = momentum;
        this.maxIterations = maxIterations;
        this.maxError = maxError;
    }

    public int getTotalHiddenNeurons() {
        if (hiddenNeurons == null) return 0;
        int sum = 0;
        for (int n : hiddenNeurons) {
            sum += n;
        }
        return sum;
    }

    public String getTopologySummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(inputNeurons);
        if (hiddenNeurons != null) {
            for (int n : hiddenNeurons) {
                sb.append("-").append(n);
            }
        }
        sb.append("-").append(outputNeurons);
        return sb.toString();
    }

    // Getters y Setters
    public int getInputNeurons() {
        return inputNeurons;
    }

    public void setInputNeurons(int inputNeurons) {
        this.inputNeurons = inputNeurons;
    }

    public List<Integer> getHiddenNeurons() {
        return hiddenNeurons;
    }

    public void setHiddenNeurons(List<Integer> hiddenNeurons) {
        this.hiddenNeurons = hiddenNeurons;
    }

    public int getOutputNeurons() {
        return outputNeurons;
    }

    public void setOutputNeurons(int outputNeurons) {
        this.outputNeurons = outputNeurons;
    }

    public String getTransferFunction() {
        return transferFunction;
    }

    public void setTransferFunction(String transferFunction) {
        this.transferFunction = transferFunction;
    }

    public double getLearningRate() {
        return learningRate;
    }

    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    public double getMomentum() {
        return momentum;
    }

    public void setMomentum(double momentum) {
        this.momentum = momentum;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public double getMaxError() {
        return maxError;
    }

    public void setMaxError(double maxError) {
        this.maxError = maxError;
    }

    @Override
    public String toString() {
        return "NetworkConfig{" +
                "topology=" + getTopologySummary() +
                ", transfer='" + transferFunction + '\'' +
                ", lr=" + learningRate +
                ", momentum=" + momentum +
                ", maxIter=" + maxIterations +
                ", maxError=" + maxError +
                '}';
    }
}
