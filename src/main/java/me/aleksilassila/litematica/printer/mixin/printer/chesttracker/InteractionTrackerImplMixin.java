package me.aleksilassila.litematica.printer.mixin.printer.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//$$ import red.jackf.chesttracker.api.ClientBlockSource;
//$$ import red.jackf.chesttracker.impl.providers.InteractionTrackerImpl;

//$$ /**
//$$  * 防御性修复：ChestTracker 记录容器时使用"最后一次右键的方块"作为归属。
//$$  * 若该方块不是容器（例如远程开箱/快捷潜影盒等非右键流程导致的陈旧记录），
//$$  * 清空记录，避免把容器内容记到错误的方块上。
//$$  */
//$$ @Mixin(value = InteractionTrackerImpl.class, remap = false)
//$$ public abstract class InteractionTrackerImplMixin {

//$$     @Inject(at = @At("TAIL"), method = "setLastBlockSource", remap = false)
//$$     public void clearStaleSource(ClientBlockSource source, CallbackInfo ci) {
//$$         if (source != null && !InventoryUtils.isInventory(source.level(), source.pos())) {
//$$             InteractionTrackerImpl.INSTANCE.clear();
//$$         }
//$$     }
//$$ }
//#endif
