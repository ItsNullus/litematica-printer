package me.aleksilassila.litematica.printer.integration.inventory;

import net.minecraft.world.item.Item;

/** Immutable, tokenized request for one material kind. */
public record MaterialRequest(long token, Item item, int minimumCount, Source source) {
    public MaterialRequest {
        if (token <= 0L) throw new IllegalArgumentException("token must be positive");
        if (item == null) throw new IllegalArgumentException("item must not be null");
        if (minimumCount <= 0) throw new IllegalArgumentException("minimumCount must be positive");
    }

    public enum Source {
        PRINT,
        PICK_BLOCK,
        OTHER
    }
}
