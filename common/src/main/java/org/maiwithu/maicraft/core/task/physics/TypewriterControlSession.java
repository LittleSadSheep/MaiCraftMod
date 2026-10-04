package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.machine.assembly.ServerBlockEntityReceipts;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.entity.InputDriver;
import static org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters.Operation.BIND_TYPEWRITER_KEY;

/** 准备真实频率物品 -> 原生认领 -> 配键或有限按住 -> 松键退出；取消同样释放本会话的控制。 */
final class TypewriterControlSession {
    private final PhysicalControlParameters p;
    private final List<Map<String,Object>> effects=new ArrayList<>();
    private final List<Map<String,Object>> feedback=new ArrayList<>();
    private List<Map<String,Object>> previousFeedback=List.of();
    private long feedbackTick=Long.MIN_VALUE;
    private final ItemStack[] frequency={ItemStack.EMPTY,ItemStack.EMPTY};
    private NativeActionReceipt action;
    private ServerBlockEntityReceipts.Watch watch;
    private LocalPlayerContext last;
    private BlockEntity entity;
    private int frequencyIndex;
    private long holdUntil,movingSince=-1;
    private String phase="preparing",pending, failure;
    private boolean connected,pressed,released,disconnected,done,submitted,configured,interrupted;
    private Object bindings;
    TypewriterControlSession(PhysicalControlParameters p) { this.p=p; }
    boolean tick(LocalPlayerContext ctx,PhysicalAssemblyFrame frame,BlockEntity entity,PhysicalControlHand hand,
                 BooleanSupplier approach,String call,long deadline) {
        // 暂停后原按键已经释放，恢复任务不能把剩余保持时间当成新的按下偷偷重放。
        if(interrupted)throw new IllegalStateException("打字机输入已被打断并收尾，请根据已完成效果重新选择控制意图");
        this.last=ctx;this.entity=entity;
        if(pressed)observeFeedback(ctx,frame);
        if(action!=null) {
            action=ctx.actions().poll(ctx,action);
            if(!action.terminal())return false;
            if(action.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED)
                throw new IllegalStateException("打字机原生请求未确认，不重放: "+action.detail());
            effects.add(Map.of("action",pending,"confirmation_scope",pending.equals("bind")?"server_synced_bindings":
                    pending.equals("connect")?"native_block_acknowledgement":"packet_dispatch_only"));
            action=null;if(watch!=null){watch.close();watch=null;}
            if(pending.equals("connect"))connected=true;
            if(pending.equals("bind")){configured=true;phase="disconnecting";}
            if(pending.equals("press")){holdUntil=ctx.level().getGameTime()+p.typewriterInput().holdTicks();phase="holding";}
            if(pending.equals("release"))phase="disconnecting";
            if(pending.equals("disconnect")){NativeTypewriterControl.detachClient(entity);done=true;phase="done";}
        }
        if(done)return true;
        if(phase.equals("disconnecting")) {
            if(!disconnected){dispatch(ctx,"disconnect",List.of(NativeTypewriterControl.disconnect(entity)));disconnected=true;}
            return false;
        }
        if(connected) {
            if(!NativeTypewriterControl.owned(entity,ctx.player())||!NativeTypewriterControl.inRange(entity,ctx.player()))
                throw new IllegalStateException("打字机使用者或原生距离改变，结束本次输入");
            if(p.requireOnboard()&&!OnboardControlApproach.supported(ctx.player(),frame))
                throw new IllegalStateException("身体已离开要求的同一载具，结束打字机输入");
            if(p.operation()!=BIND_TYPEWRITER_KEY&&!bindings.equals(NativeTypewriterControl.state(entity).get("bindings")))
                throw new IllegalStateException("运行期间按键频率改变，保留现场并释放原按键");
            if(p.operation()==BIND_TYPEWRITER_KEY) {
                int key=p.typewriterInput().keys().getFirst();
                watch=ServerBlockEntityReceipts.watch(ctx.level(),entity.getBlockPos());var expected=watch;
                submit(ctx,"bind",List.of(NativeTypewriterControl.save(entity,key,frequency[0],frequency[1])),
                        fresh->expected.advanced()&&NativeTypewriterControl.matches(entity,key,frequency[0],frequency[1])
                                ?NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING);
            } else if(!pressed) {
                // 同一批次只发送一次按下；保持时每刻检查座位、距离和绑定，不反复制造按键事件。
                dispatch(ctx,"press",p.typewriterInput().keys().stream().map(key->NativeTypewriterControl.key(entity,key,true)).toList());
                pressed=true;
            } else if(ctx.level().getGameTime()>=holdUntil&&!released) {
                dispatch(ctx,"release",p.typewriterInput().keys().stream().map(key->NativeTypewriterControl.key(entity,key,false)).toList());
                released=true;
            }
            return false;
        }
        if(p.operation()==BIND_TYPEWRITER_KEY&&!frame.stationary()) {
            if(movingSince<0)movingSince=ctx.level().getGameTime();
            if(ctx.level().getGameTime()-movingSince>200)throw new IllegalStateException("配键前需要先让载具停稳");
            return false;
        }
        if(p.operation()==BIND_TYPEWRITER_KEY&&frequencyIndex<2) {
            if(!hand.ready(ctx.player(),p.frequencyItems().get(frequencyIndex),call,deadline))return false;
            frequency[frequencyIndex++]=ctx.player().getMainHandItem().copy();return false;
        }
        if(p.operation()==BIND_TYPEWRITER_KEY&&NativeTypewriterControl.matches(entity,p.typewriterInput().keys().getFirst(),frequency[0],frequency[1])) {
            configured=true;done=true;effects.add(Map.of("action","bind","already_matched",true));return true;
        }
        if(p.operation()!=BIND_TYPEWRITER_KEY)for(int key:p.typewriterInput().keys())
            if(!NativeTypewriterControl.entries(entity).containsKey(key))throw new IllegalStateException("按键尚未配频: "+TypewriterKeyInput.name(key));
        if(!hand.ready(ctx.player(),"minecraft:air",call,deadline))return false;
        if(ControlReflection.is(ctx.player().getOffhandItem().getItem(),"com.simibubi.create.content.redstone.link.controller.LinkedControllerItem"))
            throw new IllegalStateException("副手遥控器会触发原生复制模式，请先收好遥控器");
        if(NativeTypewriterControl.inUse(entity))throw new IllegalStateException("打字机已被使用，保留现有控制会话");
        if(!approach.getAsBoolean())return false;
        if(!ctx.menus().ensureWorldVisible(ctx))return false;
        InputDriver.halt(ctx.player());InputDriver.sneak(ctx.player(),false);
        var aim=frame.aim(ctx.player(),p.position(),ctx.player().getEyePosition(),false);
        if(aim==null)return false;
        InputDriver.lookAt(ctx.player(),aim);var hit=frame.actualHit(ctx.player(),p.position(),false);
        if(hit==null||ctx.player().isShiftKeyDown()||!ctx.mutationAvailable())return false;
        bindings=NativeTypewriterControl.state(entity).get("bindings");
        pending="connect";phase="connecting";submitted=true;
        action=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,new NativeConfirmation() {
            public boolean requiresBlockAcknowledgement(){return true;}
            public Verdict observe(LocalPlayerContext fresh){return NativeTypewriterControl.owned(entity,fresh.player())?Verdict.APPLIED:Verdict.PENDING;}
        },60);
        return false;
    }
    private void dispatch(LocalPlayerContext ctx,String label,List<CustomPacketPayload> packets) {
        long tick=ctx.tickRevision();
        // 原模组不逐键同步 pressedKeys，派发证据和收端响应分开报告，不能虚构服务器已经收键。
        submit(ctx,label,packets,fresh->fresh.tickRevision()>tick?NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING);
    }
    private void observeFeedback(LocalPlayerContext ctx,PhysicalAssemblyFrame frame) {
        if(p.typewriterInput().feedbackPositions().isEmpty()||ctx.level().getGameTime()-feedbackTick<5&&feedbackTick!=Long.MIN_VALUE)return;
        feedbackTick=ctx.level().getGameTime();var sample=new ArrayList<Map<String,Object>>();
        // 每五刻读取明确点名的部件，仅保留发生变化的完整状态；缺块与未加载也作为事实返回。
        for(var offset:p.typewriterInput().feedbackPositions()) {
            var pos=frame.storage(offset);var value=new LinkedHashMap<String,Object>();
            value.put("position",List.of(offset.getX(),offset.getY(),offset.getZ()));
            if(!frame.loaded(pos))value.put("observation_unknown","unloaded");
            else {
                var component=frame.level().getBlockEntity(pos);
                if(component==null)value.put("observation_unknown","no native block entity");
                else try {value.put("actual_configuration",NativePhysicalControl.state(component));}
                catch(RuntimeException unavailable){value.put("observation_unknown",unavailable.toString());}
            }
            sample.add(Map.copyOf(value));
        }
        if(!sample.equals(previousFeedback)) {
            previousFeedback=List.copyOf(sample);
            feedback.add(Map.of("game_time",feedbackTick,"phase",phase,"components",previousFeedback));
        }
    }
    private void submit(LocalPlayerContext ctx,String label,List<CustomPacketPayload> packets,NativeConfirmation confirmation) {
        pending=label;phase=label;submitted=true;
        action=ctx.actions().submitControlProtocol(ctx,"linked typewriter "+label,
                ()->packets.forEach(packet->send(ctx,packet)),confirmation,60);
    }
    void close() {
        if(watch!=null){watch.close();watch=null;}
        if(last==null)return;
        var fresh=ClientRuntime.actor().activeContext().filter(ctx->ctx.isCurrent()&&ctx.player()==last.player()&&ctx.level()==last.level()).orElse(null);
        if(fresh!=null)last=fresh;
        if(last.minecraft().player!=last.player()||last.minecraft().level!=last.level()||last.player().connection==null) {
            if(pressed&&!released)failure="玩家或世界已离开，松键请求未发出；由原生断线收尾";
            return;
        }
        if(fresh!=null&&action!=null&&!action.terminal())fresh.actions().retireOneShotForTaskBoundary(fresh,action,"打字机会话结束");
        // 中断时只松开本会话发过的键，并退出自己的原生会话；从不改油门、频率或其他玩家的控制。
        try {
            if(pressed&&!released) {
                p.typewriterInput().keys().forEach(key->send(last,NativeTypewriterControl.key(entity,key,false)));
                released=true;effects.add(Map.of("action","release_on_interrupt","confirmation_scope","packet_dispatch_only"));
            }
            if(!disconnected&&(connected||pending!=null&&pending.equals("connect"))&&NativeTypewriterControl.owned(entity,last.player())) {
                send(last,NativeTypewriterControl.disconnect(entity));disconnected=true;
                effects.add(Map.of("action","disconnect_on_interrupt","confirmation_scope","packet_dispatch_only"));
            }
            if(connected||disconnected)NativeTypewriterControl.detachClient(entity);
        } catch(RuntimeException unknown){failure="退出控制请求未确认: "+unknown.getMessage();}
    }
    // 让位给其他任务或人工暂停时立即松开远程按键，不能只停止角色走路而留下仍通电的舵机。
    void interrupt(){interrupted=true;close();}
    private static void send(LocalPlayerContext ctx,CustomPacketPayload packet){ctx.connection().send(new ServerboundCustomPayloadPacket(packet));}
    String phase(){return phase;}
    Map<String,Object> evidence() {
        var out=new LinkedHashMap<String,Object>();
        out.put("native_submitted",submitted);out.put("completed_effects",List.copyOf(effects));
        out.put("native_configuration_confirmed",configured);out.put("key_state_confirmed",false);
        out.put("requested_keys",p.typewriterInput().keys().stream().map(TypewriterKeyInput::name).toList());
        out.put("requested_hold_ticks",p.typewriterInput().holdTicks());out.put("release_dispatched",released);
        out.put("disconnect_dispatched",disconnected);if(failure!=null)out.put("cleanup_unknown",failure);
        out.put("input_interrupted",interrupted);
        out.put("observed_feedback",List.copyOf(feedback));
        return out;
    }
}
