package me.aleksilassila.litematica.printer.mixin.printer.litematica;


import fi.dy.masa.litematica.util.InventoryUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryUtils.class)
public class MixinInventoryUtils {
    @Inject(at = @At("TAIL"),method = "schematicWorldPickBlock")
    private static void schematicWorldPickBlock(ItemStack stack, BlockPos pos, Level schematicWorld, Minecraft mc, CallbackInfo ci) {
        if (mc.player != null
                && Configs.Core.WORK_SWITCH.getBooleanValue()
                && !stack.isEmpty()
                && !ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), stack)
                && (Configs.Placement.QUICK_SHULKER.getBooleanValue()
                    || TakeItOutUtils.isAutoTakeoutEnabled())
                && mc.player.inventoryMenu.slots.stream().noneMatch(slot -> slot.getItem().is(stack.getItem()))
        ) {
            QuickShulkerBridge.requestItem(stack.getItem(), MaterialRequest.Source.PICK_BLOCK);
            QuickShulkerBridge.switchItem();
        }
    }

}
