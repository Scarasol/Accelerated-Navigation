# 已完成迭代的隔离数据清理

日期：2026-10-22。用户明确授权：
“直接删除隔离验证中已经稳定迭代的数据，保留结论和源码即可”。

已删除1,252个旧运行数据或构建文件，合计513,120,294字节，
约489.35MiB；9,673个保留文件逐文件SHA-256前后核对通过。
生产源码、默认JAR和本轮T/QG候选未清理。
当前有效U/P/N压力对照、原工具及模板继续保留，供本轮复用。

范围为2026-10-20/21已完成或被后续迭代替代的分段派发、
宏观预选、目标质量、轻量选点与范围候选。
具体有实际删除的目录：

| 目录简称 | 文件数 | 释放MiB |
| --- | ---: | ---: |
| stage-dispatch-validation | 255 | 107.92 |
| stage-dispatch-rerun | 38 | 14.61 |
| macro-prepared-segment | 344 | 135.87 |
| stage-target-quality-validation | 90 | 34.67 |
| portal-quality-distance | 93 | 33.31 |
| lightweight-target-validation | 92 | 34.74 |
| lightweight-target-no-band | 88 | 33.08 |
| lightweight-target-no-band-rerun | 76 | 29.61 |
| lightweight-target-envelope | 102 | 35.68 |
| lightweight-target-arrival-diagnosis | 74 | 29.85 |

删除旧游戏世界、JFR、运行原始时间线/日志和编译二进制等；
保留源码、配置、脚本、结论/比较摘要、审查和版本身份记录。
没有递归删除或迁移整个研究目录，没有进入libraries目录联接。
本来为腾出空间准备的build迁移未实施，build仍在原位置，
本轮游戏继续使用原D盘目录与原runner。

原报告中对上述原始文件“保留”的声明，以本清理记录为准。
旧seal可保留当时身份，但不再承诺本次删除原件仍可读；
不能据已清理的原件虚称能够重新复算或重放。
保留摘要若缺后续所需字段，应按AGENTS.md说明缺失项再补测，
不因本次清理自动重跑其它仍有效的历史数据。

逐文件删除路径、大小与删除前哈希，以及保留清单：
`.tmp/detail-two-candidates-20261022-v1/stable-data-cleanup-plan.json`。
执行和后核：
`stable-data-cleanup-result.json`，passed=true。
