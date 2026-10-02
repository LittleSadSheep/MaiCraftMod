package org.maiwithu.maicraft.server.physics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsTrim;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** 同一份船体采样连续分页交付；翻页不会重新读取移动后的姿态或把别人的快照接到当前船。 */
public final class PhysicsSnapshotService {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LoggerFactory.getLogger("maicraft.physics");
    private static final String CONTAINER = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final LinkedHashMap<UUID, Snapshot> SNAPSHOTS = new LinkedHashMap<>();
    private record Snapshot(UUID owner, WeakReference<ServerLevel> world, PhysicsBody measured,
                            PhysicsBody preflight, BlockPos origin, List<PhysicsTrim.Ballast> candidates, long created) {}
    private PhysicsSnapshotService() {}
    public static JsonObject inspect(ServerPlayer player, JsonObject request) {
        // 物理观察不提交施工；原生签名或结果编码失败时保留具体原因，服务器日志记录完整异常链。
        String structure = request.has("structure_id") ? request.get("structure_id").toString() : "missing";
        return readBounded(structure, () -> inspectNative(player, request));
    }
    static JsonObject readBounded(String structure, Supplier<JsonObject> read) {
        try {
            JsonObject result = Objects.requireNonNull(read.get(), "physics snapshot returned null");
            ProtocolJson.encode(result);
            return result;
        } catch (ServerOperationException rejected) { throw rejected; }
        catch (RuntimeException | LinkageError failed) {
            LOG.error("[maicraft-physics] physics.snapshot failed for structure {}", structure, failed);
            String detail = failed.getClass().getSimpleName() + ": " + failed.getMessage();
            if (failed.getCause() != null) detail += "; cause=" + failed.getCause();
            throw ServerOperationException.notApplied("physics_snapshot_failed", detail);
        }
    }
    private static JsonObject inspectNative(ServerPlayer player, JsonObject request) {
        UUID structureId = UUID.fromString(ServerAccess.text(request, "structure_id"));
        Object ship = find(player, structureId);
        UUID id; Snapshot snapshot;
        SNAPSHOTS.entrySet().removeIf(e -> e.getValue().world().get() == null
                || e.getValue().world().get() == player.serverLevel() && player.level().getGameTime() - e.getValue().created() > 600);
        if (request.has("snapshot_id")) {
            id = UUID.fromString(ServerAccess.text(request, "snapshot_id")); snapshot = SNAPSHOTS.get(id);
            if (snapshot == null || snapshot.world().get() != player.serverLevel() || !snapshot.owner().equals(player.getUUID())
                    || !snapshot.measured().structureId().equals(structureId))
                throw ServerAccess.denied("physics_snapshot_expired", "物理快照已过期或不属于当前玩家、维度及船体");
        } else {
            NativePhysicsCapture.watch(ship);
            var measured = NativePhysicsCapture.latest(ship);
            if (measured == null || player.level().getGameTime() - measured.tick() > 5) {
                var waiting = new JsonObject(); waiting.addProperty("state", "sampling");
                waiting.addProperty("structure_id", structureId.toString());
                waiting.addProperty("detail", NativePhysicsCapture.error(ship)); return waiting;
            }
            double rpm = request.has("reference_rpm") ? request.get("reference_rpm").getAsDouble() : 64;
            if (!Double.isFinite(rpm) || Math.abs(rpm) > 256) throw ServerAccess.denied("invalid_rpm", "参考转速必须在 -256 至 256 RPM 内");
            boolean filled = !request.has("balloon_fill") || "target".equals(ServerAccess.text(request, "balloon_fill"));
            Object plot = NativeApi.call(ship, null, "getPlot");
            BlockPos origin = (BlockPos) NativeApi.call(plot, null, "getCenterBlock");
            var model = PreflightComponents.model(ship, measured, rpm);
            var overlay = new PhysicsBlockEdits(player.serverLevel(),origin,request.has("edits") ? request.getAsJsonArray("edits") : new JsonArray());
            // 试算补丁只写入隔离视图，质量与气球拓扑都按同一份候选方块重新计算。
            if (request.has("edits")) {
                model = overlay.apply(model);
            }
            model=PreflightGasVolumes.model(ship,model,overlay,filled);
            snapshot = new Snapshot(player.getUUID(), new WeakReference<>(player.serverLevel()), measured,
                    model, origin, PreflightBallast.candidates(overlay,model,request.has("ballast_candidates") ? request.getAsJsonArray("ballast_candidates") : null),
                    player.level().getGameTime());
            id = UUID.randomUUID(); SNAPSHOTS.put(id, snapshot);
            while (SNAPSHOTS.size() > 128) SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
        }
        int offset = request.has("offset") ? ServerAccess.integer(request, "offset", 0, 1_000_000) : 0;
        // 力、未知项和推荐候选全部分页，不能只分页箭头却让大型结构的诊断挤爆协议。
        int total = Math.max(Math.max(snapshot.measured().loads().size(), snapshot.preflight().loads().size()),
                Math.max(snapshot.candidates().size(),Math.max(snapshot.measured().unknowns().size(),snapshot.preflight().unknowns().size())));
        if (offset > total) throw ServerAccess.denied("invalid_offset", "物理快照页偏移超出范围");
        int end = Math.min(total, offset + 24);
        JsonObject out = new JsonObject(); out.addProperty("state", "ready");
        out.addProperty("snapshot_id", id.toString()); out.addProperty("structure_id", structureId.toString());
        out.addProperty("tick", snapshot.measured().tick()); out.addProperty("dimension", snapshot.measured().dimension());
        out.addProperty("offset", offset); out.addProperty("next_offset", end); out.addProperty("has_more", end < total);
        out.add("origin_storage", GSON.toJsonTree(new int[]{snapshot.origin().getX(), snapshot.origin().getY(), snapshot.origin().getZ()}));
        out.add("ballast_candidates",slice(snapshot.candidates(),offset,end));
        out.add("measured", header(snapshot.measured())); out.add("preflight", header(snapshot.preflight()));
        out.add("measured_loads", page(snapshot.measured(), offset, end)); out.add("preflight_loads", page(snapshot.preflight(), offset, end));
        out.add("measured_unknowns",slice(snapshot.measured().unknowns(),offset,end));
        out.add("preflight_unknowns",slice(snapshot.preflight().unknowns(),offset,end));
        // 持续翻页只续期这份不可变快照，不用新一刻的船体事实替换旧页。
        SNAPSHOTS.put(id,new Snapshot(snapshot.owner(),snapshot.world(),snapshot.measured(),snapshot.preflight(),snapshot.origin(),snapshot.candidates(),player.level().getGameTime()));
        return out;
    }
    private static JsonObject header(PhysicsBody body) {
        // 不为每一页重复序列化整条船的力列表；页头仍完整保留质量、姿态和未知项。
        var header=new PhysicsBody(body.structureId(),body.dimension(),body.tick(),body.mass(),body.center(),body.inertia(),body.rotation(),
                body.position(),body.velocity(),body.angularVelocity(),body.gravity(),List.of(),List.of());
        JsonObject value = GSON.toJsonTree(header).getAsJsonObject(); value.remove("loads"); value.remove("unknowns"); return value;
    }
    private static JsonArray page(PhysicsBody body, int offset, int end) {
        JsonArray values = new JsonArray();
        for (int i = offset; i < end && i < body.loads().size(); i++) values.add(GSON.toJsonTree(body.loads().get(i)));
        return values;
    }
    private static JsonArray slice(List<?> source,int offset,int end) {
        JsonArray values=new JsonArray(); for(int i=offset;i<end&&i<source.size();i++) values.add(GSON.toJsonTree(source.get(i))); return values;
    }
    public static Object find(ServerPlayer player, UUID id) {
        Object container = NativeApi.call(null, CONTAINER, "getContainer", player.serverLevel());
        Object ship = resolveServerStructure(container, id);
        Object bounds = NativeApi.call(ship, null, "boundingBox");
        double distance = 0;
        String[] axes = {"X", "Y", "Z"}; double[] point = {player.getX(), player.getY(), player.getZ()};
        for (int i = 0; i < 3; i++) {
            double min = ((Number) NativeApi.call(bounds, null, "min" + axes[i])).doubleValue();
            double max = ((Number) NativeApi.call(bounds, null, "max" + axes[i])).doubleValue();
            double delta = Math.max(0, Math.max(min - point[i], point[i] - max)); distance += delta * delta;
        }
        if (!Double.isFinite(distance) || distance > 128 * 128)
            throw ServerAccess.denied("structure_out_of_range", "需要在物理结构附近观察");
        return ship;
    }
    static Object resolveServerStructure(Object container, UUID id) {
        Object ship = NativeApi.call(container, null, "getSubLevel", id);
        // finalized 是客户端接收初始区块后的标记，ServerSubLevel 没有该方法；服务器按原生容器与移除状态查找。
        if (ship == null || NativeApi.truth(NativeApi.call(ship, null, "isRemoved")))
            throw ServerAccess.denied("structure_unavailable", "服务器中的物理结构不存在或已移除");
        return ship;
    }
}
