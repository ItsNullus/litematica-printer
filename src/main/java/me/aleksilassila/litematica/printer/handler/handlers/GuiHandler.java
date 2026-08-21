package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;

public class GuiHandler extends FeatureModuleBase {
    public static final String NAME = "gui";

    public GuiHandler() {
        super(NAME, null, Configs.Core.RENDER_HUD, null, false);
    }
}
