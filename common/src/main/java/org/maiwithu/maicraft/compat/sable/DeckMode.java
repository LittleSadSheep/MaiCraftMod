// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 上下物理结构（via=board）：寻路看不见船上的方块，人站在船上要先下船、目的地在船上要先上船，这一段非它不可。
 * 上船：找船边比地面高不过一格、旁边地上站得住的地方，走过去再迈上甲板，站稳 8 刻；走不上去如实说甲板离地多高。
 * 下船：船停着才走，走到离出口近的船沿，迈下去（落差三格以内、落点站得住）；船上走几步去另一头也是它。
 */
final class DeckMode implements TravelMode {

    static final TravelVia VIA = new TravelVia("board", "上下物理结构（飞艇、船、车）：走上甲板站稳，或从船上走下来");
    /** 看多大范围里的结构（格）。 */
    static final int SEARCH = 64;
    /** 下船的落差最多几格：三格以内摔不伤。 */
    static final int MAX_DROP = 3;
    /** 估时：走一格多少刻、上下船多少刻。 */
    static final double WALK_TICKS_PER_BLOCK = 20 / 4.3;
    static final int STEP_TICKS = 40;

    /** 这一段做哪一种。 */
    enum Kind { BOARD, LEAVE, ACROSS }

    private final SableCompat compat;
    private final PlayerServices services;

