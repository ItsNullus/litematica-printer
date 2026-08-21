package me.aleksilassila.litematica.printer.handler.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owns the reset order for one client runtime session.
 *
 * <p>Runtime state is still reset on the client thread, but the lifecycle
 * boundary is now explicit instead of being spread across mixins and a
 * hand-maintained list of unrelated singletons.</p>
 */
public final class RuntimeLifecycle {
    private final List<Entry> entries = new ArrayList<>();
    private boolean sealed;

    public RuntimeLifecycle register(String name, RuntimeResetter resetter) {
        if (this.sealed) {
            throw new IllegalStateException("Runtime lifecycle is already sealed");
        }
        this.entries.add(new Entry(Objects.requireNonNull(name), Objects.requireNonNull(resetter)));
        return this;
    }

    public void seal() {
        this.sealed = true;
    }

    public void reset(String reason) {
        if (!this.sealed) {
            throw new IllegalStateException("Runtime lifecycle must be sealed before use");
        }
        for (Entry entry : this.entries) {
            entry.resetter.reset(reason);
        }
    }

    private record Entry(String name, RuntimeResetter resetter) {
    }
}
