package me.aleksilassila.litematica.printer.runtime;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;

/** Owns the bedrock state machine for one connected runtime epoch. */
final class BedrockRuntime implements RuntimeComponent {
    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        BedrockController.reset();
    }
}
