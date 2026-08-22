package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class BedrockPlacer {
    private final Minecraft client;
    private final Map<BlockPos, PendingHorizontalPlacement> pendingHorizontalPistonPlacements = new HashMap<>();

    BedrockPlacer(Minecraft client) {
        this.client = client;
    }

    public void clearHorizontalLookState() {
        pendingHorizontalPistonPlacements.clear();
        NetworkUtils.clearScopedLookOverride();
    }

    public boolean hasPendingHorizontalLook(BlockPos pistonPos) {
        return pistonPos != null && pendingHorizontalPistonPlacements.containsKey(pistonPos.immutable());
    }

    public boolean placeSimple(BlockPos supportPos, Direction clickedFace, Item item) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return false;
        }
        if (!BedrockInventory.switchToOffhand(item)) {
            return false;
        }
        PlayerLook look = new PlayerLook(clickedFace.getOpposite());
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        placeBlockAggressively(player, hitResult, true);
        return true;
    }

    public boolean placePiston(BlockPos pistonPos, Direction facing) {
        return placePiston(pistonPos, facing, pistonPos.relative(facing.getOpposite()));
    }

    public boolean preparePistonPlacementLook(BlockPos pistonPos, Direction facing) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return false;
        }

        PlayerLook look = new PlayerLook(facing.getOpposite());
        return !ensureHorizontalLookSettled(player, pistonPos, facing, look, false);
    }

    public boolean placePiston(BlockPos pistonPos, Direction facing, BlockPos... preferredAnchors) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }
        if (!BedrockInventory.switchToOffhand(Blocks.PISTON.asItem())) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }

        // Pistons face opposite to the direction the player is looking when placed.
        // We want the resulting piston facing to match `facing`, so look at the opposite side.
        PlayerLook look = new PlayerLook(facing.getOpposite());
        if (ensureHorizontalLookSettled(player, pistonPos, facing, look, true)) {
            return false;
        }
        applyPlacementLook(player, look);

        BlockPos clickedPos = pistonPos.relative(facing.getOpposite());
        Direction clickedFace = facing;
        if (client.level != null) {
            BlockPos[] anchors = preferredAnchors != null && preferredAnchors.length > 0
                    ? preferredAnchors
                    : new BlockPos[]{clickedPos};
            BedrockEnvironment.PlacementInteraction placementInteraction =
                    BedrockEnvironment.findPlacementInteraction(client.level, pistonPos, anchors);
            if (placementInteraction != null) {
                clickedPos = placementInteraction.anchorPos();
                clickedFace = placementInteraction.clickedFace();
            }
        }

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(clickedPos), clickedFace, clickedPos, false);

        placeBlockAggressively(player, hitResult, false);
        NetworkUtils.clearScopedLookOverride();
        return true;
    }

    private void placeBlockAggressively(LocalPlayer player, BlockHitResult hitResult, boolean allowLocalUseFallback) {
        boolean useShift = client.level != null && BedrockTargetBlocks.requiresSneakPlacement(client.level.getBlockState(hitResult.getBlockPos()));
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            RuntimeAccess.get().actionBroker().setShift(player, true);
        }
        try {
            InteractionUtils.getRuntime().useItemOn(false, InteractionHand.OFF_HAND, hitResult);
            if (allowLocalUseFallback) {
                ItemStack offhand = player.getOffhandItem();
                if (!offhand.isEmpty()) {
                    offhand.useOn(new UseOnContext(player, InteractionHand.OFF_HAND, hitResult));
                }
            }
        } finally {
            if (useShift && !wasSneak) {
            RuntimeAccess.get().actionBroker().setShift(player, false);
            }
        }
    }

    private boolean ensureHorizontalLookSettled(LocalPlayer player, BlockPos pistonPos, Direction facing, PlayerLook look, boolean consumeReadyPlacement) {
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

        long sentTick = RuntimeAccess.get().currentTick();
        pendingHorizontalPistonPlacements.put(pendingKey, new PendingHorizontalPlacement(facing, sentTick));
        NetworkUtils.setScopedLookOverride(look);
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        return true;
    }

    private boolean isHorizontalLookReady(PendingHorizontalPlacement pendingPlacement) {
        long now = RuntimeAccess.get().currentTick();
        // Movement and interaction packets share the ordered game connection.  Sending the
        // placement on the following client tick is sufficient and keeps the original safety
        // boundary without treating an unrelated inbound packet as an acknowledgement.
        return now > pendingPlacement.sentTick();
    }

    private void applyPlacementLook(LocalPlayer player, PlayerLook look) {
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
    }

    private record PendingHorizontalPlacement(Direction facing, long sentTick) {
    }
}
