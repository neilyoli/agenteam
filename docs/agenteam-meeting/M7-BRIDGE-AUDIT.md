# M7 桥接审计与收尾实施方案

- 审计分支：`feature/agenteam-meeting-plugin`
- 审计基线：`44b5f33a0413a6547f2932a0d6ff7bd911e4b942`
- 审计范围：Agent 工具调用、会议事件 Run、任务发布、飞书 Outbox、负责人确认、任务身份、Skill 与真实验收。
- 本文性质：代码审计与实施决策，不代表下述缺口已修复；本次不修改生产代码和数据库迁移。

## 1. 结论

M0–M6 的主要组件已存在，M7 自动化测试报告记录 62 项通过；但当前代码尚未形成“会议事件 → Agent 分析 → 任务写入 → 负责人确认卡片”的可运行闭环。关键问题不是缺少一个飞书 API，而是 **Agent 到宿主受控写命令的桥接、非交互 Run 的写入授权、任务身份模型，以及同步成功后的卡片投递** 尚未闭合。

当前不能宣称生产可用。真实租户 A19 仍未执行，见 `M7-ACCEPTANCE.md`。

## 2. 代码审计发现

### M7-A：Agent 没有任务发布入口（阻断）

已确认：

- `agenteam-plugin-meeting` 中的 8 个工具均声明为 `read`，包括 `meeting_search`、`minutes_get`、`task_validate`、`task_consolidate`。
- `MeetingSyncCommandService.publish()` 已实现字段白名单、Binding 映射和 Outbox 入队。
- 该方法目前没有接入会议 Agent 的工具调用路径；`MeetingSyncApiController` 只提供 Outbox 查询和重试。
- `PlatformBusinessTools` 已支持通过 `PlatformToolHandler` 扩展平台业务工具，`PlatformBuiltinPlugins` 可注册平台内置插件；当前没有会议任务发布 Handler/Adapter。

**决策：** 不给隔离的 Java 会议插件增加数据库或宿主服务直连能力。增加宿主内置业务工具（建议 builtinCode=`meeting_management`，工具名 `meeting_task_publish`），由 `PlatformToolHandler` 调用宿主会议命令服务，再调用 `MeetingSyncCommandService.publish()`。Agent 同一轮执行可以组合使用 Java 会议只读工具和宿主会议写工具。

工具参数只接收业务候选，不接受模型指定的 enterpriseId、actorId、bindingId、app_token、table_id、飞书字段名、commandId 或 runId。身份、Run、operationId、集成和 Binding 均从宿主执行上下文及数据库取得。

宿主必须重新执行任务发布校验，不能把插件的 `task_validate` 结果当作授权凭证：L1/L2 必须具备真实负责人、可定位证据和截止日期；L3 不进入督办；负责人歧义、证据缺失或字段未映射时拒绝发布或进入复核。

### M7-A.1：会议事件 Run 当前不能安全地自动写入（阻断）

当前 `RunSubmissionService.meetingEvent()` 将 Run mode 设为 `meeting_event`。现有执行策略存在两道限制：

1. `ToolApprovalPolicy.forRun()` 对非 `interactive` Run 返回 DEFAULT；DEFAULT 对 write 工具要求审批。
2. `PlatformBuiltinInvocation.write()` 只允许 `interactive` Run，其他 mode 返回 `PLATFORM_WRITE_MODE_UNSUPPORTED`。
3. `ExecutionToolCatalog` 还对非交互 Run 过滤现有平台写工具。

因此，即使新增 `meeting_task_publish`，也不能简单依靠当前通用写工具路径完成无人值守的会议事件发布。

**决策：** 增加专用 `MeetingRunExecutionPolicy`，只允许满足全部条件的 `meeting_event` Run 调用指定会议发布工具：
- Run 的 `sourceType=meeting`，且其 meetingEventId 对应的收件箱记录已关联当前 Run；
- 事件仍属于当前企业和飞书集成，订阅仍启用，订阅配置者仍具备执行资格；
- 当前会议插件版本、Agent 版本和 task Binding 均与订阅/Run 固定快照匹配；
- 只放行会议任务发布这一项受控写操作，其他 Todo、Schedule、MCP、Java 插件写操作仍不可用；
- 每次提交继续检查 Run lease、取消状态、企业权限、配额、字段白名单和命令幂等。

