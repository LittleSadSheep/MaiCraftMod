// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审阅机器蓝图：正文一次报全、工序做不做得了、动力连不连得上、材料缺不缺。审阅不下通过与否的结论。 */
class MachineReviewTest {

    private static final BlockPos PRESS = new BlockPos(0, 0, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一条压板配方：一台压机、一个铁锭进、一块铁板出。 */
    private static ShownRecipe pressingRecipe() {
        return new ShownRecipe("test:pressing/plate", "test:pressing", "压制",
                List.of(ShownStack.item("test:press", "测试压机", 1)),
                List.of(ShownIngredient.of(ShownStack.item("test:ingot", "铁锭", 1))),
                List.of(), List.of(ShownStack.item("test:plate", "铁板", 1)), null);
    }

    /** 审阅用的机器类型：认领熔炉，是台要动力的加工机器。 */
    private static MachineTestDoubles.FakeMachineType pressType() {
        return new MachineTestDoubles.FakeMachineType("test:press", "测试压机",
                MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
    }

    /** 只有一台压机的蓝图。 */
    private static MachineBlueprint pressOnlyBlueprint() {
        return new MachineBlueprint(
                List.of(PlannedCell.block(PRESS, Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(),
                List.of(new MachineBlueprint.Process(PRESS, "test:plate")));
    }

    private static MachineReview.Report review(MachineBlueprint blueprint, MachineTestDoubles.FakeViewer viewer,
            MachineTestDoubles.FakeMachineType type) {
        RecipeLookup recipes = new RecipeLookup(List.of(viewer), new GameRecipeTable(Optional::empty));
        MachineServices services = new MachineServices(() -> null, new MachineTestDoubles.FakeWorld(),
                List.of(type), List.of(), new MachineTestDoubles.FakeArea(), recipes,
                new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                new MachineTestDoubles.FakePlaces());
        return MachineReview.review(blueprint, services, backpack("minecraft:stone", "2"));
    }

    private static BackpackView backpack(String... itemIdAndCount) {
        List<BackpackStack> stacks = new ArrayList<>();
        for (int i = 0; i < itemIdAndCount.length; i += 2) {
            stacks.add(new BackpackStack(itemIdAndCount[i], Integer.parseInt(itemIdAndCount[i + 1]),
                    64, false, false, false, true));
        }
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return stacks;
            }

            @Override public int usedSlots() {
                return stacks.size();
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }

    @Test
    void 正文写错的地方一次报全() {
        String bad = "{\"unknown_field\": 1,"
                + " \"cells\": [{\"offset\": [0, 0], \"block\": \"minecraft:stone\"},"
                + " {\"offset\": [0, 1, 0], \"block\": \"test:none\"}],"
                + " \"parts\": [{\"offset\": [1, 0, 0], \"side\": \"sideways\", \"item\": \"test:none\"}]}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MachineBlueprints.parse(json(bad)));
        assertTrue(error.getMessage().contains("unknown_field"), error.getMessage());
        assertTrue(error.getMessage().contains("offset 要是 [x, y, z]"), error.getMessage());
        assertTrue(error.getMessage().contains("没有这个方块"), error.getMessage());
        assertTrue(error.getMessage().contains("side"), error.getMessage());
    }

    @Test
    void air与流体源格换算成清空与倒桶() {
        MachineBlueprint blueprint = MachineBlueprints.parse(json("{\"cells\": ["
                + "{\"offset\": [0, 0, 0], \"block\": \"minecraft:air\"},"
                + "{\"offset\": [1, 0, 0], \"block\": \"minecraft:water\"}]}"));
        assertEquals(2, blueprint.cells().size());
        assertEquals(CellKind.AIR, blueprint.cells().get(0).kind());
        assertEquals(CellKind.FLUID_SOURCE, blueprint.cells().get(1).kind());
    }

    @Test
    void 工序这台机器做得了但没地方投料_报出输入口的问题() {
        MachineTestDoubles.FakeViewer viewer = new MachineTestDoubles.FakeViewer();
        viewer.putAtWorkstation("test:press", List.of(pressingRecipe()));
        MachineReview.Report report = review(pressOnlyBlueprint(), viewer, pressType());
        // 查得到配方，但这台机器没有输入口，旁边也没有搬运的东西；另外整份蓝图没有动力源。
        assertTrue(report.issues().stream().anyMatch(issue -> issue.what().contains("没有输入口")),
                report.issues().toString());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.what().contains("没有动力源")),
                report.issues().toString());
    }

    @Test
    void 工序这台机器做不了_照实说() {
        MachineTestDoubles.FakeViewer viewer = new MachineTestDoubles.FakeViewer();
        // 这台机器的配方只出别的产物。
        ShownRecipe other = new ShownRecipe("test:pressing/gear", "test:pressing", "压制",
                List.of(ShownStack.item("test:press", "测试压机", 1)),
                List.of(ShownIngredient.of(ShownStack.item("test:ingot", "铁锭", 1))),
                List.of(), List.of(ShownStack.item("test:gear", "齿轮", 1)), null);
        viewer.putAtWorkstation("test:press", List.of(other));
        MachineReview.Report report = review(pressOnlyBlueprint(), viewer, pressType());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.what().contains("做 test:plate")),
                report.issues().toString());
    }

