package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters;
import org.maiwithu.maicraft.core.task.physics.PhysicalControlTaskRecord;

/** 模型指定设置意图；执行器自己走位、选工具、检查原生面板命中和服务器回执。 */
final class PhysicalControlAbilityAdapter {
    static final String ABILITY="maicraft:physical_control";
    private PhysicalControlAbilityAdapter() {}
    static void validate(Goal goal) {
        var parameters=PhysicalControlParameters.parse(goal.parameters());
        PhysicalAssemblyAbilityAdapter.validateTarget(goal,parameters.structureId());
    }
    static IntentAction adapt(Goal goal,LocalPlayer player,IntentRuntime runtime) {
        validate(goal);var p=PhysicalControlParameters.parse(goal.parameters());
        var anchor=PhysicalAssemblyAbilityAdapter.anchor(goal,player,runtime,p.structureId());
        return new IntentAction.Native(new PhysicalControlTaskRecord("physical-control-"+UUID.randomUUID(),player.level().getGameTime()+20*60*15,
                p,anchor,player.level().dimension().location().toString()));
    }
    // 出发前配部件，试车时只保持有限输入；契约交代同艇约束、松键和收端反馈，避免把发包当成已经开动。
    static JsonObject contract() {
        // 能力列表也公开航空配置入口，模型无需先猜操作名才能发现供气旋钮和螺旋桨成型流程。
        var out=new JsonObject();out.addProperty("summary","配置实际物理部件，或通过无线红石打字机执行一次有限按键试验。inspect 只读；其余操作先读实际配置、准备工具/物品、走位并命中原生面板、提交原生输入，再核对配置和整机声明差异。LLM 指定部件与设置，不提供逐刻点击脚本。长期飞行用 maicraft:fly_vehicle。"
                + "\n\n参数均在 goal.parameters；goal.outcome 是用途文字，不能代替参数。未声明的操作字段会被拒绝；无能力专属 preferences 或硬约束。可选字段省略才用默认值，null 不代表省略，数值用 JSON number，布尔值用 true/false。当前独立解析器拒绝 parameters 中的 auto_respawn/recover_after_death，公共运行时授权需放在 goal.preferences。 structure_id 与世界 goal.target 二选一；目标类型 coordinates|landmark|area|current_place，解析规则同 physical_assembly。coordinates.position 为整数世界 x/y/z 和可选 dimension；具名目标用已有 label。所有 position/observe_positions 均从世界锚点或结构 origin_storage 计偏移，只能当前维度。"
                + "\n\n默认起步前配好转速、频率、舵角、供气容量和键位。真实配置等待结构停稳，超过 200 游戏刻仍运动则停止；set_throttle、turn_crank、press_typewriter_keys 允许在移动结构上操作。require_onboard=true 在需要输入前确认身体支撑或 Create 座位属于同艇；inspect 或已满足且无需输入的分支不会额外登艇。座上控制不可达时保留座位并报告遮挡。"
                + "\n\n配键会空手原生连接打字机、等待服务器确认用户、合并完整旧映射后保存，再断开。按键要求全部已绑定且打字机未被其他人占用；连接确认后只按下一次，保持指定游戏刻，期间每 5 刻记录 observe_positions 的变化，然后松开并断开。暂停/取消释放本会话按键；恢复不得继续重放旧保持时段。原生按键本身不向普通客户端同步，所以 key_state_confirmed=false，发送成功不能证明收端已通电或载具已运动。"
                + "\n\n查看 native_submitted、input_receipt、native_configuration_confirmed、actual_configuration、completed_effects、design_declaration、declared_structure_diff 和 after_observation_unknown；typewriter 还返回 observed_feedback、release_dispatched、disconnect_dispatched。螺旋桨请求确认后另等真实 isRunning，requested_propeller_state_observed=false 仍可保留已完成输入；转速为零时不能声称成型成功。vehicle_operation_verified=false，动作与驾驶验收分开。"
                + "\n\n以下是完整 plan 工具参数；UUID 和局部位置来自曾观察到的飞艇，仅在重新观察确认仍属当前世界同一结构时使用。其他现场必须换成真实观察值。plan 只登记计划，执行使用返回的 plan_id；不要编造执行编号。"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_control\",\"outcome\":\"只读检查座位旁无线打字机\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"position\":{\"x\":-3,\"y\":-5,\"z\":-2}}}}"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:physical_control\",\"outcome\":\"在同艇进行一秒升力键试验后松开\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"press_typewriter_keys\",\"position\":{\"x\":-3,\"y\":-5,\"z\":-2},\"require_onboard\":true,\"keys\":[\"space\"],\"duration_seconds\":1}}}");
        var targets=new JsonArray();for(String kind:new String[]{"coordinates","landmark","area","current_place"})targets.add(kind);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());
        var fields=new JsonObject();
        field(fields,"operation","string","字符串，不区分大小写，默认 inspect。支持 set_speed、set_throttle、set_link_mode、set_frequency、set_burner_volume、set_spring_angle、assemble_propeller、disassemble_propeller、turn_crank、set_tire、bind_typewriter_key、press_typewriter_keys。操作专属字段必须对应本次 operation，不能混用。");
        // LLM 起飞前配好键，飞行时由有限输入会话或 Mod 飞控保持按键；不要求模型逐刻发指令。
        field(fields,"key","string","仅 bind_typewriter_key 必填单个键名字符串，禁止同时 keys、observe_positions 或 duration_seconds。如 a/w/left/space 或 key.keyboard.space；转小写后解析普通键盘键，不修剪空格，Escape/鼠标/未知键拒绝。只覆盖此键，其他绑定保留。");
        field(fields,"keys","array<string>","仅 press_typewriter_keys 必填 1..16 个字符串键名，归一化键码必须互异，禁止同时 key。规则同 key；执行时每个键必须有当前绑定。一次同时按下，有限保持后一起松开。");
        field(fields,"observe_positions","array<object>","仅 press_typewriter_keys 可用，数组 0..16 个互异 {x,y,z}，坐标类型/范围同 position，默认 []。保持期间每 5 游戏刻只读收端信号、实际舵角等，变化的完整状态和未知项留在 observed_feedback；不操作这些部件。");
        // 物理组装后仍可在停稳的轮座上更换实际轮胎；目标种类已满足时不重复交换。
        field(fields,"item_id","string","仅 set_tire 必填字符串，其他操作禁止。必须是当前注册且默认物品具有原生 TIRE 组件的轮胎，minecraft:air 表示取下；已装同种保留其真实组件。实际装取通过轮座外侧/底面原生交互，回执给真实槽位。");
        // 手摇是移动结构上的普通原生操作；每次重算世界瞄准点，持续时长不开放给其他设置的机械重放。
        field(fields,"duration_seconds","number","仅 turn_crank/press_typewriter_keys 可用，有限 number，向上取整为 seconds×20 游戏刻。手摇：0..30，省略或 0 只触发一次原生动作；打字机：大于 0 且≤30，省略默认 1 秒。游戏暂停不等于现实秒数流逝。");
        field(fields,"structure_id","string","可选观察到的结构 UUID；存在时禁止 target/design_id，偏移基于 origin_storage。省略时必须指定当前维度的世界 target。");
        // 飞艇供气和推进前选择同艇操作，避免麦麦站在地面启动后追不上载具。
        field(fields,"require_onboard","boolean","boolean，默认 false；true 必须同时有 structure_id。需要输入前确认身体支撑/乘坐同一结构，并在艇内寻找控制视线；已入座且不可达时不会下船绕行。false 不强制登艇；inspect 和无须输入的已满足分支不额外验证。");
        field(fields,"position","object","可选对象，恰含整数 x/y/z，默认三轴 0；各轴绝对值≤BuildingBudgets.maxRadius（默认 512 格）。执行时重新按姿态换算世界命中点，不是固定世界坐标。");
        field(fields,"design_id","string","可选已保存世界设计 UUID，用于配置后的完整声明差异；与 structure_id 互斥。省略不表示清空既有记录。");
        // 起飞前先设置供气容量，再通过实际红石控制启停；面板容量本身不证明气球已有升力。
        field(fields,"value","integer","仅 set_speed/set_throttle/set_burner_volume/set_spring_angle 必填整数，其他操作禁止。转速：非零 -256..256 RPM，面向会影响实际符号，0 不可用来停机。油门：实际红石信号 0..15。弹簧：1..360 度最大偏角，不是立即转到该角。供气：≥5 且≤现场原生配置上限，按 volume_step 向下量化并限到原生范围；不为燃烧器通电。");
        field(fields,"receiver","boolean","仅 set_link_mode 必填 boolean，其他操作禁止；true 接收、false 发送，已满足不再用扳手切换。省略或 null 都不能代替 false。");
        field(fields,"frequency_items","array<string>","仅 set_frequency/bind_typewriter_key 必填长度恰为 2 的有序物品 ID 字符串数组，其他操作禁止。取实际携带/补给物品并保留颜色身份；minecraft:air 清空该槽，两槽都 air 时删除指定打字机键。链接原生右键频率槽；打字机合并旧键后原生保存。");
        out.add("parameters",fields);
        out.addProperty("execution_boundary","只有真实交互、原生面板、物品或打字机协议改变世界，不写背包/NBT/力。缺少确认停止而不重放；已经满足的设置不重复交换物品。取消不回滚配置或已消耗材料；死亡、断线或换世界时不向新身体补发旧请求，无法送达松键需记录 cleanup_unknown。默认回执保留完整整机声明差异，驾驶效果另外观察。");
        return out;
    }
    private static void field(JsonObject fields,String name,String type,String description) {
        var value=new JsonObject();value.addProperty("type",type);value.addProperty("description",description);fields.add(name,value);
    }
}
