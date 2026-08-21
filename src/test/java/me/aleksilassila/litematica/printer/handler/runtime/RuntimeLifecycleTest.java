package me.aleksilassila.litematica.printer.handler.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeLifecycleTest {
    @Test
    void resetKeepsRegisteredDependencyOrderAndReason() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle();
        List<String> events = new ArrayList<>();
        lifecycle.register("first", reason -> events.add("first:" + reason));
        lifecycle.register("second", reason -> events.add("second:" + reason));
        lifecycle.seal();

        lifecycle.reset("disconnect");

        assertEquals(List.of("first:disconnect", "second:disconnect"), events);
    }

    @Test
    void registrationAfterSealIsRejected() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle();
        lifecycle.seal();

        assertThrows(IllegalStateException.class, () -> lifecycle.register("late", reason -> { }));
    }

    @Test
    void resetBeforeSealIsRejected() {
        RuntimeLifecycle lifecycle = new RuntimeLifecycle();

        assertThrows(IllegalStateException.class, () -> lifecycle.reset("world_change"));
    }
}
