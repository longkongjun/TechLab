#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".source-cache"
DOCS_REPO = CACHE / "n8n-docs-repo"
SOURCE_REPO = CACHE / "n8n-repo"
DOCS_TREE = CACHE / "n8n-docs-tree.json"
SOURCE_TREE = CACHE / "n8n-repo-tree.json"
INDEXES = ROOT / "indexes"
BY_SECTION = INDEXES / "official-docs-by-section"


DOCS_SECTION_LABELS = {
    "administer": "管理与治理",
    "build": "工作流构建",
    "connect": "连接、API 与节点开发",
    "contribute": "贡献与写作规范",
    "deploy": "部署与自托管",
    "get-started": "入门",
    "integrations": "内置集成与节点",
    "privacy-and-security": "隐私与安全",
    "release-notes": "发布说明",
    "reusable-content": "复用内容片段",
    "document-templates": "文档模板",
    "skills": "文档协作技能",
    "styles": "写作风格规则",
    "repo-root": "文档仓库根目录",
    "other": "其他",
}

DOCS_SECTION_ORDER = [
    "get-started",
    "build",
    "integrations",
    "connect",
    "deploy",
    "administer",
    "privacy-and-security",
    "release-notes",
    "contribute",
    "reusable-content",
    "document-templates",
    "skills",
    "styles",
    "repo-root",
    "other",
]

DOCS_SECTION_INTROS = {
    "administer": "管理 n8n 实例中的用户、权限、凭据、日志、环境和治理能力。",
    "build": "说明如何创建、组织、运行和调试 n8n 工作流，包括数据处理、代码节点和 AI 工作流。",
    "connect": "覆盖 n8n API、CLI、MCP 连接、自定义节点开发和外部系统接入。",
    "contribute": "说明如何为 n8n 官方文档贡献内容、遵循风格规范并获取帮助。",
    "deploy": "说明云端、自托管、Docker、Kubernetes、队列模式、配置和扩容等部署主题。",
    "get-started": "面向新用户的 n8n 入门路径、基础概念和首次使用流程。",
    "integrations": "内置节点、触发器、凭据、常见问题和第三方服务集成说明。",
    "privacy-and-security": "说明隐私、安全、数据处理、权限、合规和安全实践。",
    "release-notes": "记录 n8n 版本发布、变更说明和升级关注点。",
    "reusable-content": "官方文档复用片段，通常被多个页面 include，不一定对应独立公开页面。",
    "document-templates": "官方文档写作模板，用于新增节点、功能、教程和发布说明。",
    "skills": "官方文档仓库内用于协作文档写作的技能说明。",
    "styles": "官方文档写作检查和风格规则。",
    "repo-root": "官方文档仓库的贡献、许可、风格指南和项目说明。",
    "other": "不属于主站目录的辅助文档或仓库配置文档。",
}

SOURCE_SECTION_LABELS = {
    "root": "源码仓库根目录",
    "docs": "源码仓库 docs",
    "docker": "Docker 镜像文档",
    "packages": "包与模块文档",
    "packages/@n8n": "@n8n 包文档",
    "packages/@n8n/cli": "@n8n CLI 文档",
    "packages/cli": "CLI 服务端模块",
    "packages/frontend": "前端包与设计系统",
    "packages/nodes-base": "内置节点源码文档",
    "packages/testing": "测试与质量文档",
    "scripts": "脚本与工程工具",
    "security": "安全工程文档",
    ".github": "GitHub 工作流与协作",
    ".agents": "内部 agent 技能",
    ".claude": "Claude 协作配置",
    ".opencode": "OpenCode 配置",
    ".vscode": "编辑器调试配置",
    "other": "其他源码文档",
}

SOURCE_SECTION_ORDER = [
    "root",
    "docker",
    "docs",
    "packages/@n8n/cli",
    "packages/@n8n",
    "packages/cli",
    "packages/frontend",
    "packages/nodes-base",
    "packages/testing",
    "packages",
    "scripts",
    "security",
    ".github",
    ".agents",
    ".claude",
    ".opencode",
    ".vscode",
    "other",
]

