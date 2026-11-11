# M3 智能分析评估集与质量阈值

## 目的

M3 放行标准是「固定评估集可复现；错误候选不进入正式任务」。本文件记录评估集结构、
确定性领域层的可复现方式，以及进入正式任务前的质量阈值。

## 分层

1. **确定性领域层（可离线复现）**：稳定 task_key、去重合并、上游覆盖、L1/L2/L3 校验。
   输入为结构化候选，输出确定，评估集落在插件测试资源
   `agenteam-plugin-meeting/src/test/resources/eval/meeting-eval-set.json`，
   由 `MeetingEvalSetTest` 逐样本断言，`mvn -f agenteam-plugin-meeting/pom.xml test` 可复现。
2. **模型语义层（待 M7）**：从纪要/逐字稿提取候选的召回与准确率，需要真实租户资料与已配置模型，
   本阶段不以内置 mock 冒充真实验收。

## 评估集样本

| ID | 场景 | 期望 |
| --- | --- | --- |
| dedup-cross-meeting | 跨会议重复提及同一任务（同一负责人） | 合并为 1 条，证据 2 条，`UP-3` 未覆盖 |
| higher-classification-wins | 同一任务 L2 与 L1 并存 | 合并后取 L1 |
| upstream-covered | 下游任务覆盖全部上游承诺 | 无未覆盖项 |

## 质量阈值（发布前不得降低）

- **分类**：L1/L2 与人工标注一致率 ≥ 0.9；L3 不得误升为 L1/L2（误升比漏升更严重）。
- **证据**：进入督办的任务 100% 含可定位证据（来源 + 锚点），否则阻断。
- **负责人**：进入督办的任务 100% 有已解析稳定 openId；歧义一律转人工，不得自动指派。
- **去重**：同一（标题 + 负责人）只保留一条；`task_key` 跨会议、跨运行稳定。
- **错误候选阻断**：`task_validate` 返回 `valid=false` 的候选不得进入正式任务或督办宽表。

## 复现

```bash
cd agenteam-server
mvn -q -f agenteam-tool-api/pom.xml install -DskipTests
mvn -q -f agenteam-plugin-api/pom.xml install -DskipTests
mvn -f agenteam-plugin-meeting/pom.xml test
```

模型语义层评分脚本与真实标注集在 M7 具备飞书凭据与模型后补充，届时在本文件追加
逐样本的模型输出、人工标注与脱敏证据。
