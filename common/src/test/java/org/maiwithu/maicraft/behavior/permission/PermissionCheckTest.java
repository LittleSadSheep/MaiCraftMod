// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 许可检查点：点名即许可、保护先于档位、被拒说清差哪一项许可；看不清的生物按有主处理。
 */
class PermissionCheckTest {

    private static final WorldPosition WALL = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final WorldPosition DIRT = new WorldPosition(20, 64, 20, "minecraft:overworld");
    private static final String SELF = UUID.randomUUID().toString();
    private static final String OTHER = UUID.randomUUID().toString();
    private static final UUID SHEEP = UUID.randomUUID();
    private static final UUID WILD_COW = UUID.randomUUID();
    private static final UUID TAMED_WOLF = UUID.randomUUID();
    private static final UUID STEVE = UUID.randomUUID();

    private final Protection protection = new Protection(
            (dimension, x, y, z) -> {
                var hit = new WorldPosition(x, y, z, dimension);
                if (hit.equals(WALL)) return Optional.of(new ReadsBlockOwnership.PlacedBy(OTHER));
                if (hit.equals(DIRT)) return Optional.of(new ReadsBlockOwnership.PlacedBy(SELF));
                return Optional.empty();
            },
            () -> List.of(new RememberedRegion("河东的农场",
                    new WorldPosition(500, 64, 500, "minecraft:overworld"), 16)),
            name -> Optional.empty(),
            GuessesPlayerMade.NOTHING,
            SELF);
    private final PermissionCheck check = new PermissionCheck(protection, fixedSituations());

    @Test
    void 点名的观察编号_即许可_连玩家放的方块也放行() {
        assertEquals(Optional.empty(), check.allows(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, new Target.Seen("f3")));
        // 点名的生物要先找回是哪一只、看有没有主，不能只凭编号放行。
        assertThrows(IllegalArgumentException.class, () -> check.allows(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, new Target.Seen("e7")));
    }