    DeckMode(SableCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 非它不可：角色正站在船上，或目的地落在一艘船上。
    @Override public boolean claims(TravelRequest request) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) return false;
        List<SableStructures.Structure> near = compat.structures(level, player, new AABB(request.from()).inflate(SEARCH));
        if (near.stream().anyMatch(SableStructures.Structure::carriesPlayer)) return true;
        return request.to() != null && holding(near, request.to()).isPresent();
    }

    @Override public void offer(TravelRequest request, OfferSink sink) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) return;
        List<SableStructures.Structure> near = compat.structures(level, player, new AABB(request.from()).inflate(SEARCH));
        Optional<SableStructures.Structure> on = near.stream().filter(SableStructures.Structure::carriesPlayer).findFirst();
        Optional<SableStructures.Structure> target = request.to() == null ? Optional.empty() : holding(near, request.to());
        if (on.isPresent() && target.isPresent() && on.get().id().equals(target.get().id())) {
            across(level, player, on.get(), request, sink);
        } else if (on.isPresent()) {
            leave(level, player, on.get(), request, sink);
        } else if (target.isPresent()) {
            board(level, player, target.get(), request, sink);
        } else {
            sink.decline(Problem.Kind.UNSUPPORTED, "角色不在船上，目的地也不在船上");
        }
    }

    // 船上走几步：挑离目的地最近的甲板格。
    private void across(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, TravelRequest request,
            OfferSink sink) {
        Optional<StructureGeometry.Deck> spot = deck(level, ship).stream()
                .min(Comparator.comparingDouble(cell -> cell.world().distanceToSqr(Vec3.atBottomCenterOf(request.to()))));
        if (spot.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "船上找不到能站的地方");
            return;
        }
        long ticks = Math.round(spot.get().world().distanceTo(player.position()) * WALK_TICKS_PER_BLOCK) + STEP_TICKS;
        sink.offer(new TravelOffer(VIA, request.from(), BlockPos.containing(spot.get().world()), ticks, List.of(), List.of(),
                hint(Kind.ACROSS, ship, spot.get().local(), null)));
    }

    // 下船：离角色近的船沿，旁边落差三格以内、站得住的地上格。
    private void leave(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, TravelRequest request,
            OfferSink sink) {
        List<AABB> hull = hull(level, ship);
        Pair best = null;
        for (StructureGeometry.Deck cell : deck(level, ship)) {
            for (BlockPos ground : beside(cell.world())) {
                for (int drop = 0; drop <= MAX_DROP; drop++) {
                    BlockPos landing = ground.below(drop);
                    if (!BlockPassability.isDryStandable(level, landing) || touches(hull, landing)) continue;
                    double cost = cell.world().distanceTo(player.position()) + drop;
                    if (best == null || cost < best.cost()) best = new Pair(cell, landing, cost);
                    break;
                }
            }
        }
        if (best == null) {
            sink.decline(Problem.Kind.UNREACHABLE, "船沿下面找不到落差三格以内、站得住的地方");
            return;
        }
        long ticks = Math.round(best.cost() * WALK_TICKS_PER_BLOCK) + STEP_TICKS;
        sink.offer(new TravelOffer(VIA, request.from(), best.ground(), ticks, List.of(), List.of(),
                hint(Kind.LEAVE, ship, best.deck().local(), best.ground())));
    }

    // 上船：船边比旁边地面高不过一格的甲板格，地上那格站得住、离角色近。
    private void board(ClientLevel level, LocalPlayer player, SableStructures.Structure ship, TravelRequest request,
            OfferSink sink) {
        List<AABB> hull = hull(level, ship);
        Pair best = null;
        double lowest = Double.MAX_VALUE;
        for (StructureGeometry.Deck cell : deck(level, ship)) {
            for (BlockPos ground : beside(cell.world())) {
                for (int down = 0; down <= 2; down++) {
                    BlockPos stand = ground.below(down);
                    if (!BlockPassability.isDryStandable(level, stand) || touches(hull, stand)) continue;
                    double rise = cell.world().y - stand.getY();
                    lowest = Math.min(lowest, rise);
                    if (rise > 1.05) break;
                    double cost = Math.sqrt(stand.distSqr(player.blockPosition()))
                            + cell.world().distanceTo(Vec3.atBottomCenterOf(request.to())) * 0.25;
                    if (best == null || cost < best.cost()) best = new Pair(cell, stand, cost);
                    break;
                }
            }
        }
        if (best == null) {
            sink.decline(Problem.Kind.UNREACHABLE, lowest == Double.MAX_VALUE
                    ? "船边旁边找不到能站的地面" : "甲板离地 " + Math.round(lowest) + " 格，走不上去");
            return;
        }
        long ticks = Math.round(Math.sqrt(best.ground().distSqr(request.from())) * WALK_TICKS_PER_BLOCK) + STEP_TICKS;
        sink.offer(new TravelOffer(VIA, request.from(), BlockPos.containing(best.deck().world()), ticks, List.of(),
                List.of(), hint(Kind.BOARD, ship, best.deck().local(), best.ground())));
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        String[] hint = offer.hint().split("\\|");
        BlockPos ground = hint[3].isEmpty() ? null : BlockPos.of(Long.parseLong(hint[3]));
        return new DeckLeg(compat, services, request.permissions(), Kind.valueOf(hint[0]), hint[1],
                BlockPos.of(Long.parseLong(hint[2])), ground);
    }

    /** 一处甲板格与它旁边的地上格，以及走这一处的代价。 */
    private record Pair(StructureGeometry.Deck deck, BlockPos ground, double cost) {}

    private List<StructureGeometry.Deck> deck(ClientLevel level, SableStructures.Structure ship) {
        return StructureGeometry.deck(ship, local -> compat.block(level, ship.id(), local));
    }

    private List<AABB> hull(ClientLevel level, SableStructures.Structure ship) {
        List<AABB> boxes = new ArrayList<>();
        for (AABB local : compat.scan(level, ship).boxes()) boxes.add(StructureGeometry.toWorld(ship.pose(), local));
        return boxes;
    }

    // 甲板格旁边一两格的地上格（同一高度那一层）。
    private static List<BlockPos> beside(Vec3 deck) {
        List<BlockPos> cells = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= 2; step++) {
                cells.add(BlockPos.containing(deck.x + side.getStepX() * step, deck.y + 0.01, deck.z + side.getStepZ() * step));
            }
        }
        return cells;
    }

    private static boolean touches(List<AABB> hull, BlockPos feet) {
        AABB body = new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + 1.8,
                feet.getZ() + 0.8);
        return hull.stream().anyMatch(body::intersects);
    }

    private static Optional<SableStructures.Structure> holding(List<SableStructures.Structure> near, BlockPos to) {
        Vec3 at = Vec3.atCenterOf(to);
        return near.stream().filter(ship -> ship.worldBounds().inflate(1).contains(at)).findFirst();
    }

    private static String hint(Kind kind, SableStructures.Structure ship, BlockPos deckLocal, BlockPos ground) {
        return kind + "|" + ship.id() + "|" + deckLocal.asLong() + "|" + (ground == null ? "" : ground.asLong());
    }
}
