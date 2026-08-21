package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Feature-facing action middleware.
 *
 * <p>Feature handlers submit actions through this boundary. Minecraft and
 * third-party integration mixins may continue to observe ActionManager, but
 * feature code no longer needs to know which queue implementation owns the
 * action or how it is reset.</p>
 */
public final class ActionBroker {
    public static final ActionBroker INSTANCE = new ActionBroker(ActionManager.INSTANCE);

    private final ActionManager delegate;

    private ActionBroker(ActionManager delegate) {
        this.delegate = delegate;
    }

    public boolean queueClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int clickRepeatCount,
            @Nullable Item[] expectedItems,
            @NotNull ActionManager.ActionSource source
    ) {
        return this.delegate.queueClick(target, side, hitModifier, useShift, clickRepeatCount, expectedItems, source);
    }

    public void useProtocolHitModifier(@NotNull Vec3 hitModifier) {
        this.delegate.useProtocolHitModifier(hitModifier);
    }

    public boolean setQueueCompletionListener(@Nullable Consumer<ActionManager.SendResult> completionListener) {
        return this.delegate.setQueueCompletionListener(completionListener);
    }

    public boolean setExpectedStackPredicate(@Nullable Predicate<ItemStack> expectedStackPredicate) {
        return this.delegate.setExpectedStackPredicate(expectedStackPredicate);
    }

    public ActionManager.SendResult sendQueue(@Nullable LocalPlayer player) {
        return this.delegate.sendQueue(player);
    }

    public void cancelQueue() {
        this.delegate.cancelQueue();
    }

    public boolean isWaitingForLook() {
        return this.delegate.needWaitModifyLook;
    }

    @Nullable
    public PlayerLook getLook() {
        return this.delegate.look;
    }

    public void setLook(@Nullable PlayerLook look) {
        this.delegate.setLook(look);
    }

    public void setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.delegate.setWaitForHorizontalLook(waitForHorizontalLook);
    }

    public void setNeedWaitModifyLookFromAction(boolean actionRequiresWaitModifyLook) {
        this.delegate.setNeedWaitModifyLookFromAction(actionRequiresWaitModifyLook);
    }

    public void setShift(LocalPlayer player, boolean shift) {
        this.delegate.setShift(player, shift);
    }

    public boolean isPrinterInteractionActive() {
        return this.delegate.isPrinterInteractionActive();
    }

    public boolean isEasyPlaceProtocolActive() {
        return this.delegate.isEasyPlaceProtocolActive();
    }

    public boolean consumeManualAnvilScreenAllowance() {
        return this.delegate.consumeManualAnvilScreenAllowance();
    }

    public boolean consumeTaskAnvilScreenSuppression() {
        return this.delegate.consumeTaskAnvilScreenSuppression();
    }

    public void prioritizeManualAnvilScreen() {
        this.delegate.prioritizeManualAnvilScreen();
    }

    public void armPrintSignEdit(BlockPos blockPos) {
        this.delegate.armPrintSignEdit(blockPos);
    }

    public void confirmPrintSignEditSent(BlockPos blockPos) {
        this.delegate.confirmPrintSignEditSent(blockPos);
    }

    public void cancelPrintSignEdit(BlockPos blockPos) {
        this.delegate.cancelPrintSignEdit(blockPos);
    }

    public boolean consumePrintSignEdit(BlockPos blockPos) {
        return this.delegate.consumePrintSignEdit(blockPos);
    }

    public void resetRuntime(String reason) {
        this.delegate.resetRuntime();
    }
}
