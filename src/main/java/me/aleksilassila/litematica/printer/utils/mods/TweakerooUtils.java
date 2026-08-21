package me.aleksilassila.litematica.printer.utils.mods;

import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

public class TweakerooUtils {
    private static final int FALLBACK_ITEM_SWAP_DURABILITY_THRESHOLD = 5;

    private static @Nullable Object tweakToolSwitchEnum;
    private static @Nullable Object tweakSwapAlmostBrokenToolsEnum;
    private static @Nullable Object disableBlockBreakCooldownConfig;
    private static @Nullable Object itemSwapDurabilityThresholdConfig;
    private static @Nullable Method trySwitchToEffectiveToolMethod;
    private static @Nullable Method trySwapCurrentToolIfNearlyBrokenMethod;
    private static @Nullable Method getBooleanValueMethod;
    private static @Nullable Method getIntegerValueMethod;
    private static @Nullable Object blockTypeBreakRestriction;
    private static @Nullable Object blockTypeBreakRestrictionBlacklist;
    private static @Nullable Object blockTypeBreakRestrictionWhitelist;
    private static @Nullable Method getListTypeMethod;
    private static @Nullable Method getStringsMethod;

    static {
        if (FabricLoader.getInstance().isModLoaded("tweakeroo")) {
            try {
                Class<?> featureToggleClass = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
                tweakToolSwitchEnum = featureToggleClass.getField("TWEAK_TOOL_SWITCH").get(null);
                tweakSwapAlmostBrokenToolsEnum = featureToggleClass.getField("TWEAK_SWAP_ALMOST_BROKEN_TOOLS").get(null);

                Class<?> disableConfigsClass = Class.forName("fi.dy.masa.tweakeroo.config.Configs$Disable");
                disableBlockBreakCooldownConfig = disableConfigsClass.getField("DISABLE_BLOCK_BREAK_COOLDOWN").get(null);

                Class<?> genericConfigsClass = Class.forName("fi.dy.masa.tweakeroo.config.Configs$Generic");
                itemSwapDurabilityThresholdConfig = genericConfigsClass.getField("ITEM_SWAP_DURABILITY_THRESHOLD").get(null);

                Class<?> iConfigBooleanClass = Class.forName("fi.dy.masa.malilib.config.IConfigBoolean");
                getBooleanValueMethod = iConfigBooleanClass.getDeclaredMethod("getBooleanValue");
                getIntegerValueMethod = itemSwapDurabilityThresholdConfig.getClass().getMethod("getIntegerValue");

                Class<?> inventoryUtilsClass = Class.forName("fi.dy.masa.tweakeroo.util.InventoryUtils");
                trySwitchToEffectiveToolMethod = inventoryUtilsClass.getDeclaredMethod("trySwitchToEffectiveTool", BlockPos.class);
                trySwapCurrentToolIfNearlyBrokenMethod = inventoryUtilsClass.getDeclaredMethod("trySwapCurrentToolIfNearlyBroken");

                Class<?> placementTweaksClass = Class.forName("fi.dy.masa.tweakeroo.tweaks.PlacementTweaks");
                blockTypeBreakRestriction = placementTweaksClass.getField("BLOCK_TYPE_BREAK_RESTRICTION").get(null);

                Class<?> listConfigsClass = Class.forName("fi.dy.masa.tweakeroo.config.Configs$Lists");
                blockTypeBreakRestrictionBlacklist = listConfigsClass.getField("BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST").get(null);
                blockTypeBreakRestrictionWhitelist = listConfigsClass.getField("BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST").get(null);
                getListTypeMethod = blockTypeBreakRestriction.getClass().getMethod("getListType");
                getStringsMethod = blockTypeBreakRestrictionBlacklist.getClass().getMethod("getStrings");

            } catch (Exception e) {
                tweakToolSwitchEnum = null;
                tweakSwapAlmostBrokenToolsEnum = null;
                disableBlockBreakCooldownConfig = null;
                itemSwapDurabilityThresholdConfig = null;
                trySwitchToEffectiveToolMethod = null;
                trySwapCurrentToolIfNearlyBrokenMethod = null;
                getBooleanValueMethod = null;
                getIntegerValueMethod = null;
                blockTypeBreakRestriction = null;
                blockTypeBreakRestrictionBlacklist = null;
                blockTypeBreakRestrictionWhitelist = null;
                getListTypeMethod = null;
                getStringsMethod = null;
                e.printStackTrace();
            }
        }
    }

