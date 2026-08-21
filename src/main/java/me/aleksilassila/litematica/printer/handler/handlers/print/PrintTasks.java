package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.LongSupplier;

public final class PrintTasks {
    private static final List<PrintTaskFactory> FACTORIES = List.of(
            WaterPrintTask::tryCreate
    );

    private PrintTasks() {
    }

    @Nullable
    public static PrintTask tryCreate(SchematicBlockContext context, LongSupplier tickClock) {
        for (PrintTaskFactory factory : FACTORIES) {
            PrintTask task = factory.tryCreate(context, tickClock);
            if (task != null) {
                return task;
            }
        }
        return null;
    }
}