SOURCE_SECTION_INTROS = {
    "root": "源码仓库级说明，通常包含项目介绍、贡献规范、许可、安全和变更记录。",
    "docs": "源码仓库内的数据库、生成 schema、内部模块或工程参考文档。",
    "docker": "n8n Docker 镜像和运行器镜像相关说明。",
    "packages": "monorepo 内各 npm 包、SDK、运行时、工具和模块的说明。",
    "packages/@n8n": "@n8n 命名空间下的公共包、CLI、节点开发工具、AI、MCP 和工具库文档。",
    "packages/@n8n/cli": "n8n CLI 包的命令、指南和命令行使用说明。",
    "packages/cli": "n8n 后端 CLI、模块、命令、日志、source control、遥测和运行时相关文档。",
    "packages/frontend": "前端应用、设计系统、i18n、chat 组件、stores 和 UI 工程文档。",
    "packages/nodes-base": "官方内置节点包的开发、测试或特定节点说明。",
    "packages/testing": "端到端、性能、容器、规则引擎和测试基础设施说明。",
    "scripts": "仓库脚本、工程自动化和辅助工具说明。",
    "security": "安全扫描、供应链和安全流程相关说明。",
    ".github": "GitHub Actions、PR 模板、工作流和贡献协作规范。",
    ".agents": "n8n 内部 agent/skill 协作说明，偏工程内部使用。",
    ".claude": "Claude 插件、命令和 agent 协作配置，偏工程内部使用。",
    ".opencode": "OpenCode 相关技能或协作配置。",
    ".vscode": "本地编辑器调试说明。",
    "other": "源码仓库中无法归入主类目的 Markdown 文档。",
}

TOPIC_HINTS = [
    ("credential", "凭据配置、认证授权或安全保存方式"),
    ("credentials", "凭据配置、认证授权或安全保存方式"),
    ("oauth", "OAuth 授权和第三方登录/授权流程"),
    ("saml", "SAML 单点登录和身份提供方配置"),
    ("oidc", "OIDC 单点登录和身份提供方配置"),
    ("ldap", "LDAP 用户身份验证"),
    ("rbac", "角色、权限和访问控制"),
    ("permission", "权限分配和访问边界"),
    ("user", "用户、账号或成员管理"),
    ("workflow", "工作流创建、运行、共享、导入导出或版本管理"),
    ("execution", "执行记录、调试、重试、日志或运行状态"),
    ("node", "节点使用、节点开发或节点参数设计"),
    ("nodes", "节点使用、节点开发或节点参数设计"),
    ("trigger", "触发器节点、触发条件和外部事件接入"),
    ("webhook", "Webhook 入口、回调地址和外部请求触发"),
    ("api", "n8n API 调用、认证、分页或接口参考"),
    ("cli", "n8n CLI 命令和命令行管理"),
    ("mcp", "MCP server/client 连接和工具调用"),
    ("ai", "AI 工作流、AI 组件、评测、工具调用或智能助手"),
    ("agent", "AI agent、任务规划、工具调用或 agent 运行时"),
    ("langchain", "LangChain 相关 AI 节点或组件"),
    ("code", "Code 节点、表达式、脚本或自定义逻辑"),
    ("expression", "表达式语言、数据引用和转换函数"),
    ("data", "数据结构、数据转换、映射、二进制数据或数据表"),
    ("http", "HTTP 请求、分页、认证或接口调用"),
    ("docker", "Docker 部署、镜像或容器运行"),
    ("kubernetes", "Kubernetes 部署、扩容和运行配置"),
    ("queue", "队列模式、worker、Redis 或多进程执行"),
    ("database", "数据库配置、schema、Postgres 或 SQLite"),
    ("postgres", "Postgres schema、数据库表或迁移参考"),
    ("sqlite", "SQLite schema、数据库表或迁移参考"),
    ("environment", "环境变量、环境隔离或多环境配置"),
    ("source-control", "source control、Git 集成和环境间变更流转"),
    ("git", "Git 配置、分支策略或 source control 集成"),
    ("security", "安全配置、漏洞处理、权限边界或安全实践"),
    ("privacy", "隐私、数据处理、GDPR 或合规事项"),
    ("release", "版本发布、变更说明和升级注意事项"),
    ("template", "模板写作、模板使用或模板库管理"),
    ("test", "测试、评测、调试或质量验证"),
    ("troubleshoot", "故障排查、常见错误和修复步骤"),
    ("error", "错误处理、失败路径或异常恢复"),
    ("log", "日志、观测、审计或外部日志流"),
    ("license", "许可协议、商业边界或开源/源代码可用限制"),
    ("contributing", "贡献流程、PR 规范或协作约定"),
    ("readme", "该目录或模块的入口说明和导航"),
    ("summary", "该目录的章节摘要和导航结构"),
]


