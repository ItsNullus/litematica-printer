package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(at = @At("TAIL"), method = "handleContainerContent")
    public void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || client.player.containerMenu == client.player.inventoryMenu
                ||
                //#if MC >= 12105
                packet.containerId()
                //#else
                //$$ packet.getContainerId()
                //#endif
                != client.player.containerMenu.containerId) {
            return;
        }
        if (isOpenHandler) {
            InventoryUtils.switchInv();
        }
        if (SwitchItem.isWaitingForRestoreContainer()) {
            SwitchItem.restorePendingItem();
        }
        if (ChestTrackerBridge.isChestTrackerLoaded()) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 收到容器内容包 containerId={}", client.player.containerMenu.containerId);
        }
        ChestTrackerBridge.onContainerContent();
    }
}