不能把整个 meeting_event Run 全局设置成 AUTO_APPROVE，否则会扩大其他写工具的权限。普通用户在 Chat 中主动发起的会议分析，继续沿用原有 Tool Approval 策略。

### M7-B：任务身份与原始架构决策不一致（高优先级）

当前 `TaskKeyService` 使用“规范化标题 + 负责人 openId”的 SHA-256 生成 task_key；`TaskConsolidationService` 也按该值分组。

这与 `docs/agenteam-meeting-plan.md` §5.3 不一致。已确认的目标是：任务身份不能依赖可变标题或负责人；跨会议再次提及同一任务应关联已有 task_key，不能因改名或换负责人生成新任务身份。

当前算法存在：
- 同标题、同负责人但业务上不同的任务可能错误合并；
- 标题或负责人变化会改变 task_key；
- 不能可靠表达首次创建任务身份及跨会议引用关系。

**决策：** task_key 改为宿主首次创建时分配并持久化的稳定身份。新增/调整技术身份记录（建议企业隔离的 meeting_task_identity），在创建 Outbox 命令前与业务命令同事务保存。Agent 只能通过受控查询返回的既有 task_key 关联历史任务；相似度不足以自动合并，无法判断时转人工复核。飞书「任务ID」列仍作为外部唯一键。

### M7-C：同步成功后没有自动进入负责人确认（阻断）

已确认：

- `MeetingConfirmationService.publish()` 能创建确认轮次并发送卡片。
- `MeetingConfirmationCallbackService` 已接入 `ChatCardInteractionService`，回调身份、任务版本、消息 ID 和事件去重校验已有代码。
- `MeetingSyncTransactions` 当前没有注入或调用 `MeetingConfirmationService`；同步 Worker 只完成 Bitable 对账、创建/更新和 Outbox 状态推进。
- 因此，任务首次同步成功后不会自动触发 `MeetingConfirmationService.publish()`。

此外，`meeting_confirmation` 目前保存 card_message_id/card_command_id，但没有独立的卡片投递状态、领取租约、失败退避和重试状态。直接在任务同步 Worker 中发送卡片，会把“任务同步成功”和“卡片发送失败”耦合成同一个重试结果；不能这样处理。

**决策：** 任务 Outbox 达到 delivered_version 后，独立创建确认卡片投递任务。可扩展 `meeting_confirmation` 的卡片投递字段，或建立独立 confirmation outbox。卡片命令 ID 必须按 binding + task_key + task_version 稳定生成，重试复用同一 ID；卡片发送失败只重试卡片，不重新创建任务。仅在 ownerUserId 和 ownerSubject 均已可靠解析时发送，否则标记 unresolved，不得向未绑定成员发出无法确认归属的卡片。

### M7-D：事件、资料就绪和 Skill 接入差距

- `MeetingEventNormalizer` 校验事件前缀、app_id 和 tenant_key，但未实现 M7 校准文档要求的 `meetingSource == 1` 过滤；event_id 为空时也未拒绝。
- `MeetingEventTransactions` 创建 Run 后即将事件标记 routed。Skill 可以发现纪要尚未就绪，但当前没有完整的持久等待/恢复机制，不能保证资料稍后就绪后自动续跑。
- `meeting-intelligence` 和 `meeting-follow-up` 仍是 docs 下的 Skill 参考文件，插件安装不会自动导入、发布和绑定。
- `MeetingApiClient.encode()` 当前只是原样返回输入；查询参数和路径参数需进行正确 URL 编码，避免 ISO 时间、分页游标或特殊字符破坏请求。

**决策：** 补齐事件过滤与 event_id 校验；资料未就绪时建立持久等待状态，并由资料就绪事件或有界 Schedule 恢复，不在 Worker 内忙等；Skill 通过平台资源正式导入、发布并绑定到 Meeting Agent；补齐 API 参数编码测试。

### M7-E：跨会议上游检索尚未实现

