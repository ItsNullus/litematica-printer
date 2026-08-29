package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge;
import me.aleksilassila.litematica.printer.utils.ContainerGate;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
//#if MC > 260100
//$$ import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC > 260100
//$$ @Mixin(Gui.class)
//#else
@Mixin(Minecraft.class)
//#endif
public abstract class MixinContainerScreenGuard {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void suppressAutomatedQuickShulkerScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return;
        }
        // 仅隐藏本模组自动化流程预期的容器界面（对齐 master-4）
        if (ChestTrackerBridge.isExpectingContainerScreen()
                || InventoryUtils.shouldSuppressContainerScreen()) {
            ci.cancel();
            return;
        }
        // 其他容器界面 = 玩家手动打开 → 玩家意图优先：中止后台容器操作，让玩家界面正常显示
        if (ContainerGate.isHeld()) {
            abortBackgroundContainerOps();
        }
    }

    /**
     * 玩家手动打开容器/背包界面时，中止所有后台容器操作（只清状态与锁，
     * 不关闭玩家刚打开的菜单；各子系统自身的 reset 路径会处理自己的菜单）。
     */
    @Unique
    private static void abortBackgroundContainerOps() {
        Reference.LOGGER.info("[ContainerGate] 玩家打开界面, 中止后台容器操作");
        ChestTrackerBridge.abortRemoteOps();
        InventoryUtils.forceClearSwitchRequest();
        SwitchItem.forceClearPendingRestore();
        TakeItOutUtils.resetPending();
        InventorySwitchGuard.reset();
        ContainerGate.forceRelease();
    }
}
