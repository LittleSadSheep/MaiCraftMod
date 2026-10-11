// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.maiwithu.maicraft.game.player.DamageObserver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 挨打的片段（事件 hurt）：同一个来源（同一个攻击者，没有攻击者时同一种伤害）连着挨的算一段，
 * 5 秒没再挨这个来源的打就把这一段发出去；一直挨着打不停时每 10 秒发一条，只算这 10 秒里的。
 * 每条都是自成一体的"刚才这阵"，不分开始与结束。服务器伤害包确认的命中直接算；没收到伤害包、
 * 只看到血掉了的，单独一段写明"只看到血掉了"。只看血量，黄心（伤害吸收）到期掉的不算挨打。
 * 死了的时候没发出的段丢掉（角色死了已有自己的事件），换世界、断线时也丢掉。被玩家打照样只发一条，不替 LLM 决定怎么回应。
 */
public final class HurtEpisodes implements DamageObserver {

    /** 这么久没再挨这个来源的打，这一段就发出去。 */
    static final int QUIET_TICKS = 100;
    /** 一直挨着打时，每过这么久发一条。 */
    static final int WINDOW_TICKS = 200;
    /** 伤害包到了以后，这么多刻里血掉的算这一下的。 */
    private static final int ATTRIBUTE_TICKS = 2;
    private static final String UNSEEN = "unseen";

    private final TaskEventSink events;
    private final Function<Integer, Optional<String>> seenIdOf;
    private final Map<String, Episode> open = new LinkedHashMap<>();
    private Float lastHealth;
    private String lastHitKey;
    private long lastHitTick = Long.MIN_VALUE;

    /**
     * @param seenIdOf 游戏实体编号 → 这会儿的观察编号（看得见才有），消息里带上让 LLM 能指它
     */
    public HurtEpisodes(TaskEventSink events, Function<Integer, Optional<String>> seenIdOf) {
        this.events = Objects.requireNonNull(events, "events");
        this.seenIdOf = Objects.requireNonNull(seenIdOf, "seenIdOf");
    }

    /**
     * 一下伤害的来源：归并用的键、攻击者怎么称呼（没有为 null）、原版伤害类型。
     *
     * @param key        同一个攻击者或同一种伤害的键
     * @param attacker   攻击者的说法：玩家名、名字或种类名，看得见带观察编号；没有攻击者为 null
     * @param damageType 原版伤害类型（msgId），例如 fall、arrow、mob
     */
    record Hit(String key, String attacker, String damageType) {
    }

    // 伤害包：认出是谁、什么伤，记一下；血掉多少等下一刻看血量。
    @Override public void hurt(LocalPlayer player, DamageSource source, long gameTick) {
        Entity attacker = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        String type = source.type().msgId();
        if (attacker == null) {
            hit(new Hit("type:" + type, null, type), gameTick);
            return;
        }
        String name = attacker instanceof Player other ? "玩家 " + other.getGameProfile().getName()
                : attacker.hasCustomName() ? attacker.getCustomName().getString()
                : attacker.getType().getDescription().getString();
        String seen = seenIdOf.apply(attacker.getId()).map(id -> "（" + id + "）").orElse("");
        hit(new Hit("entity:" + attacker.getId(), name + seen, type), gameTick);
    }

