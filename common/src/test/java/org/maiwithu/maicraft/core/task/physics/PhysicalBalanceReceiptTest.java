package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 部分改造失败后，模型必须直接看到已完成拆放及全机差异，不能被外层摘要当成零效果。 */
public final class PhysicalBalanceReceiptTest {
    public static void run() {
        var effects=List.of(Map.of("action","remove","native_confirmed",true),Map.of("action","place","native_confirmed",true));
        var diff=new ArrayList<Map<String,Object>>();
        for(int i=0;i<130;i++)diff.add(Map.of("index",i,"matches",i<129));
        var construction=Map.<String,Object>of("completed_effects",effects,"declared_structure_diff",diff,
                "processed_targets",1,"total_targets",9,"placement_diagnostics",Map.of("native_can_survive",false));
        var report=new JsonObject();var result=PhysicalBalanceTask.receipt(report,true,construction);
        if(result.get("completed_effects")!=effects||result.get("declared_structure_diff")!=diff
                ||!result.get("placement_diagnostics").equals(construction.get("placement_diagnostics")))
            throw new AssertionError("部分施工回执被遗漏或截断");
        if(PhysicalBalanceTask.receipt(report,false,Map.of()).containsKey("completed_effects"))
            throw new AssertionError("只读分析不能伪造施工效果");
        System.out.println("PhysicalBalanceReceiptTest: partial effects and full diff remain directly visible");
    }
}
