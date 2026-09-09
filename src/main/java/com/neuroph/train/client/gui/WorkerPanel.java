package com.neuroph.train.client.gui;

import com.neuroph.train.client.ClientConfig;
import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.client.worker.WorkerEngine;
import com.neuroph.train.common.model.EvaluationMetrics;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Panel del modo Worker colaborativo: conexión, selector en caliente de recursos y consola de logs.
 */
public class WorkerPanel extends JPanel {

    private final ClientConfig config;
    private final ServerConnection connection;
    private final WorkerEngine engine;

    private JTextField hostField;
    private JSpinner portSpinner;
    private JTextField nameField;
    private JButton connectButton;
    private JLabel statusLabel;

    private JSlider coreSlider;
    private JLabel coreLabel;
    private JProgressBar cpuUsageBar;

    private JTextArea logArea;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public WorkerPanel(ClientConfig config, ServerConnection connection, WorkerEngine engine) {
        this.config = config;
        this.connection = connection;
        this.engine = engine;

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        initUI();
        setupListeners();
    }

    private void initUI() {
        // --- Panel Superior: Conexión al Servidor ---
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));

        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        connPanel.setBorder(new TitledBorder("Conexión al Servidor Orquestador (VPS / Local)"));

        connPanel.add(new JLabel("Host:"));
        hostField = new JTextField(config.getServerHost(), 12);
        hostField.setToolTipText("Dirección de dominio (ej: neuroph.aguilucho.ar) o IP del servidor orquestador");
        connPanel.add(hostField);

        connPanel.add(new JLabel("Puerto:"));
        portSpinner = new JSpinner(new SpinnerNumberModel(config.getServerPort(), 1, 65535, 1));
        portSpinner.setToolTipText("Puerto de conexión: 443 para HTTPS/WSS seguro mediante dominio, o puerto TCP directo");
        connPanel.add(portSpinner);

        connPanel.add(new JLabel("Alias:"));
        nameField = new JTextField(config.getWorkerName(), 10);
        nameField.setToolTipText("Nombre o alias identificador de esta computadora en el cluster y tabla de líderes");
        connPanel.add(nameField);

        connectButton = new JButton("Conectar y Entrenar");
        connectButton.setBackground(new Color(40, 140, 40));
        connectButton.setForeground(Color.WHITE);
        connectButton.setToolTipText("Establece conexión persistente con el servidor para comenzar a entrenar redes neuronales");
        connPanel.add(connectButton);

        statusLabel = new JLabel("● Desconectado");
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusLabel.setToolTipText("Estado actual del enlace de red con el orquestador");
        connPanel.add(statusLabel);

        topPanel.add(connPanel, BorderLayout.NORTH);

        // --- Panel Central Superior: Asignación en Caliente de Recursos ---
        JPanel resourcePanel = new JPanel(new BorderLayout(10, 5));
        resourcePanel.setBorder(new TitledBorder("Asignación de Recursos en Caliente (CPU Cores / Hilos)"));

        int maxCores = Runtime.getRuntime().availableProcessors();
        int initialCores = Math.min(config.getAllocatedCores(), maxCores);

        coreSlider = new JSlider(1, maxCores, initialCores);
        coreSlider.setMajorTickSpacing(Math.max(1, maxCores / 4));
        coreSlider.setMinorTickSpacing(1);
        coreSlider.setPaintTicks(true);
        coreSlider.setPaintLabels(true);
        coreSlider.setToolTipText("Control en caliente de recursos: ajusta cuántos hilos/núcleos de CPU aportas al cluster");

        double pct = (double) initialCores / maxCores * 100.0;
        coreLabel = new JLabel(String.format("Hilos asignados: %d / %d núcleos (%.0f%% de CPU)",
                initialCores, maxCores, pct), SwingConstants.CENTER);
        coreLabel.setFont(coreLabel.getFont().deriveFont(Font.BOLD, 13f));
        coreLabel.setToolTipText("Porcentaje de núcleos de procesador asignados al entrenamiento");

        cpuUsageBar = new JProgressBar(0, maxCores);
        cpuUsageBar.setValue(0);
        cpuUsageBar.setStringPainted(true);
        cpuUsageBar.setString("0 tareas activas en este nodo");
        cpuUsageBar.setToolTipText("Carga actual: cantidad de tareas de entrenamiento ejecutándose en paralelo en este equipo");

        JPanel sliderBox = new JPanel(new GridLayout(2, 1, 5, 5));
        sliderBox.add(coreSlider);
        sliderBox.add(coreLabel);

        resourcePanel.add(sliderBox, BorderLayout.CENTER);
        resourcePanel.add(cpuUsageBar, BorderLayout.SOUTH);

        topPanel.add(resourcePanel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // --- Panel Inferior: Consola de Logs y Actividad en Vivo ---
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Consola de Actividad en Tiempo Real"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setBackground(new Color(25, 25, 25));
        logArea.setForeground(new Color(200, 220, 200));
        logArea.setToolTipText("Registro cronológico en tiempo real de eventos, métricas y tareas procesadas");

        JScrollPane logScroll = new JScrollPane(logArea);
        logPanel.add(logScroll, BorderLayout.CENTER);

        add(logPanel, BorderLayout.CENTER);
    }

    private void setupListeners() {
        // Conexión / Desconexión
        connectButton.addActionListener(e -> {
            if (connection.isConnected()) {
                connection.disconnect("Desconectado por el usuario");
            } else {
                startConnection();
            }
        });

        // Cambio de Recursos en Caliente (Hot Resize)
        coreSlider.addChangeListener(e -> {
            int cores = coreSlider.getValue();
            int maxCores = Runtime.getRuntime().availableProcessors();
            double pct = (double) cores / maxCores * 100.0;
            coreLabel.setText(String.format("Hilos asignados: %d / %d núcleos (%.0f%% de CPU)",
                    cores, maxCores, pct));

            if (!coreSlider.getValueIsAdjusting()) {
                config.setAllocatedCores(cores);
                config.save();
                engine.setAllocatedCores(cores);
            }
        });

        // Eventos del ConnectionListener
        connection.setListener(new ServerConnection.Listener() {
            @Override
            public void onConnected() {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("● Conectado");
                    statusLabel.setForeground(new Color(50, 180, 50));
                    connectButton.setText("Desconectar");
                    connectButton.setBackground(new Color(180, 50, 50));
                    hostField.setEnabled(false);
                    portSpinner.setEnabled(false);
                    nameField.setEnabled(false);
                    appendLog("Conexión establecida con el Servidor exitosamente.");
                });
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("● Desconectado");
                    statusLabel.setForeground(Color.GRAY);
                    connectButton.setText("Conectar y Entrenar");
                    connectButton.setBackground(new Color(40, 140, 40));
                    hostField.setEnabled(true);
                    portSpinner.setEnabled(true);
                    nameField.setEnabled(true);
                    cpuUsageBar.setValue(0);
                    cpuUsageBar.setString("0 tareas activas");
                    appendLog("Desconectado: " + reason);
                });
            }

            @Override
            public void onTaskAssigned(com.neuroph.train.common.model.TrainingTask task) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("Asignada tarea [" + task.getTaskId() + "] con topología " +
                            task.getNetworkConfig().getTopologySummary());
                });
            }

            @Override
            public void onLog(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }
        });

        // Eventos del WorkerEngine
        engine.setListener(new WorkerEngine.WorkerListener() {
            @Override
            public void onTaskStarted(String taskId, String topology) {
                SwingUtilities.invokeLater(() -> {
                    int active = engine.getActiveTaskCount();
                    cpuUsageBar.setValue(active);
                    cpuUsageBar.setString(active + " tareas activas ejecutándose");
                });
            }

            @Override
            public void onTaskProgress(String taskId, int epoch, double error) {
                // progreso periódico sin saturar consola
            }

            @Override
            public void onTaskFinished(String taskId, EvaluationMetrics metrics, boolean success) {
                SwingUtilities.invokeLater(() -> {
                    int active = engine.getActiveTaskCount();
                    cpuUsageBar.setValue(active);
                    cpuUsageBar.setString(active + " tareas activas");
                });
            }

            @Override
            public void onLog(String message) {
                SwingUtilities.invokeLater(() -> appendLog(message));
            }
        });
    }

    private void startConnection() {
        String host = hostField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        String name = nameField.getText().trim();

        if (name.isEmpty()) {
            name = "Worker-" + System.currentTimeMillis() % 1000;
        }

        config.setServerHost(host);
        config.setServerPort(port);
        config.setWorkerName(name);
        config.save();

        statusLabel.setText("● Conectando...");
        statusLabel.setForeground(Color.ORANGE);
        appendLog("Iniciando conexión saliente hacia " + host + ":" + port + "...");

        new Thread(() -> {
            try {
                connection.connect();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("● Error de conexión");
                    statusLabel.setForeground(Color.RED);
                    appendLog("ERROR: No se pudo conectar a " + host + ":" + port + " - " + ex.getMessage());
                });
            }
        }, "Connect-Worker-Thread").start();
    }

    public void appendLog(String message) {
        String time = LocalTime.now().format(timeFormatter);
        logArea.append("[" + time + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