    /** 每刻看一眼血量：掉了的记到刚挨的那一下上，没有伤害包的记"只看到血掉了"；到点的段发出去。 */
    public void tick(PlayerContext context) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        LocalPlayer self = context.localPlayer();
        health(self.getHealth(), self.isDeadOrDying(), context.level().getGameTime());
    }

    /** 换世界、断线：没发出的段丢掉，血量基准重来。 */
    public void forget() {
        open.clear();
        lastHealth = null;
        lastHitKey = null;
    }

    void hit(Hit hit, long tick) {
        Episode episode = open.computeIfAbsent(hit.key(), key -> new Episode(hit, tick));
        episode.hits++;
        episode.lastTick = tick;
        lastHitKey = hit.key();
        lastHitTick = tick;
    }

    void health(float health, boolean dead, long tick) {
        if (dead || health <= 0f) {
            // 死了：角色死了的事件已经说了，没发出的段直接丢掉。
            forget();
            return;
        }
        if (lastHealth != null && health < lastHealth) {
            float lost = lastHealth - health;
            Episode episode = lastHitKey != null && tick - lastHitTick <= ATTRIBUTE_TICKS ? open.get(lastHitKey) : null;
            if (episode == null) {
                episode = open.computeIfAbsent(UNSEEN, key -> new Episode(new Hit(UNSEEN, null, ""), tick));
                episode.lastTick = tick;
            }
            episode.lost += lost;
        }
        lastHealth = health;
        flush(health, tick);
    }

    // 到点的段发出去：5 秒没再挨这个来源的打整段发；一直在挨的每 10 秒发一条再接着记。
    private void flush(float health, long tick) {
        var it = open.values().iterator();
        while (it.hasNext()) {
            Episode episode = it.next();
            if (tick - episode.lastTick >= QUIET_TICKS) {
                publish(episode, health);
                it.remove();
            } else if (tick - episode.windowStart >= WINDOW_TICKS) {
                publish(episode, health);
                episode.hits = 0;
                episode.lost = 0f;
                episode.windowStart = tick;
            }
        }
    }

    private void publish(Episode episode, float health) {
        if (episode.hits == 0 && episode.lost <= 0f) return;
        events.publish(TaskEvent.Kind.HURT, describe(episode.hit, episode.hits, episode.lost, health));
    }

    /** 一段挨打的一句话，纯函数：谁、什么伤、几下、掉了多少、还剩多少；没伤害包的写明只看到血掉了。 */
    static String describe(Hit hit, int hits, float lost, float health) {
        String tail = "，掉了 " + number(lost) + " 点血，还剩 " + number(health) + " 点";
        if (hit.key().equals(UNSEEN)) {
            return "只看到血掉了 " + number(lost) + " 点（没收到伤害包，说不清是谁、什么伤），还剩 " + number(health) + " 点";
        }
        String times = hits == 1 ? "一下" : " " + hits + " 下";
        if (hit.attacker() != null) return "被" + hit.attacker() + attackVerb(hit.damageType()) + times + tail;
        return selfPhrase(hit.damageType(), times) + tail;
    }

    // 有攻击者时的动词：射中、刺中、炸到……别的一律"打"。
    private static String attackVerb(String type) {
        return switch (type) {
            case "arrow" -> "射中";
            case "trident" -> "刺中";
            case "fireball", "unattributedFireball", "witherSkull" -> "用火球炸中";
            case "explosion", "explosion.player" -> "炸到";
            case "thorns" -> "反伤";
            case "sonic_boom" -> "用音爆打中";
            case "indirectMagic", "magic" -> "用魔法伤到";
            default -> "打了";
        };
    }

    // 没有攻击者时：照原版伤害类型说人话。
    private static String selfPhrase(String type, String times) {
        return switch (type) {
            case "fall" -> "摔了" + times;
            case "drown" -> "溺水呛了" + times;
            case "inFire", "onFire", "campfire" -> "着火烧了" + times;
            case "lava" -> "被岩浆烫了" + times;
            case "hotFloor" -> "被岩浆块烫了" + times;
            case "cactus" -> "被仙人掌扎了" + times;
            case "sweetBerryBush" -> "被浆果丛扎了" + times;
            case "starve" -> "饿得掉血" + times;
            case "inWall" -> "卡在方块里憋了" + times;
            case "cramming" -> "被挤了" + times;
            case "freeze" -> "冻伤了" + times;
            case "wither" -> "凋零掉血" + times;
            case "magic", "indirectMagic" -> "中了魔法伤" + times;
            case "lightningBolt" -> "被雷劈了" + times;
            case "fallingBlock", "fallingStalactite", "anvil" -> "被掉下来的东西砸了" + times;
            case "stalagmite" -> "摔到石笋上" + times;
            case "flyIntoWall" -> "飞着撞墙" + times;
            case "dragonBreath" -> "被龙息伤了" + times;
            case "outOfWorld", "outsideBorder" -> "掉出世界" + times;
            default -> "受了 " + type + " 伤" + times;
        };
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** 一个来源这一段的账：来源、几下、掉了多少、最后一下在哪刻、这一条从哪刻算起。 */
    private static final class Episode {
        final Hit hit;
        int hits;
        float lost;
        long lastTick;
        long windowStart;

        Episode(Hit hit, long tick) {
            this.hit = hit;
            this.lastTick = tick;
            this.windowStart = tick;
        }
    }
}
