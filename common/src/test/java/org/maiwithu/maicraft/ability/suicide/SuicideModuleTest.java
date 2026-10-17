// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Gear;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Moves;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Player;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.World;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 寻死下达时的判断：模式、死亡不掉落、指定的自造死法缺东西或不许动方块，读不到规则时问一句。 */
class SuicideModuleTest {

    private final Player player = new Player();
    private final World world = new World();
    private final SuicideModule module = new SuicideModule(player, permissions -> world,
            new Moves(player, world), new Gear(player));

    @Test
    void 开着死亡不掉落就去寻死_参数按默认() {
        SuicideInput input = run(decide("{}", Permissions.DEFAULT, List.of()));
        assertEquals(SuicideModule.BY_GAME_RULE, input.keepInventory());
        assertEquals(SuicideModule.DEFAULT_RADIUS, input.radius());
        assertEquals(SuicideModule.DEFAULT_SECONDS, input.maxSeconds());
        assertEquals(null, input.via());
    }

    @Test
    void 关着死亡不掉落不寻死() {
        player.keepInventory = KeepInventoryRule.Reading.OFF;
        assertProblem(decide("{\"keep_inventory_confirmed\": true}", Permissions.DEFAULT, List.of()),
                Problem.Kind.NOT_POSSIBLE_HERE);
    }

    @Test
    void 规则还没读回来先等() {
        player.keepInventory = KeepInventoryRule.Reading.READING;
        assertTrue(decide("{}", Permissions.DEFAULT, List.of()) instanceof StepDecision.NotReady);
    }

    @Test
    void 创造旁观极限都不寻死() {
        player.creative = true;
        assertProblem(decide("{}", Permissions.DEFAULT, List.of()), Problem.Kind.NOT_POSSIBLE_HERE);
        player.creative = false;
        player.spectator = true;
        assertProblem(decide("{}", Permissions.DEFAULT, List.of()), Problem.Kind.NOT_POSSIBLE_HERE);
        player.spectator = false;
        player.hardcore = true;
        assertProblem(decide("{}", Permissions.DEFAULT, List.of()), Problem.Kind.NOT_POSSIBLE_HERE);
    }

    @Test
    void 连别人的服务器_给了确认就做() {
        player.keepInventory = KeepInventoryRule.Reading.UNREADABLE;
        SuicideInput input = run(decide("{\"keep_inventory_confirmed\": true}", Permissions.DEFAULT, List.of()));
        assertEquals(SuicideModule.BY_CALLER, input.keepInventory());
    }

    @Test
    void 连别人的服务器_没确认就问一句_答了按答的办() {
        player.keepInventory = KeepInventoryRule.Reading.UNREADABLE;
        StepDecision asked = decide("{}", Permissions.DEFAULT, List.of());
        assertTrue(asked instanceof StepDecision.Ask, asked.toString());
        assertEquals(Question.Reason.NEED_APPROVAL, ((StepDecision.Ask) asked).question().reason());
        assertEquals(SuicideModule.BY_CALLER, run(decide("{}", Permissions.DEFAULT, List.of("yes"))).keepInventory());
        StepDecision no = decide("{}", Permissions.DEFAULT, List.of("no"));
        assertTrue(no instanceof StepDecision.Finish finish && finish.result().status() == TaskResult.Status.CANCELLED,
                no.toString());
    }

    @Test
    void 指定点火却没带打火石_不进游戏() {
        assertProblem(decide("{\"via\": \"fire\"}", Permissions.DEFAULT, List.of()), Problem.Kind.NEED_ITEM);
        player.carried.add("minecraft:fire_charge");
        assertEquals(WayToDie.FIRE, run(decide("{\"via\": \"fire\"}", Permissions.DEFAULT, List.of())).via());
    }

    @Test
    void 指定倒岩浆_不许动方块也照做() {
        // 寻死本身就是目标：倒一格岩浆是手段，change_blocks=none 不拦，只看身上有没有岩浆桶。
        player.carried.add(WayToDie.LAVA_BUCKET_ITEM);
        Permissions none = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        assertEquals(WayToDie.LAVA_BUCKET, run(decide("{\"via\": \"lava_bucket\"}", none, List.of())).via());
    }

    @Test
    void 死法取值拼错在参数校验就拒绝() {
        ParseResult parsed = module.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"via\": \"jump\"}").getAsJsonObject());
        assertTrue(!parsed.ok(), "via 只认六种死法");
        ParseResult far = module.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"radius\": 65}").getAsJsonObject());
        assertTrue(!far.ok(), "半径最多 64");
    }

    private StepDecision decide(String paramsJson, Permissions permissions, List<String> answers) {
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(paramsJson).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal(SuicideModule.ID, null, null, parsed.params(), permissions, List.of(), null);
        return module.decide(new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { throw new IllegalStateException("决定阶段不碰每刻上下文"); }
            @Override public List<String> answers() { return answers; }
        });
    }

    private static SuicideInput run(StepDecision decision) {
        assertTrue(decision instanceof StepDecision.Run, decision.toString());
        return (SuicideInput) ((StepDecision.Run) decision).input();
    }

    private static void assertProblem(StepDecision decision, Problem.Kind kind) {
        assertTrue(decision instanceof StepDecision.Finish, decision.toString());
        assertEquals(kind, ((StepDecision.Finish) decision).result().problem().kind());
    }
}
