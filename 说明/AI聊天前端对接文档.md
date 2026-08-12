# AI 聊天对接文档

## 接口概览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/chat/models` | 获取可用 AI 模型列表 |
| `POST` | `/chat` | SSE 流式 AI 聊天 |
| `DELETE` | `/chat` | 清除当前用户聊天记忆 |
| `POST` | `/chat/image` | AI 生成图片 |

所有接口均需要在请求头携带 JWT：`Authorization: Bearer <jwt>`

---

## 一、获取可用模型列表

### GET /chat/models

返回当前可用的 AI 模型列表，用于前端渲染下拉选择器。

### 请求

```
GET /chat/models
Authorization: Bearer <jwt>
```

### 响应

```json
{
  "code": 0,
  "msg": "操作成功",
  "data": [
    { "modelId": "Qwen/Qwen3-8B", "modelName": "通义千问3 8B" },
    { "modelId": "deepseek-ai/DeepSeek-V3", "modelName": "DeepSeek V3" },
    { "modelId": "THUDM/GLM-4-9B-Chat-1M", "modelName": "GLM-4 9B" }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Integer | 0 = 成功 |
| msg | String | 响应消息 |
| data | Array | 模型列表 |
| data[].modelId | String | 模型标识（传参时用此值） |
| data[].modelName | String | 模型显示名称（展示给用户） |

---

## 二、AI 聊天（SSE 流式）

### POST /chat

基于 SSE（Server-Sent Events）的流式 AI 聊天接口。客户端发送用户输入和模型选择，服务端以事件流形式实时返回 AI 回复。

### 请求

```
POST /chat
Authorization: Bearer <jwt>
Content-Type: application/json
```

**请求体：**

```json
{
  "userInput": "列出我的笔记",
  "modelId": "Qwen/Qwen3-8B"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userInput | String | 是 | 用户输入的消息 |
| modelId | String | 否 | 模型 ID（来自 `/chat/models`），不传则使用服务端默认模型 |

### 响应

响应为 SSE 事件流（`text/event-stream`），包含两种事件类型：

**正常消息事件：**
```
event: message
data: 好的，我来查询您的笔记列表。

event: message
data: 您共有 5 篇笔记...
```

**错误事件：**
```
event: error
data: {"error":"内部错误，请稍后重试"}
```

| 事件类型 | data 格式 | 说明 |
|---------|-----------|------|
| `message` | 纯文本 | AI 回复的文本片段（流式逐段返回） |
| `error` | JSON `{"error":"..."}` | 错误信息，流即将关闭 |

---

## 三、清除聊天记忆

### DELETE /chat

清除当前用户的全部聊天历史（JWT 中识别用户，无需传参）。

### 请求

```
DELETE /chat
Authorization: Bearer <jwt>
```

### 响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "记忆清除成功"
}
```

---

## 四、AI 图片生成

### POST /chat/image

同步接口，返回 MinIO 上的永久图片 URL。

### 请求

```
POST /chat/image
Authorization: Bearer <jwt>
Content-Type: application/json
```

**请求体：**

```json
{
  "userInput": "一只在太空中的猫"
}
```

### 响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "http://localhost:9000/ai-notes/ai-generated-xxxx.png"
}
```

---

## 五、前端实现（Vue3 + Composition API）

### 方案一：使用 fetch-event-source 库（推荐）

