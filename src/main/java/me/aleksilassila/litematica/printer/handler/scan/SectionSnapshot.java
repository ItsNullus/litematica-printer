package me.aleksilassila.litematica.printer.handler.scan;

/** Immutable compact copy of classifications observed in one section. */
public record SectionSnapshot(
        long sectionKey,
        long revision,
        byte[][] flags,
        long[][] observed
) {
    public SectionSnapshot {
        flags = deepCopy(flags);
        observed = deepCopy(observed);
    }

    @Override
    public byte[][] flags() {
        return deepCopy(this.flags);
    }

    @Override
    public long[][] observed() {
        return deepCopy(this.observed);
    }

    public boolean isObserved(ScanIntent intent, int index) {
        long[] words = this.observed[intent.ordinal()];
        return words != null && (words[index >>> 6] & 1L << (index & 63)) != 0L;
    }

    public byte flags(ScanIntent intent, int index) {
        byte[] values = this.flags[intent.ordinal()];
        return values == null ? 0 : values[index];
    }

    private static byte[][] deepCopy(byte[][] source) {
        byte[][] copy = source.clone();
        for (int index = 0; index < copy.length; index++) {
            if (copy[index] != null) copy[index] = copy[index].clone();
        }
        return copy;
    }

    private static long[][] deepCopy(long[][] source) {
        long[][] copy = source.clone();
        for (int index = 0; index < copy.length; index++) {
            if (copy[index] != null) copy[index] = copy[index].clone();
        }
        return copy;
    }
}
