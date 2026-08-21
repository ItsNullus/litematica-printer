package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Main-thread-safe observation contract consumed by the scan classifier. */
interface WorldObservationPort {
    boolean hasChunk(int chunkX, int chunkZ);

    BlockState worldState(BlockPos pos);

    @Nullable BlockState schematicState(BlockPos pos);

    boolean hasFillSupport(BlockPos pos);
}
