package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(
            method = "handleOpenScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"
            ),
            cancellable = true
    )
    private void suppressTaskAnvilScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (packet.getType() != MenuType.ANVIL
                || ActionManager.INSTANCE.consumeManualAnvilScreenAllowance()) {
            return;
        }
        if (ActionManager.INSTANCE.consumeTaskAnvilScreenSuppression()) {
            NetworkUtils.sendPacket(new ServerboundContainerClosePacket(packet.getContainerId()));
            ci.cancel();
        }
    }

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
    }
}
