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
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.TakeItOutAdapter;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerAdapter;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEngine;
import net.minecraft.client.Minecraft;
import java.util.List;

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
    private MaterialRequestCoordinator materialRequests;
    private final BedrockEngine bedrockEngine;

    private PrinterRuntime() {
        Minecraft client = Minecraft.getInstance();
        this.bedrockEngine = new BedrockEngine(client);
        this.scope.register(new MinecraftInteractionRuntime(client));
        this.scope.register(new InventorySwitchRuntime());
        this.scope.register(this.bedrockEngine);
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

    public BedrockEngine bedrockEngine() {
        return this.bedrockEngine;
    }

    public AutoCloseable register(RuntimeComponent component) {
        return this.scope.register(component);
    }

    public MaterialRequestCoordinator materialRequests() {
        if (this.materialRequests == null) {
            this.materialRequests = new MaterialRequestCoordinator(List.of(
                    new PlayerInventoryProvider(Minecraft.getInstance()),
                    QuickShulkerAdapter.INSTANCE,
                    new TakeItOutAdapter()
            ));
            this.scope.register(this.materialRequests);
        }
        return this.materialRequests;
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
        if (this.materialRequests != null) {
            this.materialRequests.tick();
        }
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
            RuntimeEvent.EpochChanged event = new RuntimeEvent.EpochChanged(previous, this.epoch, reason);
            this.scope.changeEpoch(event);
            this.events.publish(event);
        } finally {
            this.resetting = false;
        }
    }
}
