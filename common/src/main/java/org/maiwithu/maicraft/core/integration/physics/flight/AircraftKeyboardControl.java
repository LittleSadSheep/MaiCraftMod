package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.task.physics.NativeTypewriterControl;
import org.maiwithu.maicraft.core.task.physics.PhysicalStructureApproach;
import org.maiwithu.maicraft.entity.InputDriver;

/** 一次原生认领后由 Mod 持续发送按键差量，驾驶员始终留在同一座椅；不是不断建立手动保持任务。 */
public final class AircraftKeyboardControl {
    private final DriverStation station;
    private final BlockPos position;
    private final Set<Integer> configured;
    private final List<Map<String,Object>> effects=new ArrayList<>();
    private Set<Integer> pressed=Set.of();
    private Map<Integer,Object> frequencies;
    private NativeActionReceipt receipt;
    private LocalPlayerContext last;
    private BlockEntity entity;
    private boolean connecting,connected,closed,uncertain;
    public AircraftKeyboardControl(BlockPos seat,BlockPos position,Set<Integer> configured) {
        station=new DriverStation(seat,List.of(position));this.position=position.immutable();this.configured=Set.copyOf(configured);
    }
    public boolean apply(LocalPlayerContext ctx,SableStructureBridge.Structure structure,Set<Integer> desired) {
        if(closed)throw new IllegalStateException("flight keyboard session already closed");
        last=ctx;
        if(!station.seated(ctx.player()))throw new IllegalStateException("native driver seat relationship ended");
        if(!structure.isLoaded(position))throw new IllegalStateException("flight typewriter is not loaded");
        BlockEntity current=ctx.level().getBlockEntity(position);
        if(!ControlReflection.is(current,NativeTypewriterControl.TYPE)||entity!=null&&current!=entity)
            throw new IllegalStateException("flight typewriter changed");
        entity=current;
        if(!configured.containsAll(desired))throw new IllegalArgumentException("flight requested an undeclared key");
        Map<Integer,Object> now=frequencies(entity);
        if(!now.keySet().containsAll(configured))throw new IllegalStateException("flight key binding missing");
        if(frequencies==null)frequencies=now;
        if(!frequencies.equals(now))throw new IllegalStateException("flight typewriter frequencies changed");
        if(!NativeTypewriterControl.inRange(entity,ctx.player()))throw new IllegalStateException("driver outside native typewriter range");
        if(receipt!=null) {
            receipt=ctx.actions().poll(ctx,receipt);
            if(!receipt.terminal())return false;
            if(receipt.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED){uncertain=true;throw new IllegalStateException(receipt.detail());}
            receipt=null;
            if(connecting){connecting=false;connected=true;effects.add(Map.of("action","connect","scope","native_block_acknowledgement"));}
        }
        InputDriver.halt(ctx.player());
        if(!connected) {
            if(NativeTypewriterControl.inUse(entity))throw new IllegalStateException("typewriter already has an active user");
            if(!ctx.player().getMainHandItem().isEmpty()||ControlReflection.is(ctx.player().getOffhandItem().getItem(),
                    "com.simibubi.create.content.redstone.link.controller.LinkedControllerItem"))
                throw new IllegalStateException("empty main hand and no offhand copying controller required");
            if(!ctx.menus().ensureWorldVisible(ctx))return false;
            var aim=PhysicalStructureApproach.visibleAim(ctx.player(),structure,position,ctx.player().getEyePosition());
            if(aim==null)throw new IllegalStateException("no visible typewriter surface from the driver seat");
            InputDriver.sneak(ctx.player(),false);InputDriver.lookAt(ctx.player(),aim);
            var hit=DriverStation.hit(ctx.player(),structure,position);
            if(hit==null||ctx.player().isShiftKeyDown()||!ctx.mutationAvailable())return false;
            connecting=true;
            receipt=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,new NativeConfirmation() {
                public boolean requiresBlockAcknowledgement(){return true;}
                public Verdict observe(LocalPlayerContext fresh){return NativeTypewriterControl.owned(entity,fresh.player())?Verdict.APPLIED:Verdict.PENDING;}
            },60);
            return false;
        }
        if(!NativeTypewriterControl.owned(entity,ctx.player()))throw new IllegalStateException("typewriter user changed during flight");
        if(pressed.equals(desired))return true;
        if(!ctx.mutationAvailable())return false;
        Set<Integer> next=Set.copyOf(desired);
        List<Integer> up=pressed.stream().filter(k->!next.contains(k)).sorted().toList();
        List<Integer> down=next.stream().filter(k->!pressed.contains(k)).sorted().toList();
        long tick=ctx.tickRevision();
        var sentUp=new ArrayList<Integer>();var sentDown=new ArrayList<Integer>();
        // 换舵向时先释放旧键；不重发持续按住的键，也不将键包派发冒充收端或飞行成功。
        receipt=ctx.actions().submitControlProtocol(ctx,"aircraft key transitions",()->{
            for(int key:up) {
                send(ctx,NativeTypewriterControl.key(entity,key,false));sentUp.add(key);
                var held=new LinkedHashSet<>(pressed);held.remove(key);pressed=Set.copyOf(held);
            }
            for(int key:down) {
                send(ctx,NativeTypewriterControl.key(entity,key,true));sentDown.add(key);
                var held=new LinkedHashSet<>(pressed);held.add(key);pressed=Set.copyOf(held);
            }
        },fresh->fresh.tickRevision()>tick?NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING,40);
        // 原生入口拒绝或发送途中失败时只记实际派发的部分，未发出的变化不能列作已完成输入。
        if(!sentUp.isEmpty()||!sentDown.isEmpty())effects.add(Map.of("game_tick",ctx.level().getGameTime(),
                "released",List.copyOf(sentUp),"pressed",List.copyOf(sentDown),"scope","packet_dispatch_only"));
        return false;
    }
    private Map<Integer,Object> frequencies(BlockEntity source) {
        var result=new LinkedHashMap<Integer,Object>();
        NativeTypewriterControl.entries(source).forEach((key,entry)->{
            if(configured.contains(key))result.put(key,ControlReflection.call(entry,"getNetworkKey"));
        });
        return Map.copyOf(result);
    }
    /** 到地面收尾或人工接管时释放本租约；普通飞行取消应先由飞控完成降落，再调用此方法。 */
    public void close() {
        if(closed)return;closed=true;
        if(last==null)return;
        var fresh=ClientRuntime.actor().activeContext().filter(ctx->ctx.isCurrent()&&ctx.player()==last.player()&&ctx.level()==last.level()).orElse(null);
        if(fresh!=null)last=fresh;
        if(last.minecraft().player!=last.player()||last.minecraft().level!=last.level()||last.player().connection==null){uncertain=connected;return;}
        if(receipt!=null&&fresh!=null&&!receipt.terminal())fresh.actions().retireOneShotForTaskBoundary(fresh,receipt,"flight control ended");
        try {
            for(int key:pressed)send(last,NativeTypewriterControl.key(entity,key,false));
            if((connected||connecting)&&NativeTypewriterControl.owned(entity,last.player()))send(last,NativeTypewriterControl.disconnect(entity));
            if(connected||connecting)NativeTypewriterControl.detachClient(entity);
            effects.add(Map.of("action","release_and_disconnect","released",pressed.stream().sorted().toList(),"scope","packet_dispatch_only"));
            pressed=Set.of();
        } catch(RuntimeException unavailable){uncertain=true;}
    }
    private static void send(LocalPlayerContext ctx,CustomPacketPayload packet){ctx.connection().send(new ServerboundCustomPayloadPacket(packet));}
    public boolean connected(){return connected&&!closed;}
    public boolean effectsStarted(){return connected||connecting||!effects.isEmpty();}
    public boolean uncertain(){return uncertain;}
    public Map<String,Object> evidence(){return Map.of("connected",connected(),"pressed_keys",pressed,"closed",closed,"uncertain",uncertain,"native_effects",List.copyOf(effects));}
}
