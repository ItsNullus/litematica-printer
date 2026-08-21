package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface PrintTaskFactory {
    @Nullable
    PrintTask tryCreate(SchematicBlockContext context, LongSupplier tickClock);
}
