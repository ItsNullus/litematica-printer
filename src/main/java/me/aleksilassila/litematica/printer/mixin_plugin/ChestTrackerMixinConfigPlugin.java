package me.aleksilassila.litematica.printer.mixin_plugin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 配置插件：Chest Tracker 相关 mixin 只在安装 Chest Tracker 时应用，
 * 避免未安装时因 mixin 目标类不存在导致整个 mixin 配置加载失败。
 */
public class ChestTrackerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String CHESTTRACKER_MIXIN_PREFIX =
            "me.aleksilassila.litematica.printer.mixin.printer.chesttracker.";

    private boolean chestTrackerLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        this.chestTrackerLoaded = FabricLoader.getInstance().isModLoaded("chesttracker");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(CHESTTRACKER_MIXIN_PREFIX)) {
            return this.chestTrackerLoaded;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