    /**
     * 检查 Tweakeroo 的 TWEAK_TOOL_SWITCH 选项是否启用。
     * @return 如果 Tweakeroo 存在且选项启用，则返回 true，否则返回 false。
     */
    public static boolean isToolSwitchEnabled() {
        if (getBooleanValueMethod == null || tweakToolSwitchEnum == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(tweakToolSwitchEnum);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isDisableBlockBreakCooldownEnabled() {
        if (getBooleanValueMethod == null || disableBlockBreakCooldownConfig == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(disableBlockBreakCooldownConfig);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isSwapAlmostBrokenToolsEnabled() {
        if (getBooleanValueMethod == null || tweakSwapAlmostBrokenToolsEnum == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(tweakSwapAlmostBrokenToolsEnum);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 调用 Tweakeroo 的 InventoryUtils.trySwitchToEffectiveTool(BlockPos pos) 静态方法。
     * 只有在 Tweakeroo 存在且方法被成功加载时才执行。
     * @param pos 要挖掘的方块位置
     */
    public static void trySwitchToEffectiveTool(BlockPos pos) {
        if (trySwitchToEffectiveToolMethod == null) {
            return;
        }
        try {
            trySwitchToEffectiveToolMethod.invoke(null, pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void trySwapCurrentToolIfNearlyBroken() {
        if (trySwapCurrentToolIfNearlyBrokenMethod == null) {
            return;
        }
        try {
            trySwapCurrentToolIfNearlyBrokenMethod.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isToolTooDamagedForBreaking(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || !isSwapAlmostBrokenToolsEnabled()) {
            return false;
        }
        int remainingDurability = stack.getMaxDamage() - stack.getDamageValue();
        return remainingDurability <= getMinDurability(stack);
    }

    public static int getSafeBreakBudget(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || !isSwapAlmostBrokenToolsEnabled()) {
            return Integer.MAX_VALUE;
        }
        int remainingDurability = stack.getMaxDamage() - stack.getDamageValue();
        return Math.max(0, remainingDurability - getMinDurability(stack));
    }

    public static UsageRestriction.ListType getBreakRestrictionListType() {
        if (getListTypeMethod == null || blockTypeBreakRestriction == null) {
            return UsageRestriction.ListType.NONE;
        }
        try {
            Object value = getListTypeMethod.invoke(blockTypeBreakRestriction);
            return value instanceof UsageRestriction.ListType type ? type : UsageRestriction.ListType.NONE;
        } catch (ReflectiveOperationException exception) {
            return UsageRestriction.ListType.NONE;
        }
    }

    public static List<String> getBreakRestrictionBlacklist() {
        return getStrings(blockTypeBreakRestrictionBlacklist);
    }

    public static List<String> getBreakRestrictionWhitelist() {
        return getStrings(blockTypeBreakRestrictionWhitelist);
    }

    private static List<String> getStrings(@Nullable Object config) {
        if (getStringsMethod == null || config == null) {
            return List.of();
        }
        try {
            Object value = getStringsMethod.invoke(config);
            return value instanceof List<?> list
                    ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                    : List.of();
        } catch (ReflectiveOperationException exception) {
            return List.of();
        }
    }

    private static int getMinDurability(ItemStack stack) {
        int threshold = getItemSwapDurabilityThreshold();
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 100 && threshold <= 20 && (double) threshold / (double) maxDamage > 0.08D) {
            threshold = (int) Math.ceil((double) maxDamage * 0.08D);
        }
        return threshold;
    }

    private static int getItemSwapDurabilityThreshold() {
        if (getIntegerValueMethod == null || itemSwapDurabilityThresholdConfig == null) {
            return FALLBACK_ITEM_SWAP_DURABILITY_THRESHOLD;
        }
        try {
            return (int) getIntegerValueMethod.invoke(itemSwapDurabilityThresholdConfig);
        } catch (Exception e) {
            e.printStackTrace();
            return FALLBACK_ITEM_SWAP_DURABILITY_THRESHOLD;
        }
    }
}
