package me.aleksilassila.litematica.printer.runtime;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;

/**
 * Owns the active client runtime and is the only platform tick entry point.
 * Existing handlers remain behind the legacy facade while they are migrated.
 */
public final class PrinterRuntime {
    private static final PrinterRuntime INSTANCE = new PrinterRuntime();

    private final RuntimeEventBus events = new RuntimeEventBus();
    private final RuntimeScope scope = new RuntimeScope();
    private RuntimeEpoch epoch = RuntimeEpoch.INITIAL;
    private Object levelIdentity;
    private Object connectionIdentity;
    private boolean resetting;

    private PrinterRuntime() {
    }

    public static PrinterRuntime get() {
        return INSTANCE;
    }

    public RuntimeEpoch epoch() {
        return this.epoch;
    }

    public RuntimeEventBus events() {
        return this.events;
    }

    public AutoCloseable register(RuntimeComponent component) {
        return this.scope.register(component);
    }

    public void tick(Minecraft client) {
        Object currentLevel = client.level;
        Object currentConnection = client.getConnection();
        if (currentLevel != this.levelIdentity || currentConnection != this.connectionIdentity) {
            this.levelIdentity = currentLevel;
            this.connectionIdentity = currentConnection;
            this.reset("client_scope_changed");
        }

        CooldownUtils.INSTANCE.tick();
        QuickShulkerBridge.onTick();
        InteractionUtils.INSTANCE.preprocess();
        InteractionUtils.INSTANCE.onTick();
        ClientPlayerTickManager.tickLegacyRuntime();
    }

    public void reset(String reason) {
        if (this.resetting) {
            return;
        }
        this.resetting = true;
        try {
            RuntimeEpoch previous = this.epoch;
            this.epoch = previous.next();
            ClientPlayerTickManager.resetComponentsForEpoch(reason);
            RuntimeEvent.EpochChanged event = new RuntimeEvent.EpochChanged(previous, this.epoch, reason);
            this.scope.changeEpoch(event);
            this.events.publish(event);
        } finally {
            this.resetting = false;
        }
    }
}