    @Test
    void 部件的宿主不在蓝图里_报出来() {
        MachineBlueprint blueprint = new MachineBlueprint(
                List.of(PlannedCell.block(PRESS, Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(new MachineBlueprint.Part(new BlockPos(3, 0, 0),
                        Direction.NORTH, "test:terminal")),
                List.of(), List.of(), List.of());
        MachineReview.Report report = review(blueprint, new MachineTestDoubles.FakeViewer(), pressType());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.what().contains("宿主不在蓝图里")),
                report.issues().toString());
    }

    @Test
    void 消费者沿传动连到动力源_不报动力问题() {
        MachineTestDoubles.FakeViewer viewer = new MachineTestDoubles.FakeViewer();
        viewer.putAtWorkstation("test:press", List.of(pressingRecipe()));
        MachineType source = new MachineTestDoubles.FakeMachineType("test:water_wheel", "水车",
                MachineRole.POWER_SOURCE, state -> state.is(Blocks.LODESTONE));
        MachineType shaft = new MachineTestDoubles.FakeMachineType("test:shaft", "轴",
                MachineRole.TRANSMISSION, state -> state.is(Blocks.HAY_BLOCK));
        MachineBlueprint blueprint = new MachineBlueprint(
                List.of(PlannedCell.block(PRESS, Blocks.FURNACE.defaultBlockState(), Set.of()),
                        PlannedCell.block(PRESS.west(), Blocks.LODESTONE.defaultBlockState(), Set.of()),
                        PlannedCell.block(PRESS.east(), Blocks.HAY_BLOCK.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(),
                List.of(new MachineBlueprint.Process(PRESS, "test:plate")));
        RecipeLookup recipes = new RecipeLookup(List.of(viewer), new GameRecipeTable(Optional::empty));
        MachineServices services = new MachineServices(() -> null, new MachineTestDoubles.FakeWorld(),
                List.of(source, shaft, pressType()), List.of(), new MachineTestDoubles.FakeArea(), recipes,
                new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                new MachineTestDoubles.FakePlaces());
        MachineReview.Report report = MachineReview.review(blueprint, services, backpack());
        assertTrue(report.issues().stream().noneMatch(issue -> issue.what().contains("动力")),
                report.issues().toString());
        // 工序能做、但没有输入口的问题还在。
        assertTrue(report.issues().stream().anyMatch(issue -> issue.what().contains("没有输入口")));
    }

    @Test
    void 材料账给要几件身上几件缺几件() {
        MachineBlueprint blueprint = new MachineBlueprint(
                List.of(PlannedCell.block(PRESS, Blocks.FURNACE.defaultBlockState(), Set.of()),
                        PlannedCell.block(PRESS.east(), Blocks.STONE.defaultBlockState(), Set.of()),
                        PlannedCell.block(PRESS.west(), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(new MachineBlueprint.Part(PRESS, Direction.NORTH, "test:terminal")),
                List.of(), List.of(), List.of());
        MachineReview.Report report = review(blueprint, new MachineTestDoubles.FakeViewer(), pressType());
        Map<String, MachineReview.Material> byItem = new HashMap<>();
        report.materials().forEach(material -> byItem.put(material.item(), material));
        assertEquals(2, byItem.get("minecraft:stone").needed());
        assertEquals(2, byItem.get("minecraft:stone").carried());
        assertEquals(0, byItem.get("minecraft:stone").missing());
        assertEquals(1, byItem.get("test:terminal").needed());
        assertEquals(1, byItem.get("test:terminal").missing());
        assertEquals("估不出：消费与容量的应力数值要装了 Create 的联动才读得到", report.power());
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
