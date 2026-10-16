# 首次Path命中与阶段确认

- 日期：2026-10-16。
- 用户授权：继续确认首次Path前的缓存命中及构建/发布关联。
- 方案：`docs/designs/2026-10-16-intermediate-reuse-firstpath-plan-zh.md`。
- 报告：`docs/reports/2026-10-16-intermediate-reuse-firstpath-confirmation-zh.md`。
- 证据：`.tmp/intermediate-reuse-firstpath-20261016/`。

## 进度

1. 已完成独立诊断副本与8项计数/引用释放检查。
2. 已完成4独立JVM的AB/BA，64实体自然成功、DataLoss=0，目标图冷态，派发ready+30秒。
3. 已确认两次候选的16／16请求相关代次范围均有首节点发布前的跨构建命中，去重49712／48896。
4. 已分层定位：可复用184构建行阶段约8ms，基线25–29ms；72含门回退约21.5–52.7ms，基线8.8–10.1ms。
5. 已从原始JFR确认packed-r1的6个含门回退样本均为解释执行；基线采样均为JIT编译。不是JIT唯一因果证明，packed-r2没有回退样本。
6. DeepSeek自足5607B结果包65.901秒返回ACCEPT_WITH_GATES，本轮必须修改项无；仅认可stamps相关范围的机制与阶段归因，不批准生产接入。

生产源码只读，旧GC门不变，不提交推送。本轮不是生产接入批准。后续优先补含门回退的编译/反优化证据，不展开其他生产优化。
