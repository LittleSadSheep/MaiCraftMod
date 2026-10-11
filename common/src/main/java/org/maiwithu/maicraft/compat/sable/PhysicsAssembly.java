// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用物理组装器组装或拆回：组装器在世界里（贴着要组装的方块）就组装，在结构上就拆回。
 * 走到组装器跟前 →（拆回先等结构停稳）→ 看着它拉一次杆 → 看转换了没有：组装看组装器所在格空了、旁边出现带组装器的结构；
 * 拆回看结构没了。只拉一次——再拉会拆回去；三秒没转换就照实交代，不再拉第二次。
 */
final class PhysicsAssembly implements VehicleAssembler {

    /** 在指的地方周围多远找组装器（格）。 */
    static final int SEARCH = 8;
    /** 拉完等转换多少刻（3 秒）。 */
    static final int CONFIRM_TICKS = 60;
    /** 拆回前等结构停稳最多多少刻、要停多少刻。 */
    static final int MAX_WAIT_TICKS = 600;
    static final int STILL_TICKS = 10;

    private final SimulatedCompat compat;
    private final PlayerServices services;

    PhysicsAssembly(SimulatedCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    // 组装：世界里找组装器；找不到再看附近的结构上是不是已经有组装器（已经组装过）。
    // 拆回：指的结构（或指的地方所在的结构）上找组装器；没有结构、组装器还在世界里就是已经是方块。
    @Override public Optional<Run> start(Request request) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) return Optional.empty();
        Optional<BlockPos> inWorld = worldAssembler(level, request.near());
        List<SableStructures.Structure> near = compat.structures(level, player, new AABB(request.near()).inflate(SEARCH));
        if (request.assemble()) {
            if (inWorld.isPresent()) return Optional.of(new Pull(request, inWorld.get(), inWorld.get(), null));
            return near.stream().filter(ship -> !compat.scan(level, ship).assemblers().isEmpty()).findFirst()
                    .map(ship -> done(ship.id(), compat.scan(level, ship).blocks(), "附近的结构上已经有这台组装器"));
        }
        Optional<SableStructures.Structure> ship = request.structureId() != null
                ? near.stream().filter(candidate -> candidate.id().equals(request.structureId())).findFirst()
                : near.stream().filter(candidate -> candidate.worldBounds().inflate(2).contains(Vec3.atCenterOf(request.near())))
                        .findFirst();
        if (ship.isEmpty()) {
            return inWorld.map(at -> done("", -1, "组装器还在世界里，那一片已经是方块"));
        }
        List<BlockPos> assemblers = compat.scan(level, ship.get()).assemblers();
        if (assemblers.isEmpty()) return Optional.empty();
        BlockPos local = assemblers.getFirst();
        BlockPos world = BlockPos.containing(ship.get().pose().toWorld(Vec3.atCenterOf(local)));
        return Optional.of(new Pull(request, world, local, ship.get().id()));
    }

    // 指的地方周围最近的世界里的物理组装器。
    private static Optional<BlockPos> worldAssembler(ClientLevel level, BlockPos near) {
        return BlockPos.betweenClosedStream(near.offset(-SEARCH, -SEARCH, -SEARCH), near.offset(SEARCH, SEARCH, SEARCH))
                .filter(level::isLoaded)
                .filter(pos -> StructureGeometry.ASSEMBLER.equals(
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(near)));
    }

    private static Run done(String structure, int blocks, String note) {
        return new AlreadyDone(new Conversion(true, false, structure, blocks, note));
    }

    /** 已经是要的样子：第一刻就做完。 */
    private record AlreadyDone(Conversion conversion) implements Run {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "已经是要的样子";
        }
    }

    /** 走过去、拉一次、确认。 */
    private final class Pull implements Run {

        private final Request request;
        private final BlockPos world;
        private final BlockPos packet;
        private final String structureId;
        private Action current;
        private int step;
        private long stepStart = Long.MIN_VALUE;
        private long stillSince = Long.MIN_VALUE;
        private Conversion conversion;

        /**
         * @param world       组装器此刻在世界里的格
         * @param packet      拉杆时交给 Simulated 的格（世界里的就是世界坐标，结构上的是地块坐标）
         * @param structureId 拆回的那个结构；组装为 null
         */
        Pull(Request request, BlockPos world, BlockPos packet, String structureId) {
            this.request = request;
            this.world = world;
            this.packet = packet;
            this.structureId = structureId;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            ClientLevel level = context == null ? null : context.level();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (level == null || player == null || context.input() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，拉不了杆"));
            }
            long now = level.getGameTime();
            if (stepStart == Long.MIN_VALUE) stepStart = now;
            if (current != null) {
                ActionStatus status = current.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                current.close();
                current = null;
                if (status instanceof ActionStatus.Failed failed) return failed;
                return next(now);
            }
            return switch (step) {
                case 0 -> {
                    // 走到组装器跟前：够得着、看得见它。
                    current = services.approach().toward(ApproachTarget.ofBlock(world), request.permissions());
                    yield ActionStatus.progressed();
                }
                case 1 -> waitStill(level, player, now);
                case 2 -> pull(context, player, now);
                default -> confirm(level, player, now);
            };
        }

        private ActionStatus next(long now) {
            step = request.assemble() ? 2 : 1;
            stepStart = now;
            return ActionStatus.progressed();
        }

        // 拆回先等结构停稳：在动的结构拆回会落得乱七八糟，真人也等它停。
        private ActionStatus waitStill(ClientLevel level, LocalPlayer player, long now) {
            Optional<SableStructures.Structure> ship = ship(level, player);
            if (ship.isEmpty()) return finish(new Conversion(false, true, structureId, -1, "走过去时结构已经没了"));
            if (StructureGeometry.moving(ship.get())) {
                stillSince = Long.MIN_VALUE;
                if (now - stepStart >= MAX_WAIT_TICKS) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "结构一直在动，等了 "
                            + MAX_WAIT_TICKS / 20 + " 秒没停，没拆回"));
                }
                return ActionStatus.running();
            }
            if (stillSince == Long.MIN_VALUE) stillSince = now;
            if (now - stillSince < STILL_TICKS) return ActionStatus.running();
            step = 2;
            stepStart = now;
            return ActionStatus.progressed();
        }

        // 看着组装器拉一次杆。
        private ActionStatus pull(PlayerContext context, LocalPlayer player, long now) {
            context.input().halt(player);
            context.input().lookAt(player, Vec3.atCenterOf(world));
            compat.pullAssembler(packet);
            step = 3;
            stepStart = now;
            return ActionStatus.progressed();
        }

        // 看转换了没有：组装看组装器所在格空了、附近出现带组装器的结构；拆回看结构没了。三秒没转换照实交代。
        private ActionStatus confirm(ClientLevel level, LocalPlayer player, long now) {
            if (request.assemble()) {
                boolean moved = !StructureGeometry.ASSEMBLER.equals(
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(world).getBlock()).toString());
                if (moved) {
                    for (SableStructures.Structure ship : compat.structures(level, player, new AABB(world).inflate(SEARCH))) {
                        compat.forget(ship.id());
                        StructureGeometry.Scan scan = compat.scan(level, ship);
                        if (!scan.assemblers().isEmpty()) {
                            return finish(new Conversion(false, true, ship.id(), scan.blocks(), "拉了一次杆，组装成一个结构"));
                        }
                    }
                }
            } else if (ship(level, player).isEmpty()) {
                return finish(new Conversion(false, true, structureId, -1, "拉了一次杆，结构拆回成方块"));
            }
            if (now - stepStart >= CONFIRM_TICKS) {
                return finish(new Conversion(false, false, structureId == null ? "" : structureId, -1,
                        "拉了一次杆，" + CONFIRM_TICKS / 20 + " 秒内没见转换（胶没粘全、组装器没贴着要组装的方块、"
                                + "或结构太大都会这样；没再拉第二次，再拉会反过来）"));
            }
            return ActionStatus.running();
        }

        private Optional<SableStructures.Structure> ship(ClientLevel level, LocalPlayer player) {
            return compat.structures(level, player, new AABB(world).inflate(SEARCH * 4)).stream()
                    .filter(candidate -> candidate.id().equals(structureId)).findFirst();
        }

        private ActionStatus finish(Conversion result) {
            conversion = result;
            return ActionStatus.done();
        }

        @Override public Conversion conversion() {
            return conversion;
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            // 拉了杆在等转换：这时候走开看不到结局，不让不急的事插进来。
            if (step == 3) return Interruptibility.UNSAFE_TO_STOP;
            return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
        }

        @Override public String describe() {
            return switch (step) {
                case 0 -> "走到组装器跟前";
                case 1 -> "等结构停稳";
                case 2 -> "拉杆";
                default -> "看转换了没有";
            };
        }
    }
}
