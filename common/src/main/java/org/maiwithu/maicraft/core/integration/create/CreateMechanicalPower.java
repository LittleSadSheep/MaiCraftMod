// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.core.integration.create.transmission.EconomicKineticTaskRecord;

/**
 * 机械动力连接的入口：调用者给出两个语义位置，模块自己找真实接口、调查路线、补料并逐格安装。
 * AUTO 比较完整传动方案的材料和建造成本；显式封装链传动与旧续接凭据保留原执行器。
 */
public final class CreateMechanicalPower {
    public static final String CHAIN_DRIVE_ID = "create:encased_chain_drive";

    public enum Transmission { AUTO, ENCASED_CHAIN_DRIVE }

    /** 锚点通常会扩展为实时证据；exactFace 用于绑定现有动力接口。 */
    public record Endpoint(String name, BlockPos center, Direction exactFace) {
        public Endpoint(String name, BlockPos center) { this(name, center, null); }
        public Endpoint {
            name = Objects.requireNonNull(name, "name").trim();
            center = Objects.requireNonNull(center, "center").immutable();
            if (name.isEmpty()) throw new IllegalArgumentException("endpoint name is blank");
        }
    }

    /**
     * 语义请求。{@code allowFreeReceiver} 为 true 时，最近的权威目标证据可以是兼容机器或已核实的空接收端；
     * 为 false 时则必须存在真实可见的动力目标。两种模式都不会替换现有方块。
     */
    public record Request(
            Endpoint source,
            Endpoint destination,
            Transmission transmission,
            boolean preserveExisting,
            boolean allowFreeReceiver) {
        public Request {
            source = Objects.requireNonNull(source, "source");
            destination = Objects.requireNonNull(destination, "destination");
            transmission = transmission == null ? Transmission.AUTO : transmission;
        }

        public static Request preserving(Endpoint source, Endpoint destination) {
            return new Request(source, destination, Transmission.AUTO, true, false);
        }
    }

    /** 检查可选动力 API 是否存在，不在编译期链接该 API。 */
    public record Availability(boolean available, String detail) {}

    private CreateMechanicalPower() {}

    /** 客户端初始化时强制注册任务执行器；可重复调用。 */
    public static void install() {
        TaskFactory.register(CreateMechanicalPowerTaskRecord.class,
                CreateMechanicalPowerTask::new);
    }

    public static Availability availability() {
        return CreateKineticsBridge.availability();
    }

    /** 释放不会继续使用的不透明暂停搜索回执或已确认前缀回执。 */
    public static void discardContinuation(UUID token) {
        if (token == null) return;
        CreateEndpointContinuations.discard(token);
        CreateMechanicalContinuations.discard(token);
    }

    /** 创建新的语义连接任务。 */
    public static TaskRecord task(String callId, long deadlineGameTime, Request request) {
        return task(callId, deadlineGameTime, request,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY,
                List.of(), false, List.of());
    }

    public static TaskRecord task(
            String callId,
            long deadlineGameTime,
            Request request,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        install();
        Objects.requireNonNull(request, "request");
        if (request.transmission() == Transmission.AUTO && !request.allowFreeReceiver()) {
            var player = Minecraft.getInstance().player;
            if (player == null) throw new IllegalArgumentException("mechanical_connection_requires_live_player");
            if (CreateEconomicEndpointBridge.direct(player.level(), request)) return new EconomicKineticTaskRecord(
                    callId,deadlineGameTime,player.level().dimension().location().toString(),request.source().name(),
                    request.source().center(),request.source().exactFace(),request.destination().name(),
                    request.destination().center(),request.destination().exactFace(),null,0,64,false,
                    materialPolicy,protectedLabels,allowedSources,allowHarm);
        }
        return new CreateMechanicalPowerTaskRecord(callId, deadlineGameTime,
                Objects.requireNonNull(request, "request"), null, materialPolicy,
                allowedSources, allowHarm, protectedLabels);
    }

    /**
     * 继续执行已精确确认的前缀。回执未知、过期、来自不同角色或世界已改变时，均返回结构化任务失败结果，绝不会重放先前的放置动作。
     */
    public static TaskRecord resumeTask(
            String callId, long deadlineGameTime, Request request, UUID continuationToken) {
        return resumeTask(callId, deadlineGameTime, request,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.ORDINARY,
                List.of(), false, List.of(), continuationToken);
    }

    /**
     * 在不放宽原有语义材料与安全策略的前提下，继续执行精确前缀。此不透明回执由语义父任务提供，不由模型规划。
     */
    public static TaskRecord resumeTask(
            String callId,
            long deadlineGameTime,
            Request request,
            SemanticMaterialSupplyCoordinator.MaterialPolicy materialPolicy,
            List<SemanticAcquireTaskRecord.Source> allowedSources,
            boolean allowHarm,
            List<String> protectedLabels,
            UUID continuationToken) {
        install();
        return new CreateMechanicalPowerTaskRecord(callId, deadlineGameTime,
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(continuationToken, "continuationToken"),
                materialPolicy, allowedSources, allowHarm, protectedLabels);
    }

    /** 在客户端线程勘查已加载区域，供界面和预检使用；绝不修改世界。 */
    public static Survey survey(LocalPlayer player, Request request) {
        Objects.requireNonNull(player, "player");
        CreateMechanicalPlan.Result result = CreateMechanicalPlanner.plan(player, request);
        if (result.plan() != null) {
            CreateMechanicalPlan plan = result.plan();
            return new Survey(true, "ready", plan.source().position(), plan.destinationPosition(),
                    plan.cells().size(), plan.routeHash(), result.facts());
        }
        return new Survey(false, result.failureCode(), null, null, 0, null, result.facts());
    }

    public record Survey(
            boolean ready,
            String status,
            BlockPos source,
            BlockPos destination,
            int requiredChainDrives,
            String routeFingerprint,
            Map<String, Object> facts) {}
}
