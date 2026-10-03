package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 未组装和已拆回的设计继续留在同一世界数据库；换锚点只重表达坐标，不移动作者指定的世界目标。 */
final class AssemblyWorldDesignStore {
    private final StateIdentity identity;
    private final MemoryDatabase database;
    private final String scope;
    AssemblyWorldDesignStore(StateIdentity identity) {
        this.identity=identity;database=new MemoryDatabase(identity.databaseFile());scope=identity.scope()+"/physical-world-designs";
    }
    PhysicalStructureDesignStore.Registration merge(String dimension,UUID requestedId,BlockPos anchor,JsonArray patch,boolean write) throws IOException {
        UUID id=requestedId==null?UUID.randomUUID():requestedId;
        int limit=BuildingBudgets.current().maxProjectBytes();
        String existing=database.readRecord(scope,identity.key(),id.toString(),limit);
        if(requestedId!=null&&existing==null)throw new IOException("已引用的世界设计不存在，不能当成空设计重建");
        JsonObject result;
        if(write) {
            String json=database.updateRecord(scope,identity.key(),id.toString(),limit,old->{
                if(requestedId!=null&&old==null)throw new IllegalStateException("已引用设计在合并前被移除，不能覆盖未知历史");
                return combine(old,id,dimension,anchor,patch).toString();
            });
            result=decode(json,id,dimension);
        } else result=combine(existing,id,dimension,anchor,patch);
        var evidence=result.deepCopy();evidence.remove("targets");
        evidence.addProperty("persistence_status",write?"saved":"read");evidence.addProperty("known_target_count",result.getAsJsonArray("targets").size());
        if(requestedId==null&&!write)evidence.remove("world_design_id");
        return new PhysicalStructureDesignStore.Registration(result.getAsJsonArray("targets").deepCopy(),evidence);
    }
    private JsonObject combine(String previous,UUID id,String dimension,BlockPos anchor,JsonArray patch) {
        var targets=new JsonArray();
        if(previous!=null) {
            var old=decode(previous,id,dimension);BlockPos oldAnchor=AssemblyDesignMapping.point(old.getAsJsonObject("anchor"));
            targets=AssemblyDesignMapping.assembled(old.getAsJsonArray("targets"),oldAnchor,BlockPos.ZERO,anchor);
        }
        var result=new JsonObject();result.addProperty("version",1);result.addProperty("world_key",identity.key());
        result.addProperty("dimension",dimension);result.addProperty("world_design_id",id.toString());
        var at=new JsonObject();AssemblyDesignMapping.position(at,anchor);result.add("anchor",at);
        result.add("targets",PhysicalStructureDesignStore.mergeTargets(targets,patch));return result;
    }
    private JsonObject decode(String json,UUID id,String dimension) {
        var result=JsonParser.parseString(json).getAsJsonObject();
        if(result.get("version").getAsInt()!=1||!identity.key().equals(result.get("world_key").getAsString())
                ||!dimension.equals(result.get("dimension").getAsString())||!id.toString().equals(result.get("world_design_id").getAsString()))
            throw new IllegalArgumentException("世界设计身份或版本不匹配");
        AssemblyDesignMapping.point(result.getAsJsonObject("anchor"));
        PhysicalStructureDesignStore.mergeTargets(new JsonArray(),result.getAsJsonArray("targets"));return result;
    }
}
