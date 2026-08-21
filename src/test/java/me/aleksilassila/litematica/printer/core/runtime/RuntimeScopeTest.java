package me.aleksilassila.litematica.printer.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeScopeTest {
    @Test
    void registeredComponentsReceiveEveryEpochAndCanUnregister() throws Exception {
        RuntimeScope scope = new RuntimeScope();
        AtomicInteger resets = new AtomicInteger();
        RuntimeComponent component = eventCounter(resets);
        AutoCloseable registration = scope.register(component);
        RuntimeEpoch first = RuntimeEpoch.INITIAL;
        scope.changeEpoch(new RuntimeEvent.EpochChanged(first, first.next(), "test"));
        registration.close();
        scope.changeEpoch(new RuntimeEvent.EpochChanged(first.next(), first.next().next(), "ignored"));
        assertEquals(1, resets.get());
    }

    @Test
    void duplicateRegistrationDoesNotResetTwice() {
        RuntimeScope scope = new RuntimeScope();
        AtomicInteger resets = new AtomicInteger();
        RuntimeComponent component = eventCounter(resets);
        scope.register(component);
        scope.register(component);
        scope.changeEpoch(new RuntimeEvent.EpochChanged(
                RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), "test"));
        assertEquals(1, resets.get());
        assertEquals(1, scope.componentCount());
    }

    private static RuntimeComponent eventCounter(AtomicInteger resets) {
        return new RuntimeComponent() {
            @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { resets.incrementAndGet(); }
        };
    }
}
