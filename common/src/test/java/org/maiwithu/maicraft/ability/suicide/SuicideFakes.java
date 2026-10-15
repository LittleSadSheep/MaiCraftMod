// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 寻死离线测试的替身：一张格子表的世界、可以拨的角色、按脚本走的动作与穿卸。 */
final class SuicideFakes {

    private SuicideFakes() {}

    /** 格子的样子。 */
    enum Cell { AIR, SOLID, WOOD, PROTECTED, LAVA, WATER, CACTUS, FIRE, LADDER }

    /** 地形替身：没写的格 y 小于 64 是实心地面、以上是空气；离原点 100 格以外没加载，y 低于 -64 是虚空。 */
    static final class World implements SuicideWorld {
        final Map<BlockPos, Cell> cells = new HashMap<>();
        final List<HostileMob> mobs = new ArrayList<>();
        int loadedRadius = 100;
        boolean hot;
        Predicate<BlockPos> mayPlace = cell -> true;

        World put(BlockPos cell, Cell kind) {
            cells.put(cell.immutable(), kind);
            return this;
        }

        /** 挖一列：从 top 往下挖到 bottom（含），挖出的格是空气。 */
        World dig(int x, int z, int top, int bottom) {
            for (int y = top; y >= bottom; y--) put(new BlockPos(x, y, z), Cell.AIR);
            return this;
        }

        Cell at(BlockPos cell) {
            Cell kind = cells.get(cell);
            if (kind != null) return kind;
            return cell.getY() < 64 ? Cell.SOLID : Cell.AIR;
        }

        @Override public boolean loaded(BlockPos cell) {
            return Math.abs(cell.getX()) <= loadedRadius && Math.abs(cell.getZ()) <= loadedRadius
                    && cell.getY() >= -64 && cell.getY() < 320;
        }

        @Override public boolean belowWorld(BlockPos cell) {
            return cell.getY() < -64;
        }

        @Override public boolean standable(BlockPos feet) {
            return loaded(feet) && sturdyTop(feet.below()) && at(feet) == Cell.AIR && at(feet.above()) == Cell.AIR;
        }

        @Override public boolean passable(BlockPos cell) {
            if (!loaded(cell)) return false;
            Cell kind = at(cell);
            return kind == Cell.AIR || kind == Cell.LAVA || kind == Cell.WATER || kind == Cell.FIRE;
        }

        @Override public boolean breaksFall(BlockPos cell) {
            return at(cell) == Cell.LADDER;
        }

        @Override public boolean fluid(BlockPos cell) {
            return loaded(cell) && (at(cell) == Cell.LAVA || at(cell) == Cell.WATER);
        }

        @Override public boolean lava(BlockPos cell) {
            return loaded(cell) && at(cell) == Cell.LAVA;
        }

        @Override public boolean cactus(BlockPos cell) {
            return loaded(cell) && at(cell) == Cell.CACTUS;
        }

        @Override public boolean air(BlockPos cell) {
            return loaded(cell) && at(cell) == Cell.AIR;
        }

        @Override public boolean sturdyTop(BlockPos cell) {
            Cell kind = at(cell);
            return loaded(cell) && (kind == Cell.SOLID || kind == Cell.WOOD || kind == Cell.PROTECTED);
        }

        @Override public boolean fireAt(BlockPos cell) {
            return loaded(cell) && at(cell) == Cell.FIRE;
        }

        @Override public boolean flammable(BlockPos cell) {
            return at(cell) == Cell.WOOD;
        }

        @Override public boolean protectedBlock(BlockPos cell) {
            return at(cell) == Cell.PROTECTED;
        }

        @Override public boolean mayPlaceAt(BlockPos cell) {
            return mayPlace.test(cell);
        }

        @Override public boolean hotDimension() {
            return hot;
        }

        @Override public FallDamage.Landing landingOn(BlockPos support) {
            return FallDamage.Landing.ORDINARY;
        }

        @Override public List<HostileMob> hostileMobs(BlockPos origin, int radius) {
            Vec3 center = Vec3.atBottomCenterOf(origin);
            return mobs.stream().filter(mob -> mob.position().distanceToSqr(center) <= (double) radius * radius).toList();
        }

        @Override public Optional<Vec3> mobPosition(int id, String type) {
            return mobs.stream().filter(mob -> mob.id() == id && mob.type().equals(type))
                    .map(HostileMob::position).findFirst();
        }
    }

    /** 角色替身：模式、规则、血量、位置、身上带的东西、穿着的护甲与背包里的原件都可以拨。 */
    static final class Player implements SuicidePlayer {
        boolean present = true;
        boolean creative;
        boolean spectator;
        boolean hardcore;
        KeepInventoryRule.Reading keepInventory = KeepInventoryRule.Reading.ON;
        long respawns;
        float health = 20;
        Vec3 position = Vec3.atBottomCenterOf(new BlockPos(0, 64, 0));
        boolean onGround = true;
        boolean onFire;
        boolean fireResistant;
        final Set<String> carried = new HashSet<>();
        final Map<GearSlotName, WornPiece> worn = new EnumMap<>(GearSlotName.class);
        final Set<GearSlotName> totems = EnumSet.noneOf(GearSlotName.class);
        /** 背包格号 → 那一格的那件东西。 */
        final Map<Integer, WornPiece> inventory = new HashMap<>();

