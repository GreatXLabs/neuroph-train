package com.neuroph.train;

import com.neuroph.train.common.protocol.Message;
import com.neuroph.train.common.protocol.MessageFraming;
import com.neuroph.train.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class MessageFramingTest {

    @Test
    public void testSerializeAndDeserialize() throws IOException {
        Message original = Message.of(MessageType.WORKER_REGISTER, "{\"name\":\"Juan\",\"slots\":4}");
        original.setError("No error");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MessageFraming.writeMessage(baos, original);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        Message restored = MessageFraming.readMessage(bais);

        assertNotNull(restored);
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getPayload(), restored.getPayload());
        assertEquals(original.getError(), restored.getError());
    }

    @Test
    public void testReadEOFReturnsNull() throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
        Message msg = MessageFraming.readMessage(bais);
        assertNull(msg);
    }
}
