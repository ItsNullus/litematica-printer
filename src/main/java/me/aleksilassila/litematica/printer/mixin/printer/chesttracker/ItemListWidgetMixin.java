package me.aleksilassila.litematica.printer.mixin.printer.chesttracker;

//#if MC == 12104
//$$ import me.aleksilassila.litematica.printer.config.Configs;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.ChestTrackerBridge;
//$$ import net.minecraft.client.gui.components.AbstractWidget;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//$$ import java.util.List;

//$$ /**
//$$  * ChestTracker 搜索界面右键取物。
//$$  *
//$$  * ChestTracker 的物品列表左键点击是 WhereIsIt 定位（ItemListWidget.onClick），
//$$  * 右键目前无功能。这里在 AbstractWidget.mouseClicked 上拦截右键（button == 1），
//$$  * 命中 ChestTracker 物品列表时发起远程取物（取物开关开启时）。
//$$  *
//$$  * 注意：mixin 不能 extends 目标类（AbstractWidget）——mixin 要求父类是目标类的严格祖先，
//$$  * 否则应用失败。这里用强转访问 getX/getY。
//$$  */
//$$ @Mixin(AbstractWidget.class)
//$$ public abstract class ItemListWidgetMixin {
//$$     private static final int GRID_SLOT_SIZE = 18;
//$$
//$$     @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true)
//$$     private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
//$$         if (button != 1) {
//$$             return;
//$$         }
//$$         if (!Configs.Special.REMOTE_TAKE.getBooleanValue()) {
//$$             me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 右键: 远程取物开关未开启");
//$$             return;
//$$         }
//$$         if (!((Object) this instanceof ItemListWidgetAccessor accessor)) {
//$$             me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 右键: 目标不是 CT 物品列表 (accessor 未命中)");
//$$             return;
//$$         }
//$$         List<ItemStack> items = accessor.invokeGetOffsetItems();
//$$         if (items.isEmpty()) {
//$$             return;
//$$         }
//$$         int x = (int) ((mouseX - ((AbstractWidget) (Object) this).getX()) / GRID_SLOT_SIZE);
//$$         int y = (int) ((mouseY - ((AbstractWidget) (Object) this).getY()) / GRID_SLOT_SIZE);
//$$         int index = y * accessor.gridWidth() + x;
//$$         if (index < 0 || index >= items.size()) {
//$$             return;
//$$         }
//$$         ItemStack clicked = items.get(index);
//$$         me.aleksilassila.litematica.printer.Reference.LOGGER.info("[ChestTracker] 右键取物: {} @ index {}", clicked, index);
//$$         if (ChestTrackerBridge.takeFromScreen(clicked)) {
//$$             cir.setReturnValue(true);
//$$         }
//$$     }
//$$ }
//#endif
