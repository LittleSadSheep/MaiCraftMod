package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceParameters;
import org.maiwithu.maicraft.core.task.physics.PhysicalBalanceTaskRecord;

/** 起飞前选择分析、试算、推荐或施工；能力参数只描述结构目标，不暴露点击脚本。 */
final class PhysicsAbilityAdapter {
    static final String ABILITY="maicraft:physical_balance";
    private PhysicsAbilityAdapter() {}
    static void validate(Goal goal) {
        if(goal.target()!=null) throw new IllegalArgumentException("物理配平使用 structure_id，不使用固定世界目标");
        PhysicsBalanceParameters.parse(goal.parameters());
    }
    static IntentAction adapt(Goal goal,LocalPlayer player) {
        validate(goal);
        return new IntentAction.Native(new PhysicalBalanceTaskRecord("physics-"+UUID.randomUUID(),
                player.level().getGameTime()+20*60*15,goal.parameters()));
    }
    static JsonObject contract() {
        var out=new JsonObject();
        out.addProperty("summary","起飞前物理受力分析、螺旋桨启停、帆面气动和轮胎接地模拟、气球蒙皮与配重推荐。analyze/simulate/recommend 只读；apply 通过玩家原生操作施工明确 edits，并返回实际差异。比较停机、运行、启停和四向扰动，预测不冒充飞行验证。完整原生受力需要服务端 physics.snapshot。");
        out.add("accepted_target_kinds",new JsonArray()); out.add("accepted_preferences",new JsonObject()); out.add("accepted_hard_constraints",new JsonArray());
        out.addProperty("execution_boundary","LLM chooses the patch. Prediction is advisory, never a gate on native edits. Default workflow is preflight; do not add ballast automatically during flight.");
        var fields=new JsonObject();
        field(fields,"structure_id","string","Observed Sable structure UUID. All patch and ballast positions are integer block offsets from origin_storage.");
        field(fields,"operation","string","analyze (default), simulate, recommend, apply. apply requires explicit edits and does not automatically start propulsion.");
        field(fields,"reference_rpm","number","Hypothetical running RPM, -256..256, default 64. Does not set a real motor; verify actual drivetrain capacity separately.");
        // 地面预检允许模型指定比较航速，实际起步与飞行仍由原生控制及运行证据独立验收。
        field(fields,"reference_velocity","object","Optional world-space {x,y,z} velocity in blocks/s, each -256..256, for isolated running/stopping scenarios. Stopped/starting scenarios begin at rest. Never changes real velocity; measured forces retain actual motion.");
        field(fields,"balloon_fill","string","target (default): settled gas supply currently configured; current: actual gas fill. Stopped mode keeps this buoyancy and stops propulsion.");
        field(fields,"edits","array","Up to 64 {position:{x,y,z},block_id,properties?:{name:value}} cells, integer offsets -256..256 per axis. minecraft:air removes a block. Simulation changes isolated mass, fixed sail aerodynamics and balloon geometry; apply performs native placement/mining then reports actual states.");
        field(fields,"ballast_candidates","array","Optional up to 64 {id,position:{x,y,z},block_id} empty attached cells. Default searches nearby lower iron-block positions. Material mass comes from server physics data.");
        field(fields,"max_ballast_blocks","integer","0..64, default 8; candidate search can return improved_not_balanced or no_balanced_candidate_found.");
        // 同一轮胎的承重与驱动分开，模型收油门时不能把地面支撑一并关掉。
        field(fields,"controls","object","Source ID to hypothetical multiplier in [-4,4], default 1. For wheels it scales drive RPM only, preserving suspension and friction. Propulsion/drive is zero in stopped mode. This does not send control inputs.");
        // 车停着也能指定松刹车的运行工况；未声明的轮胎保留真实信号，不假定已经接好刹车电路。
        field(fields,"wheel_brakes","object","Optional observed wheel load ID to {running:0..1,stopped:0..1}, up to 64 wheels. 0 releases and 1 fully applies brakes. Interpolated during isolated start/stop transitions; suspension remains active. Omitted wheels retain measured braking. Never changes real throttle, circuits or force snapshots; actual brake control must be verified separately.");
        field(fields,"duration_seconds","number","1..30, default 6; simulation horizon per operating mode.");
        field(fields,"max_tilt_degrees","number","Allowed predicted tilt, default 8 degrees.");
        field(fields,"max_vertical_acceleration","number","Allowed vertical acceleration, default 0.25 blocks/s².");
        field(fields,"max_angular_acceleration","number","Allowed angular acceleration, default 0.035 rad/s².");
        field(fields,"perturbation_degrees","number","Pitch/roll perturbation, default 2 degrees; must be below allowed tilt.");
        out.add("parameters",fields); return out;
    }
    private static void field(JsonObject fields,String name,String type,String description) {
        var value=new JsonObject(); value.addProperty("type",type); value.addProperty("description",description); fields.add(name,value);
    }
}
