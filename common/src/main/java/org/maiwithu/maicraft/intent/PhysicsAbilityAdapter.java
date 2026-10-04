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
    // 模型起飞前先选只读比较或明确施工；契约把假设工况、实际挖放和后续飞行验收分开，避免推荐被当成已执行。
    static JsonObject contract() {
        var out=new JsonObject();
        out.addProperty("summary","起飞前比较当前物理结构的受力、启停和四向姿态扰动，再由 LLM 选择是否修改。analyze 与 simulate 目前都执行同一套隔离刚体评估；recommend 另外搜索配重；这三种操作只读，edits 只作用于副本。apply 必须给非空 edits，先真实挖放，再读新结构并计算；推荐不会自动变成施工或启动动力。"
                + "\n\n参数均在 goal.parameters；goal.outcome 是用途文字，不能代替参数。未声明的操作字段会被拒绝；无能力专属 preferences 或硬约束。可选字段省略才用默认值，null 不代表省略，数值用 JSON number，布尔值用 true/false。当前独立解析器拒绝 parameters 中的 auto_respawn/recover_after_death，公共运行时授权需放在 goal.preferences。 不接受 goal.target；structure_id 必填。所有 edits/ballast_candidates.position 都从观察到的 origin_storage 计整数方块偏移，不能填船体随运动变化的世界坐标。"
                + "\n\n服务端 physics.snapshot 提供完整分页的质量、惯性、原生载荷与未知项；离结构过远、快照过期或身份变化会中止读取。停止工况关闭推进并保留浮力和车轮支撑；运行、启动、停止与俯仰/横滚两侧扰动分别评估。reference_rpm、controls、wheel_brakes 和 reference_velocity 只改变试算，不会发送真实输入。新螺旋桨位置/朝向、帆面、蒙皮与质量可进入模型，但接线、胶层、原生成型和充气动态仍须现场核验。"
                + "\n\n读 physics_balance 的预测、来源完整性和 unknowns；native_flight_verified 始终为 false；分析预算耗尽也可正常返回 analysis_incomplete，不能按任务成功推定完整预测。recommend 可返回 improved_not_balanced 或 no_balanced_candidate_found，不是已经配平的保证。apply 还返回 construction_started、completed_effects、design_declaration、declared_structure_diff 和施工进度；施工确认后分析不可用仍保留成功的施工事实及 analysis_unavailable。失败或取消后先读已完成效果和未完成格，不能重放整份补丁。"
                + "\n\n以下是完整 plan 工具参数；UUID 和局部位置来自曾观察到的飞艇，仅在重新观察确认仍属当前世界同一结构时使用。其他现场必须换成真实观察值。plan 只登记计划，执行使用返回的 plan_id；不要编造执行编号。"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_balance\",\"outcome\":\"只读比较起飞前启停受力\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"analyze\",\"reference_rpm\":64,\"balloon_fill\":\"current\"}}}"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_balance\",\"outcome\":\"对明确选中的配重格执行原生施工后复查\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"apply\",\"edits\":[{\"position\":{\"x\":-1,\"y\":-4,\"z\":-1},\"block_id\":\"minecraft:iron_block\"}]}}}");
        out.add("accepted_target_kinds",new JsonArray()); out.add("accepted_preferences",new JsonObject()); out.add("accepted_hard_constraints",new JsonArray());
        out.addProperty("execution_boundary","预测只供设计判断，不是原生施工准入条件。只读操作不改变世界；apply 仅落实明确 edits，默认用于起飞前。暂停/取消停止读算或施工推进并保留已经发生的效果，不会撤销方块或自动在飞行中补配重。总任务重启不恢复计算线程或未确认的原生动作，恢复前读完整回执及实际结构。");
        var fields=new JsonObject();
        field(fields,"structure_id","string","必填字符串：当前观察到的 Sable 结构 UUID；不接受 target。局部坐标基于该结构 origin_storage，重组装后的新 UUID 需重新观察。");
        field(fields,"operation","string","字符串，区分大小写：analyze（默认）、simulate、recommend、apply。前两者目前同路计算；recommend 搜索候选；apply 要求非空 edits。其他操作的 edits 仅试算。");
        field(fields,"reference_rpm","number","有限 number，-256..256 RPM，默认 64；0 是允许的停转参考值。只供假设运行工况，不设置电机，也不证明传动容量足够。");
        // 地面预检允许模型指定比较航速，实际起步与飞行仍由原生控制及运行证据独立验收。
        field(fields,"reference_velocity","object","可选对象，恰含有限 number x/y/z，各 -256..256，单位世界格/秒。省略沿用快照速度；用于运行/停转试算，停止/启动从静止开始。不改真实速度或原生受力快照。");
        field(fields,"balloon_fill","string","字符串 target（默认）或 current。target 按当前配置供气的稳定目标量，current 按已观察填充量；停止工况仍保留此浮力。目标填充不证明已经充满。");
        field(fields,"edits","array","可选数组，0..64 格；apply 必须 1..64 格。每项 {position:{x,y,z},block_id,properties?:{属性名:字符串值}}，局部整数各 -256..256，同格不能重复。block_id 必须为已注册方块，minecraft:air 明确拆除；属性由原生状态表校验。省略不修改副本；[] 不能用于 apply。未声明格不视为空气。");
        field(fields,"ballast_candidates","array","可选数组，0..64 项 {id?:字符串,position:{x,y,z},block_id,properties?:{属性名:字符串值}}，局部整数各 -256..256；id 省略按坐标生成，进入推荐的 ID 必须互异。显式 [] 禁用候选；省略在质心附近及下方半径 1..6 搜索最多 64 个铁块格。只有已加载、当前为空、邻接碰撞面且正质量的候选进入搜索，其余被排除；属性仅用于候选质量读取，推荐 edits 不保留候选 properties。");
        field(fields,"max_ballast_blocks","integer","整数 0..64，默认 8；0 不添加配重。不满足限值时仍返回改善情况或无平衡候选，不自动扩展范围或施工。");
        // 同一轮胎的承重与驱动分开，模型收油门时不能把地面支撑一并关掉。
        field(fields,"controls","object","可选对象：观察到的载荷 source ID -> 有限 number 倍率 [-4,4]，省略的来源为 1；{} 沿用全部默认。0 禁用该来源的试算作用，未知 ID 在评估时拒绝。轮胎倍率只缩放驱动 RPM，支撑和摩擦保留；停止模式推进/驱动归零。");
        // 车停着也能指定松刹车的运行工况；未声明的轮胎保留真实信号，不假定已经接好刹车电路。
        field(fields,"wheel_brakes","object","可选对象，最多 64 个已观察轮胎载荷 ID，每项恰含 {running:number,stopped:number}，均为有限 0..1；0 松刹车，1 满刹车。省略或 {} 保留测得刹车。启停过程插值且保留悬挂；未知轮胎或缺少模型时拒绝。只影响副本，不改真实红石。");
        field(fields,"duration_seconds","number","有限 number 1..30 秒，默认 6；启动力过渡另加 1 秒、停转过渡另加 3 秒，再观察此窗口。0 不合法。");
        field(fields,"max_tilt_degrees","number","有限 number 0.1..89 度，默认 8；预测轨迹限值，不是对真实载具的姿态约束。");
        field(fields,"max_vertical_acceleration","number","有限 number 0.001..100 格/秒²，默认 0.25；预测竖直加速度绝对值限值。");
        field(fields,"max_angular_acceleration","number","有限 number 0.00001..10 弧度/秒²，默认 0.035；预测角加速度向量长度限值。");
        field(fields,"perturbation_degrees","number","有限 number 0.01..20 度，默认 2，并且严格小于 max_tilt_degrees；分别施加正负俯仰和横滚扰动。");
        out.add("parameters",fields); return out;
    }
    private static void field(JsonObject fields,String name,String type,String description) {
        var value=new JsonObject(); value.addProperty("type",type); value.addProperty("description",description); fields.add(name,value);
    }
}
