package com.neuroph.train.common.model;

import java.util.List;

/**
 * Métricas exhaustivas de evaluación del modelo calculadas sobre el conjunto de test (30%).
 */
public class EvaluationMetrics {

    // Métricas requeridas de error y bondad de ajuste
    private double mse;            // Error Cuadrático Medio
    private double rmse;           // Raíz del Error Cuadrático Medio
    private double mae;            // Error Absoluto Medio
    private double r2;             // Coeficiente de Determinación R²
    private double mape;           // Error Porcentual Absoluto Medio (%)
    private double maxError;       // Error Máximo Absoluto
    private double minError;       // Error Mínimo Absoluto
    private double percentil90;    // Percentil 90 del error absoluto

    // Métricas de convergencia y esfuerzo
    private double tiempoSeg;      // Tiempo de entrenamiento en segundos
    private int iteraciones;       // Épocas ejecutadas
    private double errorFinal;     // Error final alcanzado por Neuroph en entrenamiento

    // Métricas para problemas de Clasificación
    private double accuracy;       // Precisión de clasificación (%)
    private double f1Score;        // F1-Score promedio
    private int[][] confusionMatrix; // Matriz de confusión NxN
    private List<String> classLabels;
    private int testSampleCount;

    public EvaluationMetrics() {
    }

    // Getters y Setters
    public double getMse() {
        return mse;
    }

    public void setMse(double mse) {
        this.mse = mse;
    }

    public double getRmse() {
        return rmse;
    }

    public void setRmse(double rmse) {
        this.rmse = rmse;
    }

    public double getMae() {
        return mae;
    }

    public void setMae(double mae) {
        this.mae = mae;
    }

    public double getR2() {
        return r2;
    }

    public void setR2(double r2) {
        this.r2 = r2;
    }

    public double getMape() {
        return mape;
    }

    public void setMape(double mape) {
        this.mape = mape;
    }

    public double getMaxError() {
        return maxError;
    }

    public void setMaxError(double maxError) {
        this.maxError = maxError;
    }

    public double getMinError() {
        return minError;
    }

    public void setMinError(double minError) {
        this.minError = minError;
    }

    public double getPercentil90() {
        return percentil90;
    }

    public void setPercentil90(double percentil90) {
        this.percentil90 = percentil90;
    }

    public double getTiempoSeg() {
        return tiempoSeg;
    }

    public void setTiempoSeg(double tiempoSeg) {
        this.tiempoSeg = tiempoSeg;
    }

    public int getIteraciones() {
        return iteraciones;
    }

    public void setIteraciones(int iteraciones) {
        this.iteraciones = iteraciones;
    }

    public double getErrorFinal() {
        return errorFinal;
    }

    public void setErrorFinal(double errorFinal) {
        this.errorFinal = errorFinal;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
    }

    public double getF1Score() {
        return f1Score;
    }

    public void setF1Score(double f1Score) {
        this.f1Score = f1Score;
    }

    public int[][] getConfusionMatrix() {
        return confusionMatrix;
    }

    public void setConfusionMatrix(int[][] confusionMatrix) {
        this.confusionMatrix = confusionMatrix;
    }

    public List<String> getClassLabels() {
        return classLabels;
    }

    public void setClassLabels(List<String> classLabels) {
        this.classLabels = classLabels;
    }

    public int getTestSampleCount() {
        return testSampleCount;
    }

    public void setTestSampleCount(int testSampleCount) {
        this.testSampleCount = testSampleCount;
    }

    @Override
    public String toString() {
        return String.format(
                "Metrics[Acc=%.2f%%, RMSE=%.4f, MSE=%.4f, MAE=%.4f, R2=%.4f, MaxErr=%.4f, P90=%.4f, Tiempo=%.2fs, Epochs=%d, FinalErr=%.4f]",
                accuracy, rmse, mse, mae, r2, maxError, percentil90, tiempoSeg, iteraciones, errorFinal);
    }
}
