package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 旧版只有进程内设计表；从同世界已持久化的施工回执恢复声明，不用眼前方块反推设计。 */
final class PhysicalStructureDesignRecovery {
    private record Event(long tick,String source,JsonArray targets) {}
    private PhysicalStructureDesignRecovery() {}
    static PhysicalStructureDesignStore.History load(StateIdentity identity,String dimension,UUID id) {
        var loaded=new IntentStateStore().load(identity);
        if(loaded.status()!=IntentStateStore.Status.LOADED)
            return unavailable("checkpoint_"+loaded.status().name().toLowerCase(),loaded.status()!=IntentStateStore.Status.ABSENT);
        return recover(loaded.root(),dimension,id);
    }
    static PhysicalStructureDesignStore.History unavailable(String reason,boolean retry) {
        var evidence=new JsonObject();evidence.addProperty("status",reason);evidence.addProperty("prior_history_complete",false);
        return new PhysicalStructureDesignStore.History(new JsonArray(),evidence,retry);
    }
    static PhysicalStructureDesignStore.History recover(JsonObject checkpoint,String dimension,UUID id) {
        var events=new ArrayList<Event>();var unknowns=new JsonArray();var sources=new JsonArray();
        // 留存的任务不保证覆盖安装旧版本以来的全部历史；即使成功找回若干目标，也不能宣称旧历史完整。
        var evidence=new JsonObject();evidence.addProperty("status","recovered_retained_task_receipts");
        evidence.addProperty("prior_history_complete",false);
        evidence.addProperty("coverage_note","旧版本未持久化整机声明，只能恢复仍留存且身份明确的原始任务回执");
        try {
            for(var raw:checkpoint.getAsJsonArray("tasks")) {
                var task=raw.getAsJsonObject();String source=task.get("id").getAsString();
                for(var stepRaw:task.getAsJsonArray("completed_steps")) {
                    var step=stepRaw.getAsJsonObject();int index=step.get("index").getAsInt();
                    if(step.has("skipped")&&step.get("skipped").getAsBoolean()) continue;
                    collect(task.getAsJsonArray("steps").get(index).getAsJsonObject(),step,
                            source+"/completed_steps/"+index,dimension,id,events,unknowns);
                }
                int attempt=0;
                for(var attemptRaw:task.getAsJsonArray("attempts")) {
                    var row=attemptRaw.getAsJsonObject();
                    collect(row.getAsJsonObject("goal"),row,source+"/attempts/"+attempt++,dimension,id,events,unknowns);
                }
            }
        } catch(RuntimeException corrupt) {
            // 仍保留此前成功解析的真实目标，同时标出检查点不能完整恢复；下次可重试，绝不以空历史覆盖。
            unknowns.add(problem("checkpoint_decode_failed",corrupt.toString()));
            evidence.addProperty("status","partial_checkpoint_recovery");
        }
        events.sort(Comparator.comparingLong(Event::tick));
        var cells=new LinkedHashMap<String,JsonObject>();var ticks=new LinkedHashMap<String,Long>();
        var conflicts=new LinkedHashMap<String,JsonArray>();
        for(var event:events) {
            var source=new JsonObject();source.addProperty("receipt",event.source());source.addProperty("observed_tick",event.tick());sources.add(source);
            for(var cellRaw:event.targets()) {
                var cell=cellRaw.getAsJsonObject();String key=PhysicalStructureDesignStore.key(cell);
                if(ticks.containsKey(key)&&ticks.get(key)==event.tick()
                        &&(conflicts.containsKey(key)||!cell.equals(cells.get(key)))) {
                    // 同一观测刻存在冲突时保留两个声明为未知，不凭数组顺序猜哪份设计最后生效。
                    var alternatives=conflicts.computeIfAbsent(key,k->{var a=new JsonArray();a.add(cells.remove(k));return a;});
                    alternatives.add(cell.deepCopy());
                } else { cells.put(key,cell.deepCopy());ticks.put(key,event.tick());conflicts.remove(key); }
            }
        }
        conflicts.forEach((position,alternatives)->{
            var issue=problem("conflicting_receipt_order",position);issue.add("declarations",alternatives);unknowns.add(issue);
        });
        var targets=new JsonArray();cells.values().forEach(targets::add);
        evidence.add("sources",sources);evidence.add("unknowns",unknowns);
        return new PhysicalStructureDesignStore.History(targets,evidence,evidence.get("status").getAsString().equals("partial_checkpoint_recovery"));
    }
    private static void collect(JsonObject goal,JsonObject step,String source,String dimension,UUID id,
            List<Event> events,JsonArray unknowns) {
        if(!"maicraft:physical_balance".equals(text(goal,"ability"))) return;
        var parameters=goal.getAsJsonObject("parameters");
        if(!"apply".equals(text(parameters,"operation"))||!id.toString().equals(text(parameters,"structure_id"))) return;
        var result=object(step,"result");var report=object(object(result,"data"),"physics_balance");
        var construction=object(report,"construction");
        String recordedDimension=text(report,"dimension");
        if(recordedDimension==null) { unknowns.add(problem("receipt_dimension_unknown",source));return; }
        if(!dimension.equals(recordedDimension)) return;
        if(!id.toString().equals(text(report,"structure_id"))||!id.toString().equals(text(construction,"structure_id"))) {
            unknowns.add(problem("receipt_structure_identity_unknown",source));return;
        }
        try {
            if(!report.has("observed_tick")) { unknowns.add(problem("receipt_order_unknown",source));return; }
            var cells=new JsonArray();
            for(var diff:construction.getAsJsonArray("declared_structure_diff")) cells.add(diff.getAsJsonObject().get("expected").deepCopy());
            events.add(new Event(report.get("observed_tick").getAsBigDecimal().longValueExact(),source,
                    PhysicalStructureDesignStore.mergeTargets(new JsonArray(),cells)));
        } catch(RuntimeException corrupt) { unknowns.add(problem("receipt_declarations_unreadable",source+": "+corrupt)); }
    }
    private static JsonObject object(JsonObject parent,String key) {
        return parent!=null&&parent.has(key)&&parent.get(key).isJsonObject()?parent.getAsJsonObject(key):new JsonObject();
    }
    private static String text(JsonObject parent,String key) {
        return parent!=null&&parent.has(key)&&parent.get(key).isJsonPrimitive()?parent.get(key).getAsString():null;
    }
    private static JsonObject problem(String reason,String source) {
        var issue=new JsonObject();issue.addProperty("reason",reason);issue.addProperty("source",source);return issue;
    }
}
