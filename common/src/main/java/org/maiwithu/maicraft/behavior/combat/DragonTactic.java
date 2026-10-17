// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 龙的打法：打龙这一刻做什么。纯函数，看战场与龙场、这一仗记着的几件事。
 *
 * <p>先后次序（点着引信的苦力怕由普通打法先躲，压过这里的一切）：
 * <ol>
 *   <li>躲龙息：站在或快要站进龙的药水云（龙要喷时按龙头朝向预先算的那一口也算）；</li>
 *   <li>躲冲撞：龙飞着，照它此刻的速度两秒内会从我身边 12 格内扫过；跑不开了（不到半秒）、副手有盾就面朝它举盾；</li>
 *   <li>躲火球：龙火球照它此刻的速度会落到我身边 5 格内；</li>
 *   <li>先对付贴脸的：惹毛的末影人、别的敌对生物马上够得着我——交回普通打法（返回 null），打完接着打龙；</li>
 *   <li>龙死了：进了死亡动画就停手，去出口门边收经验；</li>
 *   <li>退开回血：血到 12 以下退，回到 18 或满血再回来；</li>
 *   <li>射水晶：正给龙回血的那颗先射（打掉它龙自己掉 10 点血），其余近的先；爆炸范围里有别的玩家、有主的生物、
 *       记住的区域或这次额外保护的地标不射，有末影人先等它走开（最多 10 秒）；</li>
 *   <li>龙趴着：砍龙头（趴着的龙箭射不动）；吼到第 30 刻与喷的时候退出龙头前方；</li>
 *   <li>龙飞着：有能射的、32 格内看得见就射；</li>
 *   <li>都不是：到出口门旁等它趴下。</li>
 * </ol>
 */
public final class DragonTactic {

    /** 血到这条线（含）以下就退开回血。 */
    public static final double REST_AT = 12.0;
    /** 回血回到这条线（或满血）再回来。 */
    public static final double RESUME_AT = 18.0;
    /** 爆炸范围里有末影人时最多等它这么多刻走开，过了照射。 */
    public static final int ENDERMAN_WAIT_TICKS = 200;

    private DragonTactic() {}

    /** 打龙这一刻做什么。 */
    public sealed interface Move permits AvoidCloud, DodgeSweep, DodgeFireball, Collect, Rest, SkipCrystal,
            ShootCrystal, StrikeHead, ShootDragon, WaitForPerch {}

    /** 躲开这些龙息（含预先算出的那一口）。 */
    public record AvoidCloud(List<DragonArena.Cloud> clouds) implements Move {
        public AvoidCloud {
            clouds = List.copyOf(clouds);
        }
    }

    /** 躲龙的冲撞：离开航线最近的那一处；brace 为跑不开了、面朝它举盾。 */
    public record DodgeSweep(DragonGeometry.Pass pass, boolean brace) implements Move {}

    /** 躲这个龙火球：离开它落点附近。 */
    public record DodgeFireball(DragonArena.Fireball fireball, DragonGeometry.Pass pass) implements Move {}

    /** 龙倒下了：停手，去出口门边收经验。 */
    public record Collect() implements Move {}

    /** 退开回血，附上为什么。 */
    public record Rest(String why) implements Move {}

    /** 放弃这颗水晶，附上为什么（爆炸范围里一直有受保护的东西）。 */
    public record SkipCrystal(DragonArena.Crystal crystal, String why) implements Move {}

    /** 射这颗水晶。 */
    public record ShootCrystal(DragonArena.Crystal crystal) implements Move {}

    /** 砍龙头；backOff 为龙要喷或正在喷，先退出龙头前方，在云外够得着才砍。 */
    public record StrikeHead(boolean backOff) implements Move {}

    /** 射飞着的龙。 */
    public record ShootDragon() implements Move {}

    /** 到出口门旁等龙趴下，附上为什么。 */
    public record WaitForPerch(String why) implements Move {}

    /**
     * 这一仗记着的事。
     *
     * @param resting           正在回血（回到线以上才回来）
     * @param givenUp           放弃了的水晶与为什么
     * @param endermanWaitSince 爆炸范围里有末影人的水晶，从哪一刻开始等它走开
     * @param hasShield         副手拿着盾（跑不开时举盾挡冲撞）
     */
    public record Memory(boolean resting, Map<Integer, String> givenUp, Map<Integer, Long> endermanWaitSince,
                         boolean hasShield) {
        public Memory {
            givenUp = Map.copyOf(givenUp);
            endermanWaitSince = Map.copyOf(endermanWaitSince);
        }

        public static final Memory FRESH = new Memory(false, Map.of(), Map.of(), false);
    }

