# n8n 文档出处与索引汇总

生成时间：2026-07-09T18:57:08+08:00

本目录只做“官方出处 + 文档索引 + 中文简介”汇总，不做全文翻译。索引以官方仓库快照为准，线上页面仍以 n8n 官方站点为最终来源。

## 官方出处

| 出处 | 用途 | 默认分支 | 本地快照 | 更新时间 | 索引文件 |
| --- | --- | --- | --- | --- | --- |
| 官方文档站 | 面向用户的公开文档入口。 | - | - | 实时站点，以官方线上页面为准 | [https://docs.n8n.io/](https://docs.n8n.io/) |
| n8n-io/n8n-docs | 官方文档源码，主索引数据源。 | main | [708095d82e90](https://github.com/n8n-io/n8n-docs/commit/708095d82e908ae21185b1fee4f082404f4433ce) | 2026-07-09T11:40:41+02:00 | [official-docs-index.md](indexes/official-docs-index.md) |
| n8n-io/n8n | 产品源码仓库，补充 README、CLI、Docker、包和内部工程文档。 | master | [885c250b12bd](https://github.com/n8n-io/n8n/commit/885c250b12bd3c6f3ccf6ecc3cc6c3db13e5a252) | 2026-07-09T12:49:39+03:00 | [source-repo-markdown-index.md](indexes/source-repo-markdown-index.md) |

## 官方文档仓库层级

| 中文层级 | 原始层级 | 文档数 | 中文简介 |
| --- | --- | --- | --- |
| 入门 | get-started | 6 | 面向新用户的 n8n 入门路径、基础概念和首次使用流程。 |
| 工作流构建 | build | 127 | 说明如何创建、组织、运行和调试 n8n 工作流，包括数据处理、代码节点和 AI 工作流。 |
| 内置集成与节点 | integrations | 946 | 内置节点、触发器、凭据、常见问题和第三方服务集成说明。 |
| 连接、API 与节点开发 | connect | 47 | 覆盖 n8n API、CLI、MCP 连接、自定义节点开发和外部系统接入。 |
| 部署与自托管 | deploy | 116 | 说明云端、自托管、Docker、Kubernetes、队列模式、配置和扩容等部署主题。 |
| 管理与治理 | administer | 42 | 管理 n8n 实例中的用户、权限、凭据、日志、环境和治理能力。 |
| 隐私与安全 | privacy-and-security | 5 | 说明隐私、安全、数据处理、权限、合规和安全实践。 |
| 发布说明 | release-notes | 8 | 记录 n8n 版本发布、变更说明和升级关注点。 |
| 贡献与写作规范 | contribute | 5 | 说明如何为 n8n 官方文档贡献内容、遵循风格规范并获取帮助。 |
| 复用内容片段 | reusable-content | 271 | 官方文档复用片段，通常被多个页面 include，不一定对应独立公开页面。 |
| 文档模板 | document-templates | 9 | 官方文档写作模板，用于新增节点、功能、教程和发布说明。 |
| 文档协作技能 | skills | 2 | 官方文档仓库内用于协作文档写作的技能说明。 |
| 写作风格规则 | styles | 1 | 官方文档写作检查和风格规则。 |
| 文档仓库根目录 | repo-root | 7 | 官方文档仓库的贡献、许可、风格指南和项目说明。 |
| 其他 | other | 3 | 不属于主站目录的辅助文档或仓库配置文档。 |

## 源码仓库 Markdown 层级

| 中文层级 | 原始层级 | 文档数 | 中文简介 |
| --- | --- | --- | --- |
| 源码仓库根目录 | root | 10 | 源码仓库级说明，通常包含项目介绍、贡献规范、许可、安全和变更记录。 |
| Docker 镜像文档 | docker | 2 | n8n Docker 镜像和运行器镜像相关说明。 |
| 源码仓库 docs | docs | 228 | 源码仓库内的数据库、生成 schema、内部模块或工程参考文档。 |
| @n8n CLI 文档 | packages/@n8n/cli | 19 | n8n CLI 包的命令、指南和命令行使用说明。 |
| @n8n 包文档 | packages/@n8n | 167 | @n8n 命名空间下的公共包、CLI、节点开发工具、AI、MCP 和工具库文档。 |
| CLI 服务端模块 | packages/cli | 9 | n8n 后端 CLI、模块、命令、日志、source control、遥测和运行时相关文档。 |
| 前端包与设计系统 | packages/frontend | 31 | 前端应用、设计系统、i18n、chat 组件、stores 和 UI 工程文档。 |
| 内置节点源码文档 | packages/nodes-base | 8 | 官方内置节点包的开发、测试或特定节点说明。 |
| 测试与质量文档 | packages/testing | 22 | 端到端、性能、容器、规则引擎和测试基础设施说明。 |
| 包与模块文档 | packages | 4 | monorepo 内各 npm 包、SDK、运行时、工具和模块的说明。 |
| 脚本与工程工具 | scripts | 4 | 仓库脚本、工程自动化和辅助工具说明。 |
| 安全工程文档 | security | 1 | 安全扫描、供应链和安全流程相关说明。 |
| GitHub 工作流与协作 | .github | 8 | GitHub Actions、PR 模板、工作流和贡献协作规范。 |
| 内部 agent 技能 | .agents | 30 | n8n 内部 agent/skill 协作说明，偏工程内部使用。 |
| Claude 协作配置 | .claude | 7 | Claude 插件、命令和 agent 协作配置，偏工程内部使用。 |
| OpenCode 配置 | .opencode | 1 | OpenCode 相关技能或协作配置。 |
| 编辑器调试配置 | .vscode | 1 | 本地编辑器调试说明。 |

## 索引文件

| 文件 | 内容 |
| --- | --- |
| [OUTLINE.md](OUTLINE.md) | 全局学习大纲和按问题查找入口。 |
| [indexes/official-docs-index.md](indexes/official-docs-index.md) | `n8n-io/n8n-docs` 全量 Markdown 索引，包含官方页面链接、源码链接和中文简介。 |
| [indexes/official-docs-by-section/](indexes/official-docs-by-section/) | 按官方文档顶层目录拆分的分节索引。 |
| [indexes/source-repo-markdown-index.md](indexes/source-repo-markdown-index.md) | `n8n-io/n8n` 源码仓库 Markdown 补充索引。 |
