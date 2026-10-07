package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftFlightTaskRecord;
import org.maiwithu.maicraft.core.integration.physics.flight.AircraftProfile;
import org.maiwithu.maicraft.core.tools.SemanticParameters;

/** LLM 只提供飞机、操纵声明和目的地；飞控任务在 Mod 内持续执行，不能接收逐帧点击脚本。 */
final class AircraftFlightAbilityAdapter {
    static final String ABILITY="maicraft:fly_vehicle";
    private AircraftFlightAbilityAdapter() {}
    static void validate(Goal goal) {
        JsonObject p=goal.parameters();
        // 驾驶参数写错名字时连同合法键报出，模型改正后重新提交同一次飞行。
        Set<String> flightFields=Set.of("structure_id","operation","profile","direction","distance","cruise_altitude");
        for(String key:p.keySet())if(!flightFields.contains(key))
            throw new IllegalArgumentException("unknown fly_vehicle parameter: "+key+SemanticParameters.acceptedKeys(flightFields));
        String id=text(p,"structure_id",null);if(id==null)throw new IllegalArgumentException("structure_id is required");
        UUID.fromString(id);String operation=text(p,"operation","fly");
        if(!Set.of("inspect","configure","fly").contains(operation))throw new IllegalArgumentException("flight operation must be inspect, configure or fly");
        if(p.has("profile")) {
            if(operation.equals("inspect")||!p.get("profile").isJsonObject())throw new IllegalArgumentException("inspect cannot change an aircraft profile");
            AircraftProfile.parse(p.getAsJsonObject("profile"));
        }
        if(operation.equals("configure")&&!p.has("profile"))throw new IllegalArgumentException("configure requires a complete aircraft profile");
        if(!operation.equals("fly")) {
            if(goal.target()!=null||p.has("direction")||p.has("distance")||p.has("cruise_altitude"))throw new IllegalArgumentException("flight profile operations do not move the aircraft");
            return;
        }
        String direction=text(p,"direction",null);
        if((goal.target()==null)==(direction==null))throw new IllegalArgumentException("fly requires exactly one destination target or direction");
        if(goal.target()!=null&&!Set.of("coordinates","landmark","area").contains(goal.target().kind()))throw new IllegalArgumentException("flight target must be coordinates or a remembered place");
        if(goal.target()!=null&&goal.target().kind().equals("coordinates")&&goal.target().position()==null)throw new IllegalArgumentException("flight coordinates require a world position");
        if(direction!=null&&!Set.of("north","south","east","west","forward","backward","left","right").contains(direction))throw new IllegalArgumentException("unsupported flight direction");
        if(p.has("distance")&&direction==null)throw new IllegalArgumentException("distance is only used with direction");
        double distance=number(p,"distance",256);if(distance<16||distance>8192)throw new IllegalArgumentException("flight direction distance must be 16..8192 blocks");
        number(p,"cruise_altitude",0);
    }
    static IntentAction adapt(Goal goal,LocalPlayer player,IntentRuntime runtime) {
        validate(goal);JsonObject p=goal.parameters();Vec3 destination=null;
        if(goal.target()!=null) {
            Goal.WorldPosition at;
            if(goal.target().kind().equals("coordinates"))at=goal.target().position();
            else {var place=runtime.landmark(goal.target().label());at=place==null?null:place.position();}
            if(at==null||at.dimension()!=null&&!at.dimension().equals(player.level().dimension().location().toString()))
                throw new IllegalArgumentException("flight destination must resolve in the current dimension");
            destination=new Vec3(at.x()+.5,at.y(),at.z()+.5);
        }
        return new IntentAction.Native(new AircraftFlightTaskRecord("aircraft-"+UUID.randomUUID(),player.level().getGameTime()+20*60*60,
                UUID.fromString(text(p,"structure_id",null)),text(p,"operation","fly"),p.has("profile")?AircraftProfile.parse(p.getAsJsonObject("profile")):null,
                destination,text(p,"direction",null),number(p,"distance",256),p.has("cruise_altitude")?number(p,"cruise_altitude",0):null));
    }
    // 登记操纵映射后仍须原生登机和持续飞控；用法分别交代档案、实际起降与旅行末段，避免落点冒充精确终点。
    static JsonObject contract() {
        var out=new JsonObject();out.addProperty("summary","让 Mod 持续驾驶已组装飞机：读取操纵声明，原生入座并接管无线打字机，执行起飞、巡航、局部避障、进近、着陆和松键后停稳确认。LLM 只提交结构和目的地；不会按一句自然语言自动设计或补装缺失舵面。inspect 读声明/原生遥测，configure 只保存完整声明，fly（默认）才执行飞行。"
                + "\n\n参数均在 goal.parameters；goal.outcome 是用途文字，不能代替参数。未声明的操作字段会被拒绝；无能力专属 preferences 或硬约束。可选字段省略才用默认值，null 不代表省略，数值用 JSON number，布尔值用 true/false。当前独立解析器拒绝 parameters 中的 auto_respawn/recover_after_death，公共运行时授权需放在 goal.preferences。 structure_id 始终必填。fly 恰好给 goal.target 或 parameters.direction 之一；target.kind=coordinates|landmark|area。coordinates.position={x:整数,y:整数,z:整数,dimension?:字符串}，具名目标用已记 label，只能当前维度。inspect/configure 不接受 target、direction、distance、cruise_altitude；inspect 还禁止 profile。"
                + "\n\nprofile 是完整对象，提供时整体保存覆盖，省略才读当前世界/维度/结构 UUID 的既存声明。首次 fly 或 configure 必须提供。需要真正的 Create 座位、同艇且座上能触及的无线打字机及已经配置的频率/键位。power 必须按住运行、松开断开动力，lift 表示按住启用浮力；执行中持续检查真实座位、控制器用户与绑定身份。声明解析目前只强制 power，不检查其他轴是否齐全，也不拒绝多个角色映射到同一键；保存成功不能证明可控。"
                + "\n\n飞艇先用实际质量和目标供气做只读悬停参考，无法建模时报告未知并使用默认前馈；垂直离地并稳定姿态后才向前。固定翼按加速、抬头和真实离地转入爬升。避障使用已加载地形、完整机身和自身动态转子外形，未知区域不会当作空域；这不是全世界寻路。降落选目的地附近可用落点，正常结束仍保持乘坐；精确到达交给 travel 的下机和末段步行。"
                + "\n\n回执 profile_registered/configuration_is_flight_proof:false 只证明登记。inspect 可成功但 native_observation_unknown，不能冒称遥测齐全。实际 fly 查看 boarding、hover_trim、flight、flight_outcome、flight_verified、effects_started、outcome_uncertain；完成要求真实离地证据、着陆接地、低速低倾角以及松键断开后的持续停稳。控制失联、地形未知、停车未确认等可能失败；不能承诺任意设计始终平衡。mechanical_retry_allowed=false，先读效果再决定新目标。"
                + "\n\n以下是完整 plan 工具参数；UUID 和局部位置来自曾观察到的飞艇，仅在重新观察确认仍属当前世界同一结构时使用。其他现场必须换成真实观察值。plan 只登记计划，执行使用返回的 plan_id；不要编造执行编号。"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:fly_vehicle\",\"outcome\":\"读取该飞艇的配置与当前接地证据\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"inspect\"}}}"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:fly_vehicle\",\"outcome\":\"登记曾观察的飞艇操纵映射，不启动飞行\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"operation\":\"configure\",\"profile\":{\"kind\":\"airship\",\"seat_position\":{\"x\":-4,\"y\":-5,\"z\":-2},\"typewriter_position\":{\"x\":-3,\"y\":-5,\"z\":-2},\"forward\":\"west\",\"keys\":{\"power\":\"w\",\"yaw_left\":\"a\",\"yaw_right\":\"d\",\"lift\":\"space\"},\"climb_rate\":2.5}}}}"
                + "\n\n{\"goal\":{\"ability\":\"maicraft:fly_vehicle\",\"outcome\":\"使用已登记映射向机头方向飞行并在附近落地\",\"parameters\":{\"structure_id\":\"aceba7b1-00a7-481a-b59f-ec8e38cae870\",\"direction\":\"forward\",\"distance\":256}}}");
        var targets=new JsonArray();List.of("coordinates","landmark","area").forEach(targets::add);out.add("accepted_target_kinds",targets);
        out.add("accepted_preferences",new JsonObject());out.add("accepted_hard_constraints",new JsonArray());var fields=new JsonObject();
        field(fields,"structure_id","string","必填真实 Sable UUID 字符串；档案按世界、维度和 UUID 隔离。读取/保存先确认该结构当前可读；重新组装产生的新 UUID 不自动继承旧飞控档案。");
        field(fields,"operation","string","区分大小写的字符串 fly（默认）、configure、inspect。configure 需完整 profile，只保存；inspect 禁止 profile，允许未登记档案并报告遥测未知。两者禁止目的地字段与 target。");
        field(fields,"profile","object","完整对象，可选但 configure/首次 fly 必需，不能仅给补丁。必填 kind=fixed_wing|airship（忽略大小写）、seat_position/typewriter_position={x:整数,y:整数,z:整数}（恰三轴，各 -256..256）、keys={角色:键名字符串}；forward 可省略默认 south，取 north/south/east/west，指本地机头。角色有 power（唯一强制）、brake、pitch_up、pitch_down、bank_left、bank_right、yaw_left、yaw_right、lift；角色忽略大小写，键名同 physical_control，缺轴不自动补齐，同键多角色目前未拒绝。有限 number 可选项及 fixed_wing/airship 默认分别为：takeoff_speed=8/1，cruise_speed=16/6（格/秒；0<takeoff_speed<cruise_speed≤128）；max_bank_degrees=30/12（0<值≤60）；climb_pitch_degrees=12/8（0<值≤30）；approach_pitch_degrees=6/5（0<值≤20）；climb_rate=3/2（0<值≤16 格/秒）；descent_rate=2/1（0<值≤8 格/秒）。0/null 不表示默认，额外字段拒绝；这些是控制参考，不是保证的物理性能。");
        field(fields,"direction","string","仅 fly：与 goal.target 恰选一个。区分大小写：north/south/east/west，或 forward/backward/left/right。相对方向在入座后按飞机真实姿态的机头水平投影冻结，不按玩家镜头；后续转向不移动目的地。");
        field(fields,"distance","number","仅与 direction 使用，有限 number 16..8192 格，默认 256，可为小数；0/null 不代表默认。指定 target 时禁止，即便等于默认值也不允许。");
        field(fields,"cruise_altitude","number","仅 fly，可选有限 number 世界 Y，不是相对高度；0 是显式 0，省略取起点/目的地较高处再加 32 格。规划仍抬到落点之上至少 16 格并可避障，非严格定高约束；目前无额外世界高度范围校验。");
        out.add("parameters",fields);
        out.addProperty("execution_boundary","只使用原生座位、打字机和键盘协议，不写位置、速度、力或背包。暂停/取消请求附近降落，由交通运行时继续收尾；这不保证故障下能落稳。人工接管、死亡、身体/世界变化释放旧控制，不向新身体补发飞行输入；断线清理只能保留可确认事实。重启保留档案和任务回执，不恢复空中控制会话。当前有 aircraft travel 与沿途被动观察记录；定向寻找群系/结构的航空搜索尚未接通，固定翼自动飞行及完整飞艇起降仍需实机验收。");
        return out;
    }
    private static String text(JsonObject p,String key,String fallback){if(!p.has(key))return fallback;if(!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" requires text");return p.get(key).getAsString();}
    private static double number(JsonObject p,String key,double fallback){if(!p.has(key))return fallback;if(!p.get(key).isJsonPrimitive()||!p.getAsJsonPrimitive(key).isNumber()||!Double.isFinite(p.get(key).getAsDouble()))throw new IllegalArgumentException(key+" requires a finite number");return p.get(key).getAsDouble();}
    private static void field(JsonObject fields,String name,String type,String description){var field=new JsonObject();field.addProperty("type",type);field.addProperty("description",description);fields.add(name,field);}
}
