package me.aleksilassila.litematica.printer.mixin.printer.litematica;


import fi.dy.masa.litematica.util.InventoryUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryUtils.class)
public class MixinInventoryUtils {
    @Inject(at = @At("TAIL"),method = "schematicWorldPickBlock")
    private static void schematicWorldPickBlock(ItemStack stack, BlockPos pos, Level schematicWorld, Minecraft mc, CallbackInfo ci) {
        if (mc.player != null
                && !stack.isEmpty()
                && !ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), stack)
                && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                && !TakeItOutUtils.isLoaded()
                && mc.player.inventoryMenu.slots.stream().noneMatch(slot -> slot.getItem().is(stack.getItem()))
        ) {
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.add(stack.getItem());
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem();
        }
    }

    /**
     * @author BlinkWhite
     * @reason 去除优先选择目前已选择的槽位
     */
    @Overwrite
    private static int getPickBlockTargetSlot(Player player) {
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return -1;
        }
        int slotNum;
        if (InventoryUtilsAccessor.getNextPickSlotIndex() >= InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size()) {
            InventoryUtilsAccessor.setNextPickSlotIndex(0);
        }
        for (int i = 0; i < InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size(); ++i) {
            slotNum = InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().get(InventoryUtilsAccessor.getNextPickSlotIndex());

            InventoryUtilsAccessor.setNextPickSlotIndex(InventoryUtilsAccessor.getNextPickSlotIndex() + 1);

            if (InventoryUtilsAccessor.getNextPickSlotIndex() >= InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size()) {
                InventoryUtilsAccessor.setNextPickSlotIndex(0);
            }
            if (InventoryUtilsAccessor.canPickToSlot(player.getInventory(), slotNum)) {
                return slotNum;
            }
        }
        return -1;
    }
}
