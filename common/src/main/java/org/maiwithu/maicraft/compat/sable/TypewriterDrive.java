// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 接管无线红石打字机开到一个地方：驾驶位准备好（腾手、坐下、接管）→ 定落点（飞艇）或目标（车船）与巡航高度 →
 * 每刻看一次载具交给飞控或开车，输出调成开关键按下去 → 飞艇接地、车船慢下来就松开所有键 →
 * 停稳确认：接地（船是浮着）、每秒挪不到 0.35 格、倾斜 &lt; 8° 连着 20 个样本，100 刻内做不到写明"落地了但没停稳" →
 * 交还无线红石打字机。每刻核对还接管着、还坐着、结构还在；每 2 秒核对绑的键没变（回路被改就松键停下）。
 * 按路程给的时间用完了：飞艇就地找落点降下来，车船就地刹住，不悬在空中干等。
 * 飞艇沿途每 5 刻看前方 48 格的地形，地面比巡航高度近 6 格以内就把巡航高度抬上去；只按地形抬高，不绕。
 */
final class TypewriterDrive implements VehicleDriver.Run {

    /** 停稳要连着几个样本、最多等几刻。 */
    static final int PARK_SAMPLES = 20;
    static final int PARK_TICKS = 100;
    static final double PARKED_SPEED = 0.35;
    static final double PARKED_TILT = 8;
    /** 交还后等熄掉最多几刻。 */
    static final int DISCONNECT_TICKS = 40;
    /** 隔几刻核对一次绑的键、看一次前方地形、找一次落点。 */
    static final int CIRCUIT_EVERY = 40;
    static final int TERRAIN_EVERY = 5;
    static final int SITE_EVERY = 40;
    /** 停在目的地多近算到了（格）：飞艇、车船。 */
    static final double ARRIVED_AIR = 6;
    static final double ARRIVED_GROUND = 5;
    /** 往前看多远的地形、船底至少离地多高（格）。 */
    static final double LOOK_AHEAD = 48;
    static final double TERRAIN_GAP = 6;
    /** 近到这么远还找不到落点，就落在目的地本身（格）。 */
    static final double SITE_GIVE_UP = 48;

    private enum Step { PREPARE, PLAN, MOVE, PARK, DISCONNECT }

    private final SimulatedCompat compat;
    private final PlayerServices services;
    private final VehicleDriver.DriveRequest request;
    private final TypewriterCockpit cockpit;
    private final boolean airship;
    private final boolean car;
    private final List<String> notes = new ArrayList<>();

    private Step step = Step.PREPARE;
    private long stepStart = Long.MIN_VALUE;
    private long deadline;
    private KeyMixer mixer;
    private Set<Integer> bound;
    private AirshipPilot flying;
    private GroundPilot driving;
    private AirshipPilot.Course course;
    private Vec3 target;
    private boolean siteChosen;
    private boolean overtime;
    private boolean raised;
    private String landing = "";
    private PilotSample last;
    private double traveled;
    private double maxTilt;
    private int parkedCount;
    private boolean parked;
    private boolean disconnected;
    private VehicleDriver.Report report;

