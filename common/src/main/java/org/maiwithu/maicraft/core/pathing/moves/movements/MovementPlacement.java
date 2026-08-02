package org.maiwithu.maicraft.core.pathing.moves.movements;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import java.util.List;

import org.maiwithu.maicraft.core.pathing.moves.Input;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.MovementState;
import org.maiwithu.maicraft.core.pathing.moves.MovementStatus;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 移动原语执行期的放置与视线共用逻辑:五贴面枚举、贴面中心瞄点、
 * 转速受限预判命中、耗材选取、视线命中判定。
 *
 * <p>视角推进用 AimProcessor 把理想目标转角折算成"这一 tick 头实际
 * 能转到哪"(受鼠标像素量化与转速上限约束)再 raytrace。需要单 tick
 * 大幅转头的放置候选(如 Pillar 空中低头看脚下、Parkour 切换看
 * dest.below)可能判定"这一 tick 还够不到"从而推迟到下一 tick 或换面。
 * 方块中心不可视时回退到方块碰撞形状的六面心做 raytrace(边角回退)。
 */
final class MovementPlacement {

    private MovementPlacement() {}

    /** 一次放置尝试的结论。 */
    enum PlaceResult {
        /** 视线已对准正确贴面,本 tick 可右键。 */
        READY_TO_PLACE,
        /** 找到可行贴面,已把目标转角写进 state,等转头。 */
        ATTEMPTING,
        /** 五个贴面都不可行。 */
        NO_OPTION
    }

    /** 放置贴面枚举顺序:四个水平向在前,DOWN 最后(不含 UP)。 */
    static final Direction[] HORIZONTALS_AND_DOWN = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    /** 潜行时的眼高(米)。 */
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    /**
     * 视角推进量化器:与 ExecHarness 同一灵敏度(0.5),用于把放置可行性的
     * 理想目标转角折算成"这一 tick 头实际能转到哪"再 raytrace。单例即可,
     * 纯数学、无状态。
     */
    private static final org.maiwithu.maicraft.core.pathing.execute.AimProcessor AIM =
            new org.maiwithu.maicraft.core.pathing.execute.AimProcessor();

    /** 放置可行性回退的六个面中心系数(先方块中心,再六面心)。 */
    private static final double[][] FACE_OFFSETS = {
            {0.5, 0.5, 0.5}, // 中心
            {0.5, 0.0, 0.5}, // 下
            {0.5, 1.0, 0.5}, // 上
            {0.5, 0.5, 0.0}, // 北
            {0.5, 0.5, 1.0}, // 南
            {0.0, 0.5, 0.5}, // 西
            {1.0, 0.5, 0.5}  // 东
    };

    /** 以玩家当前视角作为"当前转角"的便捷入口。 */
    static PlaceResult attemptToPlaceABlock(MovementState state, LocalPlayer player,
            BlockPos placeAt, boolean preferDown, boolean wouldSneak, Movement.ItemSelector selector) {
        return attemptToPlaceABlock(state, player, placeAt, preferDown, wouldSneak,
                player.getYRot(), player.getXRot(), selector);
    }

    /**
     * 尝试对 placeAt 找一个可行的放置贴面:先试直视 placeAt 本体
     * (可替换方块自带轮廓时能命中,中心不可视回退到六面心),再按
     * {@link #HORIZONTALS_AND_DOWN} 枚举五个贴面,要求贴面方块可贴、
     * 且沿"这一 tick 头实际能转到哪"的射线命中该贴面且命中面的邻格
     * 恰为 placeAt。preferDown=false 取第一个可行(水平优先),true 取
     * 最后一个(DOWN 优先,空中放置不必歪头)。
     *
     * <p>当前转角已命中正确目标 → READY_TO_PLACE(右键由调用方按);
     * 找到贴面但没对准 → ATTEMPTING;找不到 → NO_OPTION。
     * 没有可垫路耗材时置 UNREACHABLE 并返回 NO_OPTION。
     */
    static PlaceResult attemptToPlaceABlock(MovementState state, LocalPlayer player,
                                            BlockPos placeAt, boolean preferDown, boolean wouldSneak,
                                            float currentYaw, float currentPitch,
                                            Movement.ItemSelector selector) {
        BuildPlacementRegistry.recordScaffold(player, placeAt);
        Level level = player.level();
        double reach = NavSettings.get().blockReachDistance;
        Vec3 eye = eyePosition(player, wouldSneak);
        boolean found = false;
        BlockHitResult foundHit = null;
        float foundYaw = currentYaw;
        float foundPitch = currentPitch;

        // 直视 placeAt 本体(走到这一步说明该格必是可替换的)。中心不可视
        // 时回退到方块碰撞形状的六面心,用 peek 后的实际转角做 raytrace。
        for (double[] off : FACE_OFFSETS) {
            Vec3 aim = shapePoint(level, placeAt, off[0], off[1], off[2]);
            float yaw = AimGeometry.yawTo(eye, aim);
            float pitch = AimGeometry.pitchTo(eye, aim);
            org.maiwithu.maicraft.core.pathing.execute.AimProcessor.Rotation peek =
                    AIM.step(currentYaw, currentPitch, yaw, pitch);
            BlockHitResult hit = rayTrace(player, eye, peek.yaw(), peek.pitch(), reach);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(placeAt)) {
                if (selectForLocation(player, placeAt, hit, peek.yaw(), peek.pitch(), false, selector)
                        == Movement.ItemSelection.UNAVAILABLE) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                state.setTarget(new MovementState.MovementTarget(yaw, pitch, true));
                found = true;
                foundHit = hit;
                foundYaw = peek.yaw();
                foundPitch = peek.pitch();
                break; // 直视本体只取第一个可行,无需 preferDown
            }
        }

