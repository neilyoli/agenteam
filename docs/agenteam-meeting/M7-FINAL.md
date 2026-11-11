# M7 最终汇总：需求追踪、架构合规与发布结论

- 分支：`feature/agenteam-meeting-plugin`
- 提交：`bd912c6`（M7-A）→ `c90d4cd`（M7-B）→ `d24c9f9`（M7-C）→ `bbf61f5`（M7-D）
- 结论：M7-A 至 M7-D 代码与自动化测试（114 项）已完成；M7-E 真实飞书租户验收未执行。
  本插件**不宣称可生产发布**，交付为待审 PR，不自动合并、不创建 Tag/Release、不修改 main。

## 1. 需求追踪（ADR → 实现）

| 编号 | 决策 | 落地实现 | 阶段 |
| --- | --- | --- | --- |
| MEETING-ADR-001 | JavaToolPlugin，标识 agenteam.meeting | `agenteam-plugin-meeting` 模块，PF4J 普通 JAR | M1 |
| MEETING-ADR-002 | 长期连接/事件由宿主承接 | `MeetingEventIngressService`（事件路由、去重、租约） | M2 |
| MEETING-ADR-003 | 复用 AgentScope Harness 运行链路 | `RunSubmissionService.meetingEvent` + `MeetingRunExecutionPolicy`（meeting_event Run 专用授权） | M7-A |
| MEETING-ADR-004 | 企业统一飞书 Base：会议主表 + 督办宽表 | `MeetingSyncCommandService` + Binding（不按会议建 Base） | M4 |
| MEETING-ADR-005 | 飞书 Base 是任务业务状态唯一事实来源 | MySQL 仅存技术身份/关系/审计（`meeting_task_identity`/`meeting_task_source`/`meeting_confirmation`/Outbox） | M7-B |
| MEETING-ADR-006 | Binding + 字段白名单 + 类型转换 + Versioned Outbox | `MeetingSyncCommandService.bindingMapping` + `MeetingSyncOutbox`（versioned, claim_token/lease_until 栅栏） | M4 |
| MEETING-ADR-007 | Agent+Skill 决定分析，Java Validator 守住约束 | `meeting-intelligence` Skill + `MeetingTaskPublicationService` 宿主二次校验 | M7-A/D |
| MEETING-ADR-008 | 初期一个 Meeting Agent；Skill 以平台原生资源安装绑定 | `BuiltinSkillResourceService` 自动导入/发布/绑定两个技能 | M7-D |
| MEETING-ADR-009 | 真实负责人经飞书卡片确认 | `MeetingConfirmationDeliveryService` + `MeetingConfirmationCallbackService`（可信回调，不接受模型指定身份） | M7-C |
| MEETING-ADR-010 | 定时督办复用宿主 Schedule | `MeetingFollowUp*`（分类器/策略/事务/扫描作业） | M6 |

## 2. 架构合规审计（对照桥接审计 §6「当前不应做的事」）

| 约束 | 合规证据 |
| --- | --- |
| 不允许全局 FULL_ACCESS / 全局 AUTO_APPROVE | 会议服务包内无 `FULL_ACCESS`/`AUTO_APPROVE` 引用（grep 零命中）；会议写仅经 `MeetingRunExecutionPolicy` 逐项放行 |
| 会议事件 Run 只能调用指定会议写工具 | `ExecutionToolCatalog`（meeting_event Run 只暴露 read + `meeting_task_publish`）；`MeetingRunExecutionPolicyTest`(7) + `ExecutionToolCatalogMeetingTest`(3) |
| 模型不得指定 enterpriseId/userId/bindingId/runId/commandId/Base/Table | `meeting_task_publish` 工具 Schema 不含这些字段；`MeetingTaskPublicationService` 从宿主上下文构造身份与命令 |
| 模型不得绕过宿主字段白名单 | `MeetingSyncCommandService.bindingMapping` 过滤，未映射字段不投递；`MeetingBitableFieldValidatorTest` |
| Java 会议插件不得直接写飞书 | 插件 8 个工具均声明 read，无数据库/宿主服务直连 |
| 不用标题+负责人哈希作长期 task_key | `MeetingTaskIdentityService.resolve` 持久化稳定身份（M3 哈希算法已替换） |
| 不在任务同步事务里同步发卡 | `MeetingConfirmationDeliveryService` 独立对账扫描（delivered_version 推进后才发卡） |
| 不以自动化测试冒充真实 E2E | `M7-ACCEPTANCE.md` A19 及现场项明确 ❌ 未执行 |
| 不在真实租户验收前宣称生产可发布 | 本文及验收报告结论一致：**不可宣称可生产发布** |

## 3. 目标调用链闭环状态

```text
飞书会议事件 → 宿主验权/过滤(meetingSource==1)/Inbox 去重          ✅ M2/M7-D
  → 订阅固定 Meeting Agent 创建 meeting_event Run                   ✅ M7-A
  → meeting-intelligence Skill + 只读工具                           ✅ M7-D
  → Agent 提取候选/负责人/证据                                       ✅ M7-A
  → meeting_task_publish（MeetingRunExecutionPolicy）               ✅ M7-A
  → Host Validator + 稳定 task_key + Binding 白名单                 ✅ M7-A/M7-B
  → MeetingSyncCommandService → Versioned Outbox/Worker             ✅ M4
  → delivered_version → 独立确认卡片 Outbox                          ✅ M7-C
  → 负责人飞书卡片 → 可信回调 → task_status 字段级写回               ✅ M7-C
  → meeting.follow_up Schedule 扫描与限频提醒                        ✅ M6
  → 企业统一飞书 Base 看板                                          ⚠️ 待真实租户
```

除最后一步依赖真实飞书 Base 与真实事件外，其余环节均已由自动化测试覆盖。

## 4. 发布结论

- 自动化测试 114 项全部通过，代码实现与需求追踪、安全约束一致。
- 真实飞书租户 E2E（A19）及故障恢复/权限/升级演练未执行，无真实凭据与真实事件，未以 mock 冒充。
- 交付为单个 PR 供审核，不自动合并、不创建 Tag/Release、不修改 main。
- 后续按 [M7-ACCEPTANCE.md](M7-ACCEPTANCE.md) §4 未执行项清单在真实租户闭环并回填证据后，
  方可评估生产发布。
