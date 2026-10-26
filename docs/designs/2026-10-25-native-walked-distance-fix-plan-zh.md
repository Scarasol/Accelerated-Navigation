# 原生搜索累计距离与接受前驱同步修复

日期：2026-10-25。用户授权先与1017形成共识后修复。
同日起，全部DeepSeek方法讨论、代码和数据审核统一交给用户指定1017/max，
直到用户另行通知，覆盖此前按方法/代码数据区分provider的约定。

## 范围

母版为冻结NCH隔离候选，另建NWD，不直接接入生产。
复用[四条partial原因](../reports/2026-10-23-node-cache-partial-direct-cause-zh.md)：
原循环在判断新前驱之前先覆盖邻点walkedDistance，拒绝候选后留下失真累计长度。
四条真实失败输入中，可达路径约13.071/12.071格，
却按19.657/18.657格裁掉原range19.402/18.440。
本轮不将最初污染父节点或当次宏观依赖批次伪称已逐事件还原。

唯一语义修改：

```java
float candidateWalked = parent.walkedDistance + distance;
float newG = parent.g + distance + next.costMalus;
if (candidateWalked < range && (!next.inOpenSet() || newG < next.g)) {
    next.walkedDistance = candidateWalked;
    next.cameFrom = parent;
    next.g = newG;
    // 保持原有h及heap处理
}
```

不以含malus的g代替几何长度；范围、严格小于、访问上限、目标组、
闭集、权重、region、段长、宏观、worker、调度和其它缓存均不改。
一般带malus且有长度约束的单标签图搜索不因此获得全局最优或完备保证。
修复错误裁剪可能增加部分分支的合法展开，不预设所有成本下降。

## 实现共识

官方1017首轮5228字节自含包正常返回，选择三处精确FIELD Redirect；
方法同意具备隔离实现资格，不代替代码、数据及生产审核。

- 每次内层搜索入口捕获一次原raw标志，初始化每实例float临时值。
- 原walkedDistance写入：项目raw时只保存在临时值，普通搜索仍按原行为写。
- 条件处walkedDistance读取：raw时使用候选值，保留父节点的原字段读取。
- 接受分支cameFrom写入：raw时同步提交候选长度，再按原方式写前驱。

仅新增NativeWalkedDistanceMixin及清单项，无全循环Overwrite，
无每邻点CallbackInfo、Operation、LocalRef、表或集合。
入口Inject每搜索一次CallbackInfo，三处helper和boolean分支仍有成本，不能宣称零开销。
PathFinder原本含可变heap/neighbors，同实例非并发安全；沿用该调用前提。
raw作用限定在原NativeNavigationSearch入口，原finally清理ThreadLocal；
同实例后续查询的HEAD必重新捕获标志，异常后也不得沿用旧标志。

三处字段命中和入口均require/expect/allow=1，
真实转换类核对父walked读取保留，提前字段写入已移除，接受分支唯一。
1017初次答复关于allow=1的措辞有误：它是最大注入数约束，并非放宽；
使用精确上下限，不据此删掉上限。实际raw API名为raw()。

## 验证

先使用独立实际Forge控制，不进入性能包：

- 原四组捕获起点、完整目标及原range直接可达，逐节点累计长度与路径前缀一致。
- 真实PathFinder合成有向图覆盖拒绝更差g、拒绝超range的新更好g、
  接受更好g/changeCost、差好差序列、非零malus及严格范围边界、访问上限和断开图。
- raw与非raw作用隔离，同一PathFinder连续复用及异常后重新入场。
- 原真实地面/飞行/游泳简单路径，raw与非raw完整路径相同。

合成图只测试字段更新与控制分支，不冒充Minecraft世界邻接或性能样本。
生产原文件、原脏改、默认包及历史封存核对，控制与正式性能包分离。
原探针Java类保持，附带refmap允许仅新增本Mixin映射，逐结构核对。

正确性和代码审核通过后，仅补新NWD一次原35ms/sigma5/seed20261021、
64/128/256实体各8/28格的六场896请求。
复用NCH、两轮NTL、NT和原生等有效历史，不重跑有效基线，不扩15/25/304矩阵。
沿用原首Path、到达、搜索wall/CPU/分配、完整tick及恢复计量；
保留全部不利路线与峰值，不将DEBUG摘要相同当完整输入相同。

本轮代码、数据审核均遵循新1017规则。
若审核响应截断或无正文，记录失败，不当作通过，也不调低用户要求的max等级。
修复通过与是否生产接入分别判断；既有NCH整体提速未通过记录不被覆盖。