        void stand(BlockPos feet) {
            position = Vec3.atBottomCenterOf(feet);
        }

        void wear(GearSlotName slot, String itemId, String mark) {
            worn.put(slot, new WornPiece(itemId, mark));
        }

        int freeSlot() {
            for (int slot = 0; slot <= 35; slot++) {
                if (!inventory.containsKey(slot)) return slot;
            }
            return -1;
        }

        @Override public boolean present() { return present; }
        @Override public boolean creative() { return creative; }
        @Override public boolean spectator() { return spectator; }
        @Override public boolean hardcore() { return hardcore; }
        @Override public KeepInventoryRule.Reading keepInventory() { return keepInventory; }
        @Override public long respawns() { return respawns; }
        @Override public float health() { return health; }
        @Override public Vec3 position() { return position; }
        @Override public BlockPos feet() { return BlockPos.containing(position); }
        @Override public boolean onGround() { return onGround; }
        @Override public boolean onFire() { return onFire; }
        @Override public boolean fireResistant() { return fireResistant; }
        @Override public boolean carries(String itemId) { return carried.contains(itemId); }

        @Override public FallDamage fall() {
            return new FallDamage(health, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
        }

        @Override public Optional<WornPiece> worn(GearSlotName slot) {
            if (totems.contains(slot)) return Optional.of(new WornPiece("minecraft:totem_of_undying", "totem"));
            return Optional.ofNullable(worn.get(slot));
        }

        @Override public boolean holdsTotem(GearSlotName hand) { return totems.contains(hand); }

        @Override public int carriedSlotOf(WornPiece piece) {
            return inventory.entrySet().stream().filter(entry -> entry.getValue().equals(piece))
                    .map(Map.Entry::getKey).findFirst().orElse(-1);
        }
    }

    /** 穿卸替身：取下放进背包第一个空格（背包满时取不下），从背包那一格穿回原部位。 */
    static final class Gear implements GearChanges {
        final Player player;
        final List<String> calls = new ArrayList<>();
        boolean full;

        Gear(Player player) {
            this.player = player;
        }

        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            calls.add("takeOff:" + slot.paramName());
            return Optional.of(done(() -> {
                if (full) return;
                SuicidePlayer.WornPiece piece = player.worn(slot).orElseThrow();
                player.worn.remove(slot);
                player.totems.remove(slot);
                player.inventory.put(player.freeSlot(), piece);
            }));
        }

        @Override public Optional<Action> wearFrom(int inventorySlot, GearSlotName slot) {
            calls.add("wearFrom:" + inventorySlot + ":" + slot.paramName());
            return Optional.of(done(() -> {
                SuicidePlayer.WornPiece piece = player.inventory.remove(inventorySlot);
                if (piece != null) player.worn.put(slot, piece);
            }));
        }
    }

    /** 动作替身：走到就到（点名走不到的除外），踏进危险与跟怪一直在做，造危险按脚本成或不成。 */
    static final class Moves implements SuicideMoves {
        final Player player;
        final World world;
        final List<String> calls = new ArrayList<>();
        final Set<BlockPos> unreachable = new HashSet<>();
        /** 哪儿都走不到。 */
        boolean walkFails;
        /** 不为 null 时点火、倒岩浆按这个问题失败。 */
        Problem makeFails;

        Moves(Player player, World world) {
            this.player = player;
            this.world = world;
        }

        @Override public Action walkTo(BlockPos stand, Permissions permissions) {
            calls.add("walkTo:" + stand.toShortString());
            if (walkFails || unreachable.contains(stand)) {
                return fails(Problem.of(Problem.Kind.UNREACHABLE, "到不了 " + stand.toShortString() + "，还差 3.2 格"));
            }
            return done(() -> player.stand(stand));
        }

        @Override public Action stepInto(DeathSpot spot) {
            calls.add("stepInto:" + spot.way().param);
            return forever("踏进危险");
        }

        @Override public Action keepNear(int mobId, String mobType, Permissions permissions) {
            calls.add("keepNear:" + mobId);
            return forever("跟着怪");
        }

        @Override public Action standIn(BlockPos cell) {
            calls.add("standIn:" + cell.toShortString());
            return done(() -> player.stand(cell));
        }

        @Override public Optional<Action> hold(String itemId) {
            calls.add("hold:" + itemId);
            return Optional.empty();
        }

        @Override public Action makeHazard(WayToDie way, BlockPos cell) {
            calls.add("make:" + way.param);
            if (makeFails != null) return fails(makeFails);
            return done(() -> {
                world.put(cell, way == WayToDie.FIRE ? Cell.FIRE : Cell.LAVA);
                if (way == WayToDie.LAVA_BUCKET) player.carried.remove(WayToDie.LAVA_BUCKET_ITEM);
            });
        }
    }

    /** 第一刻就做完、做完时改一下现场的动作。 */
    static Action done(Runnable effect) {
        return new Action() {
            private boolean ran;

            @Override public ActionStatus tick(TickContext context) {
                if (!ran) {
                    ran = true;
                    effect.run();
                }
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "替身动作";
            }
        };
    }

    static Action fails(Problem problem) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(problem);
            }

            @Override public String describe() {
                return "替身动作";
            }
        };
    }

    static Action forever(String what) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.running();
            }

            @Override public String describe() {
                return what;
            }
        };
    }

    /** 每刻上下文替身：刻号递增，角色本体用不到。 */
    record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }
}
