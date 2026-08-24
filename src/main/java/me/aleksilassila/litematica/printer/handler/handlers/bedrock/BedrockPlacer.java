package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class BedrockPlacer {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final Map<BlockPos, PendingHorizontalPlacement> pendingHorizontalPistonPlacements = new HashMap<>();

    private BedrockPlacer() {
    }

    public static void clearHorizontalLookState() {
        pendingHorizontalPistonPlacements.clear();
        NetworkUtils.clearScopedLookOverride();
    }

    public static boolean hasPendingHorizontalLook(BlockPos pistonPos) {
        return pistonPos != null && pendingHorizontalPistonPlacements.containsKey(pistonPos.immutable());
    }

    /**
     * 当 USE_CARPET_PROTOCOL 生效时，所有原本需要转头的放置改用 Carpet 协议编码朝向，
     * 从而跳过转头包，提升破基岩吞吐量。
     */
    static boolean isCarpetProtocolActive() {
        boolean easyPlace = Configs.Placement.EASY_PLACE_PROTOCOL.getBooleanValue();
        boolean useCarpetEnabled = Configs.Placement.USE_CARPET_PROTOCOL.getBooleanValue();
        if (!useCarpetEnabled) {
            return false;
        }
        boolean carpetPriority = Configs.Placement.CARPET_PROTOCOL_PRIORITY.getBooleanValue();
        return !easyPlace || carpetPriority;
    }

    public static boolean placeSimple(BlockPos supportPos, Direction clickedFace, Item item) {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            return false;
        }
        if (!BedrockInventory.switchToOffhand(item)) {
            return false;
        }
        if (!isCarpetProtocolActive()) {
            PlayerLook look = new PlayerLook(clickedFace.getOpposite());
            NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        }
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        placeBlockAggressively(player, hitResult, true);
        return true;
    }

    public static boolean placePiston(BlockPos pistonPos, Direction facing) {
        return placePiston(pistonPos, facing, pistonPos.relative(facing.getOpposite()));
    }

    public static boolean preparePistonPlacementLook(BlockPos pistonPos, Direction facing) {
        if (isCarpetProtocolActive()) {
            // Carpet 协议将朝向编码进 hitPos，无需转头准备
            return true;
        }
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            return false;
        }

        PlayerLook look = new PlayerLook(facing.getOpposite());
        return !ensureHorizontalLookSettled(player, pistonPos, facing, look, false);
    }

    public static boolean placePiston(BlockPos pistonPos, Direction facing, BlockPos... preferredAnchors) {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }
        if (!BedrockInventory.switchToOffhand(Blocks.PISTON.asItem())) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }

        boolean useCarpet = isCarpetProtocolActive();

        if (!useCarpet) {
            // Pistons face opposite to the direction the player is looking when placed.
            // We want the resulting piston facing to match `facing`, so look at the opposite side.
            PlayerLook look = new PlayerLook(facing.getOpposite());
            if (ensureHorizontalLookSettled(player, pistonPos, facing, look, true)) {
                return false;
            }
            applyPlacementLook(player, look);
        }

        BlockPos clickedPos = pistonPos.relative(facing.getOpposite());
        Direction clickedFace = facing;
        if (CLIENT.level != null) {
            BlockPos[] anchors = preferredAnchors != null && preferredAnchors.length > 0
                    ? preferredAnchors
                    : new BlockPos[]{clickedPos};
            BedrockEnvironment.PlacementInteraction placementInteraction =
                    BedrockEnvironment.findPlacementInteraction(CLIENT.level, pistonPos, anchors);
            if (placementInteraction != null) {
                clickedPos = placementInteraction.anchorPos();
                clickedFace = placementInteraction.clickedFace();
            }
        }

        Vec3 hitVec;
        if (useCarpet) {
            // 使用 Carpet 协议将活塞朝向编码进 hitPos.x，服务器端 Carpet Extra 据此放置正确朝向
            BlockState pistonState = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing);
            Vec3 encoded = LitematicaUtils.usePrecisionPlacement(clickedPos, pistonState);
            hitVec = encoded != null ? encoded : Vec3.atCenterOf(clickedPos);
        } else {
            hitVec = Vec3.atCenterOf(clickedPos);
        }

        BlockHitResult hitResult = new BlockHitResult(hitVec, clickedFace, clickedPos, false);

        placeBlockAggressively(player, hitResult, false);
        NetworkUtils.clearScopedLookOverride();
        return true;
    }

    private static void placeBlockAggressively(LocalPlayer player, BlockHitResult hitResult, boolean allowLocalUseFallback) {
        boolean useShift = CLIENT.level != null && BedrockTargetBlocks.requiresSneakPlacement(CLIENT.level.getBlockState(hitResult.getBlockPos()));
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            ActionManager.INSTANCE.setShift(player, true);
        }
        try {
            InteractionUtils.INSTANCE.useItemOn(false, InteractionHand.OFF_HAND, hitResult);
            if (allowLocalUseFallback) {
                ItemStack offhand = player.getOffhandItem();
                if (!offhand.isEmpty()) {
                    offhand.useOn(new UseOnContext(player, InteractionHand.OFF_HAND, hitResult));
                }
            }
        } finally {
            if (useShift && !wasSneak) {
                ActionManager.INSTANCE.setShift(player, false);
            }
        }
    }

    private static boolean ensureHorizontalLookSettled(LocalPlayer player, BlockPos pistonPos, Direction facing, PlayerLook look, boolean consumeReadyPlacement) {
        Direction lookDirection = DirectionUtils.orderedByNearest(look.getYaw(), look.getPitch())[0];
        BlockPos pendingKey = pistonPos.immutable();
        if (!lookDirection.getAxis().isHorizontal()) {
            pendingHorizontalPistonPlacements.remove(pendingKey);
            NetworkUtils.clearScopedLookOverride();
            return false;
        }

        PendingHorizontalPlacement pendingPlacement = pendingHorizontalPistonPlacements.get(pendingKey);
        if (pendingPlacement != null && facing == pendingPlacement.facing()) {
            NetworkUtils.setScopedLookOverride(look);
            if (!isHorizontalLookReady(pendingPlacement)) {
                return true;
            }
            if (consumeReadyPlacement) {
                pendingHorizontalPistonPlacements.remove(pendingKey);
            }
            return false;
        }

        long sentTick = ClientPlayerTickManager.getCurrentHandlerTime();
        int packetEpoch = ClientPlayerTickManager.getPacketEpoch();
        pendingHorizontalPistonPlacements.put(pendingKey, new PendingHorizontalPlacement(facing, sentTick, packetEpoch));
        NetworkUtils.setScopedLookOverride(look);
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        return true;
    }

    private static boolean isHorizontalLookReady(PendingHorizontalPlacement pendingPlacement) {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now <= pendingPlacement.sentTick()) {
            return false;
        }
        if (ClientPlayerTickManager.getPacketEpoch() > pendingPlacement.packetEpoch()) {
            return true;
        }
        return now - pendingPlacement.sentTick() >= 2L;
    }

    private static void applyPlacementLook(LocalPlayer player, PlayerLook look) {
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
    }

    private record PendingHorizontalPlacement(Direction facing, long sentTick, int packetEpoch) {
    }
}
