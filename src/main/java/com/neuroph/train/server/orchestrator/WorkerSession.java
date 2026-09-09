package com.neuroph.train.server.orchestrator;

import com.neuroph.train.common.protocol.Message;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representa la sesión activa de un cliente Worker conectado al Servidor.
 */
public class WorkerSession {

    private final String workerId;
    private final String workerName;
    private final ClientHandler clientHandler;
    private volatile int allocatedSlots;
    private final AtomicInteger busySlots = new AtomicInteger(0);
    private volatile long lastHeartbeat;

    public WorkerSession(String workerId, String workerName, ClientHandler clientHandler, int initialSlots) {
        this.workerId = workerId;
        this.workerName = workerName;
        this.clientHandler = clientHandler;
        this.allocatedSlots = Math.max(1, initialSlots);
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public boolean hasAvailableSlot() {
        return busySlots.get() < allocatedSlots;
    }

    public void incrementBusy() {
        busySlots.incrementAndGet();
    }

    public void decrementBusy() {
        busySlots.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void sendMessage(Message message) throws IOException {
        if (clientHandler != null) {
            clientHandler.sendMessage(message);
        }
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    // Getters y Setters
    public String getWorkerId() {
        return workerId;
    }

    public String getWorkerName() {
        return workerName;
    }

    public ClientHandler getClientHandler() {
        return clientHandler;
    }

    public int getAllocatedSlots() {
        return allocatedSlots;
    }

    public void setAllocatedSlots(int allocatedSlots) {
        this.allocatedSlots = Math.max(1, allocatedSlots);
    }

    public int getBusySlots() {
        return busySlots.get();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
}
