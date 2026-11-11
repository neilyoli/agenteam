# agenteam.meeting 运行手册

本手册是会议闭环插件（`agenteam.meeting`）的部署与运维说明。飞书凭据（App Secret、事件加密 Key）
沿用宿主 `IntegrationSecretService` 加密存储，**不在本手册、代码或日志中写明文**；凭据通过管理页面
配置，见 [企业微信、飞书与定时通知](../deployment/enterprise-messaging.md)。

## 1. 部署前提

- 后端（agenteam-server，Spring Boot）、前端（agenteam-web）已按宿主部署方式运行；数据库
  （MariaDB/MySQL，默认 `43306`）与 Redis（默认 `46379`）可用，Flyway 迁移已执行到含
  `V0.6.0.4__新增会议持续督办.sql` 的版本。
- 飞书企业自建应用已创建，具备下述权限，测试成员在应用可用范围内。
- 企业统一飞书 Base 已就绪：会议主表 + 任务督办宽表，且企业侧已按 [M7-CALIBRATION.md](M7-CALIBRATION.md)
  新增文本列「任务ID」与单选列「任务状态」（选项：待确认/进行中/已完成/需调整/已拒绝）。

## 2. 飞书应用配置

| 项 | 配置 |
| --- | --- |
| 凭据 | 取得 `App ID`、`App Secret`；在 AgenTeam 新增飞书接入时填写，走 `IntegrationSecretService` 加密存储 |
| 机器人/消息 | 申请 `im:message:send_as_bot`（发个人/群消息卡片）与 `tenant:tenant:readonly`（校验企业） |
| 通讯录 | 申请通讯录只读（把负责人姓名解析为 `open_id`，见 `meeting_people_resolve`） |
| 会议事件 | 订阅 `meeting.ended`、`meeting.all_meeting.ended`（日历会议，`meetingSource==1`） |
| 卡片回调 | 启用 `card.action.trigger`（确认/调整/拒绝卡片回传） |
| 多维表格 | 应用对统一 Base 有读写权限（会议主表 + 督办宽表） |
| 重定向 URL | 将页面生成的完整授权回调地址加入安全设置的重定向列表 |

事件回调（`im.message.receive_v1` 若走 HTTP 回调）需要飞书服务器可访问的公开地址；卡片交互与消息
也可使用长连接（见宿主飞书接入文档的“机器人聊天与内部协作”一节）。

## 3. 企业接入与绑定

1. 企业管理 → 企业协作平台 → 新增飞书接入，填写 App ID / Secret，校验并启用。
2. 建立两张表的绑定（businessType 区分会议主表与任务宽表）：

| 绑定 | businessType | app_token / table_id | keyField | 关键 fieldMapping |
| --- | --- | --- | --- | --- |
| 会议主表 | `meeting` | 企业 Base 会议主表 | 会议ID | `meeting_key`→会议ID、`title`→会议名称、… |
| 任务宽表 | `task` | 企业 Base 督办宽表 | 任务ID | `task_key`→任务ID、`task_status`→任务状态、`title`→待办、`owner`→任务负责人、`due_date`→截止时间、… |

- 唯一键：会议主表 `meeting_key`（`source_app_id + meeting_id`）、任务宽表 `task_key`
  （标题 + 负责人 openId 的 SHA-256 内容哈希）→ 企业新增「任务ID」列。
- `task_status` 是 `MANUAL` 只读字段，**系统只读不写**；确认动作写它是唯一例外（走确认命令服务，
  非普通投递 payload）。字段映射校验会拒绝把人工字段当作普通可写目标。
- 绑定 API：`POST /api/v1/enterprises/{enterpriseId}/meeting/bindings`；列表/更新/启用/删除
  见 `MeetingBindingApiController`。

## 4. 订阅范围

`POST /api/v1/enterprises/{enterpriseId}/meeting/subscriptions` 按接入声明要处理的事件类型：

- `meeting.ended`、`meeting.all_meeting.ended`（触发会议分析 Run）
- `card.action.trigger`（确认卡片回调）
- `im.message.receive_v1`（私聊/群聊消息查询）

