// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 照明能力：四个操作收成一个能力，参数一次报全，target 解析不到就提问，status 照实报随行补光。 */
class LightModuleTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LightModule module(FollowLighting follow) {
        // 目标对象落实：观察编号查不到、地标没有，坐标与脚下直接用。
        AnchorResolver anchors = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> new WorldPosition(5, 64, 5, DIM), (x, z) -> Optional.of(64));
        ReadsCharacterPosition character = () -> new WorldPosition(5, 64, 5, DIM);
        return new LightModule(follow, anchors, character,
                permissions -> {
                    throw new UnsupportedOperationException("计划阶段不拼服务");
                },
                new ReadsLightLevels() {
                    @Override public OptionalInt blockLight(BlockPos pos) { return OptionalInt.empty(); }
                    @Override public OptionalInt skyLight(BlockPos pos) { return OptionalInt.empty(); }
                }, unloadedWorld(), null, offhandEmpty());
    }

    private static LightingWorld unloadedWorld() {
        return new LightingWorld() {
            @Override public boolean loaded(BlockPos pos) { return false; }
            @Override public BlockState state(BlockPos pos) {
                return Blocks.AIR.defaultBlockState();
            }
            @Override public boolean sturdy(BlockPos pos, Direction face) { return false; }
        };
    }

    private static OffhandContents offhandEmpty() {
        return () -> Optional.empty();
    }

    private static StepContext step(LightModule module, Target target, JsonObject raw, List<String> answers) {
        ParamValues params = module.spec().paramSpecs().parse(raw).params();
        Goal goal = Goal.of("maicraft:light", target, params);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
            @Override public List<String> answers() { return answers; }
        };
    }

    private static JsonObject params(String... keyValue) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < keyValue.length; i += 2) {
            if (keyValue[i + 1].startsWith("{") || keyValue[i + 1].startsWith("[")) {
                object.add(keyValue[i], JsonParser.parseString(keyValue[i + 1]));
            } else {
                object.addProperty(keyValue[i], keyValue[i + 1]);
            }
        }
        return object;
    }

    @Test
    void 区域补光是默认操作_落到目标对象那一格() {
        LightModule module = module(new FollowLighting());
        StepDecision decision = module.decide(step(module, new Target.Position(10, null, 10, null), params(), List.of()));
        LightInput input = assertInstanceOf(LightInput.class,
                assertInstanceOf(StepDecision.Run.class, decision).input());
        assertEquals(new WorldPosition(10, 64, 10, DIM), input.center());
        assertNull(input.radius(), "radius 不给就是连通可行走空间");
        assertEquals(LightInput.Coverage.ALL, input.coverage());
        assertEquals(8, input.minimumLight());
    }

    @Test
    void 农田档默认验收亮度是九() {
        LightModule module = module(new FollowLighting());
        StepDecision decision = module.decide(step(module, new Target.Here(),
                params("coverage", "crops"), List.of()));
        LightInput input = assertInstanceOf(LightInput.class,
                assertInstanceOf(StepDecision.Run.class, decision).input());
        assertEquals(9, input.minimumLight(), "作物要光照够 9 才长");
        assertEquals(LightInput.Coverage.CROPS, input.coverage());
    }

    @Test
    void 不是光源的物品_一次报清() {
        LightModule module = module(new FollowLighting());
        TaskResult result = ((StepDecision.Finish) module.decide(step(module, new Target.Here(),
                params("item", "minecraft:nope"), List.of()))).result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.problem().message().contains("minecraft:nope"), result.problem().message());
    }

    @Test
    void 随行补光的阈值上限十三() {
        LightModule module = module(new FollowLighting());
        StepDecision decision = module.decide(step(module, null, params("operation", "enable", "minimum_light", "14"), List.of()));
        TaskResult result = ((StepDecision.Finish) decision).result();
        assertEquals(TaskResult.Status.FAILED, result.status(), "enable 取 1..13");
        StepDecision ok = module.decide(step(module, null, params("operation", "enable", "minimum_light", "13"), List.of()));
        FollowLightSettingInput input = assertInstanceOf(FollowLightSettingInput.class,
                assertInstanceOf(StepDecision.Run.class, ok).input());
        assertTrue(input.enable());
        assertEquals(13, input.minimumLight());
    }

    @Test
    void 状态照实报_开关阈值火把数() {
        FollowLighting follow = new FollowLighting();
        follow.enable(10);
        StepDecision decision = module(follow).decide(step(module(follow), null, params("operation", "status"), List.of()));
        TaskResult result = ((StepDecision.Finish) decision).result();
        FollowLightStatus detail = assertInstanceOf(FollowLightStatus.class, result.details());
        assertTrue(detail.enabled());
        assertEquals(10, detail.minimumLight());
        assertEquals(0, detail.torchesCarried());
    }

    @Test
    void 状态里的火把数按背包与副手清点() {
        BackpackView backpack = new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of(new BackpackStack("minecraft:torch", 12, 64, false, false, false, false),
                        new BackpackStack("minecraft:cobblestone", 30, 64, false, false, false, false));
            }
            @Override public int usedSlots() { return 2; }
            @Override public int totalSlots() { return 36; }
        };
        OffhandContents offhand = () -> Optional.of(new BackpackStack("minecraft:torch", 1, 64, false, false, false, false));
        FollowLightStatus status = LightModule.status(new FollowLighting(), backpack, offhand);
        assertEquals(12, status.torchesCarried());
        assertEquals(1, status.torchesOffhand());
    }

    @Test
    void 地标解析不到_提问不改照脚下() {
        LightModule module = module(new FollowLighting());
        StepDecision decision = module.decide(step(module, new Target.Landmark("没有的地方"), params(), List.of()));
        StepDecision.Ask ask = assertInstanceOf(StepDecision.Ask.class, decision);
        assertEquals(Question.Reason.UNCLEAR_TARGET, ask.question().reason());
        // 答了"脚下"就照脚下；答了"取消"就取消。
        StepDecision here = module.decide(step(module, new Target.Landmark("没有的地方"), params(), List.of("here")));
        assertInstanceOf(LightInput.class, assertInstanceOf(StepDecision.Run.class, here).input());
        StepDecision cancel = module.decide(step(module, new Target.Landmark("没有的地方"), params(), List.of("cancel")));
        assertEquals(TaskResult.Status.CANCELLED, ((StepDecision.Finish) cancel).result().status());
    }

    @Test
    void 开关落下去_会话设置真的变了() {
        FollowLighting follow = new FollowLighting();
        LightModule module = module(follow);
        FollowLightSettingInput disable = assertInstanceOf(FollowLightSettingInput.class,
                assertInstanceOf(StepDecision.Run.class,
                        module.decide(step(module, null, params("operation", "disable"), List.of()))).input());
        assertFalse(disable.enable());
    }
}