def run_git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def load_tree(path: Path) -> list[str]:
    with path.open("r", encoding="utf-8") as fh:
        data = json.load(fh)
    return sorted(
        item["path"]
        for item in data.get("tree", [])
        if item.get("type") == "blob" and item.get("path", "").lower().endswith(".md")
    )


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def first_heading(text: str) -> str:
    if not text:
        return ""
    lines = text.splitlines()
    in_frontmatter = False
    frontmatter_seen = False
    frontmatter_title = ""
    for i, line in enumerate(lines[:120]):
        stripped = line.strip()
        if i == 0 and stripped == "---":
            in_frontmatter = True
            frontmatter_seen = True
            continue
        if in_frontmatter:
            if stripped == "---":
                in_frontmatter = False
                continue
            match = re.match(r"title:\s*[\"']?(.+?)[\"']?\s*$", stripped)
            if match:
                frontmatter_title = match.group(1).strip()
            continue
        match = re.match(r"^#\s+(.+?)\s*$", line)
        if match:
            return clean_title(match.group(1))
    if frontmatter_seen and frontmatter_title:
        return clean_title(frontmatter_title)
    return ""


def clean_title(title: str) -> str:
    title = re.sub(r"\{[#.:].*?\}", "", title)
    title = re.sub(r"<[^>]+>", "", title)
    title = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", title)
    title = re.sub(r"\s+", " ", title)
    return title.strip(" #")


def path_label(path: str) -> str:
    stem = path.split("/")[-1]
    if stem.lower() == "readme.md":
        parts = [p for p in path.split("/")[:-1] if p not in {"docs"}]
        return prettify_slug(parts[-1] if parts else "README")
    if stem.lower() == "summary.md":
        return "SUMMARY"
    return prettify_slug(Path(stem).stem)


def prettify_slug(slug: str) -> str:
    slug = slug.replace("_", "-")
    slug = re.sub(r"\s*\(\d+\)$", "", slug)
    words = [w for w in re.split(r"[-\s]+", slug) if w]
    if not words:
        return slug
    keep_upper = {"api", "cli", "mcp", "ai", "rbac", "sso", "oidc", "saml", "ldap", "http", "json", "sql", "sdk", "gdpr", "ee"}
    return " ".join(w.upper() if w.lower() in keep_upper else w.capitalize() for w in words)


def md_escape(value: str) -> str:
    value = value.replace("\n", " ").replace("\r", " ")
    value = value.replace("|", "\\|")
    return value.strip()


def quote_path(path: str) -> str:
    return "/".join(quote(part, safe="()") for part in path.split("/"))


def docs_public_url(path: str) -> str:
    if not path.startswith("docs/"):
        return ""
    web = path[len("docs/") :]
    if web.startswith("reusable-content/"):
        return ""
    if web.endswith("/SUMMARY.md") or web == "SUMMARY.md":
        return ""
    if web.endswith("/README.md"):
        web = web[: -len("README.md")]
    elif web == "README.md":
        web = ""
    elif web.endswith(".md"):
        web = web[:-3] + "/"
    return "https://docs.n8n.io/" + quote_path(web)


def source_url(repo: str, branch: str, path: str) -> str:
    return f"https://github.com/{repo}/blob/{branch}/{quote_path(path)}"


def docs_section(path: str) -> str:
    parts = path.split("/")
    if path.startswith("docs/") and len(parts) > 1:
        return parts[1]
    if parts[0] in {"document-templates", "skills", "styles"}:
        return parts[0]
    if "/" not in path:
        return "repo-root"
    return "other"


