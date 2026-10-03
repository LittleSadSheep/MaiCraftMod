package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 按世界、维度与结构 UUID 保存明确目标；数据库只记设计声明，当前方块始终重新观察。 */
final class PhysicalStructureDesignStore {
    record History(JsonArray targets,JsonObject evidence,boolean retry) {}
    record Registration(JsonArray targets,JsonObject evidence) {}
    private final StateIdentity identity;
    private final MemoryDatabase database;
    private final String scope;
    PhysicalStructureDesignStore(StateIdentity identity) {
        this.identity=identity;database=new MemoryDatabase(identity.databaseFile());scope=identity.scope()+"/physical-structure-designs";
    }
    Registration read(String dimension,UUID id,JsonArray comparison,Supplier<History> recover) {
        try {
            String json=database.readRecord(scope,identity.key(),dimension+"/"+id,BuildingBudgets.current().maxProjectBytes());
            JsonObject current;
            if(json==null) {
                History history=recover.get();current=document(dimension,id,history.targets());current.add("history",history.evidence());
            } else current=decode(json,dimension,id);
            // 观察时临时比较给出的方案，不改写此前登记的整机声明。
            current.add("targets",mergeTargets(current.getAsJsonArray("targets"),comparison));return registration(current,"read",null);
        } catch(Exception failed) {return registration(document(dimension,id,comparison.deepCopy()),"unavailable",failed.toString());}
    }
    Registration merge(String dimension,UUID id,JsonArray edits,Supplier<History> recover) {
        ResourceLocation.parse(dimension);
        String key=dimension+"/"+id;
        JsonObject[] candidate={document(dimension,id,mergeTargets(new JsonArray(),edits))};
        try {
            int limit=BuildingBudgets.current().maxProjectBytes();
            String before=database.readRecord(scope,identity.key(),key,limit);
            JsonObject existing=before==null?null:decode(before,dimension,id);
            if(existing!=null) { candidate[0]=existing.deepCopy();candidate[0].add("targets",mergeTargets(existing.getAsJsonArray("targets"),edits)); }
            // 旧任务读取在写事务之外进行，不能用另一个迁移连接等待自己持有的 SQLite 写锁。
            History history=existing==null||existing.get("recovery_pending").getAsBoolean()?recover.get():null;
            String saved=database.updateRecord(scope,identity.key(),key,limit,previous->{
                JsonObject current=previous==null?document(dimension,id,new JsonArray()):decode(previous,dimension,id);
                if(current.get("recovery_pending").getAsBoolean()&&history!=null) {
                    // 已持久化的新声明优先于恢复的旧回执，避免重试迁移时把后来改过的蒙皮改回去。
                    current.add("targets",mergeTargets(history.targets(),current.getAsJsonArray("targets")));
                    current.add("history",history.evidence().deepCopy());current.addProperty("recovery_pending",history.retry());
                }
                current.add("targets",mergeTargets(current.getAsJsonArray("targets"),edits));
                candidate[0]=current;return current.toString();
            });
            return registration(decode(saved,dimension,id),"saved",null);
        } catch(Exception unavailable) {
            // 不能读取或写入设计时不覆盖旧记录；保留本次声明及未知原因，不能冒称已经登记成功。
            return registration(candidate[0],"unavailable",unavailable.toString());
        }
    }
    private JsonObject document(String dimension,UUID id,JsonArray targets) {
        var root=new JsonObject();root.addProperty("version",1);root.addProperty("world_key",identity.key());
        root.addProperty("dimension",dimension);root.addProperty("structure_id",id.toString());root.add("targets",targets);
        root.addProperty("recovery_pending",true);var history=new JsonObject();
        history.addProperty("prior_history_complete",false);history.addProperty("status","not_yet_recovered");root.add("history",history);
        return root;
    }
    private JsonObject decode(String json,String dimension,UUID id) {
        var root=JsonParser.parseString(json).getAsJsonObject();
        if(root.get("version").getAsInt()!=1||!identity.key().equals(root.get("world_key").getAsString())
                ||!dimension.equals(root.get("dimension").getAsString())||!id.toString().equals(root.get("structure_id").getAsString())
                ||!root.get("history").isJsonObject()||!root.get("recovery_pending").getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("physical_design_identity_or_version_mismatch");
        root.add("targets",mergeTargets(new JsonArray(),root.getAsJsonArray("targets")));return root;
    }
    private Registration registration(JsonObject document,String status,String error) {
        var evidence=document.deepCopy();evidence.remove("targets");
        evidence.addProperty("persistence_status",status);evidence.addProperty("known_target_count",document.getAsJsonArray("targets").size());
        if(error!=null) { evidence.addProperty("storage_error",error);evidence.addProperty("prior_history_complete",false); }
        return new Registration(document.getAsJsonArray("targets").deepCopy(),evidence);
    }
    static JsonArray mergeTargets(JsonArray old,JsonArray patch) {
        var targets=new LinkedHashMap<String,JsonObject>();
        // 只覆盖显式给出的同一格，未声明空气不是隐含清空范围，旧属性随整格声明一同替换。
        for(JsonArray cells:new JsonArray[]{old,patch}) for(var raw:cells) {
            var cell=raw.getAsJsonObject();String position=key(cell);
            ResourceLocation.parse(cell.get("block_id").getAsString());
            if(cell.has("properties")) for(var property:cell.getAsJsonObject("properties").entrySet())
                if(!property.getValue().isJsonPrimitive()||!property.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("physical_design_invalid_property");
            targets.put(position,cell.deepCopy());
        }
        var result=new JsonArray();targets.values().forEach(result::add);return result;
    }
    static String key(JsonObject cell) {
        var p=cell.getAsJsonObject("position");
        return p.get("x").getAsBigDecimal().intValueExact()+","+p.get("y").getAsBigDecimal().intValueExact()+","+p.get("z").getAsBigDecimal().intValueExact();
    }
}
