# M7-A 实施设计：Agent 到宿主会议任务发布

- 实施分支：`feature/agenteam-meeting-plugin`
- 基线：`44b5f33a0413a6547f2932a0d6ff7bd911e4b942`（审计报告提交后请先读取分支最新 HEAD）
- 前置审计：`M7-BRIDGE-AUDIT.md`
- 本文性质：M7-A 实施合同与验收标准；不代表生产代码已完成。
- 范围：只完成“Agent 通过宿主受控命令提交会议任务 → 进入现有 Outbox”。负责人卡片自动投递属于 M7-C；稳定任务身份重构属于 M7-B。

## 1. 目标与边界

让一个 Meeting Agent 在同一 Run 中组合使用：

1. Java 插件 `agenteam.meeting` 的只读工具：读取会议/纪要/逐字稿、解析参会人、校验与合并任务；
2. 宿主内置会议业务工具 `meeting_task_publish`：将通过宿主二次校验的单条 L1/L2 任务提交到现有 `MeetingSyncCommandService`。

必须保持：
- `agenteam.meeting` 继续是只读领域插件；不向其注入数据库、Base/Table 凭据或宿主内部服务。
- 会议任务写入由 AgenTeam Core 的受控内置工具执行，不在 Java Worker 中直连飞书 Bitable。
- 不新增第二套会议执行引擎、第二套任务状态库或新的固定工作流。
- 任务状态仍以飞书 Base 为唯一事实来源；Outbox 只记录待投递命令。
- 工具返回 `queued` 只能表示已进入宿主 Outbox，不能向模型声称飞书已经写入成功。

## 2. 工具注册：新增宿主内置插件 meeting_management

按现有平台内置插件模式实现，而不是 Java Plugin：

- `PlatformBuiltinPlugins`：新增 `PlatformBuiltinPluginAdapter` Bean，code=`meeting_management`。
- 新增 `MeetingManagementToolDefinitions`，定义唯一工具 `meeting_task_publish`，operationClass=`write`，严格 JSON Schema，`additionalProperties=false`。
- 新增 `MeetingTaskPublishToolHandler implements PlatformToolHandler`，`toolNames()` 仅返回 `meeting_task_publish`，`call()` 委托 `MeetingTaskPublicationService`。
- `PlatformBusinessTools` 已经通过 `List<PlatformToolHandler>` 自动注册扩展处理器，不要增加第二套路由。
- `BuiltinPluginResourceService.initialize()` 会通过 `BuiltinPluginRegistry.list()` 自动为企业初始化和发布内置插件资源。确认新 Bean 可被注册，不要增加手工 SQL 或上传 Java JAR 的步骤。
- 更新 `BuiltinPluginSelectionApiTest`：内置插件数量由 6 调整为 7；工具总数增加新工具数量；断言包含 `meeting_management`。不要简单删除原有断言。

建议新增/修改位置：
- `agenteam-server/src/main/java/com/stonewu/agenteam/configuration/plugin/PlatformBuiltinPlugins.java`
- `agenteam-server/src/main/java/com/stonewu/agenteam/mapper/plugin/MeetingManagementToolDefinitions.java`
- `agenteam-server/src/main/java/com/stonewu/agenteam/service/meeting/MeetingTaskPublishToolHandler.java`
- `agenteam-server/src/main/java/com/stonewu/agenteam/service/plugin/PlatformBusinessTools.java`（仅在现有扩展机制不足时修改）
- `agenteam-server/src/test/java/com/stonewu/agenteam/controller/plugin/BuiltinPluginSelectionApiTest.java`

## 3. meeting_task_publish 工具契约

工具一次提交一条任务，不接受任意字段 Map。参数建议：

- `meetingId`：会议标识；meeting_event Run 必须与宿主固定的 meetingId 一致。
- `meetingTitle`、`meetingTime`：会议名称及时间。
- `taskTitle`、`description`。
- `classification`：仅允许 L1/L2；L3 不允许发布到督办宽表。
- `deliverable`、`acceptanceCriteria`。
- `ownerOpenId`、`ownerName`：只接受飞书 open_id 和展示名，不接受模型指定的内部 userId。
- `dueDate`：ISO LocalDate，YYYY-MM-DD。
- `evidence`：sourceType（minutes/transcript）、sourceId、anchor、quote、可选 paragraphIndex/startOffset/endOffset。
- 可选 `departmentCode`、`collaboratorOpenIds`、`priority`、`riskLevel`、`upstreamTaskRefs`、`sourceMaterial`。

