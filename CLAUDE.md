# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project (skip tests for speed during development)
mvn clean package -DskipTests

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=JwtTest

# Run the application
mvn spring-boot:run

# Start infrastructure (PostgreSQL/ParadeDB, Redis, MinIO)
docker compose -f dockerFlie/docker-compose.yml up -d
```

## Project Overview

**ai-notes** is a Spring Boot 3.3 + Java 21 backend for an AI-powered notes app. Users write and organize notes (articles), and interact with them via an AI chat powered by SiliconFlow (supporting multiple models like Qwen, DeepSeek, GLM). ParadeDB (PostgreSQL with pg_search + pgvector) provides full-text search and vector search capabilities.

## Architecture

**Standard layered architecture**: Controller -> Service -> Mapper (MyBatis) -> PostgreSQL

### Project Structure

```
src/main/java/com/chj/
├── AINotesApplication.java          # 启动类 (@EnableAsync)
├── controller/
│   ├── ArticleController            # 文章 CRUD + 偏移/游标分页
│   ├── CategoryController           # 分类 CRUD
│   ├── UserController               # 用户注册/登录/更新/改密码
│   ├── ChatController               # AI 聊天(SSE) + 图片生成 + 模型列表
│   └── UploadController             # MinIO 文件上传
├── service/
│   ├── ArticleService.java          # 文章业务接口
│   ├── CategoryService.java         # 分类业务接口
│   ├── UserService.java             # 用户业务接口
│   ├── ChatService.java            # AI 聊天业务接口
│   └── impl/
│       ├── ArticleServiceImpl       # 文章业务实现 (embedding 生成、混合搜索)
│       ├── CategoryServiceImpl      # 分类业务实现
│       ├── UserServiceImpl          # 用户业务实现 (MD5 加密)
│       └── ChatServiceImpl          # AI 聊天实现 (SSE 流式 + 图片生成)
├── mapper/
│   ├── ArticleMapper.java           # 文章数据访问 (注解 + XML 混合)
│   ├── CategoryMapper.java          # 分类数据访问 (全注解)
│   └── UserMapper.java              # 用户数据访问 (全注解)
├── tool/                             # Spring AI @Tool 函数 (LLM 可调用)
│   ├── ArticleTool.java             # 笔记相关工具 (查数量、搜索、增删改、统计等)
│   ├── CategoryTool.java            # 分类相关工具 (增删改查、别名建议)
│   ├── UserTool.java                # 用户相关工具 (获取当前用户信息)
│   └── TtlToolCallbackWrapper.java  # TTL 上下文传递包装器
├── config/
│   ├── ChatConfiguration.java       # ChatMemory、多模型 ChatClient 注册表、SiliconFlow 配置
│   ├── WebConfig.java               # 登录拦截器注册
│   └── FloatArrayTypeHandler.java   # float[] <-> PostgreSQL vector 类型转换
├── pojo/                             # 实体/DTO
│   ├── Article.java                 # 文章实体 (含 embedding 字段)
│   ├── Category.java                # 分类实体 (含 Add/Update 校验分组)
│   ├── User.java                    # 用户实体
│   ├── PageBean.java                # 分页结果 (total/items/hasMore)
│   ├── CategoryStats.java           # 分类统计
│   ├── ChatInput.java               # 聊天请求 (userInput + modelId)
│   └── Result.java                  # 统一响应结果
├── vo/
│   └── ArticleVO.java               # 文章视图对象 (含分类名、用户昵称)
├── interceptors/
│   └── LoginInterceptor.java        # JWT 解析 + Redis 会话校验
├── aop/
│   └── AutoFillAspect.java          # 自动填充 createTime/updateTime/createUser
├── anno/
│   ├── AutoFill.java                # 标记需要自动填充的 service 方法
│   └── State.java                   # 状态参数校验注解 (@Constraint)
├── validation/
│   └── StateValidation.java         # State 注解校验器 (已发布|草稿)
├── utils/
│   ├── JwtUtil.java                 # JWT 生成/解析
│   ├── Md5Util.java                 # MD5 加密
│   ├── ThreadLocalUtil.java         # TransmittableThreadLocal 封装
│   ├── MinioUtil.java               # MinIO 文件上传
│   └── PromptUtil.java              # 加载 AI 提示词文件 (静态块加载)
└── exception/
    └── GlobalExceptionHandler.java  # 全局异常处理 (@RestControllerAdvice)

src/main/resources/
├── application.yml                  # 主配置 (DB/Redis/MinIO/AI 模型)
├── application-local.yml            # 本地配置 (API Key)
├── prompt/
│   ├── system-prompt.txt            # AI 系统提示词
│   └── image-prompt.txt             # AI 图片生成提示词前缀
└── com/chj/mapper/
    └── ArticleMapper.xml            # 文章搜索 SQL (BM25 + 向量搜索)
