package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;

public class GuiHandler extends FeatureModuleBase {
    public static final String NAME = "gui";

    public GuiHandler() {
        super(PrinterRuntime.get(), NAME, null, Configs.Core.RENDER_HUD, null, false);
    }

    public GuiHandler(PrinterRuntime runtime) {
        super(runtime, NAME, null, Configs.Core.RENDER_HUD, null, false);
    }
}
