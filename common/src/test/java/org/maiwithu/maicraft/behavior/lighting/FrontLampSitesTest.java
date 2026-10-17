// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;

/** 随行插火的灯位：正前方视野锥里挑最近的一处；箱子不作灯座，不在锥里、够不着的不要。 */
class FrontLampSitesTest {

    private static BlockState STONE;
    private static final Predicate<BlockPos> NO_SCREEN = pos -> false;
    private static final Predicate<BlockPos> NOTHING_PROTECTED = pos -> false;

    /** 第一人称现场替身：一张方块表；看得见 = 那格已加载。 */
    private static final class FakeScene implements FirstPersonScene {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> loaded = new HashSet<>();
        final Vec3 eye = new Vec3(0.5, 65.62, 0.5);
        Vec3 view = new Vec3(0, 0, -1);

        FakeScene put(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
            loaded.add(pos.immutable());
            return this;
        }

        @Override public Vec3 eyePosition() { return eye; }
        @Override public Vec3 viewVector() { return view; }
        @Override public HitResult sightRay() { return new HitResult(Vec3.ZERO) { @Override public Type getType() { return Type.MISS; } }; }
        @Override public BlockHitResult visibleHit(BlockPos target) {
            return loaded.contains(target.immutable())
                    ? new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5),
                            Direction.UP, target, false) : null;
        }
        @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) { return null; }
        @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) { return false; }
        @Override public BlockState blockAt(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }
        @Override public boolean isLoaded(BlockPos pos) { return true; }
        @Override public ItemStack heldItem(InteractionHand hand) {
            return ItemStack.EMPTY;
        }
    }

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** 正前方一面石头墙（z=-2 那排），脚下是草地。 */
    private static FakeScene wallAhead() {
        FakeScene scene = new FakeScene();
        for (int x = -4; x <= 4; x++) {
            for (int y = 64; y <= 66; y++) scene.put(at(x, y, -2), STONE);
            scene.put(at(x, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState());
            scene.put(at(x, 65, 0), Blocks.AIR.defaultBlockState());
        }
        return scene;
    }

    @Test
    void 正前方的墙给贴墙火把_指向离开墙() {
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(wallAhead(), NO_SCREEN,
                NOTHING_PROTECTED, new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, -1), LampItem.torch());
        assertTrue(spot.isPresent());
        assertEquals(Blocks.WALL_TORCH, spot.get().state().getBlock());
        assertEquals(Direction.SOUTH, spot.get().state().getValue(WallTorchBlock.FACING), "背对墙照亮自己这边");
        assertEquals(at(0, 65, -1), spot.get().lamp());
        assertEquals(at(0, 65, -2), spot.get().support());
    }

    @Test
    void 地面给立式火把() {
        FakeScene scene = new FakeScene();
        // 没墙：正前方两格是平地。
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 2; z++) {
                scene.put(at(x, 64, z), Blocks.GRASS_BLOCK.defaultBlockState());
                scene.put(at(x, 65, z), Blocks.AIR.defaultBlockState());
            }
        }
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(scene, NO_SCREEN,
                NOTHING_PROTECTED, new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, -1), LampItem.torch());
        assertTrue(spot.isPresent());
        assertEquals(Blocks.TORCH, spot.get().state().getBlock());
        assertEquals(Direction.UP, spot.get().supportFace());
    }

    @Test
    void 箱子不作灯座() {
        FakeScene scene = new FakeScene();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 2; z++) {
                scene.put(at(x, 64, z), Blocks.GRASS_BLOCK.defaultBlockState());
                scene.put(at(x, 65, z), Blocks.AIR.defaultBlockState());
            }
        }
        // 正前方最近的地面是箱子顶：不作灯座，就再远一点找。
        scene.put(at(0, 64, -1), Blocks.CHEST.defaultBlockState());
        Predicate<BlockPos> chestOpens = pos -> pos.equals(at(0, 64, -1));
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(scene, chestOpens,
                NOTHING_PROTECTED, new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, -1), LampItem.torch());
        assertTrue(spot.isPresent());
        assertTrue(!spot.get().support().equals(at(0, 64, -1)), "箱子不当灯座");
    }

    @Test
    void 身后的格子不在视野锥里() {
        FakeScene scene = new FakeScene();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                scene.put(at(x, 64, z), Blocks.GRASS_BLOCK.defaultBlockState());
                scene.put(at(x, 65, z), Blocks.AIR.defaultBlockState());
            }
        }
        Vec3 lookingSouth = new Vec3(0, 0, 1);
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(scene, NO_SCREEN,
                NOTHING_PROTECTED, new Vec3(0.5, 65.62, 0.5), lookingSouth, LampItem.torch());
        assertTrue(spot.isPresent());
        assertTrue(spot.get().lamp().getZ() > 0, "只往前方的锥里挑");
    }

    @Test
    void 支撑在保护里_当看不见() {
        FakeScene scene = wallAhead();
        Predicate<BlockPos> wallProtected = pos -> pos.getZ() == -2;
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(scene, NO_SCREEN,
                wallProtected, new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, -1), LampItem.torch());
        // 墙全受保护：地面灯位离得远，脚下那格（正下方）在锥边界外，挑不挑都不得碰受保护的墙。
        if (spot.isPresent()) {
            assertTrue(spot.get().support().getZ() != -2);
        }
    }

    @Test
    void 视野里一个能插的地方都没有_如实交空() {
        FakeScene scene = new FakeScene();
        // 整个视野范围填满石头：没有一格空气可落灯。
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = 62; y <= 68; y++) {
                    scene.put(at(x, y, z), STONE);
                }
            }
        }
        Optional<FrontLampSites.LampSpot> spot = FrontLampSites.choose(scene, NO_SCREEN,
                NOTHING_PROTECTED, new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, -1), LampItem.torch());
        assertTrue(spot.isEmpty());
    }
}
