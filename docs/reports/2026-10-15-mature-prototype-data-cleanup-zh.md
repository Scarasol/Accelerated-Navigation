# 已落地旧原型数据清理记录

2026-10-15 按用户要求，清理 `.tmp` 中较旧、功能已接入的原型验证数据，保留文档和源码。未按日期批量删除整个目录。

## 清理结果

| 项目 | 结果 |
| --- | ---: |
| 纳入清理的旧实验目录 | 64 |
| 删除的数据文件 | 16,854 |
| 删除文件总大小 | 4,941,119,245 字节，约 4.60 GiB |
| 删除空目录 | 7,463 |
| 清理前 `.tmp` 大小 | 约 11.80 GiB |
| 清理后 `.tmp` 大小，含清理清单 | 约 7.21 GiB |

大小按普通文件的逻辑长度统计，跳过所有目录链接，不重复计算链接目标。清理清单约 10.46 MiB，净减少约 4.59 GiB；没有另外创建旧数据备份。

主要范围是 10 月 6 日至 13 日的几何、跳跃、边界、门、扫描复用、压缩缓存、寻路接入与修复、速度第一阶段等旧验证。删除重复测试世界、编译产物、缓存、原始测量、性能采样及运行日志。

| 主要目录 | 删除量 MiB |
| --- | ---: |
| `navigation-speed-root-20261013` | 980.29 |
| `navigation-native-detail-fix-20261013` | 687.46 |
| `navigation-production-integration-20261012` | 660.28 |
| `navigation-production-release-20261013` | 521.95 |
| `segment-landing-fix-20261012` | 393.87 |
| `segment-execution-validation-20261012` | 303.43 |
| `navigation-command-entity-20261013` | 262.09 |
| `segment-debug-20261012b` | 240.71 |
| `search-budget-validation-20261012` | 194.26 |

完整目录、逐文件删除清单及保留文件哈希见 [plan.json](../../.tmp/cleanup-mature-prototypes-20261015/plan.json)，执行结果见 [result.json](../../.tmp/cleanup-mature-prototypes-20261015/result.json)，执行源码见 [cleanup.ps1](../../.tmp/cleanup-mature-prototypes-20261015/cleanup.ps1)。

## 保留范围

- 原型源码、脚本、文档、配置、审查提示词与回答、身份清单和结构化汇总；含源码的历史压缩包也保留。
- 最新共享拓扑研究与 10 月 15 日接入、补充及冷阶段验证证据。
- 动态预算及待结清性能验收数据、暂停且未通过的异步持久化研究数据。
- `run` 中用户世界、工作区构建目录、Git 数据及原有未提交改动。

旧实验目录中仍被当前验收使用的公共依赖单独保护：

- `navigation-production-release-20261013/runtime/libraries`：所有独立 Forge 验证共用的真实运行库，263 个目录链接指向此处；所有链接均跳过。
- `segmented-navigation-prototype-20261012/inputs/world`：四个冻结区域输入文件。
- `segment-execution-validation-20261012/world-inputs-server-7.json` 和 `stages.json`：输入身份及路线配置。
- `segment-landing-fix-20261012/build-guarded/classes` 和 `resources/server.properties`：元数据规范化工具及当前服务配置。
- `store-level5-integration-20261011/compile-classpath.txt`：当前工具类路径，116 个条目全部存在，未引用其他 `.tmp` 产物。
- `navigation-command-entity-20261013/server-speed/mods/lostcities.jar`：当前原参数路线所需模组。

## 核对与历史证据边界

删除前逐项检查路径位于选定 `.tmp` 目录内、文件未变化且不受 Git 跟踪；逐文件删除，不递归删除链接或目录树。仅在确认普通目录为空后移除目录。

清理后 7,682 个选中目录保留文件、290 个现有 `src/docs/tools` 文件及 3 个 `.tmp` 根文件的 SHA-256 与清理前一致；32,138 个未选中证据文件的存在性、长度和修改时间一致。公共依赖、四个区域输入哈希、116 个类路径条目均核对通过。随后仅新增本记录，并在任务索引加入清理说明；未改生产源码，未运行游戏验收，未提交或推送 Git。

本次按用户授权移除了部分历史成功、失败及超时实验的原始数据。旧报告中的“原始数据仍保留”“可以直接复算”应按本记录及逐文件清单限定；保留的历史结论和审查文档不等于这些旧实验仍能完整复算。当前共享拓扑及未结清任务的原始证据保持保留。
