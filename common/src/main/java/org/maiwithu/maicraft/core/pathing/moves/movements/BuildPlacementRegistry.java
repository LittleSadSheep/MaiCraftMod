package org.maiwithu.maicraft.core.pathing.moves.movements;

import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.build.BuildValidity;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Predicate;

/** Active construction material lookup used by path movements that place blocks. */
public final class BuildPlacementRegistry {

    public interface Provider {
        BlockState desiredState(BlockPos placeAt);

        default boolean acceptsPlacement(BlockPos placeAt, BlockState state) {
            BlockState desired = desiredState(placeAt);
            return desired != null && BuildValidity.valid(state, desired, true);
        }
    }

    /** One first-person client body exists in a process; never retain that LocalPlayer here. */
    private static Provider activeProvider;
    private static final java.util.Set<BlockPos> SCAFFOLD = new java.util.LinkedHashSet<>();

    private BuildPlacementRegistry() {}

    /** 寻路准备在该格放置(脚手架/垫柱/搭桥):给建造任务留回执,交付前清残料用。 */
    public static void recordScaffold(LocalPlayer player, BlockPos placeAt) {
        if (activeProvider == null) {
            return;   // 无建造任务在册:挖矿等场景的搭桥不记账,防集合无界生长
        }
        SCAFFOLD.add(placeAt.immutable());
    }

    /** 领走该玩家累计的放置回执(领后清空)。 */
    public static java.util.Set<BlockPos> drainScaffold(LocalPlayer player) {
        java.util.Set<BlockPos> out = java.util.Set.copyOf(SCAFFOLD);
        SCAFFOLD.clear();
        return out;
    }

    public static void register(LocalPlayer player, Provider provider) {
        activeProvider = java.util.Objects.requireNonNull(provider, "provider");
    }

    public static void unregister(LocalPlayer player, Provider provider) {
        if (activeProvider == provider) {
            activeProvider = null;
            SCAFFOLD.clear();
        }
    }


    static boolean hasTarget(LocalPlayer player, BlockPos placeAt) {
        return activeProvider != null && activeProvider.desiredState(placeAt) != null;
    }

    static Movement.ItemSelection selectForLocation(
            LocalPlayer player, BlockPos placeAt, boolean select, Movement.ItemSelector selector) {
        return selectForLocation(
                player, placeAt, null, player.getYRot(), player.getXRot(), select, selector);
    }

    static Movement.ItemSelection selectForLocation(
            LocalPlayer player, BlockPos placeAt, BlockHitResult hit,
            float yaw, float pitch, boolean select, Movement.ItemSelector selector) {
        Provider provider = activeProvider;
        if (provider == null) {
            return Movement.ItemSelection.UNAVAILABLE;
        }
        BlockState desired = provider.desiredState(placeAt);
        if (desired == null) {
            return Movement.ItemSelection.UNAVAILABLE;
        }
        Movement.ItemSelection exact = selectMatching(
                player, select, selector, stack -> wouldPlaceAccepted(
                        player, stack, placeAt, hit, yaw, pitch, provider));
        if (exact != Movement.ItemSelection.UNAVAILABLE) {
            return exact;
        }
        Movement.ItemSelection sameBlock = selectMatching(
                player, select, selector, stack -> wouldPlaceSameBlock(
                        player, stack, desired, hit, yaw, pitch));
        if (sameBlock != Movement.ItemSelection.UNAVAILABLE) {
            return sameBlock;
        }
        return selectGenericThrowaway(player, hit, yaw, pitch, select, selector);
    }

    private static Movement.ItemSelection selectMatching(
            LocalPlayer player, boolean select, Movement.ItemSelector selector,
            Predicate<ItemStack> desired) {
        if (select) {
            return selector.select(desired);
        }
        Inventory inv = player.getInventory();
        int upper = NavSettings.get().allowInventory ? Math.min(36, inv.items.size()) : 9;
        for (int i = 0; i < upper; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && desired.test(stack)) {
                return Movement.ItemSelection.READY;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && desired.test(offhand)) {
            return Movement.ItemSelection.READY;
        }
        return Movement.ItemSelection.UNAVAILABLE;
    }

    private static boolean wouldPlaceAccepted(LocalPlayer player, ItemStack stack, BlockPos placeAt,
                                              BlockHitResult hit, float yaw, float pitch,
                                              Provider provider) {
        if (hit == null) {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            BlockState fallback = blockItem.getBlock().defaultBlockState();
            return provider.acceptsPlacement(placeAt, fallback);
        }
        BlockState state = predictedState(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND);
        return state != null && provider.acceptsPlacement(placeAt, state);
    }

    private static boolean wouldPlaceSameBlock(LocalPlayer player, ItemStack stack, BlockState desired,
                                               BlockHitResult hit, float yaw, float pitch) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        if (hit == null) {
            return desired.getBlock() == blockItem.getBlock();
        }
        BlockState state = predictedState(player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND);
        return state != null && state.getBlock() == desired.getBlock();
    }

    private static Movement.ItemSelection selectGenericThrowaway(
            LocalPlayer player, BlockHitResult hit, float yaw, float pitch,
            boolean select, Movement.ItemSelector selector) {
        for (Item item : ScaffoldMaterials.of(player)) {
            Predicate<ItemStack> desired = stack -> !stack.isEmpty()
                    && stack.getItem() == item
                    && wouldPlaceAny(
                            player, stack, hit, yaw, pitch, InteractionHand.MAIN_HAND);
            Movement.ItemSelection outcome = select
                    ? selector.select(desired)
                    : selectMatching(player, false, selector, desired);
            if (outcome != Movement.ItemSelection.UNAVAILABLE) {
                return outcome;
            }
        }
        return Movement.ItemSelection.UNAVAILABLE;
    }

    private static boolean wouldPlaceAny(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                         float yaw, float pitch, InteractionHand hand) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        return hit == null || predictedState(player, stack, hit, yaw, pitch, hand) != null;
    }

    private static BlockState predictedState(LocalPlayer player, ItemStack stack, BlockHitResult hit,
                                             float yaw, float pitch, InteractionHand hand) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Vec3 look = MovementPlacement.direction(yaw, pitch);
        Direction[] nearest = Direction.values();
        java.util.Arrays.sort(nearest, java.util.Comparator.comparingDouble(direction ->
                -(direction.getStepX() * look.x
                        + direction.getStepY() * look.y
                        + direction.getStepZ() * look.z)));
        try {
            BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, hand, stack, hit) {}) {
                @Override
                public Direction getHorizontalDirection() {
                    return Direction.fromYRot(yaw);
                }

                @Override
                public float getRotation() {
                    return yaw;
                }

                @Override
                public Direction getNearestLookingDirection() {
                    return Direction.getNearest(look.x, look.y, look.z);
                }

                @Override
                public Direction getNearestLookingVerticalDirection() {
                    return look.y >= 0.0 ? Direction.UP : Direction.DOWN;
                }

                @Override
                public Direction[] getNearestLookingDirections() {
                    return nearest.clone();
                }
            };
            BlockState state = blockItem.getBlock().getStateForPlacement(context);
            return state != null && context.canPlace() ? state : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}