```

### Key Packages Summary

| Package | Purpose |
|---------|---------|
| `controller/` | REST endpoints for articles, categories, users, chat (SSE), file upload |
| `service/impl/` | Business logic with interface/impl pattern |
| `mapper/` | MyBatis data access interfaces + XML mappings |
| `tool/` | Spring AI `@Tool` classes exposed as LLM function calls |
| `config/` | Bean config (AI chat, interceptors, type handlers) |
| `interceptors/` | JWT + Redis authentication interceptor |
| `pojo/` | Entities, DTOs (PageBean, Result, CategoryStats) |
| `vo/` | View objects for query results |
| `aop/` | Auto-fill aspect for common fields |
| `anno/` | Custom annotations (@AutoFill, @State) |
| `utils/` | JWT, MD5, MinIO, ThreadLocal utilities |

## Coding Conventions

### Response Result

All API responses use the unified `Result<T>` wrapper:

```json
{ "code": 0, "message": "操作成功", "data": ... }
```

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Error |

**Rules:**
- Return `Result.success()` or `Result.success(data)` for successful operations
- Return `Result.error("message")` for failures — always provide a clear Chinese error message
- The `data` field carries the response payload (can be `null` for success with no data)

### Naming Conventions

- **Entities**: Singular nouns (`Article`, `Category`, `User`) — map directly to DB tables
- **DTOs/VOs**: Suffix with purpose (`PageBean`, `CategoryStats`, `ArticleVO`)
- **Services**: Interface + `Impl` suffix (`ArticleService` / `ArticleServiceImpl`)
- **Tools**: Suffix with `Tool` (`ArticleTool`, `CategoryTool`, `UserTool`)
- **Annotations**: Descriptive names (`@AutoFill`, `@State`)

### Validation

- Use Jakarta Validation annotations on entity fields: `@NotEmpty`, `@NotNull`, `@Pattern`, `@URL`, `@Email`
- Custom `@State` annotation constrains article state to "已发布" or "草稿"
- Use validation groups (`Category.Add.class`, `Category.Update.class`) for different operations

### Auto-fill via AOP

- Annotate service methods with `@AutoFill(AutoFill.OperationType.INSERT)` or `@AutoFill(AutoFill.OperationType.UPDATE)`
- `INSERT`: auto-sets `createTime`, `updateTime`, `createUser`
- `UPDATE`: auto-sets `updateTime`
- `createUser` comes from `ThreadLocalUtil.get()` (set by `LoginInterceptor`)

### Error Handling

- `GlobalExceptionHandler` catches all exceptions and returns `Result.error(e.getMessage())`
- Never let exceptions propagate with stack traces to the client
- Always provide meaningful error messages in Chinese

### Method Documentation

- Public methods should have Javadoc with `@param` and `@return` where applicable
- Tool methods (`@Tool`) must have clear `description` so the LLM understands their purpose

## AI Integration

- **Chat endpoints** (`/chat`):
  - `POST /chat` — SSE chat stream (model selected via `ChatInput.modelId`, falls back to default)
  - `DELETE /chat` — clears the current user's chat memory
  - `POST /chat/image` — generates an image (prompt prefixed with `image-prompt.txt`), uploads to MinIO, returns URL
  - `GET /chat/models` — lists available AI models (modelId + modelName)
- **Tool functions**: `ArticleTool`, `CategoryTool`, `UserTool` are `@Component` + `@Tool` beans that the LLM can call to perform CRUD operations
- **Chat memory**: JDBC-backed chat history persistence (`MessageWindowChatMemory`, max 10 messages), auto-initialized schema. Conversation ID = user ID (isolates memory per user)
- **Thread safety**: `TtlToolCallbackWrapper` solves ThreadLocal context loss when Spring AI switches from Tomcat to WebFlux scheduler threads (uses Alibaba TTL `capture/replay/restore`)
- **System prompt**: Loaded from `resources/prompt/system-prompt.txt` via `PromptUtil` — instructs the model to answer in Chinese, cite note content, and avoid describing its thought process
- **Multi-model support**: `ChatConfiguration` builds a `Map<String, ChatClient>` registry, keyed by model ID. If user requests an unregistered model, falls back to `siliconflow.default-model`
- **Hybrid search**: `ArticleServiceImpl.listArticle()` combines ParadeDB BM25 full-text search (`@@` operator) with vector similarity search (`<=>` operator). Vector results supplement BM25; falls back to BM25 only on failure

## Database (PostgreSQL via ParadeDB)

Tables: `user`, `category`, `article`

- `article` table has a `vector` column (pgvector) storing embeddings from BAAI/bge-m3
- Full-text search uses ParadeDB's `@@` operator on `content` field (see `ArticleMapper.xml`)
- Vector similarity search uses `<=>` (cosine distance) operator on `embedding` column

## Authentication

- JWT generated on login, stored in Redis with 1-hour TTL (session management)
- `LoginInterceptor` protects all endpoints except `/user/login` and `/user/register`
- `LoginInterceptor` also populates `ThreadLocalUtil` with JWT claims (user id, username)
- Passwords hashed with MD5 before storage
- On password change, the old JWT is deleted from Redis (force re-login)

## Pagination

Two strategies in `ArticleController`:
- **Offset pagination**: PageHelper (`pageNum`/`pageSize`) — for traditional lists
- **Cursor pagination**: `lastId`/`pageSize` — for infinite scroll (uses `WHERE id > #{lastId}`)

`PageBean` has an optional `hasMore` field (serialized with `@JsonInclude(NON_NULL)`) used by cursor pagination.

## Infrastructure (Docker Compose)

| Service | Image | Port |
|---------|-------|------|
| postgres | paradedb/paradedb:latest | 5432 |
| redis | redis:6.2 | 6379 |
| minio | minio/minio:latest | 9000 (API), 9001 (Console) |

MinIO bucket `ai-notes` needs anonymous download access configured after startup (see `说明.md`).

## Important Configuration

- **SiliconFlow API key**: Set in `application-local.yml` under `apikey.SiliconFlow`
- **DB credentials**: PostgreSQL `root/1234`, Redis `1234`, MinIO `minioadmin/minioadmin` — all for local dev only
- **Spring AI version**: 1.0.4 (needs `repo.spring.io/milestone` repository in pom.xml)
- **Default AI model**: `Qwen/Qwen3-8B` (configurable via `siliconflow.default-model`)
- **Embedding model**: `BAAI/bge-m3` (via SiliconFlow)
- **Image model**: `Tongyi-MAI/Z-Image-Turbo` (via SiliconFlow)
