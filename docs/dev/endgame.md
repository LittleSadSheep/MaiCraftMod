# 末影龙战斗与鞘翅获取

本文覆盖 `maicraft:defeat_ender_dragon` 和 `maicraft:obtain_elytra`。它们按当前身体与末地现场执行；进入末地、准备传送门和跨阶段编排由其他能力负责。能力字段均放 `goal.parameters`。

## 杀龙：从已观察到的战斗开始

开始时必须身处末地，并且加载范围内恰有一条活的原版末影龙。未加载、已经死亡或存在多条候选都不会当作已完成杀龙。它不会替调用者复活龙，也不承诺适配所有模组 Boss 机制。

| 参数 | 当前规则 |
| --- | --- |
| `allow_combat` | 必须显式 `true`；缺少或为 `false` 时适配器先返回决策，不开始战斗 |
| `may_alter_terrain` | 默认 `false`；允许为当前已核实的铁栏水晶笼打开通路 |
| `minimum_health` | 默认 10 生命值，即原版 5 颗心；有限数值 1–1024，且不能高于身体当前最大生命；低于阈值退出攻击并恢复 |
| `protected_labels` | 已记住的保护地点标签，至多 64 项；无法解析时报告未知，不能猜测保护范围 |

```json
{
  "goal": {
    "ability": "maicraft:defeat_ender_dragon",
    "outcome": "完成当前末影龙战斗并核实真实死亡",
    "parameters": {"allow_combat": true, "minimum_health": 10}
  }
}
```

以上为 `plan` 请求。执行时依次观察塔区、水晶与龙，补查未加载证据，处理水晶和铁笼，再攻击龙。危险龙息、身体位置或生命条件触发恢复；恢复中的吃饭也会为新的龙息危险让路。

最终 `CONFIRM` 单独检查龙死亡／移除，并要求死亡阶段或出口传送门变化作为佐证，持续稳定后才能成功。目标暂时消失不能冒充胜利，确认超时返回 `dragon_death_unconfirmed`。

回执包括 `phase`、已观察及已摧毁水晶数量、未解决水晶、笼子开口、`dragon_state`、塔区观察覆盖、恢复和消耗次数、死亡／移除／出口证据。停止时保留 `decision.reason_code` 与 `recovery_options`，不抹掉已经发生的攻击和拆笼效果。

## 鞘翅：以进入主背包为完成条件

如果主背包已经有鞘翅，目标直接完成；否则身体必须在末地。位于主岛时先准备珍珠、寻找可见折跃门并确认穿越，已在外岛时直接搜索末地城和末地船。

| 参数 | 当前规则 |
| --- | --- |
| `max_search_distance` | 外岛搜索范围，默认 2048 格，支持 128–4096 格 |
| `may_alter_terrain` | 默认 `false`，允许原生搭桥、垫高和清障；有保护标签时现有执行器会收紧地形修改 |
| `allow_combat` | 默认 `false`；允许处理阻挡推进的已加载敌对目标，缺珠时也会向补料子任务开放狩猎来源 |
| `allow_rare_consumables` | 默认 `false`；原生投珠穿越折跃门前必须允许真实珍珠消耗 |
| `protected_labels` | 已登记的保护地点，至多 64 项；未知标签失败并交付原因 |

```json
{
  "goal": {
    "ability": "maicraft:obtain_elytra",
    "outcome": "从末地船取得鞘翅并放入主背包",
    "parameters": {"max_search_distance": 2048, "allow_rare_consumables": true}
  }
}
```

主岛流程为：确认珍珠 -> 寻找折跃门 -> 到可投掷站位 -> 原生投珠 -> 核对同维度突变位移。珍珠已消耗与传送已确认分别记账；结果不确定时保留事实并返回恢复选项，不盲目再投一颗。

外岛流程为：有界搜索末地城 -> 观察末地船与带鞘翅的展示框 -> 确认可操作后破框 -> 跟踪并拾取掉落。看到城市、看到展示框或完成一次攻击，都不等于鞘翅入包；每刻都以真实主背包中的鞘翅数量检查最终目标。

回执用 `gateway_verified`、`end_city_evidence`、`ship_frame_verified` 区分各阶段事实，`elytra_count` 报告实际入包数量；`consumed` 保留珍珠消耗，`search_budget` 保留搜索尝试，`recovery_options` 给出未完成原因及可选恢复。没有找到城市只能说明当前有界搜索未命中。

## 暂停、中断与边界

两条链都持有内部移动、攻击或收集子任务。暂停、取消、死亡及换世界通过任务生命周期释放身体输入并结算子任务，已发生的消耗或拆改不回滚。恢复必须重新检查活体、门、展示框和背包证据，不能把保存的“正在攻击”当作仍握有有效目标。

`target` 的区域或前步位置不会让这两个执行器自动旅行到指定地区；它们实际从当前末地现场开始。需要远程到场时先执行旅行。内部杀龙工具有稀有消耗开关，当前公开杀龙契约没有声明和透传它，不能照搬内部参数到公开请求。

## 贡献者代码地图

| 想看什么 | 实现入口 |
| --- | --- |
| 公开参数如何变成内部任务 | [AbilityAdapter.defeatEnderDragon / obtainElytra](../../common/src/main/java/org/maiwithu/maicraft/intent/AbilityAdapter.java) |
| 杀龙生命周期与分阶段原生确认 | [DragonFightCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/endgame/DragonFightCompanionTask.java) 的 `onStart/onTick/tickConfirm/resultData` |
| 杀龙阈值与保护标签约束 | [DragonFightTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/endgame/DragonFightTaskRecord.java) |
| 珍珠、折跃、找船和入包 | [SemanticElytraCompanionTask](../../common/src/main/java/org/maiwithu/maicraft/core/task/endgame/SemanticElytraCompanionTask.java) 的 `acquirePearl/waitForTeleport/findShipFrame/collectElytra` |
| 鞘翅搜索范围与任务记录 | [SemanticElytraTaskRecord](../../common/src/main/java/org/maiwithu/maicraft/core/task/endgame/SemanticElytraTaskRecord.java) |

本轮只按现有源码整理契约、文档和中文状态注释；没有运行回归或启动游戏。对模组龙、不同末地生成和原生回执时序的兼容，需要在对应环境另行验证。