def source_section(path: str) -> str:
    parts = path.split("/")
    if len(parts) == 1:
        return "root"
    if parts[0] in {".github", ".agents", ".claude", ".opencode", ".vscode", "docker", "docs", "scripts", "security"}:
        if parts[0] == "docker":
            return "docker"
        return parts[0]
    if parts[:2] == ["packages", "@n8n"]:
        if len(parts) > 2 and parts[2] == "cli":
            return "packages/@n8n/cli"
        return "packages/@n8n"
    if parts[:2] == ["packages", "cli"]:
        return "packages/cli"
    if parts[:2] == ["packages", "frontend"]:
        return "packages/frontend"
    if parts[:2] == ["packages", "nodes-base"]:
        return "packages/nodes-base"
    if parts[:2] == ["packages", "testing"]:
        return "packages/testing"
    if parts[0] == "packages":
        return "packages"
    return "other"


def kind_for_docs(path: str) -> str:
    name = path.split("/")[-1].lower()
    if path.startswith("docs/reusable-content/.gitbook/"):
        return "复用 include 片段"
    if path.startswith("docs/reusable-content/"):
        return "复用内容目录"
    if path.startswith("document-templates/"):
        return "文档模板"
    if path.startswith("skills/"):
        return "文档协作技能"
    if path.startswith("styles/"):
        return "写作风格规则"
    if name == "summary.md":
        return "章节摘要"
    if path.startswith("docs/"):
        return "公开文档页"
    return "仓库说明"


def kind_for_source(path: str) -> str:
    if path.startswith(".agents/") or path.startswith(".claude/") or path.startswith(".opencode/"):
        return "内部协作/agent 文档"
    if path.startswith(".github/"):
        return "GitHub 协作文档"
    if path.startswith("docs/generated/"):
        return "生成 schema 文档"
    if path.startswith("docs/"):
        return "源码 docs 文档"
    if path.startswith("docker/"):
        return "Docker 文档"
    if path.startswith("packages/"):
        return "包/模块文档"
    if path.startswith("scripts/"):
        return "脚本文档"
    if path.startswith("security/"):
        return "安全文档"
    if "/" not in path:
        return "仓库根文档"
    return "源码补充文档"


def topic_summary(path: str, title: str) -> str:
    haystack = re.sub(r"[^a-z0-9]+", " ", (path + " " + title).lower())
    haystack = f" {haystack} "
    topics: list[str] = []
    for key, desc in TOPIC_HINTS:
        key_text = re.sub(r"[^a-z0-9]+", " ", key.lower()).strip()
        if f" {key_text} " in haystack and desc not in topics:
            topics.append(desc)
        if len(topics) >= 2:
            break
    if topics:
        return "聚焦" + "、".join(topics)
    return "用于快速定位该层级的概念、配置步骤或参考资料"


def chinese_intro(path: str, title: str, section: str, source: str) -> str:
    if source == "docs":
        base = DOCS_SECTION_INTROS.get(section, DOCS_SECTION_INTROS["other"])
    else:
        base = SOURCE_SECTION_INTROS.get(section, SOURCE_SECTION_INTROS["other"])
    topic = topic_summary(path, title)
    base = base.rstrip("。；; ")
    if title and title.upper() not in {"README", "SUMMARY"}:
        return f"{base}。本页围绕「{title}」，{topic}。"
    return f"{base}，{topic}。"


def order_index(order: list[str], section: str) -> int:
    try:
        return order.index(section)
    except ValueError:
        return len(order)


def breadcrumb(path: str) -> str:
    parts = path.split("/")[:-1]
    if parts and parts[0] == "docs":
        parts = parts[1:]
    if not parts:
        return "根目录"
    return " / ".join(DOCS_SECTION_LABELS.get(part, SOURCE_SECTION_LABELS.get(part, prettify_slug(part))) for part in parts[:5])


def collect_docs_rows() -> list[dict[str, str]]:
    rows = []
    for path in load_tree(DOCS_TREE):
        local = DOCS_REPO / path
        text = read_text(local)
        title = first_heading(text) or path_label(path)
        section = docs_section(path)
        rows.append(
            {
                "section": section,
                "section_label": DOCS_SECTION_LABELS.get(section, DOCS_SECTION_LABELS["other"]),
                "kind": kind_for_docs(path),
                "path": path,
                "breadcrumb": breadcrumb(path),
                "title": title,
                "intro": chinese_intro(path, title, section, "docs"),
                "public_url": docs_public_url(path),
                "source_url": source_url("n8n-io/n8n-docs", "main", path),
            }
        )
    return sorted(rows, key=lambda row: (order_index(DOCS_SECTION_ORDER, row["section"]), row["path"]))


