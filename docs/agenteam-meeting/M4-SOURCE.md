# M4 飞书同步 — 来源复核与机制映射

> 状态：M4 实施记录。记录迁移来源的仓库、分支、commit、文件、许可证与保留/重构/淘汰结论（计划 §3.2 要求）。
> 不复制来源代码，仅提取机制并在 agenteam 现有风格内重新实现。

## 1. 来源仓库

| 仓库 | 分支 | commit | 用途 |
| --- | --- | --- | --- |
| neilyoli/magic-ai | `feature/meeting-record-feishu-sync` | `089cd7151807d1a0df48fabf2bebbee90f3dc932` | Bitable Outbox / Worker / 版本 / 重试 / 幂等创建 |
| neilyoli/erp-middle-center | `feat_neilyo_feishu_sync` | `78235ea6b8aa4fa474bb5d9a47c115feb31bc64a` | Binding / 字段白名单 / Schema 校验 / 对账日志 |

许可证：magic-ai 为 MIT（Copyright 2026 ruoyi-ai）；erp-middle-center 仓库含空 LICENSE 文件、无明确许可证声明。
结论：两者仅作**机制参考**，不复制任何代码；本实现以 agenteam 既有风格（MyBatis-Plus + Spring 事务 + 独立 Worker 线程）重写。

## 2. magic-ai — Versioned Outbox（`ruoyi-feishu` 模块）

关键文件：`outbox/FeishuBitableOutbox.java`、`outbox/FeishuBitableWorker.java`、`core/SdkFeishuBitableSyncApi.java`、`core/FeishuFieldMapper.java`、`exception/FeishuBitableSyncException.java`。

提取机制（保留）：

- **稳定同步键**：`sync_key = SHA-256(长度前缀(tenant, sourceApp, businessType, businessId, appToken, tableId, keyField, keyValue))`，长度前缀消除分隔符碰撞。
- **版本化入队**：`INSERT ... ON DUPLICATE KEY UPDATE desired_version = desired_version + 1, attempt_count = 0`，同一逻辑目标只存一份最新快照；`create_token` 首次插入生成、重复入队**不重生成**，保证幂等创建 token 在记录生命周期内稳定。
- **claim 栅栏**：原子 `UPDATE ... SET claim_token=?, claim_until=? WHERE delivered_version<desired_version AND attempt_count<max AND next_attempt_at<=now AND (claim_token IS NULL OR claim_until<=now)`；随后按 `(sync_key, claim_token)` 读回 Attempt。
- **交付**：先 `findRecord`（按唯一键过滤，>1 或 has_more 拒绝）；未命中则 `create` 且**只发稳定主键**（`{keyField: keyValue}`，同一 create_token 重试载荷始终相同）；`bindRecord` 回填 record_id（带 claim_token 栅栏）；最后 `update` 全量业务字段。
- **complete**：`delivered_version = 本次发送的 version`，只确认实际发送版本，发送中到达的新快照保持待同步；清空 claim_token、attempt_count=0。
- **fail 退避**：`delay = min(300, 5 << min(6, attemptCount-1))`。

重构结论：保留版本化/栅栏/幂等创建/对账 findRecord 语义；去掉 JdbcTemplate、Spring Event 内存推送、`FeishuFieldMapper` 注解反射（agenteam 用字段白名单枚举 + 显式映射表）；payload 仅存字段级 patch，不含人工字段。

## 3. erp-middle-center — Binding + 白名单 + 对账

关键文件：`service/FeishuSyncBindingService.java`、`service/FeishuSyncExecuteService.java`、`service/FeishuSyncKeyService.java`、`bean/entity/FeishuSyncBindingDO.java`、`bean/entity/FeishuSyncLogDO.java`、`dto/FeishuFieldMeta.java`。

提取机制（保留）：

- **字段白名单**：映射目标只能取业务字段枚举，且必须覆盖业务唯一键；映射目标不重复。
- **Schema 联网校验**：读真实字段列表，`SUPPORTED_UI_TYPES = Text/Number/DateTime/SingleSelect/MultiSelect/Url/Formula/Currency/Attachment`；`READONLY_UI_TYPES = Formula/Currency`（只读不可回写）；附件字段只能映射到按附件处理的业务字段。
- **回写归一化**：结果键必须为结果键枚举，值为非空飞书字段名且不重复；空配置归一为 null。
- **对账不覆盖**：回写前 `selectNewerSuccessLogId`，旧任务不覆盖新结果（对应本实现“delivered_version 只推进到实际发送版本”）。
- **触发密钥**：32 字节随机、库中只存 SHA-256 摘要、常量时间比较（本实现 M5 确认回调复用此思想，M4 不涉及）。
- **中断遗留 RUNNING 标记为失败待核查**，逐条串行处理、失败保存原因等待人工重试。

淘汰结论：erp 方向为“飞书 → PLM”，agenteam 为“系统 → 飞书”，方向相反；不复刻 `ProductDevFieldConverter`、PLM 业务 DTO、租户 Job 框架。机制（白名单 + Schema 校验 + 唯一键覆盖 + 对账不覆盖新结果）保留并改造。

## 4. 本实现映射

| 参考机制 | agenteam M4 落点 |
| --- | --- |
| sync_key 稳定标识 | `meeting_sync_outbox.business_key`（= 唯一键字段值），`UNIQUE(enterprise_id, binding_id, business_key)` |
| 版本化入队 | `MeetingSyncOutbox.enqueue`（MANDATORY 事务，`desired_version+1`，create_token 稳定） |
| claim 栅栏 | `claim_token` + `lease_until` + 原子 UPDATE |
| findRecord→create→update | `MeetingSyncTransactions` 交付流程 |
| 白名单 + Schema 校验 | `MeetingFieldCode` 枚举 + `MeetingBitableFieldValidator` |
| 对账不覆盖新结果 | `delivered_version` 只推进实际发送版本；字段级 patch 不覆盖人工字段 |
| 失败队列/补推 | `status ∈ pending/claimed/delivered/failed/blocked` + 管理员补推入口 + 定时扫描 |

字段所有权（§6.1）：AI 提议字段 + 技术字段可入 payload；人工业务字段（确认/执行进度）不入 payload，飞书为唯一事实来源。
