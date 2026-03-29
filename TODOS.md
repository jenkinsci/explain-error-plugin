# TODOS

## Usage Metrics (Issue #122 follow-up)

### TODO: Per-provider/model 指标分组
**What:** 在 `UsageStatisticsManager.recordCall()` 中加入 provider 维度，记录每个 provider（OpenAI/Gemini/Bedrock/Ollama）的调用次数。
**Why:** 对于多 provider 部署，"Top 10 Jobs" 而不知道 provider/model，无法做准确成本审计。
**Pros:** 直接提升成本可见性；`ErrorExplanationAction.providerName` 已存在，信息已有。
**Cons:** 增加 `perJobCounts` 的 key 复杂度（需要 job+provider 组合 key 或嵌套 Map）。
**Context:** 当前 `UsageStatisticsManager` 只按 job 分组。Codex 评审（2026-03-29）指出这是成本审计的关键缺失维度。
**Depends on:** Issue #122 主体实现完成后。

### TODO: perJobCounts 僵尸 Key 清理
**What:** 定期（或在 load 时）检查 `perJobCounts` 中是否有 Jenkins 中已不存在的 Job，删除其计数器。
**Why:** Job 重命名或移动后，旧名字的计数器永远不会被更新，导致统计数据越来越不准确。
**Pros:** 保持 `perJobCounts` 数据质量；防止长期运行实例的无限增长。
**Cons:** 遍历所有 Job 有一定开销；Job 删除后的历史数据争议（有人认为应保留审计轨迹）。
**Context:** Jenkins 所有基于 job name 的统计都有这个通病。Codex 评审（2026-03-29）指出此问题。
**Depends on:** Issue #122 主体实现完成后。
