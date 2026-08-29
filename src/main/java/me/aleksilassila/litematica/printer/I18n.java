package me.aleksilassila.litematica.printer;

import lombok.Getter;
import me.aleksilassila.litematica.printer.utils.minecraft.StringUtils;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

@Getter
public class I18n {
    public static final I18n MESSAGE_TOGGLED = of("message.toggled");
    public static final I18n MESSAGE_VALUE_OFF = of("message.value.off");
    public static final I18n MESSAGE_VALUE_ON = of("message.value.on");

    public static final I18n AUTO_DISABLE_NOTICE = of("auto_disable_notice");
    public static final I18n FREE_NOTICE = of("free_notice");

    public static final I18n UPDATE_AVAILABLE = of("update.available");
    public static final I18n UPDATE_DOWNLOAD = of("update.download");
    public static final I18n UPDATE_FAILED = of("update.failed");
    public static final I18n UPDATE_PASSWORD = of("update.password");
    public static final I18n UPDATE_RECOMMENDATION = of("update.recommendation");
    public static final I18n UPDATE_REPOSITORY = of("update.repository");

    // 下落方块检查提示
    public static final I18n FALLING_BLOCK_NO_SUPPORT = of("message.falling_block.no_support");
    public static final I18n FALLING_BLOCK_MISMATCH = of("message.falling_block.mismatch");

    // 破基岩模式提示
    public static final I18n BEDROCK_CREATIVE_MODE = of("message.bedrock.creative_mode");

    // 快捷潜影盒提示
    public static final I18n SHULKER_MOD_NOT_LOADED = of("message.shulker.mod_not_loaded");

    // 关闭全部模式提示
    public static final I18n CLOSE_ALL_MODE_NOTICE = of("message.close_all_mode");

    // 库存提示
    public static final I18n INVENTORY_FULL = of("message.inventory.full");
    public static final I18n INVENTORY_RESTORE_FAILED = of("message.inventory.restore_failed");
    public static final I18n INVENTORY_SHULKER_OCCUPIED = of("message.inventory.shulker_occupied");
    public static final I18n RESERVE_ITEM_SKIP = of("message.reserve_item.skip");

    // 远程取物提示
    public static final I18n REMOTE_TAKE_START = of("message.remote_take.start");
    public static final I18n REMOTE_TAKE_COMPLETE = of("message.remote_take.complete");
    public static final I18n REMOTE_TAKE_CANCELLED = of("message.remote_take.cancelled");
    public static final I18n REMOTE_TAKE_FAILED = of("message.remote_take.failed");
    public static final I18n REMOTE_TAKE_TIMEOUT = of("message.remote_take.timeout");
    public static final I18n REMOTE_TAKE_SCAN_TIMEOUT = of("message.remote_take.scan_timeout");
    public static final I18n REMOTE_TAKE_NO_ITEM = of("message.remote_take.no_item");
    public static final I18n REMOTE_TAKE_INV_FULL = of("message.remote_take.inv_full");
    public static final I18n REMOTE_TAKE_NOT_SYNCED = of("message.remote_take.not_synced");
    public static final I18n REMOTE_TAKE_EXCEPTION = of("message.remote_take.exception");
    public static final I18n REMOTE_OPEN_TIMEOUT = of("message.remote_take.open_timeout");
    public static final I18n REMOTE_TAKE_RETURN_START = of("message.remote_take.return_start");
    public static final I18n REMOTE_TAKE_RETURN_DONE = of("message.remote_take.return_done");
    public static final I18n REMOTE_TAKE_RETURN_FAILED = of("message.remote_take.return_failed");
    public static final I18n REMOTE_TAKE_RETURN_NO_SPACE = of("message.remote_take.return_no_space");

    private static final String PREFIX_CONFIG = "config";
    private static final String PREFIX_COMMENT = "desc";

    private final @Nullable String prefix;
    private final String nameKey;
    private final String withPrefixNameKey;
    private final String descKey;
    private final String configNameKey;
    private final String configDescKey;

    private I18n(@Nullable String prefix, String nameKey) {
        this.prefix = prefix;
        this.nameKey = nameKey;
        this.withPrefixNameKey = prefix == null ? nameKey : prefix + "." + nameKey;
        this.descKey = withPrefixNameKey + "." + PREFIX_COMMENT;
        String configNameKey = prefix == null ? PREFIX_CONFIG : prefix + "." + PREFIX_CONFIG;
        this.configNameKey = configNameKey + "." + nameKey;
        this.configDescKey = configNameKey + "." + nameKey + "." + PREFIX_COMMENT;
    }

    public static I18n of(@Nullable String prefix, String key) {
        return new I18n(prefix, key);
    }

    public static I18n of(String key) {
        return new I18n(Reference.MOD_ID, key);
    }

    /*** 获取键名 ***/
    public MutableComponent getName() {
        return StringUtils.translatable(this.withPrefixNameKey);
    }

    /*** 获取键名(带参数) ***/
    public MutableComponent getName(Object... objects) {
        return StringUtils.translatable(this.withPrefixNameKey, objects);
    }

    /*** 获取描述 ***/
    public MutableComponent getDesc() {
        return StringUtils.translatable(this.descKey);
    }

    /*** 获取描述(带参数) ***/
    public MutableComponent getDesc(Object... objects) {
        return StringUtils.translatable(this.descKey, objects);
    }

    /*** 获取配置键名 ***/
    public MutableComponent getConfigName() {
        return StringUtils.translatable(this.configNameKey);
    }

    /*** 获取配置键名(带参数) ***/
    public MutableComponent getConfigName(Object... objects) {
        return StringUtils.translatable(this.configNameKey, objects);
    }

    /*** 获取配置描述 ***/
    public MutableComponent getConfigDesc() {
        return StringUtils.translatable(this.configDescKey);
    }

    /*** 获取配置描述(带参数) ***/
    public MutableComponent getConfigDesc(Object... objects) {
        return StringUtils.translatable(this.configDescKey, objects);
    }

    /*** 获取简易键名(一般用于枚举, 会取 "." 最后的文本) ***/
    public String getSimpleKey() {
        if (nameKey == null || nameKey.isEmpty()) {
            return nameKey == null ? "" : nameKey;
        }
        int lastDotIndex = nameKey.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return nameKey;
        }
        if (lastDotIndex == nameKey.length() - 1) {
            return "";
        }
        return nameKey.substring(lastDotIndex + 1);
    }
}
