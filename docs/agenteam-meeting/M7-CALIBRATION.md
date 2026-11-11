# M7 现场校准 — 飞书 Base 字段与事件类型

校准来源：`D:\Workspase\neilyo-code\magic-ai` 的 `ruoyi-meeting` 模块真实源码与配置
（`FeishuEventHandlerRegistrar`、`meeting-base-auto.yml`、`MeetingBaseProjectionTool`、
`meeting-record-feishu-sync.md`、`CURRENT_IMPLEMENTATION.md`）。本文件只记录“真实租户事实”，
作为 `agenteam.meeting` 绑定配置与 M7 验收的基线；不含任何凭证。

## 1. 事件类型（真实飞书事件）

| 飞书事件 | dispatch/过滤 | 说明 |
| --- | --- | --- |
| `meeting.ended` | `onP2MeetingEndedV1` | 单场会议结束 |
| `meeting.all_meeting.ended` | `onP2MeetingAllMeetingEndedV1` | 全体会议结束 |
| `card.action.trigger` | `onP2CardActionTrigger` | 卡片回调（确认/调整/拒绝） |
| `im.message.receive_v1` | `onP2MessageReceiveV1` | 私聊/群聊消息查询 |

统一过滤：`meetingSource == 1`（只处理日历创建的会议）。agenteam 订阅 API 已按 `event_type`
自由声明，这四个类型即为 M2 `MeetingEventNormalizer` 的现场基准。

## 2. 企业统一 Base 与表

- `app_token`：`bascn…`（`meeting.agent.projection.base.base-token`）；final 入口
  `https://fcnzbc7ixbgj.feishu.cn/app/M0OfbWQTaa7REwsd6BscU5iJnbd`。
- 会议主表（`meeting`）`tblAezYd09Atgm4s`，row-path `meeting`，键语义 `id`。
- 参会人表（`participants`）`tbl53E3xmLCIVorv`，row-path `participants`，键语义 `meetingId+openId`。
- 任务宽表（`tasks`，督办宽表）`tblV30BP1PO4Ode9`，row-path `structuredTasks`，键语义 `meetingId+title`。
- 会议体系表（上游合法性）`tblk5lUiEqU8HKi9`（公司级会议体系）。
- 会议记录台账（record-sync）`tblRp64cYkXCgZ0A`（系统接收台账，5 列文本，与业务表分开）。

## 3. 会议主表字段（`meeting`）

| 飞书字段 | 语义 | 类型/形状 | agenteam 编码 |
| --- | --- | --- | --- |
| 会议ID | id | 文本 | `meeting_id` |
| 会议名称 | title | 文本 | `title` |
| 会议时间 | startTime | 文本 | `start_time` |
| 会议开始时间 | startTime | 文本 | `start_time` |
| 会议结束时间 | endTime | 文本 | `end_time` |
| 会议组织者 | organizer.openId | 人员 | `host` |
| 会议录屏链接 | recordingUrl | 文本 | `recording_url` |
| 会议总结 | summary | 文本 | `summary` |
| AI分类需要识别的会议 | aiCategory | 文本 | `type` |
| 完整会议纪要 | noteUrl | 文本 | `minutes_url` |
| 上游任务覆盖状态 | （收尾写） | Select：`待检查`/`无需上游` | `upstream_covered` |

注意：`上游任务覆盖状态` 在**会议主表**，取值是 `待检查`/`无需上游`，不是 `未覆盖/uncovered`；
它在会议投影收尾时由系统写入，专项会议写 `无需上游`，普通会议写 `待检查`。

## 4. 任务宽表字段（`tasks`，督办宽表）

| 飞书字段 | 语义 | 类型/形状 | agenteam 编码 |
| --- | --- | --- | --- |
| 会议ID | meetingId | 文本（键语义） | `meeting_id` |
| 待办 | title | 文本（键语义） | `title` |
| 任务描述 | contextSummary | 文本 | `description` |
| 任务确认人信息 | taskConfirmer | 文本（姓名） | — |
| 任务确认人 | taskConfirmerOpenId | 人员（open_id） | — |
| 任务负责人信息 | ownerName | 文本（姓名） | — |
| 任务负责人 | ownerOpenIds | 人员（open_id 数组） | `owner` |
| 会议名称 | meetingTitle | 文本 | `meeting_title` |
| 会议时间 | meetingTimeText | 文本 `yyyy-MM-dd HH:mm:ss` | `meeting_time` |
| 截止时间 | dueDate | 文本 `yyyy-MM-dd HH:mm:ss` | `due_date` |
| 验收标准 | acceptanceCriteria | 文本 | `acceptance_criteria` |
| 任务ID | （企业新增） | 文本，承载稳定 task_key | `task_key`（唯一键） |
| 任务状态 | （企业新增） | 单选：待确认/进行中/已完成/需调整/已拒绝 | `task_status` |

