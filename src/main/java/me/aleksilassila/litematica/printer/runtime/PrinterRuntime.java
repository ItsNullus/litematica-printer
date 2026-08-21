package me.aleksilassila.litematica.printer.runtime;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.handler.FeatureModuleSet;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.RttReplayController;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
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
    private final FeatureModuleSet modules;
    private final ActionBroker actionBroker;
    private final ScanEngine scanEngine;
    private final InventoryAvailabilityTracker inventoryAvailability;
    private final CooldownUtils cooldownUtils;
    private final InteractionUtils interactionUtils;
    private final RttReplayController rttReplayController;
    private final MissingMaterialTracker missingMaterials;
    private final HudStatsManager hudStats;
    private final QuickShulkerAdapter quickShulkerAdapter;

    private PrinterRuntime() {
        Minecraft client = Minecraft.getInstance();
        this.bedrockEngine = new BedrockEngine(client);
        this.scope.register(new MinecraftInteractionRuntime(client));
        this.scope.register(new InventorySwitchRuntime());
        this.scope.register(this.bedrockEngine);
        this.actionBroker = new ActionBroker(new ActionManager());
        this.scope.register(this.actionBroker);
        this.scanEngine = new ScanEngine();
        this.scope.register(this.scanEngine);
        this.inventoryAvailability = new InventoryAvailabilityTracker();
        this.scope.register(this.inventoryAvailability);
        this.cooldownUtils = new CooldownUtils();
        this.scope.register(this.cooldownUtils);
        this.interactionUtils = new InteractionUtils();
        this.scope.register(this.interactionUtils);
        this.rttReplayController = new RttReplayController();
        this.scope.register(this.rttReplayController);
        this.missingMaterials = new MissingMaterialTracker();
        this.scope.register(this.missingMaterials);
        this.hudStats = new HudStatsManager();
        this.scope.register(this.hudStats);
        this.quickShulkerAdapter = new QuickShulkerAdapter();
        this.scope.register(this.quickShulkerAdapter);
        this.modules = new FeatureModuleSet(this);
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

    public FeatureModuleSet modules() {
        return this.modules;
    }

    public ActionBroker actionBroker() {
        return this.actionBroker;
    }

    public ScanEngine scanEngine() {
        return this.scanEngine;
    }

    public InventoryAvailabilityTracker inventoryAvailability() {
        return this.inventoryAvailability;
    }

    public CooldownUtils cooldownUtils() {
        return this.cooldownUtils;
    }

    public InteractionUtils interactionUtils() {
        return this.interactionUtils;
    }

    public RttReplayController rttReplayController() {
        return this.rttReplayController;
    }

    public MissingMaterialTracker missingMaterials() {
        return this.missingMaterials;
    }

    public HudStatsManager hudStats() {
        return this.hudStats;
    }

    public QuickShulkerAdapter quickShulkerAdapter() {
        return this.quickShulkerAdapter;
    }

    public Minecraft client() {
        return Minecraft.getInstance();
    }

    public AutoCloseable register(RuntimeComponent component) {
        return this.scope.register(component);
    }

    public MaterialRequestCoordinator materialRequests() {
        if (this.materialRequests == null) {
            this.materialRequests = new MaterialRequestCoordinator(List.of(
                    new PlayerInventoryProvider(Minecraft.getInstance()),
                    this.quickShulkerAdapter,
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

        this.cooldownUtils.tick();
        QuickShulkerBridge.onTick();
        if (this.materialRequests != null) {
            this.materialRequests.tick();
        }
        this.interactionUtils.preprocess();
        this.interactionUtils.onTick();
        this.modules.tick();
    }

    public void tickModules() {
        this.modules.tick();
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
