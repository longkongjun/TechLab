# 如何在 Indexed-Media 中落地文件整理 DSL

在 Indexed-Media 中，管理员可以通过规则改变哪些整理行为？Core 需要掌握哪些文件事实才能给出可靠的预演？关于 DSL 的词汇、语义和中间表示，先读 CreaLoom 的学习选题。[如何理解 DSL](https://github.com/longkongjun/CreaLoom/blob/main/topic-pool.md#如何理解-dsl)

## 从现有行为出发

当前 Rust Core 已有结构化匹配规则。规划器使用已确认的媒体身份、目标配置和文件系统检查结果生成 `PlanDraft`，其中记录目标路径、操作、授权结论和风险。电影、剧集和通用视频各有固定命名逻辑。比较方案时，以这套行为为基线。[规则模型](https://github.com/longkongjun/Indexed-Media/blob/ae5abc96b72faf4922ab4ebef0d2d52b4265d945/apps/core/src/organization/model.rs#L316) · [规划器](https://github.com/longkongjun/Indexed-Media/blob/ae5abc96b72faf4922ab4ebef0d2d52b4265d945/apps/core/src/organization/planner.rs#L293)

## 要讨论的设计选择

- 如果管理员只想调整季目录和文件名，扩展现有目标配置与命名模板能覆盖多少需求？哪条真实规则会开始需要条件或组合？
- 如果引入 DSL，哪些错误能在保存规则时发现，例如剧集模板引用了照片字段？哪些判断必须等文件出现，例如该文件覆盖哪几集、目标路径是否已被占用？
- 如果照片和剧集共用一套语法，资源类型如何声明可用字段和动作？用“照片原图只允许原地登记或追加备份”检验边界。[照片域需求](https://github.com/longkongjun/Indexed-wiki/blob/main/product/requirements.md#L50)

## 怎样比较方案

第一组样例沿用 Core 的双集视频测试。同一个文件含《The Expanse》第二季第 1、2 集，先看两种方案能否预演出既有路径，再让管理员只改季目录名称。观察哪些代码或配置需要变化、多集文件是否仍只对应一个目标，以及目标已被占用时能否解释暂停原因。[现有规划器测试](https://github.com/longkongjun/Indexed-Media/blob/ae5abc96b72faf4922ab4ebef0d2d52b4265d945/apps/core/tests/organization_planner.rs#L113)

讨论后再决定采用扩展配置还是引入独立的规则表示，以及是否值得在 TechLab 做只读预演原型。