def collect_source_rows() -> list[dict[str, str]]:
    rows = []
    for path in load_tree(SOURCE_TREE):
        local = SOURCE_REPO / path
        text = read_text(local)
        title = first_heading(text) or path_label(path)
        section = source_section(path)
        rows.append(
            {
                "section": section,
                "section_label": SOURCE_SECTION_LABELS.get(section, SOURCE_SECTION_LABELS["other"]),
                "kind": kind_for_source(path),
                "path": path,
                "breadcrumb": breadcrumb(path),
                "title": title,
                "intro": chinese_intro(path, title, section, "source"),
                "source_url": source_url("n8n-io/n8n", "master", path),
            }
        )
    return sorted(rows, key=lambda row: (order_index(SOURCE_SECTION_ORDER, row["section"]), row["path"]))


def table(headers: list[str], rows: list[list[str]]) -> str:
    out = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        out.append("| " + " | ".join(md_escape(cell) for cell in row) + " |")
    return "\n".join(out)


def link(label: str, url: str) -> str:
    return f"[{label}]({url})" if url else ""


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.rstrip() + "\n", encoding="utf-8")


def commit_link(repo: str, commit: str) -> str:
    return f"https://github.com/{repo}/commit/{commit}"


def section_file_name(section: str) -> str:
    safe = section.replace("/", "__").replace(".", "_")
    return f"{safe}.md"


def render_official_index(rows: list[dict[str, str]], docs_commit: str, generated_at: str) -> str:
    counts = Counter(row["section"] for row in rows)
    section_rows = []
    for section, count in sorted(counts.items(), key=lambda item: order_index(DOCS_SECTION_ORDER, item[0])):
        label = DOCS_SECTION_LABELS.get(section, section)
        section_rows.append(
            [
                label,
                section,
                str(count),
                DOCS_SECTION_INTROS.get(section, DOCS_SECTION_INTROS["other"]),
                link("分节索引", f"official-docs-by-section/{section_file_name(section)}"),
            ]
        )
    detail_rows = []
    for row in rows:
        public = link("官方页面", row["public_url"]) if row["public_url"] else "无独立公开页面"
        detail_rows.append(
            [
                row["section_label"],
                row["kind"],
                row["breadcrumb"],
                row["title"],
                row["intro"],
                public,
                link("源码", row["source_url"]),
                row["path"],
            ]
        )
    return f"""# n8n 官方文档索引（中文简介）

生成时间：{generated_at}

数据来源：n8n 官方文档仓库 `n8n-io/n8n-docs`，默认分支 `main`，当前快照 `{docs_commit}`。

本文件不是全文翻译，而是文档定位索引：保留官方标题、官方页面/源码链接，并为每个条目补充中文简介。

## 按层级汇总

{table(["中文层级", "原始层级", "文档数", "中文简介", "分节索引"], section_rows)}

## 全量索引

{table(["中文层级", "类型", "层级路径", "官方标题", "中文简介", "官方页面", "源码", "源文件路径"], detail_rows)}
"""


def render_source_index(rows: list[dict[str, str]], source_commit: str, generated_at: str) -> str:
    counts = Counter(row["section"] for row in rows)
    section_rows = []
    for section, count in sorted(counts.items(), key=lambda item: order_index(SOURCE_SECTION_ORDER, item[0])):
        label = SOURCE_SECTION_LABELS.get(section, section)
        section_rows.append(
            [
                label,
                section,
                str(count),
                SOURCE_SECTION_INTROS.get(section, SOURCE_SECTION_INTROS["other"]),
            ]
        )
    detail_rows = []
    for row in rows:
        detail_rows.append(
            [
                row["section_label"],
                row["kind"],
                row["breadcrumb"],
                row["title"],
                row["intro"],
                link("源码", row["source_url"]),
                row["path"],
            ]
        )
    return f"""# n8n 源码仓库 Markdown 补充索引（中文简介）

生成时间：{generated_at}

数据来源：n8n 官方源码仓库 `n8n-io/n8n`，默认分支 `master`，当前快照 `{source_commit}`。

这个索引覆盖源码仓库中的 Markdown 文档。它包含公开 README、Docker/CLI/包文档，也包含内部协作、测试、生成 schema 等工程文档；使用时请优先把 `n8n-docs` 官方文档索引作为用户文档入口。

## 按层级汇总

{table(["中文层级", "原始层级", "文档数", "中文简介"], section_rows)}

## 全量索引

{table(["中文层级", "类型", "层级路径", "标题", "中文简介", "源码", "源文件路径"], detail_rows)}
"""