    @Test
    void 点名打野生动物_点名即许可() {
        assertEquals(Optional.empty(), check.namedCreatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, WILD_COW, "一头牛"));
    }

    @Test
    void 点名打别人的狗_要再确认一次_开到any才放行() {
        var refusal = check.namedCreatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, TAMED_WOLF, "一只狗").orElseThrow();
        assertEquals(Problem.Kind.NEED_APPROVAL, refusal.kind());
        assertTrue(refusal.suggestion().contains("fight") && refusal.suggestion().contains("any"), refusal.suggestion());
        var any = Permissions.DEFAULT.mergedWith(null, Permissions.Fight.ANY, null, null, null, null);
        assertEquals(Optional.empty(), check.namedCreatureAllowed(any,
                PermissionCheck.WorldAction.FIGHT, TAMED_WOLF, "一只狗"));
    }

    @Test
    void 点名打玩家_默认拒_看不清的生物按有主() {
        assertTrue(check.namedCreatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, STEVE, "玩家 Steve").isPresent());
        assertTrue(check.namedCreatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, SHEEP, "草坡上的羊").isPresent());
    }

    @Test
    void 没给高度的坐标_先落实到具体一格再问() {
        assertThrows(IllegalArgumentException.class, () -> check.allows(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, new Target.Position(1, null, 1, null)));
    }

    @Test
    void 默认许可_可以挖无人认领的天然方块() {
        var position = new WorldPosition(30, 64, 30, "minecraft:overworld");
        assertEquals(Optional.empty(), check.blockAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, position, null));
    }

    @Test
    void 玩家放的方块_任何档位都不动_被拒说清要点名() {
        var refusal = check.blockAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, WALL, null).orElseThrow();
        assertEquals(Problem.Kind.NEED_APPROVAL, refusal.kind());
        assertTrue(refusal.message().contains("(10, 64, 10)"), refusal.message());
        assertTrue(refusal.suggestion().contains("观察编号"), refusal.suggestion());
    }

    @Test
    void 记住区域里的格子_挡在保护一关() {
        var inside = new WorldPosition(505, 64, 500, "minecraft:overworld");
        var refusal = check.blockAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, inside, null).orElseThrow();
        assertTrue(refusal.message().contains("地盘"), refusal.message());
    }

    @Test
    void 临时许可_放行放置_也放行收回自己搭的_但不许挖别人的天然方块() {
        var temporary = Permissions.DEFAULT.mergedWith(
                Permissions.BlockChanges.TEMPORARY, null, null, null, null, null);
        assertEquals(Optional.empty(), check.blockAllowed(temporary,
                PermissionCheck.WorldAction.PLACE_BLOCK, DIRT, null));
        // 自己搭的用完要收回：挖自己的不算挖天然。
        assertEquals(Optional.empty(), check.blockAllowed(temporary,
                PermissionCheck.WorldAction.DIG_BLOCK, DIRT, null));
        var natural = new WorldPosition(30, 64, 30, "minecraft:overworld");
        var refusal = check.blockAllowed(temporary,
                PermissionCheck.WorldAction.DIG_BLOCK, natural, null).orElseThrow();
        assertTrue(refusal.message().contains("change_blocks"), refusal.message());
    }

    @Test
    void 不改任何方块的许可_放置也被拒_说清要提到哪一档() {
        var none = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        var refusal = check.blockAllowed(none,
                PermissionCheck.WorldAction.PLACE_BLOCK, DIRT, null).orElseThrow();
        assertTrue(refusal.suggestion().contains("temporary"), refusal.suggestion());
    }

    @Test
    void 稀有消耗品_默认不许用_开了才放行() {
        assertTrue(check.rareItemAllowed(Permissions.DEFAULT).isPresent());
        var allowed = Permissions.DEFAULT.mergedWith(
                null, null, true, null, null, null);
        assertEquals(Optional.empty(), check.rareItemAllowed(allowed));
    }

    @Test
    void 打玩家_默认拒_开到any放行() {
        var refusal = check.allows(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, new Target.Player("Steve")).orElseThrow();
        assertTrue(refusal.message().contains("fight"), refusal.message());
        var any = Permissions.DEFAULT.mergedWith(null, Permissions.Fight.ANY, null, null, null, null);
        assertEquals(Optional.empty(), check.allows(any,
                PermissionCheck.WorldAction.FIGHT, new Target.Player("Steve")));
        // 击杀玩家没有任何许可挡得住。
        assertTrue(check.allows(any,
                PermissionCheck.WorldAction.KILL_ANIMAL, new Target.Player("Steve")).isPresent());
    }

    @Test
    void 只自卫_不主动打_敌对生物也先放过() {
        var selfDefense = Permissions.DEFAULT.mergedWith(
                null, Permissions.Fight.SELF_DEFENSE, null, null, null, null);
        assertTrue(check.creatureAllowed(selfDefense, PermissionCheck.WorldAction.FIGHT,
                new ReadsCreatureSituation.CreatureSituation(false, true, false, false, false, false),
                "一只僵尸").isPresent());
    }

    @Test
    void 主动处理敌对_打敌对放行_打温和的要再开一档() {
        var hostile = new ReadsCreatureSituation.CreatureSituation(false, true, false, false, false, false);
        var passive = new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false);
        assertEquals(Optional.empty(), check.creatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, hostile, "一只僵尸"));
        assertTrue(check.creatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, passive, "一头牛").isPresent());
    }

    @Test
    void 击杀动物_野外的放行_有名字的不放行哪怕开到any() {
        var wild = new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false);
        var named = new ReadsCreatureSituation.CreatureSituation(false, false, true, false, false, false);
        assertEquals(Optional.empty(), check.creatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.KILL_ANIMAL, wild, "一只野羊"));
        var refusal = check.creatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.KILL_ANIMAL, named, "叫小花的羊").orElseThrow();
        assertTrue(refusal.message().contains("有主"), refusal.message());
        var any = Permissions.DEFAULT.mergedWith(null, null, null, Permissions.AnimalKilling.ANY, null, null);
        assertTrue(check.creatureAllowed(any,
                PermissionCheck.WorldAction.KILL_ANIMAL, named, "叫小花的羊").isPresent());
    }

    @Test
    void 处境看不清的生物_按有主处理_想动就点名() {
        var refusal = check.creatureAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.KILL_ANIMAL, SHEEP, "草坡上的羊").orElseThrow();
        assertTrue(refusal.suggestion().contains("点名"), refusal.suggestion());
    }

    @Test
    void 被拒的许可问题_能升级成向提问_带同意与不同意两个选项() {
        var refusal = check.blockAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK, WALL, null).orElseThrow();
        Question question = check.approvalQuestion(refusal).orElseThrow();
        assertEquals(Question.Reason.NEED_APPROVAL, question.reason());
        assertEquals(2, question.options().size());
    }

    @Test
    void 动作与目标对象配不上_当场报错_能力应先落实目标() {
        assertThrows(IllegalArgumentException.class, () -> check.allows(Permissions.DEFAULT,
                PermissionCheck.WorldAction.DIG_BLOCK,
                new Target.Direction(Target.Toward.NORTH, 100)));
        assertThrows(IllegalArgumentException.class, () -> check.blockAllowed(Permissions.DEFAULT,
                PermissionCheck.WorldAction.FIGHT, WALL, null));
    }

    // 野牛、别人的狗、玩家各有处境；羊看不清。
    private static ReadsCreatureSituation fixedSituations() {
        return entityId -> {
            if (WILD_COW.equals(entityId)) {
                return Optional.of(new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));
            }
            if (TAMED_WOLF.equals(entityId)) {
                return Optional.of(new ReadsCreatureSituation.CreatureSituation(false, false, false, true, false, false));
            }
            if (STEVE.equals(entityId)) {
                return Optional.of(new ReadsCreatureSituation.CreatureSituation(true, false, false, false, false, false));
            }
            return Optional.empty();
        };
    }
}