        for (int i = 0; i < 5; i++) {
            BlockPos against = placeAt.relative(HORIZONTALS_AND_DOWN[i]);
            if (!MovementHelper.canPlaceAgainst(level, against)) {
                continue;
            }
            // 贴面中心:两格坐标的中点,落在共享面上
            double faceX = (placeAt.getX() + against.getX() + 1.0) * 0.5;
            double faceY = (placeAt.getY() + against.getY() + 0.5) * 0.5;
            double faceZ = (placeAt.getZ() + against.getZ() + 1.0) * 0.5;
            Vec3 face = new Vec3(faceX, faceY, faceZ);
            float yaw = AimGeometry.yawTo(eye, face);
            float pitch = AimGeometry.pitchTo(eye, face);
            // 转速受限:把理想目标转角折算成这一 tick 头实际能转到哪再 raytrace
            org.maiwithu.maicraft.core.pathing.execute.AimProcessor.Rotation peek =
                    AIM.step(currentYaw, currentPitch, yaw, pitch);
            BlockHitResult hit = rayTrace(player, eye, peek.yaw(), peek.pitch(), reach);
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(against)
                    && hit.getBlockPos().relative(hit.getDirection()).equals(placeAt)) {
                if (selectForLocation(player, placeAt, hit, peek.yaw(), peek.pitch(), false, selector)
                        == Movement.ItemSelection.UNAVAILABLE) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                state.setTarget(new MovementState.MovementTarget(yaw, pitch, true));
                found = true;
                foundHit = hit;
                foundYaw = peek.yaw();
                foundPitch = peek.pitch();
                if (!preferDown) {
                    break; // 水平优先:第一个可行即取
                }
            }
        }

        // 当前转角已经命中正确目标 → 就绪
        BlockHitResult looking = rayTrace(player, eyePosition(player, wouldSneak), currentYaw, currentPitch, reach);
        if (looking.getType() == HitResult.Type.BLOCK) {
            BlockPos selected = looking.getBlockPos();
            if (selected.equals(placeAt)
                    || (MovementHelper.canPlaceAgainst(level, selected)
                            && selected.relative(looking.getDirection()).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                Movement.ItemSelection material = selectForLocation(
                        player, placeAt, looking, currentYaw, currentPitch, true, selector);
                if (material == Movement.ItemSelection.WAITING) {
                    return PlaceResult.ATTEMPTING;
                }
                if (material == Movement.ItemSelection.UNAVAILABLE) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            Movement.ItemSelection material = selectForLocation(
                    player, placeAt, foundHit, foundYaw, foundPitch, true, selector);
            if (material == Movement.ItemSelection.UNAVAILABLE) {
                state.setStatus(MovementStatus.UNREACHABLE);
                return PlaceResult.NO_OPTION;
            }
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    // 选料只有一个出口:先按图纸挑精确材料(施工中的格子值得放对),挑不出就
    // 退回通用垫路料。两条路最终都落到 selectThrowaway——免耗材画像的自动补料
    // 那只手就长在那里,于是"背包空着也能垫路"对两条路同时成立。
    //
    // 这条汇流是必需的,不是顺手:规划器按画像位认定"有料可垫",执行器若在某
    // 条支路上选不出料就报 UNREACHABLE,两边对同一动作各执一词,而重新规划的
    // 输入分毫未变——必然算出同一条路、再次夭折,规划器与执行器能对着掐到天
    // 荒地老。可行性判据必须只有一处真源。
    static Movement.ItemSelection selectForLocation(
            LocalPlayer player, BlockPos placeAt, boolean select, Movement.ItemSelector selector) {
        if (BuildPlacementRegistry.hasTarget(player, placeAt)
                ) {
            Movement.ItemSelection exact = BuildPlacementRegistry.selectForLocation(
                    player, placeAt, select, selector);
            if (exact != Movement.ItemSelection.UNAVAILABLE) {
                return exact;
            }
        }
        return selectThrowaway(player, select, selector);
    }

    static Movement.ItemSelection selectForLocation(
            LocalPlayer player, BlockPos placeAt, BlockHitResult hit,
            float yaw, float pitch, boolean select, Movement.ItemSelector selector) {
        if (BuildPlacementRegistry.hasTarget(player, placeAt)) {
            Movement.ItemSelection exact = BuildPlacementRegistry.selectForLocation(
                    player, placeAt, hit, yaw, pitch, select, selector);
            if (exact != Movement.ItemSelection.UNAVAILABLE) {
                return exact;
            }
        }
        return selectThrowaway(player, select, selector);
    }
    /** 方块碰撞形状上按 (mx,my,mz) 比例取点;空形状退回满格方块。 */
    private static Vec3 shapePoint(Level level, BlockPos pos, double mx, double my, double mz) {
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            shape = net.minecraft.world.phys.shapes.Shapes.block();
        }
        double x = shape.min(Direction.Axis.X) * mx + shape.max(Direction.Axis.X) * (1 - mx);
        double y = shape.min(Direction.Axis.Y) * my + shape.max(Direction.Axis.Y) * (1 - my);
        double z = shape.min(Direction.Axis.Z) * mz + shape.max(Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** Pick a configured scaffold by priority; selection/staging is delegated to the actor. */
    static Movement.ItemSelection selectThrowaway(
            LocalPlayer player, boolean select, Movement.ItemSelector selector) {
        List<Item> acceptable = ScaffoldMaterials.of(player);
        for (Item item : acceptable) {
            java.util.function.Predicate<ItemStack> desired =
                    stack -> !stack.isEmpty() && stack.getItem() == item;
            Movement.ItemSelection outcome = select
                    ? selector.select(desired)
                    : containsMaterial(player, desired);
            if (outcome != Movement.ItemSelection.UNAVAILABLE) {
                return outcome;
            }
        }
        return Movement.ItemSelection.UNAVAILABLE;
    }

    private static Movement.ItemSelection containsMaterial(
            LocalPlayer player, java.util.function.Predicate<ItemStack> desired) {
        Inventory inventory = player.getInventory();
        int upper = NavSettings.get().allowInventory
                ? Math.min(36, inventory.items.size()) : 9;
        for (int i = 0; i < upper; i++) {
            if (desired.test(inventory.getItem(i))) {
                return Movement.ItemSelection.READY;
            }
        }
        return desired.test(player.getOffhandItem())
                ? Movement.ItemSelection.READY
                : Movement.ItemSelection.UNAVAILABLE;
    }

    /** 玩家当前视线是否命中该方块(轮廓射线,不穿流体)。 */
    static boolean isLookingAt(LocalPlayer player, BlockPos pos) {
        BlockHitResult hit = rayTrace(player, player.getEyePosition(),
                player.getYRot(), player.getXRot(), NavSettings.get().blockReachDistance);
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** 玩家当前视角是否已对准 state 中的目标转角(容差 0.01°)。 */
    static boolean isFacing(LocalPlayer player, MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(normalizeDegrees(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 角度归一到 [-180, 180)。 */
    static float normalizeDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        return wrapped;
    }

    /** 执行期霜行者判定:装备有霜行者且目标格是静水源。 */
    static boolean canUseFrostWalker(LocalPlayer player, BlockState state) {
        return frostWalkerLevel(player) != 0
                && state.getBlock() == Blocks.WATER
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /** 全身装备的霜行者附魔最高等级。 */
    static int frostWalkerLevel(LocalPlayer player) {
        int level = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    level = Math.max(level, itemEnchantments.getLevel(enchant));
                }
            }
        }
        return level;
    }

    /** 沿指定转角从 eye 出发的轮廓射线(不含流体)。 */
    static BlockHitResult rayTrace(LocalPlayer player, Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 end = eye.add(direction(yaw, pitch).scale(reach));
        return player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /** 转角 → 单位视线向量。 */
    static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** 眼位;wouldSneak 时按潜行眼高取(提前用放置那一刻的视角算贴面)。 */
    static Vec3 eyePosition(LocalPlayer player, boolean wouldSneak) {
        if (wouldSneak) {
            return new Vec3(player.getX(), player.getY() + SNEAK_EYE_HEIGHT, player.getZ());
        }
        return player.getEyePosition();
    }
}
