# 开发执行计划（S0 – S9）

> 项目：CodeVisualPlugin · 代码结构可视化 + AI 语义辅助
> 目标：**推到 GitHub → clone 下来本地能跑 → 打开 IDE 就能用可视化 AI 辅助开发**
> 更新：2026-11-04（权限已放开，Claude 可直接构建/自测）

---

## 0. 执行规则（请先确认这三条）

1. **一次只推进一个 S**。每完成一步，我停下来，给出「测试卡」，你测通了我才动下一步。
2. **每步一个 commit**，提交信息带步骤号（`S2: 前端打包进插件`），任何一步不满意可以 `git revert` 单独回退。
3. **你测不过，我只修这一步**，绝不带着问题往前推。

每一步我交付四样东西：

| 交付项 | 说明 |
|---|---|
| 改动清单 | 改了哪些文件、为什么 |
| 我的自测证据 | 实际跑过的命令 + 真实输出（编译/构建/curl） |
| 你的测试卡 | 几分钟内能做完的点击步骤 + 预期现象 + 失败时给我什么 |
| 回滚方法 | 怎么退回上一步 |

---

## 1. 环境基线（已实测）

| 项 | 值 | 状态 |
|---|---|---|
| Gradle | 9.0.0（wrapper） | ✅ 可跑 |
| 编译 JDK | **必须 17/21**（已用 `corretto-17` 验证） | ⚠️ `JAVA_HOME=JDK 25` 时 Konlin 编译器报 `IllegalArgumentException: 25.0.2` |
| IDE 使用的 JDK | `.idea/gradle.xml` → `corretto-17` | ✅ 所以 IDE 里一直能编 |
| Node / npm | v24.14.0 / 11.9.0 | ✅ `npm run build` 成功 |
| 目标 IDE | 沙箱 IC-2024.2（`runIde`） | ✅ 插件可加载 |
| 你本机 IDE | IntelliJ IDEA 2025.3.3（build 253） | ⚠️ 当前 `untilBuild=243.*`，**装不进主 IDE** |
| 插件服务 | Jetty :8899 | ✅ 实测 `/parseCurrentFile` 返回正常 |

---

## 2. 十步总览

| 步骤 | 名称 | 产出 | 估时 | 风险 |
|---|---|---|---|---|
| **S0** | 基线固化 | ASCII 路径、JDK 钉死、git 仓库、清模板 | 1h | 低（会打断你正在跑的 vite） |
| **S1** | 契约打通 | 画布第一次显示真实结构图 | 代码已就绪，待你验收 | 低 |
| **S2** | 打包进插件 | 装个 zip 就能用，**不再需要 npm** | 2h | 中 |
| **S3** | 服务层健壮化 | 动态端口、鉴权、线程安全、多工程隔离 | 3h | 中 |
| **S4** | PSI 图谱 | 跨文件真实调用边、点节点跳源码 | 4h | 中高 |
| **S5** | 实时刷新 | 改代码画布自动更新（SSE） | 3h | 中 |
| **S6** | 接入 AI | 设置页 + 首个 Provider + 语义摘要 | 4h | 中 |
| **S7** | 缓存与增量 | 二次打开 0 次 API 调用 | 2h | 低 |
| **S8** | AI 动作与回写 | 解释/注释/单测 + diff 预览写回 | 4h | 高 |
| **S9** | 发布工程化 | GitHub Release、CI、README、LICENSE | 3h | 低 |

合计约 **26 小时**有效工作。S0–S2（3 小时）结束时你就有一个"发出去别人能装"的版本。

---

## 3. 详细步骤

### S0 · 基线固化　⏱1h

**为什么先做**：现在目录名 `code‑canvas‑vue` 里是 U+2011 非断字连字符（不是键盘上的 `-`），已经导致构建工具路径出现 `code%E2%80%91canvas%E2%80%91vue`；CLI 构建又被 JDK 25 卡死。这两件事不解决，后面每一步都会踩。