    /** 这一刻的打法；该先对付贴脸的别的怪时返回 null，交回普通打法。 */
    public static Move decide(Battlefield field, Memory memory) {
        DragonArena arena = field.arena();
        DragonArena.Dragon dragon = arena.dragon();
        Battlefield.Me me = field.me();
        // 躲龙息：站进云里每两秒挨一下瞬间伤害（火球的云两倍），什么都先放下跑出去。
        List<DragonArena.Cloud> danger = DragonGeometry.dangerClouds(arena);
        if (DragonGeometry.inAnyCloud(danger, me.x(), me.y(), me.z())) return new AvoidCloud(danger);
        // 躲冲撞：龙头撞上 10 点、翅膀扇开 5 点，主岛边上被扇下去就掉进虚空。
        if (dragon.activity().airborne()) {
            DragonGeometry.Pass pass = DragonGeometry.sweep(dragon, me.x(), me.y(), me.z());
            if (DragonGeometry.sweepsMe(dragon, pass, me.y())) {
                return new DodgeSweep(pass, pass.ticks() < DragonGeometry.BRACE_TICKS && memory.hasShield());
            }
        }
        for (DragonArena.Fireball ball : arena.fireballs()) {
            DragonGeometry.Pass pass = DragonGeometry.fireball(ball, me.x(), me.y(), me.z());
            if (DragonGeometry.fireballHits(pass)) return new DodgeFireball(ball, pass);
        }
        // 惹毛的末影人、末影螨这类已经贴上来的：按普通打法先打它，打完接着打龙。
        for (Battlefield.Foe foe : field.foes()) {
            if (foe.chasing() && foe.fightsBack() && foe.imminent()) return null;
        }
        if (dragon.activity() == DragonArena.Activity.DYING) return new Collect();
        if (me.health() <= REST_AT) return new Rest("血到 " + Math.round(me.health()) + "，先退开回血");
        if (memory.resting() && me.health() < Math.min(RESUME_AT, arena.maxHealth())) {
            return new Rest("还在回血，回到 " + Math.round(Math.min(RESUME_AT, arena.maxHealth())) + " 再回去");
        }
        Move crystals = crystals(field, memory);
        if (crystals != null) return crystals;
        if (dragon.activity().perched()) {
            if (me.hasMelee()) return new StrikeHead(DragonGeometry.breathComing(dragon));
            return new WaitForPerch("龙趴着，手上没有近战武器，趴着的龙箭射不动");
        }
        if (me.canShoot() && dragon.visible() && dragon.distance() <= DragonGeometry.DRAGON_SHOT_RANGE) {
            return new ShootDragon();
        }
        return new WaitForPerch(dragon.activity() == DragonArena.Activity.LANDING ? "龙正往出口门上落" : "等龙落到出口门上");
    }

    // 水晶：有能射的才管；正给龙回血的先，其余近的先；爆炸范围里有别人的跳过，末影人在的等一会儿；
    // 剩下的都因为有别人不能射时放弃最前面那颗。身上没有能射的交给任务说缺弓箭，这里不硬打。
    private static Move crystals(Battlefield field, Memory memory) {
        Battlefield.Me me = field.me();
        DragonArena arena = field.arena();
        if (!me.canShoot()) return null;
        List<DragonArena.Crystal> open = new ArrayList<>();
        for (DragonArena.Crystal crystal : arena.crystals()) {
            if (memory.givenUp().containsKey(crystal.id())) continue;
            if (arena.portal() != null && Math.hypot(crystal.x() - arena.portal().x(), crystal.z() - arena.portal().z())
                    > DragonGeometry.ISLAND_RADIUS) {
                continue;
            }
            open.add(crystal);
        }
        if (open.isEmpty()) return null;
        int healing = arena.dragon().healingCrystalId();
        open.sort(Comparator.comparingInt((DragonArena.Crystal crystal) -> crystal.id() == healing ? 0 : 1)
                .thenComparingDouble(crystal -> DragonGeometry.distance(me.x(), me.y(), me.z(),
                        crystal.x(), crystal.y(), crystal.z()))
                .thenComparingInt(DragonArena.Crystal::id));
        DragonArena.Crystal blocked = null;
        for (DragonArena.Crystal crystal : open) {
            // 爆炸范围里有受保护的（别的玩家、有主的生物、记住的区域、这次额外保护的地标）：先跳过，射别的。
            if (crystal.protectedInBlast() != null) {
                if (blocked == null) blocked = crystal;
                continue;
            }
            if (crystal.endermanNear()) {
                Long since = memory.endermanWaitSince().get(crystal.id());
                if (since == null || arena.tick() - since < ENDERMAN_WAIT_TICKS) continue;
            }
            return new ShootCrystal(crystal);
        }
        // 能射的都射完了，剩下的爆炸范围里还有受保护的：放弃最前面那颗，结果里写明是什么挡着。
        if (blocked != null) return new SkipCrystal(blocked, blocked.protectedInBlast());
        // 只剩等末影人走开的：先去打龙，回头再射。
        return null;
    }
}
