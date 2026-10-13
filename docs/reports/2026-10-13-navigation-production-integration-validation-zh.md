# Navigation 生产接入验证记录

日期：2026-10-13。目标是验证原版 Navigation、GoalSelector、Brain 与 POI 调用能否自动接入分段服务。本轮不新增性能基准。

标准 Forge 1.20.1-47.3.0 发行环境清除 `MOD_CLASSES`/`MCP_MAPPINGS`，加载最终 all.jar；验证驱动单独重混淆，并将生产类 hierarchy jar 作为 FART library，避免测试驱动继承 `Path` 时出现错误方法名。最终 all.jar SHA-256 为 `85EA14B2F78CE115D5169FF14510DE5B62210A7E06115EFAC66AA97520366434`。

已通过的接入控制包括：五类导航入口、参数和取消；原版 Path 序列化读回；纯查询不替换活动 Path；控制来源代次、旧 Goal.stop 和外来 Path 所有权；原版 Melee 目标漂移；原版 Brain Sink 到达；AcquirePoi 的实际异步等待、真实 POI 占票且没有同步查询增量；四个原版 Melee 同时执行；正常重算保留任务身份；当前窗口语义证明变化暂停局部执行后恢复；部分原生 Path 在未到最终目标时以 `EXHAUSTED` 结束；READY 语义变化和 200 tick TTL 拒绝收养；服务器关闭后服务、记录和 scheduler pending 均为零。最终发行驱动业务断言为 97 项通过、1 项保留失败；发行字节码审计为 295 项、60 个 handler、17 个 mixin group 全部通过。

四条默认参数真实路线中，stairs-forward、stairs-backward 和 city-forward 自然到达。city-backward 在实体到达 `(96,84,-264)` 后，多个真实局部 Path 只返回部分结果；有限候选/恢复及一次宏观刷新耗尽后按设计结束为 `UNAVAILABLE`。从该位置独立起步的短程对照可以自然到达城市终点，但不能替代全程资格，因此该失败保留，不能声称四路线全部通过。没有为掩盖失败而提高全局寻路范围、节点限额或恢复上限。

本记录证明当前自动接入契约和资源/生命周期控制已在发行环境工作；资源清理门禁通过，但整体业务门禁因 `city-backward` 保留失败而不为全绿。它不证明所有实体自定义 Navigation、TargetGoal/LongJump 等完整节点消费者或所有复杂地形路线已取得资格，也不替代长期性能验证。