**改动清单**
- `code‑canvas‑vue/` → `frontend/`（ASCII 路径）
- 删除模板死代码：`src/components/HelloWorld.vue`、`src/assets/hero.png`、`src/assets/vue.svg`、`public/icons.svg`
- `src/style.css` 只保留 reset，删掉 Vite 落地页样式（其中 `#app{text-align:center}` 会干扰画布布局）
- `build.gradle.kts`：`kotlin { jvmToolchain(17) }`，删掉已废弃的 `kotlinOptions`
- 新增 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=17`），让 `.\gradlew.bat` 不再挑 JAVA_HOME
- `.gitignore` 补：`.intellijPlatform/`、`frontend/dist/`、`*.hprof`、`*.log`
- `git init` + 首次提交

**我的自测**
```
.\gradlew.bat clean buildPlugin      → BUILD SUCCESSFUL
cd frontend; npm run build            → dist 产出
```

**你的测试卡**
1. `cd C:\Users\ZhuanZ1\IdeaProjects\code_visual_plugin\frontend` → `npm run dev`，浏览器开 5173 能看到画布（会报"无法连接插件服务"，正常）
2. 关掉，回到项目根：`.\gradlew.bat runIde` → 沙箱 IDE 能起来，右侧工具窗还在

**失败时给我**：报错原文。**回滚**：`git reset --hard init`

---

### S1 · 契约打通　⏱ 代码已就绪，待验收

**已交付**
- 重写 `frontend/src/App.vue`：兼容新旧契约、分层布局、结构边/调用边、悬停高亮、点击出详情栏、去掉强制轮询
- 新增 `GraphContract.kt`：`GraphNode` / `GraphEdge` / `GraphBuilder`
- `JavaCodeParser.kt`：`ParseResult` 增加 `nodes` / `edges`（`packages` 保留兼容）

**我的自测证据**
- `clean compileKotlin` → BUILD SUCCESSFUL
- 用你正在跑的真实接口响应做归一化 → 产出 2 节点 / 1 边
- `vue/compiler-sfc` 编译 App.vue → 通过

**你的测试卡**
1. IDE 里打开 `dome/src/Main.java`
2. 右侧工具窗点「刷新解析」
3. **预期**：左边一个 `Main` 方块，右边一个 `main` 方块，中间一条带箭头的实线；底部显示 `节点 2 · 连线 1`
4. 点一下 `main` 方块 → 右侧详情栏出现：签名 `main`、语义「待 AI 生成」、下面一段源码
5. 鼠标悬停 → 相邻边变亮、其它节点变暗

> 若还是空白：在工具窗内按 `Ctrl + F5` 强刷（JCEF 对 HMR 不一定生效）。

**失败时给我**：工具窗截图 + `build/idea-sandbox/IC-2024.2/log/idea.log` 最后 50 行

---

### S2 · 打包进插件　⏱2h

**为什么关键**：这是"本地部署"的分水岭。现在别人 clone 下来必须自己装 Node、跑 dev server 才有画面；做完这步，**一个 zip 装进 IDE 就完整可用**。

**改动清单**
- `build.gradle.kts`：新增 `buildFrontend` 任务（`npm ci` + `npm run build`），挂到 `processResources`，把 `frontend/dist` 拷进 `src/main/resources/web`
- `PluginHttpServer.kt`：新增静态资源路由（`/` → `index.html`，`/assets/*` → 带 Content-Type，从 classpath `web/` 读）
- `MyToolWindowFactory.kt`：`loadURL` 改为 `http://127.0.0.1:8899/`；保留 `-Dcodecanvas.devServer` 开关走 5173
- `plugin.xml`：`untilBuild` 放宽到 `253.*`（否则装不进你的 2025.3.3）

**我的自测**
```
.\gradlew.bat clean buildPlugin
# 解压 zip 检查 web/index.html、web/assets/* 存在
curl http://127.0.0.1:8899/   → 返回 index.html
```

**你的测试卡**
1. **先把 vite dev server 关掉**（这是本步的关键验证条件）
2. `.\gradlew.bat runIde`
3. 打开一个 Java 文件 → 点刷新
4. **预期**：画布正常出图，且**全程没有 npm 参与**

**失败时给我**：`build/distributions/*.zip` 里 `plugins/.../lib/*.jar` 的内容列表 + 报错

---

### S3 · 服务层健壮化　⏱3h

**解决的问题**：端口写死 8899、绑 0.0.0.0、无鉴权、跨线程读编辑器、单例跨工程串味。

**改动清单**
- 端口改为动态分配（`:0`），实际端口写入单例供 UI 读取；启动失败降级提示而不是崩工具窗
- 显式绑定 `127.0.0.1`
- 启动生成随机 token；`/api/*` 校验 `Authorization`；`Origin` 白名单只允许自己的页面
- `PluginHttpServer` 从 `object` 单例改为项目级 `@Service(Service.Level.PROJECT)`，随工程关闭释放
- 读编辑器改为 `ApplicationManager.invokeAndWait` + `ReadAction.compute`
- 未知路由返回 404（现在返回 200）

**你的测试卡**
1. 同时打开两个工程 → 两个工具窗都能出图，互不影响
2. 关掉一个工程 → 另一个不受影响
3. 在编辑器里改一行**不保存** → 点刷新 → 画布反映的是改后内容
4. 浏览器直接访问 `http://127.0.0.1:<端口>/parseCurrentFile` → 应被拒绝（401）

---

### S4 · PSI 图谱　⏱4h

**解决的问题**：javaparser 没有工程索引，`printf`/`println` 这类跨文件调用连不出去，图永远是"一座孤岛"。

**改动清单**
- 新增 `analysis/CodeGraphService.kt`：用 `PsiJavaFile` / `PsiClass` / `PsiMethod` 取结构；用 `PsiMethodCallExpression.resolveMethod()` 拿到**真实目标方法**
- 节点 id 改为全限定名，跨文件去重
- 新增 `/api/open?nodeId=xxx`：前端点节点 → `OpenFileDescriptor.navigate(true)` 跳到源码
- javaparser 依赖降级为"非索引文件兜底"（或直接移除）

**你的测试卡**
1. 打开一个**多文件互相调用**的工程（比如 `CodePulse` 那个 Spring 项目）
2. 刷新解析
3. **预期**：边指向别的类的节点；节点数量明显多于单文件
4. 点某个节点 → IDE 光标跳到该方法定义处

---

### S5 · 实时刷新　⏱3h

**改动清单**
- `PsiTreeChangeListener` + 300ms 去抖 → 快照版本号自增
- 新增 `GET /api/events`（SSE）推送 `{version}`
- 前端 `EventSource` 替换手动刷新与轮询，增量更新不清空画布

**你的测试卡**
1. 改一个方法名并保存
2. **预期**：1 秒内画布自动更新，节点位置不跳变，不需要点任何按钮

---

### S6 · 接入 AI　⏱4h

**改动清单**
- `AiSettingsConfigurable`：provider / baseUrl / model / temperature；Key 存 `PasswordSafe`
- `AiProvider` 抽象 + `OpenAiCompatProvider`（`/v1/chat/completions`，一份代码覆盖 DeepSeek / 通义 / Kimi / Ollama）
- `/api/ai/summarize`：批量（≤20 方法/次）只要签名+方法体，严格 JSON 返回，填回 `summary` 并置 `summaryState=ai`
- 前端「生成语义摘要」按钮 + 节点状态点（灰待生成 / 绿已生成 / 红失败）

**你的测试卡**
1. Settings 里填 DeepSeek BaseURL + Key + model
2. 点「生成语义摘要」
3. **预期**：每个节点出现一句中文解释，角标变绿
4. 故意填错 Key → 报错文案清晰，画布不崩

> ⚠️ **这一步需要你先拍板用哪家 API**（见 §4 决策 1）

---

### S7 · 缓存与增量　⏱2h

**改动清单**
- 缓存键 = `签名 + 方法体 SHA-256 + model`，结果存项目级状态
- 只对变化的方法重新请求

**你的测试卡**
1. 已生成过摘要的文件，再点一次刷新 → 面板无变化且**不消耗额度**（我提供日志可查）
2. 改一个方法体 → 只有那一个节点重新生成

---

### S8 · AI 动作与回写　⏱4h

**改动清单**
- 节点右键菜单：解释这段代码 / 生成注释 / 生成单测 / 重构建议
- AI 返回 unified diff → `DiffManager.showDiff` 预览 → 确认后 `WriteCommandAction` 写回
- 保证可 `Ctrl+Z` 撤销

**你的测试卡**
1. 右键某个方法 →「生成注释」
2. 弹出 diff 窗口 → 点应用
3. **预期**：源码出现注释，`Ctrl+Z` 能撤销

---

### S9 · 发布工程化　⏱3h

**改动清单**
- 命名空间 `org.example` → 你的（如 `io.github.zhuanz1.codecanvas`）
- `LICENSE`(Apache-2.0)、正式 `README.md`（截图 + 安装 + 配置 + 架构）、`CHANGELOG.md`
- `.github/workflows/build.yml`（JDK 21 + Node 22 + `buildPlugin` + 上传产物）
- `v0.1.0` tag + Release 挂 zip

**你的测试卡**
1. 找一台没装过 Node 的电脑，按 README 装 zip → 打开 Java 文件能出图

---

## 4. 需要你拍板的决策

| # | 问题 | 影响 | 我的建议 |
|---|---|---|---|
| 1 | AI 用哪家？DeepSeek / 通义 / Kimi / 本地 Ollama / 腾讯混元 | S6 的实现与默认配置 | **先做 OpenAI 兼容通用实现，默认填 DeepSeek**；混元留作第二个 Provider |
| 2 | 目标 IDE 版本范围 | `sinceBuild/untilBuild`、API 兼容测试 | 242–253（覆盖你本机 2025.3.3） |
| 3 | 前端是否迁 TypeScript | S5 之后的重构成本 | **暂不迁**，等契约稳定再说 |

---

## 5. 附录：体检结论摘要（背景）

### 已修复
- 前后端 JSON 契约不一致（后端 `packages`，前端读 `nodes/edges`）→ 已在 S1 修复
- 画布布局/交互缺失 → 已在 S1 重写

### 待处理
- 前端未打进 jar（S2）
- 端口写死 + 绑 0.0.0.0 + CORS 全开（S3）
- Jetty 线程直接读编辑器文档（S3）
- javaparser 无法解析跨文件调用（S4）
- 5 秒轮询（S5）
- AI 未接入（S6–S8）
- `org.example` 命名空间、无 LICENSE、无 CI、README 仍是模板（S9）

### 🔴 与本项目无关但必须处理
`C:\Users\ZhuanZ1\IdeaProjects\CodePulse\src\main\resources\application.yml` 里有**明文腾讯云 SecretId/SecretKey**。如果这个仓库也要推 GitHub，务必先到控制台禁用并轮换，再改成环境变量注入。