[@microsoft/fetch-event-source](https://www.npmjs.com/package/@microsoft/fetch-event-source) 支持 POST 请求和自定义请求头，专为 SSE 设计。

#### 安装

```bash
npm install @microsoft/fetch-event-source
```

#### 完整聊天组件（含模型选择）

```vue
<template>
  <div class="chat-container">
    <!-- 模型选择器 -->
    <div class="model-selector">
      <label>模型：</label>
      <select v-model="selectedModel" :disabled="isLoading">
        <option v-for="m in models" :key="m.modelId" :value="m.modelId">
          {{ m.modelName }}
        </option>
      </select>
    </div>

    <!-- 消息列表 -->
    <div class="messages" ref="messagesRef">
      <div v-for="(msg, i) in messages" :key="i" :class="msg.role">
        <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
        <div class="content">
          <!-- 显示当前使用的模型名称 -->
          <div v-if="msg.role === 'assistant' && msg.modelName" class="model-tag">
            {{ msg.modelName }}
          </div>
          {{ msg.content }}
        </div>
      </div>
      <!-- 正在输入指示 -->
      <div v-if="isLoading" class="assistant">
        <div class="avatar">AI</div>
        <div class="content thinking">思考中<span class="dots">...</span></div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <input
        v-model="inputText"
        placeholder="输入消息..."
        :disabled="isLoading"
        @keydown.enter="sendMessage"
      />
      <button :disabled="!inputText.trim() || isLoading" @click="sendMessage">
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'

const token = localStorage.getItem('token')

const models = ref([])           // 可用模型列表
const selectedModel = ref('')   // 当前选中的模型 ID
const messages = ref([])        // 消息列表
const inputText = ref('')       // 输入框内容
const isLoading = ref(false)    // 是否正在等待回复
const abortController = ref(null)
const messagesRef = ref(null)

// 页面加载时获取模型列表
onMounted(async () => {
  try {
    const res = await fetch('/chat/models', {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const data = await res.json()
    if (data.code === 200) {
      models.value = data.data
      // 默认选中第一个模型
      if (data.data.length > 0) {
        selectedModel.value = data.data[0].modelId
      }
    }
  } catch (err) {
    console.error('获取模型列表失败:', err)
  }
})

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isLoading.value) return

  // 查找当前选中模型的显示名称
  const currentModel = models.value.find(m => m.modelId === selectedModel.value)
  const modelName = currentModel ? currentModel.modelName : ''

  // 添加用户消息
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  isLoading.value = true
  scrollToBottom()

  // 添加占位的 AI 消息
  const aiMsg = { role: 'assistant', content: '', modelName }
  messages.value.push(aiMsg)

  abortController.value = new AbortController()

  try {
    await fetchEventSource('/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        userInput: text,
        modelId: selectedModel.value,
      }),
      signal: abortController.value.signal,

      onmessage(event) {
        if (event.event === 'message') {
          aiMsg.content += event.data
          scrollToBottom()
        }
        if (event.event === 'error') {
          const err = JSON.parse(event.data)
          aiMsg.content = `[错误] ${err.error}`
          scrollToBottom()
        }
      },

      onclose() {
        if (!aiMsg.content) {
          aiMsg.content = '（未获取到回复）'
        }
        isLoading.value = false
        abortController.value = null
        scrollToBottom()
      },

      onerror(err) {
        console.error('SSE 错误:', err)
        aiMsg.content = aiMsg.content || '连接失败，请稍后重试'
        isLoading.value = false
        abortController.value = null
        scrollToBottom()
        return // 返回非 undefined 阻止自动重连
      },
    })
  } catch (err) {
    if (err.name === 'AbortError') return
    console.error('请求失败:', err)
    aiMsg.content = aiMsg.content || '请求失败，请稍后重试'
  } finally {
    isLoading.value = false
    abortController.value = null
  }
}
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: 800px;
  margin: 0 auto;
}
.model-selector {
  padding: 8px 16px;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  gap: 8px;
}
.model-selector select {
  padding: 4px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 14px;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.user, .assistant {
  display: flex;
  margin-bottom: 16px;
  gap: 8px;
}
.assistant { justify-content: flex-start; }
.user { justify-content: flex-end; }
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}
.user .avatar {
  background: #1677ff;
  color: #fff;
}
.content {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  position: relative;
}
.user .content {
  background: #1677ff;
  color: #fff;
  border-radius: 12px 4px 12px 12px;
}
.assistant .content {
  background: #f5f5f5;
  border-radius: 4px 12px 12px 12px;
}
.model-tag {
  font-size: 11px;
  color: #999;
  margin-bottom: 4px;
  padding-bottom: 4px;
  border-bottom: 1px dashed #e0e0e0;
}
.thinking { color: #999; }
.dots { animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.input-area {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #eee;
}
.input-area input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  outline: none;
  font-size: 14px;
}
.input-area input:focus { border-color: #1677ff; }
.input-area button {
  padding: 10px 20px;
  background: #1677ff;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}
.input-area button:disabled {
  background: #d9d9d9;
  cursor: not-allowed;
}
</style>
```

---

### 方案二：使用原生 fetch + ReadableStream（无额外依赖）

```vue
<script setup>
import { ref, onMounted } from 'vue'

const token = localStorage.getItem('token')
const models = ref([])
const selectedModel = ref('')
const messages = ref([])
const inputText = ref('')
const isLoading = ref(false)

onMounted(async () => {
  const res = await fetch('/chat/models', {
    headers: { 'Authorization': `Bearer ${token}` }
  })
  const data = await res.json()
  if (data.code === 200) {
    models.value = data.data
    selectedModel.value = data.data[0]?.modelId || ''
  }
})

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isLoading.value) return

  const currentModel = models.value.find(m => m.modelId === selectedModel.value)

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  isLoading.value = true

  const aiMsg = { role: 'assistant', content: '', modelName: currentModel?.modelName || '' }
  messages.value.push(aiMsg)

  try {
    const response = await fetch('/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        userInput: text,
        modelId: selectedModel.value,
      }),
    })

    if (!response.ok) {
      aiMsg.content = `请求失败 (${response.status})`
      isLoading.value = false
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()

      let currentEvent = ''
      for (const line of lines) {
        if (line.startsWith('event: ')) {
          currentEvent = line.slice(7)
        } else if (line.startsWith('data: ')) {
          const data = line.slice(6)
          if (currentEvent === 'message') {
            aiMsg.content += data
          } else if (currentEvent === 'error') {
            try {
              const err = JSON.parse(data)
              aiMsg.content = `[错误] ${err.error}`
            } catch {
              aiMsg.content = `[错误] ${data}`
            }
          }
        }
      }
    }
  } catch (err) {
    console.error('请求失败:', err)
    aiMsg.content = aiMsg.content || '请求失败，请检查网络'
  } finally {
    isLoading.value = false
  }
}
</script>
```

---

## 六、关键要点

### 1. 模型选择流程

```
页面加载 → GET /chat/models → 渲染下拉框 → 用户选择 → 存入 selectedModel
                                                        ↓
发送消息 → POST /chat { userInput, modelId: selectedModel }
```

### 2. 为什么不能用原生 EventSource？

浏览器原生 `EventSource` 只支持 `GET` 请求，无法携带请求体和自定义请求头（如 `Authorization`）。AI 聊天需要：
- `POST` 方法发送用户输入
- `Bearer Token` 鉴权

因此必须使用 `fetch` 或 `fetch-event-source` 库。

### 3. 取消请求

用户发送消息后、AI 回复完成前，可以取消请求：

```javascript
abortController.value = new AbortController()

// 取消请求
function cancel() {
  if (abortController.value) {
    abortController.value.abort()
    isLoading.value = false
  }
}
```

### 4. 错误处理

前端需要处理几类错误：

| 场景 | 表现 | 处理方式 |
|------|------|---------|
| 网络断开 | fetch 抛出异常 | 显示"连接失败" |
| HTTP 4xx/5xx | response.ok 为 false | 检查状态码，提示用户 |
| SSE error 事件 | `event: error` | 解析 JSON 显示错误详情 |
| 空回复 | 流正常结束但无内容 | 显示"未获取到回复" |

### 5. 滚动行为

每次追加内容后自动滚动到底部，但用户主动上滑查看历史时不要强制滚动：

```javascript
let isNearBottom = true

function handleScroll() {
  const el = messagesRef.value
  if (!el) return
  isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}
```

### 6. 消息历史

目前未提供获取历史记录的接口，前端可以在本地缓存：

```javascript
watch(messages, (val) => {
  localStorage.setItem('chatHistory', JSON.stringify(val))
}, { deep: true })

const saved = localStorage.getItem('chatHistory')
if (saved) {
  messages.value = JSON.parse(saved)
}
```

---

## 七、完整流程时序

```
前端                              后端
│                                 │
├── GET /chat/models ──────────→  │
│   ←── [{ modelId, modelName }]  │
│                                 │
├── 用户选择模型，存入 selectedModel │
│                                 │
├── POST /chat ──────────────→    │
│   { userInput, modelId }        │
│   Authorization: Bearer <jwt>   │
│                                 │
│    ←── event: message           │
│         data: 您好，我来        │
│         查询您的笔记...          │
│                                 │
│    ←── event: message           │
│         data: 您共有 5 篇       │
│         笔记，分别是...          │
│                                 │
│    ←── [连接关闭]               │
│                                 │
├── 追加 AI 回复到界面            │
│                                 │
├── POST /chat ──────────────→    │
│   { userInput: "删除第3篇",     │
│     modelId: "Qwen/Qwen3-8B" }  │
│                                 │
│    ←── event: message           │
│         data: 确定要删除吗？    │
│                                 │
│    ←── [连接关闭]               │
│                                 │
└── 等待用户下一次输入            │
```

---

## 八、注意事项

1. **Token 有效期**：JWT 过期后请求会返回 401，需在请求拦截器中统一处理跳转到登录页
2. **并发控制**：同一时间只能有一条消息在发送，通过 `isLoading` 状态锁定
3. **连接断开**：SSE 流结束后不会自动重连，用户需重新发送消息
4. **敏感操作确认**：AI 执行删除等操作前会先向用户确认，前端正常展示确认消息即可
5. **内容安全**：AI 返回内容直接渲染到 DOM，建议对用户输入做 XSS 过滤
6. **modelId 容错**：如果传了不存在的 modelId，后端会自动回退到默认模型，不会报错
7. **模型切换不影响记忆**：所有模型共享同一套聊天记忆（按 userId 区分），切换模型后历史上下文仍然保留
