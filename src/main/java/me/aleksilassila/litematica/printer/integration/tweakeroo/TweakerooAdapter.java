package me.aleksilassila.litematica.printer.integration.tweakeroo;

import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.UsageRestrictionCache;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils;
import net.minecraft.world.level.block.state.BlockState;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

/** Optional Tweakeroo capability boundary used by mining. */
public final class TweakerooAdapter {
    private final UsageRestrictionCache restrictionCache = new UsageRestrictionCache();

    public boolean isLoaded() {
        return ModLoadUtils.isTweakerooLoaded();
    }

    public boolean isToolSwitchEnabled() {
        return this.isLoaded() && TweakerooUtils.isToolSwitchEnabled();
    }

    public boolean allowsBreak(BlockState state) {
        if (!this.isLoaded()) {
            return true;
        }
        UsageRestriction.ListType listType = BLOCK_TYPE_BREAK_RESTRICTION.getListType();
        return this.restrictionCache.allows(
                "tweakeroo",
                listType,
                BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings(),
                BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings(),
                state
        );
    }

    public boolean allowsConfiguredBreak(BlockState state) {
        Object optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
        UsageRestriction.ListType listType = optionListValue instanceof UsageRestriction.ListType type
                ? type
                : UsageRestriction.ListType.NONE;
        return this.restrictionCache.allows(
                "custom",
                listType,
                Configs.Mine.EXCAVATE_BLACKLIST.getStrings(),
                Configs.Mine.EXCAVATE_WHITELIST.getStrings(),
                state
        );
    }
}
