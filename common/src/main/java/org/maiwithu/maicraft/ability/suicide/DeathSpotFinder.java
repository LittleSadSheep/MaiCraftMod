// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 找能死的地方：分刻扫起点附近已加载的地形，按档挑一处。纯判断，只读 {@link SuicideWorld} 与 {@link SuicidePlayer}。
 *
 * <p>地形扫一遍记下来（岩浆、摔得死的落差、仙人掌），扫完不重扫；敌对生物每次挑都重读；随身点火、倒岩浆
 * 到轮到它那一档时才在身边选格。挑的时候按档来，同一档里挑离角色最近的；挑中的只是"看起来能用"，
 * 走不走得到由走路如实回答。放弃过的地方不再挑。
 */
final class DeathSpotFinder {

    /** 每刻最多看多少个站位：起点半径 64 时一共四十多万个，分刻看不卡游戏。 */
    static final int STANDS_PER_TICK = 1024;
    /** 站位比起点高低各多少格以内。 */
    static final int VERTICAL_REACH = 12;
    /** 落差往下最多看多少格找落地的地方。 */
    static final int FALL_PROBE = 64;

    /** 挑的结论。 */
    sealed interface Choice {
        /** 挑中这一处。 */
        record Found(DeathSpot spot) implements Choice {}

        /** 还在扫地形或在身边选格，下一刻再挑。 */
        record Searching() implements Choice {}

        /** 这一轮一处都没有了。 */
        record Nothing() implements Choice {}
    }

    private final SuicideWorld world;
    private final SuicidePlayer player;
    private final BlockPos origin;
    private final int radius;
    /** 这次能用的死法：指定了就只有那一种；许可不让动方块时没有随身造危险的两种。 */
    private final Set<WayToDie> allowed;
    private final List<DeathSpot> terrain = new ArrayList<>();
    private final int volume;
    private int cursor;
    /** 正在身边选格的那一种随身死法；同一种接着看，换一种重新开始。 */
    private CarriedHazards carried;
    /** 身边一格都选不出来的随身死法。 */
    private final Set<WayToDie> noCell = EnumSet.noneOf(WayToDie.class);
    /** 这一刻的摔伤估算：一刻里要算很多列落差，取样一次就够。 */
    private FallDamage fallNow;

    /**
     * @param origin  起点：收完护具后角色的脚位，之后人走动不带着范围走
     * @param radius  找死法的半径（格）
     * @param allowed 这次能用的死法
     */
    DeathSpotFinder(SuicideWorld world, SuicidePlayer player, BlockPos origin, int radius, Set<WayToDie> allowed) {
        this.world = Objects.requireNonNull(world, "world");
        this.player = Objects.requireNonNull(player, "player");
        this.origin = origin.immutable();
        this.radius = radius;
        this.allowed = allowed.isEmpty() ? EnumSet.noneOf(WayToDie.class) : EnumSet.copyOf(allowed);
        int width = radius * 2 + 1;
        boolean scansTerrain = this.allowed.stream().anyMatch(WayToDie::terrain);
        // 只要生物或随身造危险时不扫地形。
        this.volume = scansTerrain ? width * width * (VERTICAL_REACH * 2 + 1) : 0;
    }

    /** 地形扫完了。 */
    boolean scanned() {
        return cursor >= volume;
    }

