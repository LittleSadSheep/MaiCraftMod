// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;

/**
 * Sable（物理结构）的服务端联动入口：登记只读操作 sable.forces——客户端问一个物理结构的受力，回答质量、重心、惯性、
 * 速度，开着动力与关着动力两种情况往哪边倒、倒得多快，升力比重量；要配重时附几处放一块铁块（或给定方块）能让两种情况
 * 都倒得慢的位置。各处的力要打开 Sable 的记账才记得到：第一次问时打开，回答写明下一刻再问。只读，不施力、不改结构。
 */
public final class SableServerCompat extends ServerCompatModule {

    public static final String MOD_ID = "sable";
    /** 只读操作的名字：machine_inspect 看载具的受力经它问。 */
    public static final String FORCES_OPERATION = "sable.forces";
    /** 配重最多试多少个空格、给几处。 */
    static final int CELL_LIMIT = 4096;
    static final int TOP = 5;
    /** 不说用什么配重时按铁块算。 */
    static final String DEFAULT_BALLAST = "minecraft:iron_block";

    private final StructureForceReads reads;

    public SableServerCompat(StructureForceReads reads) {
        super(MOD_ID, "Sable 物理结构");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(ServerCompatRegistry registry) {
        registry.readOperation(this, FORCES_OPERATION, 1, this::answer);
    }

    // 读这个结构的受力底子，算两种情况；要配重再挑位置。没在记账就打开、照实说下一刻再问。
    private JsonObject answer(ServerPlayer player, JsonObject request) {
        if (!(player.level() instanceof ServerLevel level)) {
            throw ServerOperationException.notApplied("not_loaded", "角色不在服务端的世界里");
        }
        if (!request.has("structure") || !request.get("structure").isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "要给 structure（物理结构的身份）");
        }
        String id = request.get("structure").getAsString();
        Optional<StructureForceReads.Forces> found = call("读物理结构的受力", () -> reads.forces(level, id, true));
        if (found.isEmpty()) throw ServerOperationException.notApplied("not_found", "这个世界里没有这个物理结构");
        StructureForceReads.Forces forces = found.get();
        JsonObject out = new JsonObject();
        out.addProperty("tracking", forces.tracking());
        out.addProperty("mass", ForceBalance.round(forces.mass()));
        out.add("center", vector(forces.center()));
        call("换到世界坐标", () -> reads.toWorld(level, id, forces.center())).ifPresent(world -> out.add("center_world", vector(world)));
        out.add("inertia", vector(forces.inertia()));
        out.add("gravity", vector(forces.gravity()));
        out.add("velocity", vector(forces.velocity()));
        out.add("spin", vector(forces.spin()));
        if (!forces.tracking()) {
            out.addProperty("note", "刚打开各处力的记账，下一刻再问才有受力");
            return out;
        }
        boolean powered = ForceBalance.powered(forces);
        out.addProperty("powered", powered);
        out.addProperty("lift_ratio", ForceBalance.liftRatio(forces));
        JsonArray cases = new JsonArray();
        if (powered) cases.add(caseJson(ForceBalance.of(forces, true)));
        cases.add(caseJson(ForceBalance.of(forces, false)));
        out.add("cases", cases);
        JsonArray pushes = new JsonArray();
        for (StructureForceReads.Push push : forces.pushes()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("group", push.group());
            entry.add("force", vector(push.force()));
            entry.add("torque", vector(push.torque()));
            entry.addProperty("points", push.points());
            pushes.add(entry);
        }
        out.add("pushes", pushes);
        if (request.has("ballast") && request.get("ballast").getAsBoolean()) {
            String block = request.has("ballast_block") ? request.get("ballast_block").getAsString() : DEFAULT_BALLAST;
            List<BlockPos> cells = call("找能放配重的空格", () -> reads.freeCells(level, id, CELL_LIMIT));
            List<ForceBalance.Ballast> picks = ForceBalance.ballast(forces, cells,
                    cell -> call("算配重多重", () -> reads.blockMass(level, id, cell, block)), TOP);
            JsonArray ballast = new JsonArray();
            for (ForceBalance.Ballast pick : picks) {
                JsonObject entry = new JsonObject();
                entry.add("local", vector(Vec3.atLowerCornerOf(pick.local())));
                call("换到世界坐标", () -> reads.toWorld(level, id, Vec3.atCenterOf(pick.local())))
                        .ifPresent(world -> entry.add("world", vector(world)));
                entry.addProperty("block", block);
                entry.addProperty("mass", ForceBalance.round(pick.mass()));
                entry.addProperty("before", pick.before());
                entry.addProperty("after", pick.after());
                ballast.add(entry);
            }
            out.add("ballast", ballast);
            out.addProperty("ballast_cells_tried", cells.size());
        }
        return out;
    }

    private static JsonObject caseJson(ForceBalance.Case found) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", found.name());
        entry.addProperty("tilt_x", found.tiltX());
        entry.addProperty("tilt_z", found.tiltZ());
        entry.addProperty("steady", found.steady());
        entry.addProperty("tendency", found.tendency());
        return entry;
    }

    private static JsonObject vector(Vec3 value) {
        JsonObject entry = new JsonObject();
        entry.addProperty("x", ForceBalance.round(value.x));
        entry.addProperty("y", ForceBalance.round(value.y));
        entry.addProperty("z", ForceBalance.round(value.z));
        return entry;
    }
}
