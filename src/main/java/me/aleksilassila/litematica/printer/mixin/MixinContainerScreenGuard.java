package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge;
import net.minecraft.client.Minecraft;
//#if MC > 260100
//$$ import net.minecraft.client.gui.Gui;
//#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
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
        // 远程开箱进行中（内容包未到）→ 抑制远程操作自身的容器菜单
        if (ChestTrackerBridge.isRemoteOpening() || ChestTrackerBridge.isAwaitingRemoteContent()) {
            ci.cancel();
            return;
        }
        // 快捷潜影盒无头开盒（isOpenHandler / restore）→ 保持原行为，抑制
        if (InventoryUtils.shouldSuppressContainerScreen()) {
            ci.cancel();
            return;
        }
        // 到这里说明远程开箱空闲：玩家主动打开界面（E/鼠标键/点击箱子等）→ 中止远程取物/添加，让玩家操作优先
        if (ChestTrackerBridge.isAwaitingStack() || ChestTrackerBridge.isAdderActive()) {
            ChestTrackerBridge.abortRemoteOps();
        }
    }
}
