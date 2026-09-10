package com.neuroph.train.client.gui;

import com.neuroph.train.client.ClientConfig;
import com.neuroph.train.client.network.ServerConnection;
import com.neuroph.train.client.worker.WorkerEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Ventana principal de la aplicación cliente con pestañas para Worker y Administrador.
 */
public class ClientMainWindow extends JFrame {

    private final ClientConfig config;
    private final ServerConnection connection;
    private final WorkerEngine engine;
    private WorkerPanel workerPanel;

    public ClientMainWindow(ClientConfig config, ServerConnection connection, WorkerEngine engine) {
        super("Neuroph-Train | Sistema Distribuido de Entrenamiento de Redes Neuronales");
        this.config = config;
        this.connection = connection;
        this.engine = engine;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1050, 720);
        setMinimumSize(new Dimension(850, 600));
        setLocationRelativeTo(null);

        initUI();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (workerPanel != null) {
                    workerPanel.applyAndSaveConnectionConfig();
                }
                config.save();
                connection.disconnect("Ventana cerrada");
                engine.shutdown();
            }
        });
    }

    private void initUI() {
        JTabbedPane tabbedPane = new JTabbedPane();

        this.workerPanel = new WorkerPanel(config, connection, engine);
        AdminPanel adminPanel = new AdminPanel(connection);

        tabbedPane.addTab("Modo Worker (Colaborativo)", workerPanel);
        tabbedPane.setToolTipTextAt(0, "Modo de entrenamiento colaborativo para donar poder de cómputo al cluster de Neuroph");

        tabbedPane.addTab("Modo Administrador (Gestión & Modelos)", adminPanel);
        tabbedPane.setToolTipTextAt(1, "Panel de administración para cargar datasets, orquestar campañas y evaluar modelos");

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
    }
}
