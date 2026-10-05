// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import sun.misc.Unsafe;

/**
 * 脱困放行策略回归：起点已在岩浆致死邻域内时，比身体更远离危险源的
 * 破坏开口放行（危险邻域起点不再「任何路线都全拒」）；无危险邻域时
 * 策略不激活，常规危险回避原样生效。放行唯一依据是破坏开口与危险源
 * 的几何距离比较（方块中心欧氏距离），头顶岩浆永不禁豁免。
 */
public final class HazardEscapePolicyTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Scene scene = new Scene();

        // 无岩浆:策略不激活。
        BlockPos feet = BlockPos.ZERO;
        check(HazardEscapePolicy.detect(scene, feet) == HazardEscapePolicy.INACTIVE,
                "no lava means no escape mode");

        // 距源 2 格:激活,建议方向为净远离一侧(源在东,建议向西撤)。
        BlockPos lava = new BlockPos(2, 0, 0);
        scene.put(lava, Blocks.LAVA.defaultBlockState());
        HazardEscapePolicy escape = HazardEscapePolicy.detect(scene, feet);
        check(escape.active(), "lava two blocks away activates escape mode");
        check(escape.hazard().equals(lava), "nearest hazard cell is reported");
        check(Math.abs(escape.hazardDistance() - 2.0) < 1e-9, "hazard distance is measured from the feet");
        check("WEST".equals(escape.evidence().get("escape_direction")),
                "escape direction points away from the hazard");
        check(escape.evidence().get("suggested_retreat").equals(java.util.List.of(-6, 0, 0)),
                "suggested retreat is six blocks away on the far side");

        // 放行判定的几何:比起点更远离危险源的开口放行,同距或更近一侧拒绝。
        check(escape.permitsBreakingBesideLava(feet, -1, 0, 0),
                "a break cell beyond the body away from the hazard is admitted");
        check(!escape.permitsBreakingBesideLava(feet, 0, 0, 0),
                "the body cell itself is not admitted (same distance)");
        check(!escape.permitsBreakingBesideLava(feet, 1, 0, 0),
                "a break cell between the body and the hazard stays rejected");

        // 检测半径:3 格内激活,4 格外不激活(原场景 2 格被拒,边界未知,取 3 保守覆盖)。
        scene.clear();
        scene.put(new BlockPos(3, 0, 0), Blocks.LAVA.defaultBlockState());
        check(HazardEscapePolicy.detect(scene, feet).active(), "lava at the detection edge activates");
        scene.clear();
        scene.put(new BlockPos(4, 0, 0), Blocks.LAVA.defaultBlockState());
        check(HazardEscapePolicy.detect(scene, feet) == HazardEscapePolicy.INACTIVE,
                "lava beyond the detection edge keeps normal avoidance");

        // 危险在正上方:仍激活,水平方向退化为向上提示。
        scene.clear();
        scene.put(new BlockPos(0, 1, 0), Blocks.LAVA.defaultBlockState());
        HazardEscapePolicy overhead = HazardEscapePolicy.detect(scene, feet);
        check(overhead.active() && "UP".equals(overhead.evidence().get("escape_direction")),
                "overhead lava still activates and reports an upward hint");

        // 禁挖判定端到端:岩浆源在正前 2 格并已横向漫延到两侧前斜格,两侧石壁
        // 都贴岩浆。常规模式两壁全拒(危险回避不变);脱困模式放行远离侧的
        // 侧壁开口,头顶岩浆的邻格在任何模式下都拒绝。
        scene.clear();
        scene.put(new BlockPos(0, 0, 2), Blocks.LAVA.defaultBlockState());
        scene.put(new BlockPos(0, 0, 1), Blocks.LAVA.defaultBlockState());
        scene.put(new BlockPos(-1, 0, 1), Blocks.LAVA.defaultBlockState());
        scene.put(new BlockPos(1, 0, 1), Blocks.LAVA.defaultBlockState());
        scene.put(new BlockPos(-1, 0, 0), Blocks.STONE.defaultBlockState());
        scene.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        CalculationContext ctx = emptyContext(scene);
        check(MovementHelper.avoidBreaking(ctx, -1, 0, 0, Blocks.STONE.defaultBlockState()),
                "without escape mode the lava-adjacent side wall stays unbreakable (normal avoidance unchanged)");
        check(MovementHelper.avoidBreaking(ctx, 1, 0, 0, Blocks.STONE.defaultBlockState()),
                "the other side wall is equally refused before escape mode");
        setEscape(ctx, HazardEscapePolicy.detect(scene, feet));
        check(!MovementHelper.avoidBreaking(ctx, -1, 0, 0, Blocks.STONE.defaultBlockState()),
                "escape mode admits breaking the side wall opening away from the hazard");
        scene.put(new BlockPos(-1, 1, 0), Blocks.LAVA.defaultBlockState());
        setEscape(ctx, HazardEscapePolicy.detect(scene, feet));
        check(MovementHelper.avoidBreaking(ctx, -1, 0, 0, Blocks.STONE.defaultBlockState()),
                "a wall with lava directly above stays unbreakable even in escape mode");

        System.out.println("HazardEscapePolicyTest: passed");
    }

    /** 空场景上下文:绕开需要实时玩家的构造,只填禁挖判定读到的字段。 */
    private static CalculationContext emptyContext(Scene scene) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var memory = (Unsafe) field.get(null);
        var ctx = (CalculationContext) memory.allocateInstance(CalculationContext.class);
        var borderField = CalculationContext.class.getDeclaredField("worldBorder");
        borderField.setAccessible(true);
        borderField.set(ctx, new CalculationContext.BorderSnapshot(-1e7, 1e7, -1e7, 1e7));
        setEscape(ctx, HazardEscapePolicy.INACTIVE);
        var bodyField = CalculationContext.class.getDeclaredField("escapeBody");
        bodyField.setAccessible(true);
        bodyField.set(ctx, BlockPos.ZERO);
        var viewField = CalculationContext.class.getDeclaredField("view");
        viewField.setAccessible(true);
        viewField.set(ctx, scene);
        var cursorField = CalculationContext.class.getDeclaredField("cursor");
        cursorField.setAccessible(true);
        cursorField.set(ctx, new BlockPos.MutableBlockPos());
        return ctx;
    }

    /** final 字段经反射换装脱困策略,模拟两种模式的上下文。 */
    private static void setEscape(CalculationContext ctx, HazardEscapePolicy policy) throws Exception {
        var escapeField = CalculationContext.class.getDeclaredField("hazardEscape");
        escapeField.setAccessible(true);
        escapeField.set(ctx, policy);
    }

    /** 脚部零点周围的固定小场景。 */
    private static final class Scene implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(BlockPos pos, BlockState state) { blocks.put(pos.immutable(), state); }

        void clear() { blocks.clear(); }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("escape policy must not read block entities"); }

        @Override
        public int getHeight() { return 384; }

        @Override
        public int getMinBuildHeight() { return -64; }
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