    /** 扫一刻的地形：最多看 {@link #STANDS_PER_TICK} 个站位。 */
    void scan() {
        fallNow = player.fall();
        int width = radius * 2 + 1;
        for (int budget = 0; cursor < volume && budget < STANDS_PER_TICK; budget++, cursor++) {
            int x = cursor % width - radius;
            int z = cursor / width % width - radius;
            int y = cursor / (width * width) - VERTICAL_REACH;
            BlockPos stand = origin.offset(x, y, z);
            if (stand.distSqr(origin) > (double) radius * radius || !world.standable(stand)) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                WayToDie way = terrainWay(stand.relative(direction));
                if (way != null) terrain.add(DeathSpot.at(way, stand, stand.relative(direction)));
            }
        }
    }

    /**
     * 按档挑一处：同一档里的现成死法挑最近的，都没有再看这一档的随身死法。
     *
     * @param abandoned 放弃过的地方的记号
     * @param givenUp   这次不再用的死法（右键出去没效果、拿不到手上）
     */
    Choice choose(Set<String> abandoned, Set<WayToDie> givenUp) {
        if (!scanned()) return new Choice.Searching();
        fallNow = player.fall();
        for (int tier = 1; tier <= 4; tier++) {
            Optional<DeathSpot> nearest = nearestReady(tier, abandoned, givenUp);
            if (nearest.isPresent()) return new Choice.Found(nearest.get());
            for (WayToDie way : WayToDie.values()) {
                if (way.tier != tier || !way.carried() || !usable(way, givenUp) || noCell.contains(way)) continue;
                if (carried == null || carried.way() != way) carried = new CarriedHazards(world, way, player.feet(), radius);
                switch (carried.step(abandoned)) {
                    case CarriedHazards.Step.Found found -> {
                        return new Choice.Found(DeathSpot.at(way, found.cell(), found.cell()));
                    }
                    case CarriedHazards.Step.Searching searching -> {
                        return new Choice.Searching();
                    }
                    case CarriedHazards.Step.Exhausted exhausted -> noCell.add(way);
                }
            }
        }
        return new Choice.Nothing();
    }

    /** 这一处此刻还在不在、还能不能用：走过去之前、站进去之前再看一眼。 */
    boolean stillThere(DeathSpot spot) {
        fallNow = player.fall();
        return switch (spot.way()) {
            case HOSTILE_MOB -> world.mobPosition(spot.mobId(), spot.mobType()).isPresent();
            case FIRE, LAVA_BUCKET -> CarriedHazards.fits(world, spot.way(), spot.entry());
            default -> world.standable(spot.stand()) && terrainWay(spot.entry()) == spot.way();
        };
    }

    /** 同一种死法、站位离这一处不到 distance 格的地形死法的记号：一处走不到，挨着的多半也走不到。 */
    Set<String> sameWayNear(DeathSpot spot, double distance) {
        Set<String> keys = new HashSet<>();
        for (DeathSpot other : terrain) {
            if (other.way() == spot.way() && other.stand().distSqr(spot.stand()) <= distance * distance) {
                keys.add(other.key());
            }
        }
        return keys;
    }

    /**
     * 一种死法这次用不上的原因（没带东西、有抗火、身边选不出格、附近没找到）；用得上时为空。
     * 挑不出任何一处时，按死法摊开说一次。
     */
    Map<WayToDie, String> whyUnavailable(Set<WayToDie> givenUp) {
        Map<WayToDie, String> reasons = new EnumMap<>(WayToDie.class);
        for (WayToDie way : allowed) {
            if (givenUp.contains(way)) continue;
            if (way.burns() && player.fireResistant()) {
                reasons.put(way, "身上有抗火效果，烧不死");
            } else if (way.carried() && !carriesFor(way)) {
                reasons.put(way, way == WayToDie.FIRE ? "身上没有打火石或火焰弹" : "身上没有岩浆桶");
            } else if (way.carried() && noCell.contains(way)) {
                reasons.put(way, "身边 " + Math.min(CarriedHazards.REACH, radius) + " 格内没有不会烧着东西、不碰保护的空地");
            } else if (!way.carried() && terrain.stream().noneMatch(spot -> spot.way() == way)) {
                reasons.put(way, "附近 " + radius + " 格内没找到");
            }
        }
        return reasons;
    }

    // 这一档里现成的（地形与敌对生物）挑离角色最近、此刻还在的一处。
    private Optional<DeathSpot> nearestReady(int tier, Set<String> abandoned, Set<WayToDie> givenUp) {
        List<DeathSpot> pool = new ArrayList<>();
        for (DeathSpot spot : terrain) {
            if (spot.way().tier == tier && usable(spot.way(), givenUp) && !abandoned.contains(spot.key())) pool.add(spot);
        }
        if (WayToDie.HOSTILE_MOB.tier == tier && usable(WayToDie.HOSTILE_MOB, givenUp)) {
            for (SuicideWorld.HostileMob mob : world.hostileMobs(origin, radius)) {
                BlockPos at = BlockPos.containing(mob.position());
                DeathSpot spot = new DeathSpot(WayToDie.HOSTILE_MOB, at, at, mob.id(), mob.type());
                if (!abandoned.contains(spot.key())) pool.add(spot);
            }
        }
        Vec3 here = player.position();
        pool.sort(Comparator.comparingDouble(spot -> here.distanceToSqr(Vec3.atBottomCenterOf(spot.stand()))));
        return pool.stream().filter(this::stillThere).findFirst();
    }

    // 这种死法这次能不能用：允许、没放弃；烧的死法有抗火不行；随身的要带着东西。
    private boolean usable(WayToDie way, Set<WayToDie> givenUp) {
        if (!allowed.contains(way) || givenUp.contains(way)) return false;
        if (way.burns() && player.fireResistant()) return false;
        return !way.carried() || carriesFor(way);
    }

    private boolean carriesFor(WayToDie way) {
        if (way == WayToDie.LAVA_BUCKET) return player.carries(WayToDie.LAVA_BUCKET_ITEM);
        return WayToDie.IGNITERS.stream().anyMatch(player::carries);
    }

    // 站位旁边这一格是哪种地形死法：仙人掌本身就是入口；岩浆与落差要求这一格和上面一格身体进得去。
    private WayToDie terrainWay(BlockPos entry) {
        if (allowed.contains(WayToDie.CACTUS) && world.cactus(entry)) return WayToDie.CACTUS;
        if (!world.loaded(entry) || !world.passable(entry) || !world.passable(entry.above())) return null;
        if (allowed.contains(WayToDie.LAVA) && (world.lava(entry) || world.loaded(entry.below()) && world.lava(entry.below()))) {
            return WayToDie.LAVA;
        }
        if (allowed.contains(WayToDie.FALL) && !world.fluid(entry) && lethalDrop(entry)) return WayToDie.FALL;
        return null;
    }

    // 从这一格迈出去往下掉会不会摔死：往下找落地的方块，途中有流体、没加载的格就不算；落进虚空必死。
    // 按此刻的血量与摔伤估算算（护甲已经脱了），缓降这类效果由摔伤估算自己算进去。
    private boolean lethalDrop(BlockPos entry) {
        for (int depth = 1; depth <= FALL_PROBE; depth++) {
            BlockPos below = entry.below(depth);
            if (world.belowWorld(below)) return true;
            if (!world.loaded(below) || world.fluid(below) || world.breaksFall(below)) return false;
            if (!world.passable(below)) {
                return !fallNow.survives(depth - 1, world.landingOn(below), false);
            }
        }
        // 往下看满了还没见底（深坑、末地岛边）：少说也摔这么多格，按这个落差算。
        return !fallNow.survives(FALL_PROBE, FallDamage.Landing.ORDINARY, false);
    }
}