事件经 `MeetingEventNormalizer` 归一化，`MeetingEventIngressService` 只接受已订阅且启用的
`event_type`，并写入收件箱（去重/租约/重试），见 M2。

## 5. Skill 导入与发布

两个 Skill 作为插件资源发布（`docs/agenteam-meeting/skills/`）：

- `meeting-intelligence`：会议结束 → 读取纪要/妙记/逐字稿 → 提炼 L1/L2/L3 候选 → `task_validate`
  校验 → 宿主受控命令发布到督办宽表。
- `meeting-follow-up`：Schedule 定时触发，对任务给出催办/升级/修订/收尾判断（只判断，不写库不发消息）。

导入后按宿主 Skill 流程发布；Agent 只输出 `MeetingFieldCode` 白名单内的字段编码，Java Validator
执行 L1/L2/L3、证据、负责人与字段写入约束。

## 6. 测试会议

1. 以测试成员（负责人 open_id 见联调约定）创建一场日历会议，确保产生纪要/妙记/逐字稿。
2. 会议结束后等待 `meeting.ended` 事件触发分析 Run；分析完成后核对督办宽表新增任务行。
3. 负责人收到确认卡片，点击「确认」；核对任务宽表「任务状态」变为「进行中」。
4. 触发督办 Schedule，核对催办卡片与限频（`meeting_follow_up` 台账 + `meeting_follow_up_scan` 作业）。

## 7. 看板配置

角色看板由企业统一 Base 视图承载（会议主表 + 督办宽表），不在 AgenTeam 另建任务副本：

- 按「任务状态」建视图（待确认/进行中/已完成/需调整/已拒绝）。
- 按「任务负责人」建负责人视角；老板/战略中心/主持人视图按负责人/状态过滤。
- 「上游任务覆盖状态」在会议主表（Select：待检查/无需上游）；M6「上游未覆盖」条件依赖任务宽表
  企业侧另配「上游覆盖」单选（已覆盖/未覆盖）。

## 8. 失败队列与对账入口

- 版本化投递箱（Outbox）：`GET /api/v1/enterprises/{enterpriseId}/meeting/sync/outbox` 列出待投递/
  失败命令；`POST /api/v1/enterprises/{enterpriseId}/meeting/sync/outbox/{outboxId}/retry` 重投。
- 督办台账与扫描作业：`GET /api/v1/enterprises/{enterpriseId}/meeting/follow-up` 与
  `.../meeting/follow-up/scans` 查看候选/催办/限频跳过/失败聚合。
- 对账语义：写成功但响应丢失时按 `record_id`/`business_key` 先对账，不盲目重建；无法证明未写入时转人工复核。

## 9. 停用、暂停与恢复

| 操作 | 方式 |
| --- | --- |
| 停用订阅 | `enabled` 置 false 或删除订阅；已入收件箱的事件不再触发新 Run |
| 停用绑定 | 绑定 `enabled` 置 false 或删除；Outbox 旧命令停止投递（版本不匹配停用） |
| 暂停投递 | 关闭 `AGENTEAM_CHANNEL_DELIVERY_WORKER_ENABLED`/`AGENTEAM_NOTIFICATION_ACTIONS_ENABLED`（见宿主升级文档） |
| 恢复 | 恢复开关并重启；Outbox 租约与扫描作业凭 `claim_token + lease_until` 防过期 Worker 回写，重启后自动恢复 |

停用插件后保留飞书业务记录与历史审计，不自动删除任务。

## 10. 安全红线

- App Secret / 事件加密 Key 只进 `IntegrationSecretService`（用途 `app_secret` / `chat_encrypt_key`），
  不写代码、不打印日志、不入插件清单。
- 确认回调经 `ChatIdentityService` 解析操作者身份 + 事件去重 + 版本校验；非负责人、重复、旧版本回调被拒绝。
- 人工字段（`task_status`）为飞书唯一事实来源，Outbox 重试不覆盖人工更新。