## 5. 关键校准结论（与 agenteam 抽象模型的差异）

1. **无“任务唯一键”单字段 → 已确认加列「任务ID」**。真实任务宽表原本用复合键 `会议ID + 待办`
   （`key-semantics: [meetingId, title]`）。企业侧新增文本列 `任务ID`，agenteam 把稳定 `task_key`
   （标题 + 负责人 openId 的 SHA-256 内容哈希）写入该列作为唯一键；绑定配置
   `keyField=任务ID`、`fieldMappingJson.task_key→任务ID`，`MeetingBitableClient.findRecordId` 仍用
   单字段等值查询做幂等 upsert。注意 `任务ID` 必须是 agenteam 可写入的文本列，不能用飞书自动编号
   （自动编号在创建后才生成，无法作为 upsert 前的幂等查询键）。

2. **日期是文本，不是毫秒时间戳**。任务宽表 `会议时间`/`截止时间` 均为文本 `yyyy-MM-dd HH:mm:ss`
   （Asia/Shanghai 业务时间，日期只写日期则补 `00:00:00`）。`MeetingFollowUpClassifier.date()` 原仅
   识别毫秒/秒时间戳与 ISO 文本，本阶段已扩展支持上述文本格式（按 Asia/Shanghai 解释），并补测试。

3. **人员字段是 open_id 数组**。`任务负责人`（ownerOpenIds）是人员字段，值为 `["ou_xxx", …]`；
   姓名另存 `任务负责人信息`（ownerName 文本）。`owner` 编码应映射到 `任务负责人`（人员字段），
   分类器 `person()` 已兼容文本/对象/数组三种形状。

4. **任务宽表无状态字段 → 已收敛为单「任务状态」字段**。magic-ai 的确认是卡片流程（上游确认/处理确认/
   分析结果确认），任务宽表原本没有状态列。经确认，agenteam 把「确认状态」「执行状态」合并为单
   「任务状态」单选字段（待确认/进行中/已完成/需调整/已拒绝）：M5 确认动作写它、M6 督办读它，
   `MeetingFieldCode` 以 `task_status`（`MANUAL` 只读）承载，符合“飞书唯一事实来源”。
   `上游任务覆盖状态` 仍在会议主表（Select：待检查/无需上游）；M6「上游未覆盖」条件需企业侧在
   任务宽表另配「上游覆盖」单选（已覆盖/未覆盖）。

5. **字段集合是超集关系**。magic-ai 任务宽表实际只有 11 列，agenteam 的 `MeetingFieldCode` 约 50 项
   中 `deliverable/l1/l2/source_material/evidence_location/collaborators/owner_department/priority/
   risk_level/upstream_task_ref/uncovered_upstream` 等在 magic-ai 当前 Base 无直接对应，属 agenteam
   扩展；绑定通过 `fieldMappingJson` 配置，未映射字段不进入投递 payload，不构成阻断。

## 6. 本次代码校准

- `MeetingFollowUpClassifier`：`date()` 增加文本日期解析（`yyyy-MM-dd HH:mm:ss` 等，Asia/Shanghai）；
  `MeetingFollowUpClassifierTest` 新增 2 项文本/纯日期逾期判定测试。
- 状态字段收敛（字段能少则少）：合并「确认状态」「执行状态」为单「任务状态」
  （`task_status`，单选：待确认/进行中/已完成/需调整/已拒绝）。
  - `MeetingFieldCode`：`MANUAL` 只读段仅保留 `TASK_STATUS`，删除 `CONFIRM_STATUS`/`CONFIRM_COMMENT`/
    `CONFIRM_USER`/`CONFIRM_TIME`/`EXECUTION_STATUS`/`COMPLETE_TIME`/`COMPLETE_EVIDENCE`。
  - 新增 `MeetingTaskStatus` 枚举（值+标签+`of`/`isDone`/`isUnconfirmed`）。
  - `MeetingConfirmationAction`：新增 `taskStatus()` 映射（确认→进行中、需调整→需调整、拒绝→已拒绝）；
    构造签名变为 `(value, state, taskStatus)`。
  - `MeetingConfirmationCommandService`/`MeetingConfirmationService`/`MeetingConfirmationCallbackService`：
    确认回调只允许写 `task_status`（飞书为唯一事实来源），不再写确认评论/操作人/时间到 Base，审计留 MySQL
    `meeting_confirmation`。分类器统一读 `task_status` 判定未确认/已完成。
- 未改动：字段映射、复合键支持（见结论 1/4，属需企业侧新增字段或后续设计决策，不在此阶段擅自扩展）。
