package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum SupportPlaceModeType implements ConfigOptionListEntry<SupportPlaceModeType> {
    NONE("supportPlaceMode.none"),
    DOWN("supportPlaceMode.down"),
    ALL("supportPlaceMode.all");

    private final I18n i18n;

    SupportPlaceModeType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
