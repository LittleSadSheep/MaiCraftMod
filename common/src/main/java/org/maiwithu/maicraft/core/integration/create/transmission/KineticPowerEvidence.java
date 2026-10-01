// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.create.CreateBeltObservation;

/** 明确标注同步原生数据；绝不会用其替代被拒绝的服务器请求。 */
final class KineticPowerEvidence {
    private KineticPowerEvidence() {}
    static JsonObject client(KineticNativeView.Observation live, Level world) {
        // 纯客户端接线也读取同一原生方向方法，沿用同步观察来源标记，绝不冒充服务端实时状态。
        var result=client(live);CreateBeltObservation.append(world.getBlockEntity(live.endpoint().position()),KineticNativeReads.kinetics(result));return result;
    }
    static JsonObject client(KineticNativeView.Observation live) {
        var result=new JsonObject();result.addProperty("block_id",live.blockId());
        result.addProperty("provenance","client_synchronized_native");
        result.add("position",KineticNativeReads.position(live.endpoint().position()));
        var nativeData=new JsonObject();var create=new JsonObject();result.add("native",nativeData);nativeData.add("create",create);
        create.addProperty("getSpeed",live.rpm());create.addProperty("network_id",live.networkId());
        // 这里只转述客户端已同步的原生字段，不把停转或尚未入网伪装成应力不足。
        create.addProperty("hasNetwork",live.hasNetwork());create.addProperty("isOverStressed",live.overstressed());
        return result;
    }

    /** 失败时保留端点各自的最后观察；没有读到数据就明确未知，整条路线未通过不能抹掉已有转速。 */
    static void append(Map<String,Object> result, String endpoint, JsonObject observed, double minimumRpm) {
        var evidence = new JsonObject();
        evidence.addProperty("observation_status", observed == null ? "not_observed" : "incomplete");
        result.put(endpoint + "_power_evidence", evidence);
        if (observed == null) return;
        evidence.addProperty("provenance", KineticNativeReads.text(observed,"provenance"));
        if (observed.has("tick")) evidence.add("observed_game_tick", observed.get("tick").deepCopy());
        if (!observed.has("native") || !observed.get("native").isJsonObject()) return;
        var nativeData = observed.getAsJsonObject("native");
        if (!nativeData.has("create") || !nativeData.get("create").isJsonObject()) return;
        var kinetic = nativeData.getAsJsonObject("create");
        // 方向与供能是两项事实；优先保留已读到的方向，即使随后发现网络字段尚未同步完整。
        if (kinetic.has("belt_motion")) evidence.add("belt_motion", kinetic.get("belt_motion").deepCopy());
        // 部分原生接口可能不可读；缺失、类型错误和非有限转速都保持不完整，不补成零或否。
        if (!kinetic.has("getSpeed") || !kinetic.get("getSpeed").isJsonPrimitive()
                || !kinetic.getAsJsonPrimitive("getSpeed").isNumber()) return;
        for (String flag : new String[]{"hasNetwork", "isOverStressed"})
            if (!kinetic.has(flag) || !kinetic.get(flag).isJsonPrimitive() || !kinetic.getAsJsonPrimitive(flag).isBoolean()) return;
        double rpm = kinetic.get("getSpeed").getAsDouble();
        if (!Double.isFinite(rpm)) return;
        boolean powered = KineticNativeReads.powered(observed,0);
        evidence.addProperty("observation_status", "observed");
        evidence.addProperty("actual_rpm", rpm);
        evidence.addProperty("has_network", kinetic.get("hasNetwork").getAsBoolean());
        evidence.addProperty("overstressed", kinetic.get("isOverStressed").getAsBoolean());
        evidence.addProperty("powered", powered);
        evidence.addProperty("minimum_rpm_met", powered && Math.abs(rpm) >= minimumRpm);
        // 旧布尔字段只在真实读到端点状态时给出；未知不再以 false 冒充已确认无动力。
        result.put(endpoint + "_power_observed", powered);
    }
    static boolean sameNetwork(KineticNativeView.Observation live,JsonObject observed) {
        return live!=null&&observed!=null&&!live.networkId().isEmpty()
                &&live.networkId().equals(KineticNativeReads.text(KineticNativeReads.kinetics(observed),"network_id"));
    }
    static boolean clientFace(Level level,BlockPos at,Direction face) {
        var target=KineticNativeView.read(level,at,face,false);
        var neighbor=KineticNativeView.read(level,at.relative(face),face.getOpposite(),false);
        return target!=null&&neighbor!=null&&target.powered()&&neighbor.powered()
                &&!target.networkId().isEmpty()&&target.networkId().equals(neighbor.networkId());
    }
}
