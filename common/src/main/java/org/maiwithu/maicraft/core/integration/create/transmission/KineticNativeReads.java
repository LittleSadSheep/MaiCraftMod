// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;

final class KineticNativeReads {
    private ClientRequestReceipt pending;
    private String operation;
    private JsonObject query;
    private boolean renewed;
    JsonObject call(String op,JsonObject body) {
        if(pending==null) {
            operation=op;query=body.deepCopy();pending=ServerAssistClient.submit(op,body,false);return null;
        }
        if(!operation.equals(op)||!query.equals(body))throw new IllegalArgumentException("kinetic_read_scope_changed");
        var result=pending.snapshot();if(!result.settled())return null;
        if(!renewed&&result.code().equals("session_expired")&&ServerAssistClient.takeExpiredReadForRefresh(result.requestId())) {
            pending=null;renewed=true;return null;
        }
        if(result.backend()!=ClientRequestReceipt.Backend.SERVER||result.status()!=ClientRequestReceipt.Status.SUCCEEDED)
            throw new IllegalArgumentException("kinetic_native_read_"+result.code());
        pending=null;renewed=false;return result.result();
    }
    JsonObject snapshot(BlockPos at,String dimension) {
        var request=new JsonObject();var points=new JsonArray();points.add(position(at));request.add("positions",points);request.addProperty("resource_limit",8);
        var body=call("machine.snapshot",request);if(body==null)return null;
        if(!dimension.equals(text(body,"dimension")))throw new IllegalArgumentException("kinetic_native_dimension_changed");
        for(var raw:body.getAsJsonArray("observations")) {
            var row=raw.getAsJsonObject();if(position(at).equals(row.get("position"))&&text(row,"provenance").equals("server_native")) return row;
        }
        throw new IllegalArgumentException("kinetic_native_endpoint_missing");
    }
    boolean connectedFace(BlockPos target,net.minecraft.core.Direction face,String dimension) {
        var path=new JsonArray();path.add(position(target.relative(face)));path.add(position(target));
        var request=new JsonObject();request.addProperty("system","create");request.addProperty("medium","kinetic");
        request.add("path",path);request.addProperty("to_face",face.getSerializedName());
        var result=call("machine.connections",request);if(result==null)return false;
        if(!dimension.equals(text(result,"dimension"))||!path.equals(result.get("path"))
                ||!truth(result,"complete")||!truth(result,"verified_connection")||!truth(result,"operational"))
            throw new IllegalArgumentException("kinetic_existing_declared_face_not_connected");
        return true;
    }
    static boolean truth(JsonObject row,String key){return row.has(key)&&row.get(key).isJsonPrimitive()&&row.get(key).getAsBoolean();}
    void cancel(){if(pending!=null)ServerAssistClient.cancel(pending.id());pending=null;}
    static JsonObject position(BlockPos at){var row=new JsonObject();row.addProperty("x",at.getX());row.addProperty("y",at.getY());row.addProperty("z",at.getZ());return row;}
    static String text(JsonObject o,String k){return o!=null&&o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    static JsonObject kinetics(JsonObject row){return row.has("native")?row.getAsJsonObject("native").getAsJsonObject("create"):null;}
    static boolean powered(JsonObject row,double minimum) {
        var k=kinetics(row); if(k==null||!k.has("getSpeed")||!k.has("isOverStressed")||!k.has("hasNetwork"))return false;
        double rpm=k.get("getSpeed").getAsDouble();return Double.isFinite(rpm)&&Math.abs(rpm)>0&&Math.abs(rpm)>=minimum
                &&!k.get("isOverStressed").getAsBoolean()&&k.get("hasNetwork").getAsBoolean();
    }
    static boolean sameNetwork(JsonObject a,JsonObject b){var x=kinetics(a);var y=kinetics(b);return x!=null&&y!=null&&!text(x,"network_id").isEmpty()&&text(x,"network_id").equals(text(y,"network_id"));}
}