def render_sources(
    docs_rows: list[dict[str, str]],
    source_rows: list[dict[str, str]],
    docs_commit: str,
    source_commit: str,
    docs_date: str,
    source_date: str,
    generated_at: str,
) -> str:
    docs_counts = Counter(row["section"] for row in docs_rows)
    source_counts = Counter(row["section"] for row in source_rows)
    source_table = table(
        ["出处", "用途", "默认分支", "本地快照", "更新时间", "索引文件"],
        [
            [
                "官方文档站",
                "面向用户的公开文档入口。",
                "-",
                "-",
                "实时站点，以官方线上页面为准",
                "[https://docs.n8n.io/](https://docs.n8n.io/)",
            ],
            [
                "n8n-io/n8n-docs",
                "官方文档源码，主索引数据源。",
                "main",
                f"[{docs_commit[:12]}]({commit_link('n8n-io/n8n-docs', docs_commit)})",
                docs_date,
                "[official-docs-index.md](indexes/official-docs-index.md)",
            ],
            [
                "n8n-io/n8n",
                "产品源码仓库，补充 README、CLI、Docker、包和内部工程文档。",
                "master",
                f"[{source_commit[:12]}]({commit_link('n8n-io/n8n', source_commit)})",
                source_date,
                "[source-repo-markdown-index.md](indexes/source-repo-markdown-index.md)",
            ],
        ],
    )
    docs_section_rows = [
        [
            DOCS_SECTION_LABELS.get(section, section),
            section,
            str(count),
            DOCS_SECTION_INTROS.get(section, DOCS_SECTION_INTROS["other"]),
        ]
        for section, count in sorted(docs_counts.items(), key=lambda item: order_index(DOCS_SECTION_ORDER, item[0]))
    ]
    source_section_rows = [
        [
            SOURCE_SECTION_LABELS.get(section, section),
            section,
            str(count),
            SOURCE_SECTION_INTROS.get(section, SOURCE_SECTION_INTROS["other"]),
        ]
        for section, count in sorted(source_counts.items(), key=lambda item: order_index(SOURCE_SECTION_ORDER, item[0]))
    ]
    return f"""# n8n 文档出处与索引汇总

生成时间：{generated_at}

本目录只做“官方出处 + 文档索引 + 中文简介”汇总，不做全文翻译。索引以官方仓库快照为准，线上页面仍以 n8n 官方站点为最终来源。

## 官方出处

{source_table}

## 官方文档仓库层级

{table(["中文层级", "原始层级", "文档数", "中文简介"], docs_section_rows)}

## 源码仓库 Markdown 层级

{table(["中文层级", "原始层级", "文档数", "中文简介"], source_section_rows)}

## 索引文件

| 文件 | 内容 |
| --- | --- |
| [OUTLINE.md](OUTLINE.md) | 全局学习大纲和按问题查找入口。 |
| [indexes/official-docs-index.md](indexes/official-docs-index.md) | `n8n-io/n8n-docs` 全量 Markdown 索引，包含官方页面链接、源码链接和中文简介。 |
| [indexes/official-docs-by-section/](indexes/official-docs-by-section/) | 按官方文档顶层目录拆分的分节索引。 |
| [indexes/source-repo-markdown-index.md](indexes/source-repo-markdown-index.md) | `n8n-io/n8n` 源码仓库 Markdown 补充索引。 |
"""


