// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 选灯位：资格闸（支撑、敏感面、保护、窄道、卡住自己）与贪心（覆盖最多、平局就近、单轮上限）。 */
class LampSiteChooserTest {

    @BeforeAll
    static void bootMinecraft() {
        // 方块注册表要等引导完才能碰：STONE 这些静态字段在这里才取。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
        TORCH = LampItem.torch();
    }

    private static BlockState STONE;
    private static LampItem TORCH;
    private static final Predicate<BlockPos> NOTHING_PROTECTED = pos -> false;

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** 一片平整地面：石头地板在 y=63，y=64 是空气，全都加载。 */
    private static LightingFakes.FakeWorld openGround() {
        return new LightingFakes.FakeWorld().floor(STONE, 63, -8, 8, -8, 8);
    }

    @Test
    void 围绕暗格的立式灯位_脚下要结实() {
        LightingFakes.FakeWorld world = openGround();
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(0, 64, 2)), world,
                Set.of(), NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        assertEquals(1, selection.chosen().size());
        assertEquals(Blocks.TORCH, selection.chosen().getFirst().state().getBlock());
    }

    @Test
    void 贴墙火把_朝向背离墙() {
        // 只在 x=2 那一列有地面（其余脚下悬空）：支撑面只剩墙，贴墙是唯一落法。
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        for (int z = -8; z <= 8; z++) {
            world.put(at(2, 63, z), STONE);
            world.sturdy.add(at(2, 63, z));
            world.load(at(2, 64, z));
            for (int y = 64; y <= 66; y++) {
                world.put(at(2, y, z), STONE);
                world.sturdy.add(at(2, y, z));
            }
        }
        world.load(at(1, 64, 0));
        world.load(at(0, 64, 0));
        world.load(at(3, 64, 0));
        world.load(at(4, 64, 0));
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(0, 64, 0)), world,
                Set.of(), NOTHING_PROTECTED, at(-4, 64, 0), TORCH, 8);
        LampSiteChooser.LampSite site = selection.chosen().getFirst();
        assertEquals(Blocks.WALL_TORCH, site.state().getBlock(), "没有立式支撑就只能贴墙");
        assertEquals(Direction.WEST, site.state().getValue(WallTorchBlock.FACING), "墙在东边，火把指向西");
    }

    @Test
    void 敏感面上不落灯() {
        LightingFakes.FakeWorld world = openGround();
        Set<BlockPos> sensitive = Set.of(at(1, 64, 1), at(1, 65, 1));
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(0, 64, 0)), world,
                sensitive, NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        for (LampSiteChooser.LampSite site : selection.chosen()) {
            assertTrue(!sensitive.contains(site.pos()), "灯位不在敏感面上");
        }
    }

    @Test
    void 受保护的格不落灯_暗格记成插不得() {
        LightingFakes.FakeWorld world = openGround();
        // 整片都受保护：暗格周围每一处候选都在保护里。
        Predicate<BlockPos> everythingProtected = pos -> true;
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(0, 64, 0)), world,
                Set.of(), everythingProtected, at(0, 64, 0), TORCH, 8);
        assertTrue(selection.chosen().isEmpty(), "选了 " + selection.chosen());
        assertTrue(selection.onlyProtected().contains(at(0, 64, 0)),
                "暗格应记成插不得，实际 " + selection.onlyProtected() + " / 未覆盖 " + selection.uncovered());
        assertEquals(Set.of(at(0, 64, 0)), selection.uncovered());
    }

    @Test
    void 窄道不占_放灯具不能堵死路() {
        LightingFakes.FakeWorld world = openGround();
        // 一格宽的走廊：两侧全是石头墙，走廊里的格只有两个出口。
        for (int x = 0; x <= 4; x++) {
            for (int z = -1; z <= 1; z += 2) {
                world.put(at(x, 64, z), STONE);
                world.sturdy.add(at(x, 64, z));
                world.put(at(x, 65, z), STONE);
                world.sturdy.add(at(x, 65, z));
            }
        }
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(2, 64, 0)), world,
                Set.of(), NOTHING_PROTECTED, at(2, 64, 0), TORCH, 8);
        for (LampSiteChooser.LampSite site : selection.chosen()) {
            assertTrue(site.pos().getZ() != 0 || site.state().getBlock() == Blocks.WALL_TORCH,
                    "立式灯不占走廊正中；贴墙火把没有碰撞可以放");
        }
    }

    @Test
    void 不卡住自己_角色脚下头上不选() {
        LightingFakes.FakeWorld world = openGround();
        BlockPos feet = at(0, 64, 0);
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(feet), world,
                Set.of(), NOTHING_PROTECTED, feet, TORCH, 8);
        for (LampSiteChooser.LampSite site : selection.chosen()) {
            assertTrue(!site.pos().equals(feet) && !site.pos().equals(feet.above()));
        }
    }

    @Test
    void 贪心_一支盖两格的就不用两支() {
        LightingFakes.FakeWorld world = openGround();
        // 两格暗，距离 2：一格火把（发射 14，验收 8）两格都盖得住。
        LampSiteChooser.Selection selection = LampSiteChooser.choose(
                List.of(at(0, 64, 0), at(2, 64, 0)), world, Set.of(), NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        assertEquals(1, selection.chosen().size(), "覆盖估算下一支就够");
        assertTrue(selection.uncovered().isEmpty());
    }

    @Test
    void 覆盖估算按发射量减曼哈顿距离_够不着的另插一支() {
        LightingFakes.FakeWorld world = openGround();
        // 两格相距 13：围绕暗格最远的候选也差一格盖不住对头，只能各插一支。
        LampSiteChooser.Selection selection = LampSiteChooser.choose(
                List.of(at(0, 64, 0), at(13, 64, 0)), world, Set.of(), NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        assertEquals(2, selection.chosen().size());
    }

    @Test
    void 平局挑离角色近的() {
        LightingFakes.FakeWorld world = openGround();
        // 角色站在西边远处：东西各一格暗，两侧的灯位候选覆盖相同，选离角色近的那一支。
        LampSiteChooser.Selection selection = LampSiteChooser.choose(
                List.of(at(-2, 64, 0), at(2, 64, 0)), world, Set.of(), NOTHING_PROTECTED, at(-4, 64, 0), TORCH, 8);
        assertEquals(1, selection.chosen().size());
        assertTrue(selection.chosen().getFirst().pos().getX() < 0, "同覆盖时选近的那一支");
    }

    @Test
    void 单轮最多四十八支() {
        LightingFakes.FakeWorld world = openGround();
        // 一条 60 格长的暗廊：盖不住全部，一轮最多 48 支。
        List<BlockPos> dark = new ArrayList<>();
        for (int x = 0; x < 60; x += 8) dark.add(at(x, 64, 0));
        LampSiteChooser.Selection selection = LampSiteChooser.choose(dark, world,
                Set.of(), NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        assertTrue(selection.chosen().size() <= LampSiteChooser.MAX_PER_ROUND);
    }

    @Test
    void 没有资格的暗格_如实留在未覆盖里() {
        // 暗格四周一整片都是敏感面：围绕它的候选全被资格闸拦下。
        LightingFakes.FakeWorld world = openGround();
        Set<BlockPos> sensitive = new HashSet<>();
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = 62; y <= 66; y++) {
                    sensitive.add(at(x, y, z));
                }
            }
        }
        LampSiteChooser.Selection selection = LampSiteChooser.choose(List.of(at(0, 64, 0)), world,
                sensitive, NOTHING_PROTECTED, at(0, 64, 0), TORCH, 8);
        assertTrue(selection.chosen().isEmpty());
        assertEquals(Set.of(at(0, 64, 0)), selection.uncovered());
        assertTrue(selection.onlyProtected().isEmpty(), "不是保护，是没处插");
    }
}
