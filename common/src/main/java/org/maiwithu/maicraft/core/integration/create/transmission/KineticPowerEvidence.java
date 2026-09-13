// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Labels synchronized native data explicitly; it is never substituted for a denied server request. */
final class KineticPowerEvidence {
    private KineticPowerEvidence() {}
    static JsonObject client(KineticNativeView.Observation live) {
        var result=new JsonObject();result.addProperty("block_id",live.blockId());
        result.addProperty("provenance","client_synchronized_native");
        result.add("position",KineticNativeReads.position(live.endpoint().position()));
        var nativeData=new JsonObject();var create=new JsonObject();result.add("native",nativeData);nativeData.add("create",create);
        create.addProperty("getSpeed",live.rpm());create.addProperty("network_id",live.networkId());
        create.addProperty("hasNetwork",!live.networkId().isEmpty());create.addProperty("isOverStressed",!live.powered());
        return result;
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
