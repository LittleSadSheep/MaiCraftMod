// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 寻死能力：开着死亡不掉落时主动死一次，回出生点或床边。
 *
 * <p>下达时先看能不能这么干：创造、旁观死不了，极限死了不能重生，都不做；死亡不掉落读得到真实规则（单人、局域网主机）
 * 就按规则，关着不做；连别人的服务器读不到时看调用方确认，没确认就问一句"这个服务器开着吗"。
 * 指定了点火、倒岩浆，身上却没有东西、或许可不让动方块，也不进游戏直接说。
 */
public final class SuicideModule implements AbilityModule {

    static final String ID = "maicraft:suicide";
    /** 找现成死法的默认范围（格）与上下限。 */
    static final int DEFAULT_RADIUS = 24;
    static final int MIN_RADIUS = 4;
    static final int MAX_RADIUS = 64;
    /** 最多花多少秒去死的默认值与上下限。 */
    static final int DEFAULT_SECONDS = 120;
    static final int MIN_SECONDS = 10;
    static final int MAX_SECONDS = 600;
    /** 怎么知道开着死亡不掉落的：读到真实规则 / 调用方确认（参数或回答）。 */
    static final String BY_GAME_RULE = "game_rule";
    static final String BY_CALLER = "caller_confirmed";

    private final SuicidePlayer player;
    private final Function<Permissions, SuicideWorld> worlds;
    private final SuicideMoves moves;
    private final GearChanges gear;

    /**
     * @param worlds 按这次目标的许可建看地形的读端（放火、倒岩浆按许可过放方块的检查）
     */
    SuicideModule(SuicidePlayer player, Function<Permissions, SuicideWorld> worlds, SuicideMoves moves,
            GearChanges gear) {
        this.player = Objects.requireNonNull(player, "player");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.gear = Objects.requireNonNull(gear, "gear");
    }

    /**
     * 接在真实客户端上的一份：读角色与世界、走到、直接按键、换手、瞄准交互、穿卸装备、保护与许可都用共用的那一套。
     */
    public static SuicideModule live(Supplier<PlayerContext> context, WalkTo walks, Interactions interactions,
            ClientMovesToMainhand toMainhand, GearChanges gear, ReadsEffects effects, Protection protection,
            PermissionCheck permission) {
        LiveSuicidePlayer player = new LiveSuicidePlayer(context, new KeepInventoryRule(), effects);
        return new SuicideModule(player,
                permissions -> new LiveSuicideWorld(context, protection, permission, permissions),
                new LiveSuicideMoves(context, walks, interactions, toMainhand), gear);
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec(ID, "开着死亡不掉落时主动死一次，回出生点或床边",
                AbilityDoc.forAbility("suicide"),
                ParamSpecs.of(
                        ParamSpec.of("via", ParamType.CHOICE).choices(WayToDie.params())
                                .doc("死法：lava 现成岩浆 / fall 摔得死的落差 / cactus 仙人掌 / hostile_mob 敌对生物 / "
                                        + "fire 随身点火 / lava_bucket 随身倒岩浆；不给按死得快慢自己挑，给了只用它").build(),
                        ParamSpec.of("radius", ParamType.INTEGER).range(MIN_RADIUS, MAX_RADIUS).defaultValue(DEFAULT_RADIUS)
                                .doc("找现成死法的范围（格）").build(),
                        ParamSpec.of("max_seconds", ParamType.INTEGER).range(MIN_SECONDS, MAX_SECONDS)
                                .defaultValue(DEFAULT_SECONDS).doc("最多花多少秒去死；死亡、暂停、穿回护甲的时间不算").build(),
                        ParamSpec.of("keep_inventory_confirmed", ParamType.BOOLEAN)
                                .doc("你确认这个服务器开着死亡不掉落；连别人的服务器时给 true，单人世界以游戏规则为准").build()),
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        if (!player.present()) return StepDecision.NOT_READY;
        ParamValues params = step.goal().params();
        Permissions permissions = step.goal().permissions();
        WayToDie via = params.has("via") ? WayToDie.fromParam(params.text("via")) : null;
        // 创造、旁观死不了；极限死了不能重生，寻死回家没有意义。点火、倒岩浆是寻死的手段，不看 change_blocks，
        // 只看身上有没有点火物、岩浆桶。
        if (player.creative() || player.spectator()) {
            return fail("创造、旁观模式死不了", Problem.Kind.NOT_POSSIBLE_HERE, null);
        }
        if (player.hardcore()) {
            return fail("极限模式死了不能重生，回不了家", Problem.Kind.NOT_POSSIBLE_HERE, null);
        }
        if (via == WayToDie.FIRE && WayToDie.IGNITERS.stream().noneMatch(player::carries)) {
            return fail("身上没有打火石或火焰弹", Problem.Kind.NEED_ITEM, null);
        }
        if (via == WayToDie.LAVA_BUCKET && !player.carries(WayToDie.LAVA_BUCKET_ITEM)) {
            return fail("身上没有岩浆桶", Problem.Kind.NEED_ITEM, null);
        }
        return switch (player.keepInventory()) {
            case READING -> StepDecision.NOT_READY;
            case OFF -> fail("这个世界关着死亡不掉落（keepInventory），死了东西会掉在原地，不寻死",
                    Problem.Kind.NOT_POSSIBLE_HERE, null);
            case ON -> run(params, permissions, via, BY_GAME_RULE);
            case UNREADABLE -> unreadableRule(step, params, permissions, via);
        };
    }

    // 连别人的服务器读不到规则：调用方确认了就做；没确认就问一句，答了按答的办，不用重下。
    private StepDecision unreadableRule(StepContext step, ParamValues params, Permissions permissions, WayToDie via) {
        if (params.has("keep_inventory_confirmed") && params.bool("keep_inventory_confirmed")) {
            return run(params, permissions, via, BY_CALLER);
        }
        if (!step.answers().isEmpty()) {
            String answer = step.answers().get(step.answers().size() - 1).trim();
            if (answer.equals("yes")) return run(params, permissions, via, BY_CALLER);
            return new StepDecision.Finish(TaskResult.cancelled("这个服务器没开死亡不掉落，不寻死"));
        }
        return new StepDecision.Ask(new Question(Question.Reason.NEED_APPROVAL,
                "连的是别人的服务器，读不到死亡不掉落（keepInventory）开没开。这个服务器开着吗？"
                        + "开着才寻死；没开的话死了东西会掉在原地。",
                List.of(new Question.Option("yes", "开着，继续寻死"),
                        new Question.Option("no", "没开，不做了"))));
    }

    private StepDecision run(ParamValues params, Permissions permissions, WayToDie via, String keepInventory) {
        int radius = params.has("radius") ? (int) params.integer("radius") : DEFAULT_RADIUS;
        long seconds = params.has("max_seconds") ? params.integer("max_seconds") : DEFAULT_SECONDS;
        return new StepDecision.Run(new SuicideInput(via, radius, seconds, permissions, keepInventory));
    }

    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        return new StepDecision.Finish(TaskResult.failed("不寻死：" + message, Problem.of(kind, message, suggestion)));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(SuicideInput.class,
                input -> new SuicideTask(input, player, worlds.apply(input.permissions()), moves, gear));
    }
}