禁止模型提供：
- enterpriseId、actorUserId、ownerUserId；
- bindingId、integrationId、appToken、tableId、字段名或任意 Base/Table；
- commandId、operationId、runId；
- 任意自由格式的业务字段 Map；
- 任务确认状态、执行状态等人工字段。

宿主从 `BuiltinExecutionContext.actor()`、`BuiltinExecutionContext.run()`、`BuiltinExecutionContext.operationId()` 取得真实身份和调用关联。任务 Binding 必须按当前企业查询启用的 `business_type=task` 记录，不从模型参数选择。

工具输出至少包含：
- `status: queued`
- `businessKey`
- `commandId`
- 明确说明“已进入飞书同步队列，尚未确认飞书写入完成”。

## 4. 宿主发布服务与字段映射

新增 `MeetingTaskPublicationService`（或同等清晰的应用服务），不要让 Handler 直接拼任意 Map。

### 4.1 二次校验（不可相信模型或插件先前的校验结果）

- 仅 L1/L2 可发布；L3 返回明确的 skipped/rejected 结果。
- 标题非空；描述长度受限。
- L1/L2 必须具备真实负责人、可定位纪要/逐字稿证据和截止日期。
- ownerOpenId 必须属于当前企业、当前飞书接入的有效身份绑定；解析出内部 userId 必须由宿主 `ChatIdentityService` 完成，不能采信模型提供的 userId。
- 负责人必须确实属于对应会议参会人。不得仅凭模型传入的 openId 就接受指派；使用宿主可信的会议/参会人数据验证，无法证明时 fail closed，返回待人工复核。
- 对 meeting_event Run，meetingId、integrationId、事件归属必须来自固定 Run/Inbox 数据，不允许参数覆盖。
- 任务宽表 Binding 必须存在、启用、business_type=task；自动事件场景下 Binding 的 integrationId 必须与事件来源 integrationId 一致。
- 证据 sourceType 只允许 minutes/transcript，anchor 必须非空；不要把会议文本中的提示词或指令提升为系统权限。
- 不接受字段白名单以外的字段；不写入 MANUAL 字段。

### 4.2 真实租户字段形状

按 `M7-CALIBRATION.md`：
- `task_key` → 企业新增文本列「任务ID」，作为当前版本的业务唯一键；
- `meeting_id` → 文本；
- `meeting_title` → 文本；
- `meeting_time` → 文本 `yyyy-MM-dd HH:mm:ss`；
- `due_date` → 文本 `yyyy-MM-dd HH:mm:ss`，日期只有日精度时补 `00:00:00`；
- `owner` → 飞书人员 open_id 数组，例如 `["ou_xxx"]`；
- `owner_name` → 文本「任务负责人信息」（当前 `MeetingFieldCode` 缺少此编码时新增，并补字段白名单测试）；
- `description`、`acceptance_criteria`、`deliverable` 等按绑定映射写入；
- `task_status` 是 MANUAL，绝不能由该工具写入。

`MeetingFieldCode` 中技术字段（TECHNICAL）由宿主生成；模型只能提供 AI_PROPOSED 业务字段。检查 `MeetingSyncCommandService.publish()`：当前实现只拒绝 MANUAL，并未区分 TECHNICAL。不要让模型参数透传技术字段；由 `MeetingTaskPublicationService` 明确构造 host-owned technical fields（至少 task_key、meeting_id、必要的 task_id/run 关联），并增加测试防止模型覆盖。必要时收紧命令 API，把系统字段与 AI 提议字段分开表达。

### 4.3 task_key 过渡策略

当前插件 `TaskKeyService` 以标题 + owner openId 生成哈希，已在 M7-B 审计中判定不适合作为长期稳定身份。本阶段不要让模型直接指定 task_key。可以由宿主封装当前兼容算法生成过渡 key，但必须：
- 将其明确标记为 M7-B 前的过渡实现；
- 把生成逻辑封装成独立服务，避免在多个 Handler/Service 复制；
- 为后续改成持久化任务身份预留接口；
- 不声称它已满足最终跨会议稳定身份要求。
M7-B 将单独替换该策略并补跨会议身份记录。