def render_readme(
    docs_rows: list[dict[str, str]],
    source_rows: list[dict[str, str]],
    docs_commit: str,
    source_commit: str,
    generated_at: str,
) -> str:
    public_count = sum(1 for row in docs_rows if row["public_url"])
    return f"""# n8n Docs 中文索引

这个目录汇总 n8n 官方文档和官方源码仓库中的 Markdown 文档，提供官方出处、层级索引和中文简介。当前不做全文翻译。

## 快速入口

| 入口 | 说明 |
| --- | --- |
| [OUTLINE.md](OUTLINE.md) | 全局学习大纲和问题查找入口，建议先看这里。 |
| [sources.md](sources.md) | 官方出处、仓库快照、层级汇总表。 |
| [indexes/official-docs-index.md](indexes/official-docs-index.md) | 官方文档仓库全量索引，优先从这里查用户文档。 |
| [indexes/official-docs-by-section/](indexes/official-docs-by-section/) | 按 `administer`、`build`、`deploy`、`integrations` 等官方层级拆分。 |
| [indexes/source-repo-markdown-index.md](indexes/source-repo-markdown-index.md) | n8n 源码仓库 Markdown 补充索引，包含 README、CLI、Docker、包文档和内部工程文档。 |

## 当前快照

| 来源 | 文档数 | 快照 |
| --- | ---: | --- |
| `n8n-io/n8n-docs` | {len(docs_rows)} | `{docs_commit}` |
| `n8n-io/n8n-docs` 中可映射到 docs.n8n.io 的页面 | {public_count} | 同上 |
| `n8n-io/n8n` Markdown | {len(source_rows)} | `{source_commit}` |

生成时间：{generated_at}

## 使用建议

先看 [OUTLINE.md](OUTLINE.md)，它按学习顺序和问题类型组织入口。需要查全量条目时，再看 [official-docs-index.md](indexes/official-docs-index.md)。

需要了解源码仓库里的 CLI、Docker、包、测试或内部工程文档时，再查 [source-repo-markdown-index.md](indexes/source-repo-markdown-index.md)。

中文简介用于快速定位，不替代官方原文。涉及许可、部署参数、安全策略和版本行为时，以索引链接指向的官方页面或源码文件为准。
"""


def render_section_files(rows: list[dict[str, str]]) -> None:
    grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        grouped[row["section"]].append(row)
    for section, section_rows in sorted(grouped.items()):
        label = DOCS_SECTION_LABELS.get(section, section)
        detail_rows = []
        for row in section_rows:
            public = link("官方页面", row["public_url"]) if row["public_url"] else "无独立公开页面"
            detail_rows.append(
                [
                    row["kind"],
                    row["breadcrumb"],
                    row["title"],
                    row["intro"],
                    public,
                    link("源码", row["source_url"]),
                    row["path"],
                ]
            )
        content = f"""# {label}：n8n 官方文档分节索引

文档数：{len(section_rows)}

{DOCS_SECTION_INTROS.get(section, DOCS_SECTION_INTROS["other"])}

{table(["类型", "层级路径", "官方标题", "中文简介", "官方页面", "源码", "源文件路径"], detail_rows)}
"""
        write_file(BY_SECTION / section_file_name(section), content)


def main() -> None:
    generated_at = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    docs_commit = run_git(DOCS_REPO, "rev-parse", "HEAD")
    source_commit = run_git(SOURCE_REPO, "rev-parse", "HEAD")
    docs_date = run_git(DOCS_REPO, "log", "-1", "--format=%cI")
    source_date = run_git(SOURCE_REPO, "log", "-1", "--format=%cI")

    docs_rows = collect_docs_rows()
    source_rows = collect_source_rows()

    write_file(ROOT / "README.md", render_readme(docs_rows, source_rows, docs_commit, source_commit, generated_at))
    write_file(ROOT / "sources.md", render_sources(docs_rows, source_rows, docs_commit, source_commit, docs_date, source_date, generated_at))
    write_file(INDEXES / "official-docs-index.md", render_official_index(docs_rows, docs_commit, generated_at))
    write_file(INDEXES / "source-repo-markdown-index.md", render_source_index(source_rows, source_commit, generated_at))
    render_section_files(docs_rows)


if __name__ == "__main__":
    main()
