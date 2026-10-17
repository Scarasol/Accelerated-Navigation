# 边界候选位图求交交接

日期：2026-10-17。

用户提出六方向开口位、补充按组件的256位边界位置求交，并授权结合网络与DeepSeek选择构型。本轮选定共享合法锚点行位图、原组件标签筛选、按原inset/distance/dy求交。生产与默认Jar不动，没有叠加上一轮隔离候选。

完整报告：`docs/reports/2026-10-17-boundary-port-bitmaps-zh.md`。

证据根：`.tmp/boundary-port-bits-20261017-v1/`；权威汇总`summary-v2.json`，封存`final-seal-v1.json`。

- `indexed-main`只加索引，完整Boundary仍为原标量oracle。
- `intersect-main`改完整Boundary为求交后的置位枚举。
- `dependencies-main`再加Base正向/反向依赖前的当前组件零拒绝；缺失邻居仍是BASE_CLUSTER依赖。
- 515,840行集合、737,280标签位、2,520组完整Boundary差分通过，873边、2,898band一致。
- 四个开口错位/组件反例通过；Core 9、boundary-recovery 9、macro-commit 4、Door 28及图导出通过。
- 正式60个JMH测量fork，完整Boundary平地约-21.0%、固定城市重复列约-45.6%；这些不代表实际城市路线或worker净收益。
- 位置索引额外驻留/分配约1 KiB/Base。完整构建fork波动保留，单独索引构建结果用于摊销判断，不能从均值负差宣称免费。
- 单个索引平地/城市约0.403/0.277微秒，稠密VOLUME约2.698微秒；独立索引分配1,080字节。优先GROUND与重复查询，VOLUME不能忽略转置和驻留成本。
- DeepSeek首轮REQUEST_CHANGES及脚本未识别状态保留；提交实际代码和差分后第二轮ACCEPT_SCOPED、必须修改项无，配置未变。不要恢复首轮错误的反向物理移动建议。

接手先区分原型位置和输入，保留`bench-links-v1`空组件失败、`harness-v2`编译失败，以及所有正式v2原件。真实城市运行、实际依赖计数、驻留负载和整体worker对照尚未取得。Super宏观依赖前还未增加组件对位图摘要；不要把Base候选数量当成所有宏观依赖下降。
