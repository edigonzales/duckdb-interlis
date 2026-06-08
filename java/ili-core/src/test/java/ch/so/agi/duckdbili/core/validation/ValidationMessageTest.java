package ch.so.agi.duckdbili.core.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationMessageTest {

    @Test
    void builderAllFields() {
        ValidationMessage msg = new ValidationMessage.Builder()
                .severity("ERROR")
                .code("MULTIPLICITY")
                .message("Attribute Name is mandatory")
                .fileName("/data/test.xtf")
                .line(42)
                .column(5)
                .xtfTid("123")
                .xtfBid("basket1")
                .model("TestModel")
                .topic("TestTopic")
                .className("TestClass")
                .attributeName("Name")
                .raw("raw csv line")
                .build();

        assertEquals("ERROR", msg.getSeverity());
        assertEquals("MULTIPLICITY", msg.getCode());
        assertEquals("Attribute Name is mandatory", msg.getMessage());
        assertEquals("/data/test.xtf", msg.getFileName());
        assertEquals(42, msg.getLine());
        assertEquals(5, msg.getColumn());
        assertEquals("123", msg.getXtfTid());
        assertEquals("basket1", msg.getXtfBid());
        assertEquals("TestModel", msg.getModel());
        assertEquals("TestTopic", msg.getTopic());
        assertEquals("TestClass", msg.getClassName());
        assertEquals("Name", msg.getAttributeName());
        assertEquals("raw csv line", msg.getRaw());
    }

    @Test
    void builderDefaultsAreNull() {
        ValidationMessage msg = new ValidationMessage.Builder()
                .severity("INFO")
                .message("info message")
                .build();

        assertEquals("INFO", msg.getSeverity());
        assertEquals("info message", msg.getMessage());
        assertNull(msg.getCode());
        assertNull(msg.getFileName());
        assertNull(msg.getLine());
        assertNull(msg.getColumn());
        assertNull(msg.getXtfTid());
        assertNull(msg.getXtfBid());
        assertNull(msg.getModel());
        assertNull(msg.getTopic());
        assertNull(msg.getClassName());
        assertNull(msg.getAttributeName());
        assertNull(msg.getRaw());
    }

    @Test
    void builderPartialFields() {
        ValidationMessage msg = new ValidationMessage.Builder()
                .severity("WARNING")
                .message("warning message")
                .model("M")
                .className("C")
                .build();

        assertEquals("WARNING", msg.getSeverity());
        assertEquals("M", msg.getModel());
        assertEquals("C", msg.getClassName());
        assertNull(msg.getXtfTid());
        assertNull(msg.getCode());
    }

    @Test
    void immutability() {
        ValidationMessage msg1 = new ValidationMessage.Builder()
                .severity("ERROR")
                .message("msg1")
                .line(1)
                .build();

        // Create a second message reusing the builder
        ValidationMessage msg2 = new ValidationMessage.Builder()
                .severity("WARNING")
                .message("msg2")
                .line(2)
                .build();

        // msg1 should not be affected by msg2 creation
        assertEquals("ERROR", msg1.getSeverity());
        assertEquals(1, msg1.getLine());
        assertEquals("WARNING", msg2.getSeverity());
        assertEquals(2, msg2.getLine());
    }
}
