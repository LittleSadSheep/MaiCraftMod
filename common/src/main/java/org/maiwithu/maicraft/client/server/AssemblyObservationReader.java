package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** 客户端先登记观察，再等待原生请求和搬移证据；读请求不会替玩家扳动组装器。 */
public final class AssemblyObservationReader implements AutoCloseable {
    private final Level world;
    private final BlockPos assembler;
    private ClientRequestReceipt pending;
    private UUID watch;
    private long nextPoll;
    private JsonObject latest;
    public AssemblyObservationReader(LocalPlayer player,BlockPos assembler) { world=player.level();this.assembler=assembler.immutable(); }
    public JsonObject tick(LocalPlayer player) {
        if(player.level()!=world) throw new IllegalStateException("组装观察期间切换了世界");
        if(!ServerAssistClient.serverSupported("physics.assembly")) throw new IllegalStateException("服务器未提供原生组装回执 physics.assembly");
        if(latest!=null&&latest.get("complete").getAsBoolean()) return latest.deepCopy();
        if(pending==null) {
            if(world.getGameTime()<nextPoll) return latest==null?null:latest.deepCopy();
            var request=new JsonObject();
            if(watch!=null) request.addProperty("watch_id",watch.toString());
            else {
                var position=new JsonObject();position.addProperty("x",assembler.getX());position.addProperty("y",assembler.getY());position.addProperty("z",assembler.getZ());
                request.add("position",position);
            }
            pending=ServerAssistClient.submit("physics.assembly",request,false);return latest==null?null:latest.deepCopy();
        }
        var result=pending.snapshot();if(!result.settled()) return latest==null?null:latest.deepCopy();pending=null;
        if(result.retired()||result.status()!=ClientRequestReceipt.Status.SUCCEEDED||result.backend()!=ClientRequestReceipt.Backend.SERVER)
            throw new IllegalStateException("原生组装观察失败: "+result.code()+": "+result.message());
        var page=result.result();UUID id=UUID.fromString(page.get("watch_id").getAsString());
        if(watch!=null&&!watch.equals(id)||!world.dimension().location().toString().equals(page.get("dimension").getAsString()))
            throw new IllegalStateException("组装回执的观察身份或维度改变");
        var position=page.getAsJsonArray("assembler_position");
        if(position.size()!=3||!assembler.equals(new BlockPos(position.get(0).getAsBigDecimal().intValueExact(),
                position.get(1).getAsBigDecimal().intValueExact(),position.get(2).getAsBigDecimal().intValueExact())))
            throw new IllegalStateException("组装回执属于另一台组装器");
        watch=id;latest=page.deepCopy();nextPoll=world.getGameTime()+2;return latest.deepCopy();
    }
    @Override public void close() { if(pending!=null) {ServerAssistClient.cancel(pending.id());pending=null;} }
}