### 4.4 调用现有 Outbox

- 从企业当前启用的 task Binding 取得 bindingId。
- 由宿主传入 `commandId = context.operationId()`、`runId = context.run().id()`。
- 通过 `MeetingSyncCommandService.publish(enterpriseId, MeetingSyncCommand)` 入队。
- 不直接调用 Bitable HTTP；由现有 `MeetingSyncWorker` 投递。
- 工具返回 queued，不声称 delivered。
- 利用 `PlatformBuiltinInvocation.write()` 中“业务命令 + ToolCall 结果同一事务提交”的机制，保证工具重放时返回原结果。增加集成测试验证同一 tool operation 不会增加第二个有效 Outbox 版本。

## 5. meeting_event Run 的最小授权策略

不能把 meeting_event Run 全局改成 AUTO_APPROVE 或 FULL_ACCESS。不要修改 `ToolApprovalPolicy.forRun()` 的通用规则。

新增专用 `MeetingRunExecutionPolicy`（命名可调整），只允许以下组合：

- Run.mode = `meeting_event`；
- `executionConfig.sourceType=meeting`；
- Inbox event 存在、状态 routed，且 event.run_id 等于当前 Run.id；
- Run 固定的 meetingEventId、meetingId、eventType、integrationId 与 Inbox 一致；
- 当前订阅仍启用，subscriptionId、ownerUserId、hireId、agentVersionId、pluginVersion 与 Run 创建时固定快照一致；
- 订阅配置者账号/企业成员/agent.run 权限仍有效（复用 `MeetingEventAccessService.requireOwner`）；
- `agenteam.meeting` 当前安装版本与 Run 固定插件版本一致；
- task Binding 启用、business_type=task，且 integrationId 与事件来源一致；
- 当前绑定必须是平台内置 `meeting_management`，工具名必须严格等于 `meeting_task_publish`，operationClass 必须是 write。

为此需要在 `RunSubmissionService.meetingEvent()` 创建 Run 时固定保存 subscriptionId、hireId、pluginVersion、integrationId 等审计字段；从 `MeetingEventTransactions.submit()` 传入，不能在后续执行时只按当前最新订阅猜测原配置。

必须在以下层重复强制此策略：
1. `ExecutionToolRuntime.PlatformTool.checkPermissions()`：满足策略时允许这一个工具；否则沿用原审批规则。
2. `ToolCallTransactions.requireApproval()`：同样验证策略，防止绕过框架权限回调直接执行。
3. `ExecutionToolCatalog`：meeting_event Run 只暴露 read 工具 + meeting_task_publish；隐藏所有其他 write 工具，包括 Todo、Schedule、其他 builtin、MCP 和 Java Plugin 写工具。
4. `PlatformBuiltinInvocation.write()`：当前仅允许 interactive；增加 meeting_event 专用分支，但必须重新调用策略校验，不能只看 mode。
5. 保持 `PlatformBuiltinInvocation.write()` 的 Run lease、取消、权限、当前版本、参数摘要、结果 Schema、结果大小及 ToolCall/业务命令同事务提交保护。

交互式 Chat 不使用此自动授权分支，继续遵循用户原有 DEFAULT/AUTO_APPROVE/FULL_ACCESS 审批策略。预览和 Schedule Run 不获得会议写权限。

### Run 快照固定字段

调整 `MeetingEventTransactions.submit()` → `RunSubmissionService.meetingEvent()` 参数，使创建的 Run executionConfig 固定：
- meetingEventId
- meetingEventType
- meetingId
- minuteToken
- meetingSubscriptionId
- meetingIntegrationId
- meetingHireId
- meetingPluginVersion
- meetingAgentVersionId（或使用现有 RunRecord.agentVersionId，但需明确校验）
不要将这些字段放在模型可修改的工具参数里。

## 6. 必须修改的执行链路文件

