# AI API 设计

基础路径：`/api/v1/ai`。所有接口使用 Cookie 会话；写请求携带 `X-CSRF-Token`。AI 文本只按纯文本渲染。

## 对话

### 创建会话

`POST /sessions`

```json
{ "scene": "STUDY" }
```

场景支持 `STUDY`、`FITNESS`、`CAREER`、`EMOTIONAL_SUPPORT`。响应返回 `publicId`、`scene` 和 `status`。

### 流式消息

`POST /sessions/{sessionId}/messages:stream`

请求头必须接受 `text/event-stream`：

```json
{ "message": "请帮我拆解今天的学习任务" }
```

正常事件顺序为：

1. `meta`：会话、消息、模型和风险等级元数据。
2. `delta`：零到多段纯文本增量。
3. `done`：最终消息 ID 和状态。

L2/L3 安全响应使用 `meta -> safety -> done`，不得发送 `delta`。供应商或服务错误使用 `error`，客户端展示手动计划入口。

客户端只允许在响应流开始前遇到 `401` 时刷新 Cookie 并重试一次。开始读取 SSE 后不自动重放，避免重复消息。

### 会话历史

`GET /sessions/{sessionId}/messages`

只返回当前用户拥有的消息，跨用户访问返回 `404`。

## 结构化建议

### 目标草案

`POST /goal-template`：将当前用户拥有的会话整理为可编辑草案，不创建目标或任务。

```json
{ "sessionPublicId": "当前会话的 publicId" }
```

模型输出使用 `backend/src/main/resources/ai/goal-draft.schema.json` 中的契约，与前端 `GoalDraft` 的业务字段一致：

```json
{
  "title": "四周整理学习记录",
  "description": "建议每周整理三次学习记录，在第四周完成总结；频率待确认。",
  "dimensionCode": "KNOWLEDGE",
  "durationDays": 28,
  "weeklyFocus": "先整理要点，再记录仍不清楚的问题。",
  "starterTasks": [
    { "title": "整理课堂笔记", "estimatedMinutes": 20, "difficulty": 2 }
  ]
}
```

这是格式示例，不是某位用户的已保存目标。任务行动来自系统已有的学习模板；运行时检索实际 `PUBLISHED` 模板，不固定复用示例。

- 模型只能输出上述六个字段；`starterTasks` 为 1—3 项，每项只有 `title`、`estimatedMinutes`、`difficulty`。
- 周期 14—84 天，任务 5—60 分钟，难度为整数 1—3；维度限 `KNOWLEDGE`、`HEALTH`、`CAREER`、`RELATIONSHIP`、`WELLBEING`。
- API 响应在 `data` 中由服务端附加 `sourceSessionPublicId`、`model`、`providerRequestId`；模型不得生成这些元数据。
- 用户在现有“目标草案”面板编辑，通过“查看 JSON”预览，填入目标表单后再提交。聊天中的草案回复不等于已创建目标。
- 缺少必需字段、错误类型、额外字段、重复键、多个 JSON 文档、空任务列表会被拒绝；不再为缺失的数字字段偷偷补默认值。保留已有中文/英文维度别名归一化、整数越界收敛及文本长度截断兼容行为，最终返回值符合草案范围。
- 真实供应商遇到语法或草案结构错误时最多修复一次，修复保留原系统知识与用户上下文；再次无效返回 `AI_INVALID_GOAL_TEMPLATE`。草案输入和解码后的输出均经过现有安全检查。
- 当前场景没有可用的已发布模板时返回 `AI_KNOWLEDGE_UNAVAILABLE`，不得虚构模板。无用户消息返回 `AI_CONVERSATION_REQUIRED`；他人的会话返回 `AI_SESSION_NOT_FOUND`。

### 周计划建议

- `POST /suggestions`：按场景和目标生成可编辑建议集。
- `GET /suggestions/{setId}`：读取未过期建议集。
- `POST /suggestions/{setId}/adopt`：使用 `Idempotency-Key` 将用户确认的建议写入周计划。

建议不会自动修改目标或任务，必须由用户明确采纳。

## 系统提示词与知识库

对话、目标草案、任务建议统一使用 `AiPromptService`，不使用外部网页、外部向量库或自动导入的通用知识。

