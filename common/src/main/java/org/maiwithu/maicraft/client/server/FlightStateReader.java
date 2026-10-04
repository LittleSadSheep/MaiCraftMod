package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.physics.flight.FlightSample;

/** 以有界频率更新服务器接地证据；飞控姿态仍每刻采样，旧接地不能跨世界或失联后继续沿用。 */
public final class FlightStateReader implements AutoCloseable {
    private final UUID structureId;
    private final Level world;
    private final BlockPos typewriter;
    private ClientRequestReceipt pending;
    private long nextRequest,lastTick=Long.MIN_VALUE;
    private long requestTick,receivedTick=Long.MIN_VALUE,responseAge;
    private FlightSample.Contact contact=FlightSample.Contact.UNKNOWN;
    private JsonObject evidence=new JsonObject();
    public FlightStateReader(LocalPlayer player,UUID structureId,BlockPos typewriter){world=player.level();this.structureId=structureId;this.typewriter=typewriter==null?null:typewriter.immutable();}
    public FlightSample.Contact tick(LocalPlayer player) {
        if(player.level()!=world)throw new IllegalStateException("flight observation changed world");
        if(!ServerAssistClient.serverSupported("physics.flight_state")) {
            // 长途飞行会跨过协议观察会话的有效期；续期只重取只读遥测，不重放操纵输入。
            if(ServerAssistClient.renegotiating("physics.flight_state"))return freshContact();
            throw new IllegalStateException("server physics.flight_state unavailable");
        }
        if(pending!=null) {
            var receipt=pending.snapshot();
            if(receipt.settled()) {
                if(receipt.code().equals("session_expired")&&ServerAssistClient.takeExpiredReadForRefresh(pending.id())) {
                    pending=null;nextRequest=world.getGameTime()+1;return freshContact();
                }
                pending=null;
                if(receipt.retired()||receipt.status()!=ClientRequestReceipt.Status.SUCCEEDED||receipt.backend()!=ClientRequestReceipt.Backend.SERVER)
                    throw new IllegalStateException("native flight observation failed: "+receipt.code()+" "+receipt.message());
                var data=receipt.result();
                if(!structureId.toString().equals(data.get("structure_id").getAsString())
                        ||!world.dimension().location().toString().equals(data.get("dimension").getAsString()))
                    throw new IllegalStateException("native flight observation identity mismatch");
                if(data.get("state").getAsString().equals("ready")) {
                    long tick=data.get("tick").getAsLong();
                    if(tick<lastTick||tick<0)throw new IllegalStateException("native flight observation tick diverged");
                    lastTick=tick;evidence=data.deepCopy();
                    receivedTick=world.getGameTime();responseAge=receivedTick-requestTick;
                    contact=FlightSample.Contact.valueOf(data.getAsJsonObject("native_contact").get("state").getAsString().toUpperCase(Locale.ROOT));
                }
                nextRequest=world.getGameTime()+3;
            }
        }
        if(pending==null&&world.getGameTime()>=nextRequest) {
            var request=new JsonObject();request.addProperty("structure_id",structureId.toString());
            if(typewriter!=null) {
                var at=new JsonObject();at.addProperty("x",typewriter.getX());at.addProperty("y",typewriter.getY());at.addProperty("z",typewriter.getZ());request.add("typewriter_position",at);
            }
            requestTick=world.getGameTime();
            pending=ServerAssistClient.submit("physics.flight_state",request,false);
        }
        // 用客户端往返时长保守计算样本年龄，不能假定服务器与客户端的绝对 tick 总是相同。
        return freshContact();
    }
    private FlightSample.Contact freshContact(){return receivedTick==Long.MIN_VALUE||world.getGameTime()-receivedTick+responseAge>10?FlightSample.Contact.UNKNOWN:contact;}
    public JsonObject evidence(){return evidence.deepCopy();}
    public boolean controllerOwned(){return evidence.has("typewriter")&&evidence.getAsJsonObject("typewriter").has("owned_by_player")
            &&evidence.getAsJsonObject("typewriter").get("owned_by_player").getAsBoolean();}
    public boolean controllerReleased(){return evidence.has("typewriter")&&evidence.getAsJsonObject("typewriter").has("in_use")
            &&!evidence.getAsJsonObject("typewriter").get("in_use").getAsBoolean()
            &&evidence.getAsJsonObject("typewriter").getAsJsonArray("pressed_keys").isEmpty();}
    @Override public void close(){if(pending!=null){ServerAssistClient.cancel(pending.id());pending=null;}}
}