`task_consolidate` 只能比较调用方传入的 upstreamTaskKeys 与候选 upstreamTaskRefs，不会主动查询历史会议和任务。需要增加受控只读工具（建议 `meeting_task_search` / `meeting_upstream_search`），读取飞书最新任务状态与来源证据；不能将“没有查到”自动解释为“未覆盖”。

## 3. 目标调用链

```text
飞书会议事件
  → 宿主验权/过滤/Inbox 去重
  → 订阅固定的 Meeting Agent 创建 meeting_event Run
  → meeting-intelligence Skill + agenteam.meeting 只读工具
  → Agent 提取候选、负责人和证据
  → 宿主 meeting_task_publish（专用 MeetingRunExecutionPolicy）
  → Host Validator + 稳定 task_key + Binding 白名单
  → MeetingSyncCommandService
  → Versioned Outbox / MeetingSyncWorker
  → 确认 delivered_version
  → 独立确认卡片 Outbox
  → 负责人飞书卡片
  → 可信回调 / task_status 字段级写回
  → meeting.follow_up Schedule 扫描与限频提醒
  → 企业统一飞书 Base 看板
```

Java 会议插件继续保持只读，不获得数据库凭据、Base/Table 任意选择权或绕过宿主授权的能力。

## 4. 推荐实施顺序与放行标准

| 阶段 | 工作 | 必须通过的验收 |
| --- | --- | --- |
| M7-A | 宿主会议发布 Adapter/Handler、Host Validator、MeetingRunExecutionPolicy、Run 关联校验 | Chat 交互发布可按原策略确认；meeting_event Run 只允许会议发布工具自动执行；其他写工具全部拒绝 |
| M7-B | 稳定 task_key、任务身份记录、历史任务查询与上游引用 | 改标题/换负责人不改变身份；不同任务不误合并；跨会议关联必须引用已有 task_key |
| M7-C | 任务同步成功后的确认卡片 Outbox、稳定命令 ID、失败独立重试 | 任务写入成功后只发一次有效卡片；卡片失败不重新创建任务；未绑定负责人不发卡 |
| M7-D | 事件过滤、event_id 校验、资料未就绪等待恢复、Skill 导入绑定、URL 编码 | 真实事件样本通过；资料稍后就绪可恢复；Agent 发布版本包含所需 Skill 与工具 |
| M7-E | 真实飞书租户 E2E、重启/限流/重复事件/升级卸载演练 | A19 全链路证据齐全；未执行项明确记录，不以 mock 代替真实验收 |

## 5. 必须新增的测试

1. meeting_event Run 可发布合规 L1/L2；L3、缺负责人、歧义负责人、缺证据、缺截止日期均被宿主拒绝。
2. meeting_event Run 不能调用 todo_create、todo_update、schedule_create、任意 MCP/Java 插件写工具。
3. 交互式 Chat 的写入仍经过原有审批；不能因会议插件引入全局 AUTO_APPROVE。
4. 企业、集成、订阅、插件版本、Run 关联或 Binding 不匹配时拒绝发布。
5. 相同 operationId/commandId 重放不会创建第二条业务任务；Outbox 结果未知时先对账，不盲目重建。
6. Outbox 已 delivered 后才可创建确认卡片；卡片失败重试不重新触发任务创建。
7. 非负责人、旧 task_version、旧 card_message_id、重复 event_id 的回调被拒绝或幂等返回。
8. 会议事件重复、event_id 缺失、meetingSource 非 1、纪要延迟就绪和 Worker 重启场景均有测试。
9. task_key 在标题/负责人变化后稳定；不同业务任务不会仅因标题相同而合并。
10. 真实租户验收覆盖 A19，保留脱敏 Run、Outbox、飞书 record/message id 作为证据。

## 6. 当前不应做的事

- 不把飞书写入 API 直接放进 Java 会议插件。
- 不把会议事件 Run 全局改成 AUTO_APPROVE。
- 不允许模型传入任意 Base/Table、企业身份、负责人内部 userId 或确认操作者身份。
- 不用标题+负责人哈希作为正式长期 task_key。
- 不在任务同步事务里同步发送确认卡片。
- 不把 62 项自动化测试通过表述成真实飞书 E2E 已通过。
- 不在 M7 真实租户验收前宣称生产可发布。