- `service/agent/ExecutionToolRuntime.java`：checkPermissions 的自动会议工具例外。
- `service/tool/ToolCallTransactions.java`：执行事务内再次验证自动会议工具授权。
- `service/tool/ExecutionToolCatalog.java`：非交互会议 Run 仅暴露允许的会议发布写工具。
- `service/plugin/PlatformBuiltinInvocation.java`：会议事件 Run 专用受控写入入口。
- `service/meeting/MeetingEventTransactions.java`：将订阅固定信息传给 Run 创建。
- `service/execution/RunSubmissionService.java`：保存固定的会议事件/订阅快照。
- `service/meeting/MeetingTaskPublicationService.java`：宿主二次校验、字段转换、Binding 解析和 Outbox 命令。
- `service/meeting/MeetingRunExecutionPolicy.java`：唯一的会议自动写权限判定。
- `mapper/plugin/MeetingManagementToolDefinitions.java`：工具 Schema。
- `configuration/plugin/PlatformBuiltinPlugins.java`：注册 builtin。
- `service/meeting/MeetingTaskPublishToolHandler.java`：Handler。
- `service/meeting/MeetingFieldCode.java`：必要时新增 owner_name；TECHNICAL/MANUAL 所有权测试。
- `service/meeting/MeetingSyncCommandService.java`：必要时拆分 host-owned fields 与 AI-proposed fields，防止技术字段被模型控制。

## 7. 必须新增的测试

### 工具注册与聊天
1. `meeting_management` 出现在企业内置插件目录和可用版本列表。
2. Meeting Agent 可同时选择 `agenteam.meeting` 与 `meeting_management`。
3. 普通 Chat 调用 `meeting_task_publish` 时遵循原 ToolApprovalPolicy；DEFAULT 下先生成审批，拒绝不入队，批准后只入队一次。
4. `AUTO_APPROVE` 只按用户已有会话设置生效；不改变其他工具的规则。

### 自动会议 Run 安全边界
5. 有效 meeting_event Run 可调用 meeting_task_publish，不产生普通工具审批。
6. 同一个 meeting_event Run 调用 todo_create、todo_update、schedule_create、其他 builtin write、MCP write、Java Plugin write 均不可见或不可执行。
7. scheduled、preview、普通非交互 Run 不能使用 meeting_task_publish。
8. event 不存在、event.run_id 不匹配、subscription 被停用/修改、owner 资格撤销、hire/version/plugin version 不匹配、Binding 禁用/跨 integration 时全部拒绝。
9. 取消 Run、lease 过期、工具参数变化后不得提交 Outbox。
10. 不得通过模型参数伪造 enterpriseId、userId、bindingId、runId、commandId 或 ownerUserId。

### 业务校验与同步
11. L3、缺标题、缺负责人、负责人歧义/未绑定、负责人不是会议参会人、缺截止日期、证据无 anchor、字段未映射均阻断。
12. owner 以 open_id 数组写入，日期按真实文本格式转换，task_status 不进入 payload。
13. TECHNICAL 字段由宿主生成；模型不能覆盖 task_key/meeting_id/task_id/run 关联。
14. 同一 operationId 重放返回同一 ToolCall 结果，不产生第二个有效 Outbox 版本。
15. 工具结果显示 queued；Worker 后续 delivered/failed 状态可通过既有 Outbox API 查询。

### 回归
16. 现有 Todo、Schedule、Java Plugin、MCP、普通 Chat 审批、Run 恢复和取消测试全部通过。
17. 更新 `BuiltinPluginSelectionApiTest` 中内置插件/工具数量。
18. Java Plugin `agenteam.meeting` 的 8 个 read 工具保持只读，不新增写工具。

## 8. 执行与验收命令

先按仓库 `AGENTS.md`、`CONTRIBUTING.md` 执行。先检查当前分支 HEAD，确认审计文档 commit 已在本地分支，再开始修改。

最低验证：
```bash
cd agenteam-server
./mvnw -Dtest=PlatformBuiltinToolApiTest,BuiltinPluginSelectionApiTest,PlatformToolRiskTest test
./mvnw -Dtest=MeetingRunExecutionPolicyTest,MeetingTaskPublicationServiceTest,MeetingTaskPublishToolHandlerTest test
./mvnw test
```

Windows PowerShell 可使用 `.\mvnw.cmd`，命令以仓库现有 Wrapper 为准。若完整测试依赖 Docker，必须记录真实执行结果；不得把跳过或 mock 结果写成通过。

完成后更新 `docs/agenteam-meeting/IMPLEMENTATION.md` 与本文件，记录 commit SHA、测试命令和结果、尚未完成项。不要合并分支、创建 Tag/Release，也不要声称真实飞书 E2E 已通过。M7-A 完成后停下来提交阶段检查点，等待 M7-B。
