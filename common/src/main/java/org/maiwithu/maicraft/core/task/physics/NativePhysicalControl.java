package org.maiwithu.maicraft.core.task.physics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;
import static org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters.Operation.*;

/** 读取原生设置并构造玩家协议；旋钮、网络频率和油门实际状态均由原模组结算，不直接调用设置器。 */
final class NativePhysicalControl {
    static final String MOTOR="com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity";
    static final String SPEED="com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity";
    static final String LINK="com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity";
    static final String THROTTLE="dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity";
    static final String SPRING="dev.simulated_team.simulated.content.blocks.torsion_spring.TorsionSpringBlockEntity";
    static final String PROPELLER="dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity";
    static final String VALUE="com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour";
    static final String BOX="com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform";
    static final String SIDED=BOX+"$Sided";
    private static final String LINK_BEHAVIOUR="com.simibubi.create.content.redstone.link.LinkBehaviour";
    private static final String FREQUENCY="com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler$Frequency";
    private NativePhysicalControl() {}
    static void require(BlockEntity entity,PhysicalControlParameters p) {
        boolean valid=switch(p.operation()) {
            case INSPECT -> entity!=null;
            case SET_SPEED -> NativeApi.is(entity,MOTOR)||NativeApi.is(entity,SPEED);
            case SET_THROTTLE -> NativeApi.is(entity,THROTTLE);
            case SET_SPRING_ANGLE -> NativeApi.is(entity,SPRING);
            case BIND_TYPEWRITER_KEY,PRESS_TYPEWRITER_KEYS -> NativeApi.is(entity,NativeTypewriterControl.TYPE);
            case SET_BURNER_VOLUME -> NativeApi.is(entity,NativeBurnerDial.BURNER);
            case ASSEMBLE_PROPELLER,DISASSEMBLE_PROPELLER -> NativeApi.is(entity,PROPELLER);
            case TURN_CRANK -> entity!=null&&CreateManualInput.supported(entity.getLevel(),entity.getBlockPos());
            case SET_LINK_MODE,SET_FREQUENCY -> NativeApi.is(entity,LINK);
            case SET_TIRE -> NativeApi.is(entity,NativeWheelControl.WHEEL);
        };
        if(!valid)throw new IllegalArgumentException("当前部件不支持所选原生控制操作");
        if(p.operation()==SET_TIRE)NativeWheelControl.requireItem(p.itemId());
    }
    static boolean valueBox(PhysicalControlParameters.Operation operation) { return operation==SET_SPEED||operation==SET_BURNER_VOLUME||operation==SET_SPRING_ANGLE; }
    static boolean propeller(PhysicalControlParameters.Operation operation) { return operation==ASSEMBLE_PROPELLER||operation==DISASSEMBLE_PROPELLER; }
    static boolean typewriter(PhysicalControlParameters.Operation operation) { return operation==BIND_TYPEWRITER_KEY||operation==PRESS_TYPEWRITER_KEYS; }
    static Object setting(BlockEntity entity) {
        if(NativeApi.is(entity,NativeBurnerDial.BURNER))return NativeBurnerDial.setting(entity);
        if(NativeApi.is(entity,SPRING))return NativeApi.field(entity,SPRING,"angleInput");
        return NativeApi.is(entity,MOTOR)?NativeApi.field(entity,MOTOR,"generatedSpeed"):NativeApi.field(entity,SPEED,"targetSpeed");
    }
    static Object link(BlockEntity entity) {
        Object type=NativeApi.constant(LINK_BEHAVIOUR,"TYPE");
        return NativeApi.call(null,"com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour","get",entity.getLevel(),entity.getBlockPos(),type);
    }
    static Object frequency(BlockEntity entity,int index) {
        return NativeApi.call(NativeApi.call(link(entity),LINK_BEHAVIOUR,"getNetworkKey"),null,index==0?"getFirst":"getSecond");
    }
    static boolean frequencyMatches(BlockEntity entity,int index,ItemStack selected) {
        return frequency(entity,index).equals(NativeApi.call(null,FREQUENCY,"of",selected));
    }
    static boolean property(BlockEntity entity,String key) {
        var property=entity.getBlockState().getBlock().getStateDefinition().getProperty(key);
        return property!=null&&Boolean.parseBoolean(entity.getBlockState().getValue(property).toString());
    }
    static boolean matches(BlockEntity entity,PhysicalControlParameters p,int index,ItemStack selected) {
        require(entity,p);
        return switch(p.operation()) {
            case INSPECT -> true;
            case SET_SPEED -> ((Number)NativeApi.call(setting(entity),null,"getValue")).intValue()==p.value();
            case SET_BURNER_VOLUME -> ((Number)NativeApi.call(setting(entity),null,"getValue")).intValue()==NativeBurnerDial.applied(p.value(),NativeBurnerDial.maximum());
            case SET_SPRING_ANGLE -> ((Number)NativeApi.call(setting(entity),null,"getValue")).intValue()==p.value();
            case BIND_TYPEWRITER_KEY,PRESS_TYPEWRITER_KEYS -> throw new IllegalStateException("打字机由独立会话结算完整配键或有界按键");
            case ASSEMBLE_PROPELLER,DISASSEMBLE_PROPELLER -> NativeApi.truth(NativeApi.call(entity,null,"isRunning"))==(p.operation()==ASSEMBLE_PROPELLER);
            case TURN_CRANK -> false; // 每次手摇都有明确持续窗口，已有转速不能冒充本次已操作。
            case SET_THROTTLE -> ((Number)NativeApi.call(entity,THROTTLE,"getState")).intValue()==p.value();
            case SET_LINK_MODE -> property(entity,"receiver")==p.receiver();
            case SET_FREQUENCY -> frequencyMatches(entity,index,selected);
            case SET_TIRE -> NativeWheelControl.matches(NativeWheelControl.held(entity),p.itemId());
        };
    }
    static String requiredItem(BlockEntity entity,PhysicalControlParameters p,int index) {
        // 桨叶轴承只有空手右键才执行原生组装或减速拆回，不能带着扳手把它旋转成另一朝向。
        if(propeller(p.operation())||p.operation()==TURN_CRANK)return "minecraft:air";
        if(p.operation()==SET_FREQUENCY)return p.frequencyItems().get(index);
        if(p.operation()==SET_TIRE)return p.itemId();
        if(p.operation()==SET_LINK_MODE||valueBox(p.operation())&&NativeApi.truth(NativeApi.call(setting(entity),VALUE,"onlyVisibleWithWrench")))return "create:wrench";
        return null;
    }
    static boolean settingAccessible(BlockEntity entity,LocalPlayer player) {
        Object setting=setting(entity);
        return NativeApi.truth(NativeApi.call(setting,VALUE,"isActive"))&&NativeApi.truth(NativeApi.call(setting,VALUE,"acceptsValueSettings"))
                &&NativeApi.truth(NativeApi.call(setting,VALUE,"mayInteract",player))
                &&!NativeApi.truth(NativeApi.call(setting,VALUE,"bypassesInput",player.getMainHandItem()));
    }
    static CustomPacketPayload packet(BlockEntity entity,LocalPlayer player,PhysicalControlParameters p,BlockHitResult hit) {
        BlockPos pos=entity.getBlockPos();
        if(p.operation()==SET_THROTTLE) {
            int signal=property(entity,"inverted")?15-p.value():p.value();
            return (CustomPacketPayload)ControlReflection.construct("dev.simulated_team.simulated.network.packets.ThrottleLeverSignalPacket",pos,signal);
        }
        if(!valueBox(p.operation()))throw new IllegalArgumentException("该操作使用原生物品右键");
        Object setting=setting(entity),board=NativeApi.call(setting,VALUE,"createBoard",player,hit);
        // 燃烧器只有一行容量刻度，不能沿用电机的正负转速行；输出仍由原生设置包和红石信号共同决定。
        boolean burner=p.operation()==SET_BURNER_VOLUME;
        // 舵面限角与供气容量都只有一行，不能套用电机的正负转速行号。
        int row=burner||p.operation()==SET_SPRING_ANGLE?0:p.value()<0?0:1;
        int magnitude=burner?NativeBurnerDial.column(p.value(),NativeBurnerDial.maximum()):Math.abs(p.value());
        if(row>=((List<?>)NativeApi.call(board,null,"rows")).size()||magnitude>((Number)NativeApi.call(board,null,"maxValue")).intValue())
            throw new IllegalArgumentException("请求超出此原生旋钮面板范围");
        // 使用原生面板的行号与行为网络编号，不能绕过该面板支持的范围，或用服务端字段写入伪造零转速。
        return (CustomPacketPayload)ControlReflection.construct("com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsPacket",
                pos,row,magnitude,null,null,hit.getDirection(),false,((Number)NativeApi.call(setting,VALUE,"netId")).intValue());
    }
    static Map<String,Object> state(BlockEntity entity) {
        var out=new LinkedHashMap<String,Object>();
        out.put("block_id",BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString());
        out.put("block_state",entity.getBlockState().toString());
        // 配置结算后无线接收端仍可能等下一次同步；保留读取来源，不能把客户端旧值冒充实时服务端信号。
        out.put("observation_source","client_synced_native_fields_may_lag_server");
        if(CreateManualInput.supported(entity.getLevel(),entity.getBlockPos())) {
            out.put("actual_rpm",NativeApi.call(entity,null,"getSpeed"));
            out.put("overstressed",NativeApi.call(entity,null,"isOverStressed"));
            out.put("supported_operations",List.of("inspect","turn_crank"));
        } else if(NativeApi.is(entity,MOTOR)||NativeApi.is(entity,SPEED)) {
            out.put("speed_setting",NativeApi.call(setting(entity),null,"getValue"));out.put("actual_rpm",NativeApi.call(entity,null,"getSpeed"));
            out.put("overstressed",NativeApi.call(entity,null,"isOverStressed"));
            out.put("supported_operations",List.of("inspect","set_speed"));
        } else if(NativeApi.is(entity,PROPELLER)) {
            boolean assembled=NativeApi.truth(NativeApi.call(entity,null,"isRunning"));
            double rpm=((Number)NativeApi.call(entity,null,"getSpeed")).doubleValue();
            out.put("assembled",assembled);out.put("actual_rpm",rpm);
            out.put("overstressed",NativeApi.call(entity,null,"isOverStressed"));
            out.put("rotor",NativePropellerState.capture(entity,assembled));
            // 原生机械轴承在零转速时只接受右键而不成型；说明当前事实，不据此阻止已授权的尝试。
            if(!assembled&&rpm==0)out.put("assembly_observation","native bearing requires nonzero kinetic speed to assemble");
            out.put("rotation_speed",NativeApi.call(entity,null,"getRotationSpeed"));
            Object failure=NativeApi.call(entity,null,"getLastAssemblyException");
            if(failure!=null)out.put("assembly_error",((Component)NativeApi.field(failure,null,"component")).getString());
            out.put("supported_operations",List.of("inspect","assemble_propeller","disassemble_propeller"));
        } else if(NativeApi.is(entity,NativeBurnerDial.BURNER)) {
            out.put("volume_setting",NativeApi.call(setting(entity),null,"getValue"));
            out.put("minimum_volume",5);out.put("maximum_volume",NativeBurnerDial.maximum());
            out.put("volume_step",NativeBurnerDial.interval(NativeBurnerDial.maximum()));
            out.put("received_signal",NativeApi.call(entity,null,"getSignalStrength"));
            out.put("gas_output",NativeApi.call(entity,null,"getGasOutput"));
            out.put("supported_operations",List.of("inspect","set_burner_volume"));
        } else if(NativeApi.is(entity,SPRING)) {
            // 同时报告限位与实际舵角，让模型区分“设置好了”与“舵面已偏转或回中”。
            out.put("angle_limit_degrees",NativeApi.call(setting(entity),null,"getValue"));
            out.put("angle_degrees",NativeApi.call(entity,null,"getAngle"));
            out.put("input_rpm",NativeApi.call(entity,null,"getSpeed"));
            out.put("output_rpm",NativeApi.call(NativeApi.call(entity,null,"getExtraKinetics"),null,"getSpeed"));
            out.put("powered",property(entity,"powered"));
            out.put("return_to_center_rule","input stopped and spring not redstone-powered");
            out.put("supported_operations",List.of("inspect","set_spring_angle"));
        } else if(NativeApi.is(entity,NativeWheelControl.WHEEL)) {
            out.put("tire",NativeWheelControl.state(entity));out.put("actual_rpm",NativeApi.call(entity,null,"getSpeed"));
            out.put("supported_operations",List.of("inspect","set_tire"));
        } else if(NativeApi.is(entity,THROTTLE)) {
            out.put("signal",NativeApi.call(entity,THROTTLE,"getState"));out.put("supported_operations",List.of("inspect","set_throttle"));
        } else if(NativeApi.is(entity,NativeTypewriterControl.TYPE)) {
            out.putAll(NativeTypewriterControl.state(entity));
        } else if(NativeApi.is(entity,LINK)) {
            out.put("receiver",property(entity,"receiver"));out.put("frequencies",List.of(frequencyState(entity,0),frequencyState(entity,1)));
            out.put("received_signal",NativeApi.call(entity,LINK,"getReceivedSignal"));
            out.put("supported_operations",List.of("inspect","set_link_mode","set_frequency"));
        } else out.put("supported_operations",List.of("inspect"));
        return Map.copyOf(out);
    }
    private static Map<String,Object> frequencyState(BlockEntity entity,int index) {
        ItemStack item=(ItemStack)NativeApi.call(frequency(entity,index),null,"getStack");var color=item.get(DataComponents.DYED_COLOR);
        return Map.of("item_id",BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),"dyed_color",color==null?-1:color.rgb());
    }
}