| 层级 | 来源 | 作用 |
| --- | --- | --- |
| 场景发布策略 | `ai_prompt_version` | 对话保留已有已发布场景策略，生成消息记录对应的 `prompt_version_id` |
| 系统规则 | `ai/prompts/system.md` | 事实来源、指令与数据隔离、安全边界、确认流程 |
| 本地知识 | `ai/knowledge-base.json` | 由已有代码、路由和迁移核对的条目，每条保留本地 `sources` 和知识库版本 |
| 模式指令 | `ai/prompts/chat.md`、`ai/prompts/goal-template.md` | 普通问答、待确认方案与六字段 JSON 的区别 |
| 输出契约 | `ai/goal-draft.schema.json` | 同时提供给聊天、结构化生成和服务端草案解析，避免字段规则分叉 |
| 请求上下文 | `AiContextService` | 当前用户的规划偏好、启用维度、进行中目标、当天状态、相关已发布模板和本会话历史 |

知识条目按场景及本地关键词匹配。任务模板只从当前场景、`PUBLISHED`、5—60 分钟、难度 1—3 的记录中取最多 100 条候选，按用户请求的文字匹配排序，注入最多 8 条；这是有界本地检索，并非全库语义搜索。退役模板不参与。草案检索词来自用户消息，避免直接把助手建议当成用户要求。

会话历史最多读取最近 12 条、合计 12,000 字符，保留时间顺序和真实 `USER`/`ASSISTANT` 角色；只读取所属用户、所属会话、未删除、未过期且完成的消息。普通对话排除 L2/L3 历史；草案生成保留用户风险内容用于安全拦截。不会读取其他会话或 `ai_memory`，不改变记忆默认关闭的设置。

每日状态按账号时区查询当天记录；缺失时提供 `todayStatus: null`，不再把旧状态称作“今日”。上下文不包含邮箱、出生日期、密码、好友聊天或内部记录 ID。用户输入与业务文本作为 JSON 数据发送，不拼进高优先级系统指令。

普通对话优先围绕目标名称、完成标准、维度、天数、每周重点、起步任务组织内容。信息不足时先澄清；建议中的未确认假设必须明示。用户明确要求 JSON 且信息充分时，提示模型仅输出六字段 JSON。**聊天文本仍是模型回复；需要服务端校验保证的 JSON，应使用目标草案接口/面板。** 提示词不能从机制上证明每句话的事实正确性，用户仍需确认内容。

### 维护和启用

1. 修改本地知识或提示词前，先核对现有实现；新增知识条目必须填写实际仓库来源，不把项目计划中尚未实现的功能写成事实。
2. 更新知识库 `version`，同步字段变更与契约测试。只有扩展输出协议时才修改 Schema 和前端数据类型，不新增无消费方的字段。
3. 资源随后端构建打包，重新启动后端生效；无需新增数据库迁移、向量服务或 API 密钥。继续使用已有 Qwen 配置。
4. `mock` 供应商保留确定性测试输出，不代表真实模型已执行提示词；内容质量需在用户授权的真实模型环境中另行验收。

定向验证：

```bash
cd backend
mvn -o -Dtest='AiPromptServiceTest,AiContextServiceTest,GoalDraftContractTest,GoalTemplateGenerationTest,GoalTemplateNormalizeTest,GoalTemplateClampTest,QwenContractTest' test
mvn -o -Dtest=AiFlowIT test
```

第一组使用本机假供应商验证知识来源、指令隔离、历史角色、草案契约与单次修复，不调用外部模型。第二组需要运行中的 Docker，用隔离 MySQL 验证数据库与接口链路。

## 千问适配

后端通过 `QwenProvider` 隔离供应商。`QWEN_PROVIDER=mock` 仅用于本地确定性验证；`QWEN_PROVIDER=qwen` 时调用 OpenAI 兼容的 `/chat/completions`，对话请求使用 `stream=true` 并解析服务端事件，模型、超时、基础 URL 和密钥全部由环境变量提供。模型请求默认等待 120 秒，SSE 连接默认保留 130 秒，可分别通过 `QWEN_TIMEOUT` 和 `QWEN_STREAM_TIMEOUT` 调整。缺少有效密钥或仍使用占位值时，应用会在启动阶段拒绝启用真实服务。

硅基流动使用 `QWEN_BASE_URL=https://api.siliconflow.cn/v1`，模型填写平台返回的完整 ID，例如 `Qwen/Qwen3-30B-A3B-Instruct-2507`。阿里云百炼使用 `QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`。密钥必须与基础 URL 所属平台匹配。

真实供应商密钥不得写入仓库。结构化输出最多修复一次，无效输出、超时、鉴权失败、限流和非 2xx 响应统一映射为可恢复的 AI 服务错误；错误正文不会回传供应商响应内容。
