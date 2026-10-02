package org.maiwithu.maicraft.client.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsTrim;

/** 客户端自动读完受力分页；同一轮的船体、快照编号、维度和页偏移必须一致。 */
public final class PhysicsSnapshotReader implements AutoCloseable {
    public record Observation(UUID snapshotId, PhysicsBody measured, PhysicsBody preflight,
                              List<PhysicsTrim.Ballast> candidates, int[] origin) {}
    private final JsonObject request;
    private final Level world;
    private long progressTick;
    private ClientRequestReceipt pending;
    private UUID snapshotId;
    private int offset;
    private long nextTick;
    private JsonObject measured, preflight;
    private final JsonArray measuredLoads=new JsonArray(), preflightLoads=new JsonArray();
    private final JsonArray measuredUnknowns=new JsonArray(), preflightUnknowns=new JsonArray();
    private final List<PhysicsTrim.Ballast> candidates=new ArrayList<>();
    private int[] origin;
    private Observation result;
    public PhysicsSnapshotReader(LocalPlayer player,JsonObject options) {
        world=player.level(); progressTick=world.getGameTime(); request=new JsonObject();
        for(String name:List.of("structure_id","reference_rpm","balloon_fill","edits","ballast_candidates"))
            if(options.has(name)) request.add(name,options.get(name).deepCopy());
    }
    public Observation tick(LocalPlayer player) {
        if(result!=null) return result;
        if(player.level()!=world) throw new IllegalStateException("物理观察期间切换了世界");
        if(!ServerAssistClient.serverSupported("physics.snapshot")) throw new IllegalStateException("服务器未提供 physics.snapshot；需要在服务端安装支持 Sable 的 MaiCraft");
        if(world.getGameTime()-progressTick>400) throw new IllegalStateException("原生物理采样超时：检查世界是否暂停以及 Sable 观察钩子是否可用");
        if(pending==null) {
            if(world.getGameTime()<nextTick) return null;
            var args=request.deepCopy();
            if(snapshotId!=null) { args.addProperty("snapshot_id",snapshotId.toString()); args.addProperty("offset",offset); }
            pending=ServerAssistClient.submit("physics.snapshot",args,false); return null;
        }
        var receipt=pending.snapshot(); if(!receipt.settled()) return null;
        pending=null;
        if(receipt.retired()||receipt.status()!=ClientRequestReceipt.Status.SUCCEEDED||receipt.backend()!=ClientRequestReceipt.Backend.SERVER)
            throw new IllegalStateException("物理观察未完成: "+receipt.code()+" "+receipt.message());
        var page=receipt.result();
        if(!request.get("structure_id").getAsString().equals(page.get("structure_id").getAsString()))
            throw new IllegalStateException("原生物理回执的船体身份不匹配");
        if("sampling".equals(page.get("state").getAsString())) {
            if(page.has("detail")&&!page.get("detail").isJsonNull()) throw new IllegalStateException("原生物理采样失败: "+page.get("detail").getAsString());
            nextTick=world.getGameTime()+2; return null;
        }
        UUID incoming=UUID.fromString(page.get("snapshot_id").getAsString());
        if(!world.dimension().location().toString().equals(page.get("dimension").getAsString())
                || page.get("offset").getAsInt()!=offset || snapshotId!=null&&!snapshotId.equals(incoming))
            throw new IllegalStateException("原生物理快照分页发生串页或维度变化");
        var gson=new Gson();
        if(snapshotId==null) {
            snapshotId=incoming; measured=page.getAsJsonObject("measured").deepCopy(); preflight=page.getAsJsonObject("preflight").deepCopy();
            origin=gson.fromJson(page.get("origin_storage"),int[].class);
        }
        progressTick=world.getGameTime();
        for(var item:page.getAsJsonArray("ballast_candidates")) candidates.add(gson.fromJson(item,PhysicsTrim.Ballast.class));
        measuredLoads.addAll(page.getAsJsonArray("measured_loads")); preflightLoads.addAll(page.getAsJsonArray("preflight_loads"));
        measuredUnknowns.addAll(page.getAsJsonArray("measured_unknowns")); preflightUnknowns.addAll(page.getAsJsonArray("preflight_unknowns"));
        int next=page.get("next_offset").getAsInt();
        if(page.get("has_more").getAsBoolean()) {
            if(next<=offset) throw new IllegalStateException("物理快照分页没有前进"); offset=next; return null;
        }
        measured.add("loads",measuredLoads); preflight.add("loads",preflightLoads);
        measured.add("unknowns",measuredUnknowns); preflight.add("unknowns",preflightUnknowns);
        result=new Observation(snapshotId,gson.fromJson(measured,PhysicsBody.class),gson.fromJson(preflight,PhysicsBody.class),List.copyOf(candidates),origin.clone());
        return result;
    }
    @Override public void close() { if(pending!=null) { ServerAssistClient.cancel(pending.id()); pending=null; } }
}
