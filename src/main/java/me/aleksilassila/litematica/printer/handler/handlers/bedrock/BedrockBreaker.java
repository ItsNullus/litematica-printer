package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class BedrockBreaker {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    private BedrockBreaker() {
    }

    public static boolean breakBlock(BlockPos pos) {
        return breakBlock(pos, Direction.DOWN, true);
    }

    public static boolean breakBlock(BlockPos pos, boolean predictRemoval) {
        return breakBlock(pos, Direction.DOWN, predictRemoval);
    }

    public static boolean breakBlock(BlockPos pos, Direction direction, boolean predictRemoval) {
        if (CLIENT.level == null || CLIENT.player == null) {
            return false;
        }
        var state = CLIENT.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        boolean cleanupResidue = BedrockTargetBlocks.isCleanupResidue(state);
        boolean switched = cleanupResidue
                ? BedrockInventory.switchToCleanupTool(state)
                : BedrockInventory.switchToBestTool(state);
        if (!switched) {
            return false;
        }
        if (!InteractionUtils.protectCurrentToolBeforeBreak(state)) {
            return false;
        }

        if (CLIENT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension) {
            BlockBreakResult result = gameModeExtension.litematica_printer$continueDestroyBlock(
                    false,
                    pos,
                    direction,
                    false,
                    false
            );
            return result != BlockBreakResult.FAILED && result != BlockBreakResult.ABORTED;
        }
        return false;
    }
}