    TypewriterDrive(SimulatedCompat compat, PlayerServices services, VehicleDriver.DriveRequest request,
            TypewriterDriver.Station station) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.request = Objects.requireNonNull(request, "request");
        this.cockpit = new TypewriterCockpit(compat, services, request.structureId(), station);
        this.airship = request.kind().equals("airship");
        this.car = request.kind().equals("car");
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return stop(null, Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，开不了"));
        }
        long now = level.getGameTime();
        if (stepStart == Long.MIN_VALUE) stepStart = now;
        Optional<SableStructures.Structure> ship = compat.structure(level, player, request.structureId());
        if (ship.isEmpty()) return stop(level, Problem.of(Problem.Kind.TARGET_GONE, "结构不见了"));
        if (step == Step.PREPARE) {
            ActionStatus prepared = cockpit.prepare(tick);
            if (prepared instanceof ActionStatus.Failed failed) return stop(level, failed.problem());
            if (!(prepared instanceof ActionStatus.Done)) return prepared;
            return go(last == null ? Step.PLAN : Step.MOVE, now);
        }
        PilotSample sample = sample(level, ship.get(), now);
        if (step != Step.DISCONNECT) {
            String lost = cockpit.lost(level, player);
            if (lost != null) return stop(level, Problem.of(Problem.Kind.STUCK, lost));
        }
        return switch (step) {
            case PLAN -> plan(level, ship.get(), sample, now);
            case MOVE -> move(level, ship.get(), sample, now);
            case PARK -> park(sample, now);
            case DISCONNECT -> disconnect(level, now);
            case PREPARE -> ActionStatus.running();
        };
    }

    // 定这一趟：操纵表的输入换成键码、核对都绑着频率；飞艇找落点、定巡航高度；按路程给时间。
    private ActionStatus plan(ClientLevel level, SableStructures.Structure ship, PilotSample sample, long now) {
        Map<String, Integer> keys = new HashMap<>();
        bound = cockpit.bound(level);
        for (Map.Entry<String, String> role : request.roles().entrySet()) {
            int key = TypewriterKeys.key(role.getValue());
            if (key == TypewriterKeys.UNKNOWN) continue;
            if (bound != null && !bound.contains(key)) {
                return stop(level, Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "操纵表里 " + role.getKey() + " 用的 "
                        + role.getValue() + " 没绑频率", "用 machine_inspect 看这辆现在的 controls，再用 machine_configure 改操纵表"));
            }
            keys.put(role.getKey(), key);
        }
        mixer = new KeyMixer(keys);
        double distance = sample.flatDistance(request.destination());
        deadline = now + Math.min(12_000, Math.max(1_200, Math.round((60 + distance / 2) * 20)));
        if (airship) {
            flying = new AirshipPilot(sample.position().y);
            double ground = request.heightKnown() ? request.destination().y : surface(level, request.destination());
            Vec3 touchdown = new Vec3(request.destination().x, ground, request.destination().z);
            Optional<Vec3> site = LandingSites.find(touchdown, halfWidth(ship), ground(level));
            if (site.isPresent()) {
                touchdown = site.get();
                siteChosen = true;
                landing = "落点 " + shortText(touchdown) + "（目的地附近够平够大、不是水的一片）";
            } else {
                landing = "目的地附近还没加载或没找到平地，先往那飞，近了再找落点";
            }
            double cruise = request.cruiseAltitude() != null ? request.cruiseAltitude()
                    : Math.max(sample.position().y, touchdown.y) + 32;
            course = new AirshipPilot.Course(touchdown, Math.max(cruise, touchdown.y + 16));
        } else {
            driving = new GroundPilot(car);
            target = request.destination();
            landing = "目标 " + shortText(target) + (car ? "（车只开直线，路上的障碍不绕）" : "（船只在水面上开直线）");
        }
        return go(Step.MOVE, now);
    }

    // 开：每刻把样本交给飞控或开车，输出调成键；到了松键去停稳确认，做不下去松键停。
    private ActionStatus move(ClientLevel level, SableStructures.Structure ship, PilotSample sample, long now) {
        if ((now - stepStart) % CIRCUIT_EVERY == 0 && bound != null && !bound.equals(cockpit.bound(level))) {
            return stop(level, Problem.of(Problem.Kind.STUCK, "开着开着回路变了（绑的键不一样了），松开所有键停下"));
        }
        if (now >= deadline && !overtime) {
            overtime = true;
            notes.add("开了 " + (now - stepStart) / 20 + " 秒还没到，就地" + (airship ? "降落" : "刹住"));
            if (airship) {
                Vec3 here = new Vec3(sample.position().x, surface(level, sample.position()), sample.position().z);
                course = new AirshipPilot.Course(LandingSites.find(here, halfWidth(ship), ground(level)).orElse(here),
                        course.cruiseAltitude());
                siteChosen = true;
            } else {
                target = sample.position();
            }
        }
        PilotCommand command;
        if (airship) {
            steerCourse(level, ship, sample, now);
            command = flying.tick(sample, course);
            if (flying.phase() == AirshipPilot.Phase.FAILED) return stop(level, Problem.of(Problem.Kind.STUCK, flying.failure()));
            if (flying.phase() == AirshipPilot.Phase.LANDED) {
                cockpit.releaseAll();
                return go(Step.PARK, now);
            }
        } else {
            command = driving.tick(sample, target);
            if (driving.phase() == GroundPilot.Phase.FAILED) return stop(level, Problem.of(Problem.Kind.STUCK, driving.failure()));
            if (driving.phase() == GroundPilot.Phase.STOPPED) {
                cockpit.releaseAll();
                return go(Step.PARK, now);
            }
        }
        cockpit.hold(mixer.tick(now, command, car));
        return ActionStatus.running();
    }

    // 飞艇的航线：落点还没定的近了再找，近到 48 格还没有就落在目的地；前方地形高就抬巡航高度。
    private void steerCourse(ClientLevel level, SableStructures.Structure ship, PilotSample sample, long now) {
        double distance = sample.flatDistance(course.touchdown());
        if (!siteChosen && (now - stepStart) % SITE_EVERY == 0 && distance < 96) {
            Vec3 aim = new Vec3(request.destination().x, surface(level, request.destination()), request.destination().z);
            Optional<Vec3> site = LandingSites.find(aim, halfWidth(ship), ground(level));
            if (site.isPresent()) {
                course = new AirshipPilot.Course(site.get(), Math.max(course.cruiseAltitude(), site.get().y + 16));
                siteChosen = true;
                landing = "落点 " + shortText(site.get()) + "（近了找到的平地）";
            } else if (distance < SITE_GIVE_UP) {
                course = new AirshipPilot.Course(aim, course.cruiseAltitude());
                siteChosen = true;
                landing = "目的地附近找不到够平够大的落点，落在目的地 " + shortText(aim);
            }
        }
        if ((now - stepStart) % TERRAIN_EVERY == 0) {
            OptionalDouble highest = LandingSites.highestAhead(sample.position(), course.touchdown(), halfWidth(ship),
                    LOOK_AHEAD, ground(level));
            if (highest.isPresent() && highest.getAsDouble() + TERRAIN_GAP > course.cruiseAltitude()
                    && flying.phase() != AirshipPilot.Phase.DESCENT && distance > LOOK_AHEAD / 2) {
                course = new AirshipPilot.Course(course.touchdown(), highest.getAsDouble() + TERRAIN_GAP);
                if (!raised) notes.add("前方地形高，巡航高度抬到 " + Math.round(course.cruiseAltitude()));
                raised = true;
            }
        }
    }

    // 停稳确认：松开所有键后，接地（船浮着就行）、慢、不斜，连着 20 个样本；100 刻内做不到照实说。
    private ActionStatus park(PilotSample sample, long now) {
        boolean settled = (request.kind().equals("boat") || sample.grounded())
                && sample.velocity().length() < PARKED_SPEED && sample.tilt() < PARKED_TILT;
        parkedCount = settled ? parkedCount + 1 : 0;
        if (parkedCount >= PARK_SAMPLES) {
            parked = true;
            return go(Step.DISCONNECT, now);
        }
        if (now - stepStart >= PARK_TICKS) {
            notes.add(airship ? "落地了但没停稳" : "停下了但没停稳");
            return go(Step.DISCONNECT, now);
        }
        return ActionStatus.running();
    }

    private ActionStatus disconnect(ClientLevel level, long now) {
        cockpit.disconnect(level);
        if (!cockpit.lit(level)) {
            disconnected = true;
            return finish();
        }
        if (now - stepStart >= DISCONNECT_TICKS) {
            notes.add("交还无线红石打字机后 " + DISCONNECT_TICKS / 20 + " 秒它还亮着");
            return finish();
        }
        return ActionStatus.running();
    }

    // 这一刻的样本：船底中心、速度（每秒）、车头朝向、倾斜、转速、接地；顺手记行程与最大倾斜。
    private PilotSample sample(ClientLevel level, SableStructures.Structure ship, long now) {
        AABB bounds = ship.worldBounds();
        Vec3 position = new Vec3(bounds.getCenter().x, bounds.minY, bounds.getCenter().z);
        Vec3 velocity = StructureGeometry.velocity(ship).scale(20);
        SableStructures.Pose pose = ship.pose();
        Vec3 nose = pose.toWorld(pose.rotationPoint().add(axis(request.forward()))).subtract(pose.position());
        double heading = Math.toDegrees(Math.atan2(-nose.x, nose.z));
        VehicleAttitude.Angles angles = VehicleAttitude.angles(pose);
        double tilt = Math.max(Math.abs(angles.pitch()), Math.abs(angles.roll()));
        String contact = VehicleAttitude.contact(pose, compat.scan(level, ship).boxes(),
                cell -> VehicleReading.solidTop(level, cell));
        double turnRate = last == null ? 0 : PilotSample.turn(last.heading(), heading) * 20;
        double tiltRate = last == null ? 0 : (tilt - last.tilt()) * 20;
        if (last != null) traveled += Math.hypot(position.x - last.position().x, position.z - last.position().z);
        maxTilt = Math.max(maxTilt, tilt);
        last = new PilotSample(now, position, velocity, heading, tilt, turnRate, tiltRate, contact);
        return last;
    }

    private static Vec3 axis(String forward) {
        double sign = forward.startsWith("-") ? -1 : 1;
        return forward.endsWith("x") ? new Vec3(sign, 0, 0) : new Vec3(0, 0, sign);
    }

    private static double halfWidth(SableStructures.Structure ship) {
        AABB bounds = ship.worldBounds();
        return Math.max(bounds.getXsize(), bounds.getZsize()) / 2;
    }

    // 那一柱地面的顶面高度（站上去脚底的 y）；没加载按原来的高度。
    private static double surface(ClientLevel level, Vec3 at) {
        int x = (int) Math.floor(at.x);
        int z = (int) Math.floor(at.z);
        if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return at.y;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // 读世界地面给落点与前方地形用：只读已经加载的区块，没加载答不知道。
    private static LandingSites.Ground ground(ClientLevel level) {
        return new LandingSites.Ground() {
            @Override public Integer top(int x, int z) {
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }

            @Override public boolean liquid(int x, int z) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
            }
        };
    }

    private static String shortText(Vec3 at) {
        return Math.round(at.x) + "," + Math.round(at.y) + "," + Math.round(at.z);
    }

    private ActionStatus go(Step next, long now) {
        step = next;
        stepStart = now;
        return ActionStatus.progressed();
    }

    // 停：先松开按着的键，接管着就交还，经过照写。
    private ActionStatus stop(ClientLevel level, Problem problem) {
        cockpit.releaseAll();
        cockpit.disconnect(level);
        notes.add(problem.message());
        writeReport();
        return ActionStatus.failed(problem);
    }

    private ActionStatus finish() {
        writeReport();
        return ActionStatus.done();
    }

    private void writeReport() {
        List<String> phases = flying != null ? flying.transitions() : driving != null ? driving.transitions() : List.of();
        double left = last == null ? -1 : TypewriterTrial.round(last.flatDistance(request.destination()), 10);
        boolean arrived = left >= 0 && left <= (airship ? ARRIVED_AIR : ARRIVED_GROUND);
        if (flying != null && flying.unplannedTouchdown()) notes.add("不是按进近落的：还没到落点就碰到了地面");
        VehicleDriver.Drove drove = new VehicleDriver.Drove(phases, landing, TypewriterTrial.round(traveled, 10),
                TypewriterTrial.round(maxTilt, 10), arrived, left, parked, parkedCount);
        report = new VehicleDriver.Report(request.structureId(), cockpit.seat(), List.of(), cockpit.released(),
                disconnected, String.join("；", notes), drove);
    }

    @Override public VehicleDriver.Report report() {
        return report;
    }

    // 被打断：松开所有键（飞艇会慢慢往下沉，不会悬着加速），回来先核对还接管着，再按现在的样子接着开。
    @Override public void pause() {
        cockpit.pause();
        if (step == Step.MOVE || step == Step.PARK) {
            step = Step.PREPARE;
            stepStart = Long.MIN_VALUE;
        }
    }

    @Override public void close() {
        cockpit.close();
        if (report == null) {
            notes.add("开到一半被收掉了");
            writeReport();
        }
    }

    @Override public Interruptibility interruptibility() {
        if (step == Step.PREPARE) return cockpit.interruptibility();
        // 在空中、在开着的车船上停下不安全：只让必须立刻处理的事插进来。
        boolean airborne = last != null && !last.grounded();
        if (step == Step.MOVE && (!airship || airborne)) return Interruptibility.UNSAFE_TO_STOP;
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (step) {
            case PREPARE -> cockpit.describe();
            case PLAN -> "定航线";
            case MOVE -> airship ? "飞（" + flying.phase().name().toLowerCase(Locale.ROOT) + "）" : "开过去";
            case PARK -> "停稳确认";
            case DISCONNECT -> "交还无线红石打字机";
        };
    }
}
