<template>
  <div class="container">
    <header class="header">
      <div class="brand">
        <h1 class="title">项目包结构图</h1>
        <span class="subtitle">{{ subtitle }}</span>
      </div>
      <div class="actions">
        <span v-if="aiStatus.phase === 'running'" class="ai-msg">{{ aiStatus.message }}</span>
        <label class="auto">
          <input type="checkbox" v-model="autoRefresh" />
          自动
        </label>
        <button
          class="ai-btn"
          :disabled="aiStatus.phase === 'running' || !aiStatus.configured"
          :title="aiStatus.configured
            ? '让 AI 生成每个模块的用途与关系说明'
            : '请先点击右侧「AI 设置」配置 API Key'"
          @click="startAi()"
        >
          {{ aiStatus.phase === 'running' ? '分析中...' : 'AI 生成语义' }}
        </button>
        <button class="cfg-btn" title="打开 CodeCanvas AI 设置" @click="openSettings">⚙</button>
        <button class="refresh-btn" :disabled="loading" @click="fetchData">
          {{ loading ? '扫描中...' : '刷新' }}
        </button>
      </div>
    </header>

    <div v-if="!aiStatus.configured" class="cfg-hint">
      <span>尚未配置 AI，当前只显示结构事实。</span>
      <button class="ghost-btn" @click="openSettings">去配置</button>
    </div>

    <div v-if="aiStatus.phase === 'error' && aiStatus.error" class="ai-error">
      <span class="ai-error-text">{{ aiStatus.error }}</span>
      <button class="ghost-btn" @click="startAi()">重试</button>
      <button class="ghost-btn" @click="resetAi">清除</button>
    </div>

    <div v-if="notice" class="notice-bar">
      <span class="notice-icon">!</span>
      <span class="notice-text">{{ notice }}</span>
    </div>

    <div v-if="loading && !modules.length" class="empty">
      <p class="empty-main">正在扫描项目 PSI...</p>
      <p class="tip">首次扫描需要几百毫秒到几秒，取决于项目大小</p>
    </div>

    <div v-else-if="error" class="error-box">
      <p class="error-title">无法获取包结构</p>
      <p class="error-msg">{{ error }}</p>
      <button class="ghost-btn" @click="fetchData">重试</button>
    </div>

    <div v-else-if="!modules.length" class="empty">
      <p class="empty-main">没有可显示的包</p>
      <p class="tip">当前项目里没有 .java 源文件？</p>
    </div>

    <div v-else class="stage" :class="{ focused: !!selected }">
      <div class="canvas-wrap" :class="{ files: !!fileView }">
      <svg
        ref="svgEl"
        class="canvas"
        :viewBox="fileView ? fileViewBox : viewBox"
        :style="fileView ? { width: fileRowWidth + 'px', height: '100%' } : null"
        xmlns="http://www.w3.org/2000/svg"
        :class="{ panning }"
        @contextmenu.prevent="openMenu($event, null)"
        @wheel.prevent="onWheel"
        @mousedown="onCanvasDown"
      >
        <defs>
          <marker
            id="arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="5"
            markerHeight="5"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
          </marker>
        </defs>

        <g :transform="`translate(${view.x}, ${view.y}) scale(${view.k})`">

        <g v-for="r in routed" :key="r.key">
          <path
            :d="r.d"
            class="edge"
            :class="[`edge-${r.edge.kind}`, { active: isEdgeActive(r.edge) }]"
            fill="none"
            marker-end="url(#arrow)"
          />
          <text
            v-if="!selected"
            :x="r.lx"
            :y="r.ly"
            class="edge-label"
            :class="{ active: isEdgeActive(r.edge) }"
            text-anchor="middle"
          >{{ shortLabel(r.edge.label) }}</text>
        </g>

        <g
          v-for="m in placed"
          :key="m.node.id"
          class="module"
          :class="{ selected: m.node.id === activeNodeId }"
          @click="onNodeClick(m.node.id)"
          @dblclick="onNodeDblClick(m.node.id)"
          @contextmenu.prevent.stop="openMenu($event, m.node.id)"
        >
          <title>{{ m.node.name }}
{{ m.node.desc }}
{{ m.node.fileCount }} 文件 · {{ m.node.lineCount }} 行 · {{ m.node.language }}</title>

          <rect
            :x="m.x"
            :y="m.y"
            :width="CARD_W"
            :height="CARD_H"
            rx="12"
            :fill="fillOf(m.node)"
            :stroke="m.node.id === activeNodeId ? '#35e0c8' : borderColorOf(m.node)"
            :stroke-width="m.node.id === activeNodeId ? 1.8 : 1"
          />

          <!-- 左侧职责色条 -->
          <rect
            :x="m.x"
            :y="m.y + 10"
            width="4"
            :height="CARD_H - 20"
            rx="2"
            :fill="roleColor(m.node.role)"
          />

          <text :x="m.x + 20" :y="m.y + 31" class="m-name">{{ m.node.shortName }}</text>

          <text
            :x="m.x + CARD_W - 16"
            :y="m.y + 30"
            class="m-role"
            text-anchor="end"
            :fill="roleColor(m.node.role)"
          >{{ m.node.role }}</text>

          <text
            v-if="m.node.name !== m.node.shortName"
            :x="m.x + 20"
            :y="m.y + 52"
            class="m-pkg"
          >{{ tailPath(m.node.name, 30) }}</text>

          <text
            v-for="(line, i) in m.lines"
            :key="i"
            :x="m.x + 20"
            :y="m.y + 76 + i * 18"
            class="m-desc"
            :class="{ issue: m.node.descState === 'error', ai: m.node.descState === 'ai' }"
          >{{ line }}</text>

          <text :x="m.x + 20" :y="m.y + CARD_H - 13" class="m-stat">
            {{ m.node.statText || m.node.fileCount + ' 文件 · ' + m.node.lineCount + ' 行' }}
          </text>

          <text
            v-if="m.node.refText"
            :x="m.x + CARD_W - 16"
            :y="m.y + CARD_H - 13"
            class="m-ref"
            text-anchor="end"
          >{{ m.node.refText }}</text>
          <text
            v-else-if="m.node.descState === 'ai'"
            :x="m.x + CARD_W - 16"
            :y="m.y + CARD_H - 13"
            class="m-ai"
            text-anchor="end"
          >AI</text>
          <text
            v-else-if="m.node.descState === 'error'"
            :x="m.x + CARD_W - 16"
            :y="m.y + CARD_H - 13"
            class="m-ai err"
            text-anchor="end"
          >问题</text>
        </g>
        </g>
      </svg>

      <div v-if="fileView" class="crumb-bar">
        <button class="crumb" @click="exitFileView">全图</button>
        <span class="crumb-sep">›</span>
        <button class="crumb" @click="exitFileView">{{ fileView.moduleName }}</button>
        <span class="crumb-sep">›</span>
        <span class="crumb-current">{{ selectedFile ? selectedFile.name : '选择文件' }}</span>
        <span class="crumb-meta">
          {{ fileView.stats.files }} 文件 · {{ fileView.stats.edges }} 引用
        </span>
      </div>

      <div v-if="!fileView" class="zoom-bar">
        <button class="zoom-btn" title="放大" @click="zoomBy(1.2)">＋</button>
        <button class="zoom-btn" title="缩小" @click="zoomBy(1 / 1.2)">－</button>
        <button class="zoom-btn wide" title="适应窗口" @click="fitView">适应</button>
        <button class="zoom-btn wide" title="实际大小（Ctrl+0）" @click="resetView">1:1</button>
        <span class="zoom-val">{{ Math.round(view.k * 100) }}%</span>
      </div>

      <!-- 左下角：线条示意（始终保留） -->
      <div v-if="!fileView" class="canvas-legend">
        <span><i class="lg lg-extends"></i>继承</span>
        <span><i class="lg lg-implements"></i>实现</span>
        <span><i class="lg lg-uses"></i>依赖</span>
        <span><i class="lg lg-contain"></i>包含</span>
      </div>

      <!-- 右下角：未聚焦时是全局统计，聚焦时是这条链路的处理量与规模 -->
      <div v-if="!fileView" class="canvas-stats">
        <template v-if="fileView">
          <div class="stat-row"><span class="stat-k">文件</span><span class="stat-v">{{ fileView.stats.files }}</span></div>
          <div class="stat-row"><span class="stat-k">引用</span><span class="stat-v">{{ fileView.stats.edges }}</span></div>
          <div class="stat-row"><span class="stat-k">规模</span><span class="stat-v">{{ fileView.stats.lines }} 行</span></div>
          <div v-if="fileView.stats.hidden" class="stat-row"><span class="stat-k">未显示</span><span class="stat-v">{{ fileView.stats.hidden }}</span></div>
        </template>
        <template v-else-if="selected">
          <div class="stat-row"><span class="stat-k">关联模块</span><span class="stat-v">{{ focusStats.related }}</span></div>
          <div class="stat-row"><span class="stat-k">数据流转</span><span class="stat-v">{{ focusStats.refs }} 次</span></div>
          <div class="stat-row"><span class="stat-k">链路规模</span><span class="stat-v">{{ focusStats.files }} 文件 / {{ focusStats.lines }} 行</span></div>
        </template>
        <template v-else>
          <div class="stat-row"><span class="stat-k">模块</span><span class="stat-v">{{ stats.modules }}</span></div>
          <div class="stat-row"><span class="stat-k">规模</span><span class="stat-v">{{ stats.files }} 文件 / {{ stats.lines }} 行</span></div>
          <div class="stat-row"><span class="stat-k">关系</span><span class="stat-v">{{ stats.relations }}</span></div>
          <div v-if="aiStatus.total" class="stat-row"><span class="stat-k">AI 缓存</span><span class="stat-v">{{ aiStatus.cached }}/{{ aiStatus.total }}</span></div>
        </template>
      </div>

      <p v-if="!selected" class="stage-hint">点击模块进入聚焦 · 滚轮缩放 · 空白处拖拽平移</p>
      </div>

      <!-- 下半部分：当前模块介绍（宽度不够时自动换行成单列） -->
      <!-- 三级：文件详情 / 逐行解释 -->
      <section v-if="fileView && selectedFile" class="detail-panel">
        <div v-if="explainResult" class="explain-wrap">
          <div class="dp-head">
            <span class="badge file-badge">逐行</span>
            <span class="dp-title">{{ explainResult.file }}</span>
            <button class="act-btn ghost small" @click="closeExplain">返回文件</button>
            <button class="dp-close" title="返回全图（Esc）" @click="exitFileView">×</button>
          </div>
          <p class="dp-meta">
            {{ explainResult.lines.length }} 行有说明 · 文件共 {{ explainResult.totalLines }} 行
            <span v-if="explainResult.truncated"> · 仅解释了前 220 行</span>
          </p>
          <ul class="line-list">
            <li v-for="l in explainResult.lines" :key="l.line" :class="{ bad: !!l.issue }">
              <span class="ln">{{ l.line }}</span>
              <span class="lc">{{ l.code }}</span>
              <span class="ly">{{ l.note }}</span>
              <span v-if="l.issue" class="li">⚠ {{ l.issue }}</span>
            </li>
          </ul>
        </div>

        <template v-else>
          <div class="dp-main">
            <div class="dp-head">
              <span class="badge file-badge">文件</span>
              <span class="dp-title">{{ selectedFile.name }}</span>
              <button class="dp-close" title="返回模块（Esc）" @click="exitFileView">×</button>
            </div>

            <p class="dp-path">{{ fileView.moduleName }}/{{ selectedFile.name }}</p>

            <p class="dp-desc">{{ selectedFile.purpose || '（这个文件没有头部说明）' }}</p>

            <div class="dp-actions">
              <button
                class="act-btn"
                :disabled="explainPhase === 'running'"
                @click="explainFile"
              >{{ explainPhase === 'running' ? '解释中...' : '逐行解释' }}</button>
              <button class="act-btn ghost" @click="openInIde(fileView.moduleId, selectedFile.name)">
                在编辑器打开
              </button>
            </div>

            <p v-if="explainMessage" class="dp-hint">{{ explainMessage }}</p>
            <p v-if="explainError" class="dp-hint error">{{ explainError }}</p>

            <div class="dp-meta">
              <span>{{ selectedFile.lineCount }} 行</span>
              <span>{{ selectedFile.language }}</span>
              <span>{{ fileRelations.length }} 条引用</span>
            </div>
          </div>

          <div class="dp-block">
            <p class="dp-label">引用关系（{{ fileRelations.length }}）</p>
            <ul class="dp-rel">
              <li v-for="(r, i) in fileRelations" :key="i">
                <span class="rel-dir">{{ r.dir }}</span>
                <span class="rel-name">{{ r.name }}</span>
                <span class="rel-note">{{ r.label }}</span>
              </li>
            </ul>
          </div>
        </template>
      </section>
      <!-- 文件图里没选文件时：提示 -->
      <section v-else-if="fileView" class="detail-panel">
        <div class="dp-main">
          <div class="dp-head">
            <span class="badge file-badge">模块</span>
            <span class="dp-title">{{ fileView.moduleName }}</span>
            <button class="dp-close" title="返回全图（Esc）" @click="exitFileView">×</button>
          </div>
          <p class="dp-desc">
            共 {{ fileView.stats.files }} 个有引用的文件、{{ fileView.stats.edges }} 条引用
            <template v-if="fileView.stats.hidden">（另有 {{ fileView.stats.hidden }} 个无引用文件未显示）</template>
          </p>
          <p class="dp-hint">点击上方文件卡片查看详情</p>
        </div>
      </section>
      <section v-else-if="selected" class="detail-panel">
        <div class="dp-main">
          <div class="dp-head">
            <span class="badge" :style="{ color: roleColor(selected.role) }">{{ selected.role }}</span>
            <span class="dp-title">{{ selected.shortName }}</span>
            <button class="dp-close" title="回到全图（Esc）" @click="selectedId = ''">×</button>
          </div>

          <p class="dp-path" :title="selected.name">{{ selected.name }}</p>

          <p v-if="selected.advice" class="dp-label">修改意见</p>
          <p
            class="dp-desc"
            :class="{ ai: selected.descState === 'ai', bad: selected.descState === 'error' }"
          >{{ selected.desc }}</p>

          <div class="dp-actions">
            <button class="act-btn" @click="enterFileView(selected.id)">
              查看文件关系（{{ selected.fileCount }}）
            </button>
          </div>

          <p v-if="fileError" class="dp-hint error">{{ fileError }}</p>

          <div class="dp-meta">
            <span>{{ selected.fileCount }} 文件</span>
            <span>{{ selected.lineCount }} 行</span>
            <span>{{ selected.language }}</span>
            <span>{{ selectedRelations.length }} 条关联</span>
            <span v-if="selected.issues && selected.issues.length">
              {{ selected.issues.length }} 个问题
            </span>
          </div>
        </div>

        <div v-if="childModules.length" class="dp-block">
          <p class="dp-label">子模块（{{ childModules.length }}）</p>
          <ul class="dp-sub">
            <li
              v-for="m in childModules"
              :key="m.id"
              :style="{ paddingLeft: (childDepth(m.name) - 1) * 12 + 'px' }"
              @click="selectedId = m.id"
            >
              <span class="sub-dir">└</span>
              <span class="sub-name">{{ m.shortName }}</span>
              <span class="sub-meta">{{ m.role }} · {{ m.fileCount }} 文件</span>
            </li>
          </ul>
        </div>

        <div v-if="selectedRelations.length" class="dp-block">
          <p class="dp-label">关联模块（{{ selectedRelations.length }}）</p>
          <ul class="dp-rel">
            <li v-for="(r, i) in selectedRelations" :key="i">
              <span class="rel-dir">{{ r.dir }}</span>
              <span class="rel-name">{{ r.other }}</span>
              <span class="rel-note">{{ r.label }}</span>
            </li>
          </ul>
        </div>

        <div v-if="selected.signatures && selected.signatures.length" class="dp-block">
          <p class="dp-label">关键签名（{{ selected.signatures.length }}）</p>
          <ul class="dp-sig">
            <li v-for="(s, i) in selected.signatures.slice(0, 8)" :key="i" :title="s">{{ s }}</li>
          </ul>
        </div>

        <div class="dp-block">
          <p class="dp-label">文件（{{ selected.fileCount }}）· 点击打开</p>
          <div class="chips chips-row">
            <button
              v-for="c in selected.files"
              :key="c"
              class="chip chip-btn"
              :title="`在编辑器中打开 ${c}`"
              @click="openInIde(selected.id, c)"
            >{{ c }}</button>
          </div>
        </div>

        <div v-if="selected.issues && selected.issues.length" class="dp-block">
          <p class="dp-label">问题</p>
          <ul class="dp-issues">
            <li v-for="(it, i) in selected.issues" :key="i">{{ it }}</li>
          </ul>
        </div>
      </section>
    </div>


    <div
      v-if="menu.open"
      class="ctx-menu"
      :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      @click.stop
    >
      <button v-if="menu.moduleId" class="ctx-item" @click="regenerateOne(menu.moduleId)">
        重新生成此模块
      </button>
      <button class="ctx-item" @click="regenerateAll">重新生成全部</button>
      <button class="ctx-item danger" @click="clearCache">清除全部缓存</button>
    </div>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'

// 允许用 ?api= 覆盖，便于用 mock 联调前端
const API = new URLSearchParams(location.search).get('api') || 'http://127.0.0.1:8899'

const CARD_W = 288
const CARD_H = 132
const GAP_X = 88
const GAP_Y = 44
const MAX_DESC_LINES = 2
const MAX_PER_COLUMN = 4

const modules = ref([])
const relations = ref([])
const stats = ref({ modules: 0, files: 0, lines: 0, relations: 0, languages: [] })
const projectName = ref('')
const notice = ref('')
const loading = ref(false)
const error = ref('')
const selectedId = ref('')
const autoRefresh = ref(false)

const aiStatus = ref({
  configured: false,
  enabled: false,
  phase: 'idle',
  error: '',
  message: '',
  cached: 0,
  total: 0,
})

let timer = null
let controller = null
let aiTimer = null

const subtitle = computed(() =>
  projectName.value
    ? `${projectName.value} · ${stats.value.modules} 个模块${stats.value.languages?.length ? ' · ' + stats.value.languages.join(' / ') : ''}`
    : '未加载项目',
)

const footerStats = computed(
  () =>
    `${stats.value.modules} 模块 · ${stats.value.files} 文件 · ${stats.value.lines} 行 · ${stats.value.relations} 关系`,
)

/** 聚焦时的链路指标：关联数 / 数据流转次数 / 规模 */
function refWeight(label) {
  const m = /×(\d+)/.exec(label || '')
  return m ? Number(m[1]) : 1
}

const focusStats = computed(() => {
  const node = selected.value
  if (!node) return { related: 0, refs: 0, files: 0, lines: 0 }

  const rels = relations.value.filter((e) => e.from === node.id || e.to === node.id)
  const nodes = focusNodes.value

  return {
    related: rels.length,
    refs: rels.reduce((sum, e) => sum + refWeight(e.label), 0),
    files: nodes.reduce((s, n) => s + (n.fileCount || 0), 0),
    lines: nodes.reduce((s, n) => s + (n.lineCount || 0), 0),
  }
})

const cacheLabel = computed(() =>
  aiStatus.value.total > 0
    ? ` · AI 缓存 ${aiStatus.value.cached}/${aiStatus.value.total}`
    : '',
)

/* ------------------------------ 文本工具 ------------------------------ */

function unitsOf(ch) {
  return /[\u4e00-\u9fa5\u3000-\u303f\uff00-\uffef]/.test(ch) ? 1 : 0.56
}

function wrapText(text, maxUnits) {
  // 中文按单字断行，英文/数字保持整词，避免 "Vue" 被拆成 "Vu"+"e"
  const tokens = String(text || '').match(/[\u4e00-\u9fa5\u3000-\u303f\uff00-\uffef]|[A-Za-z0-9_./#:-]+|\s+|./g) || []

  const lines = []
  let cur = ''
  let used = 0

  for (const token of tokens) {
    let u = unitsTotal(token)
    if (used + u > maxUnits && cur.trim()) {
      lines.push(cur.trimEnd())
      cur = ''
      used = 0
      if (/^\s+$/.test(token)) continue
    }
    // 单个 token 就超长（极长的标识符）：按单位硬切，剩余继续参与排版
    let rest = token
    while (unitsTotal(rest) > maxUnits) {
      let cut = 0
      let acc = 0
      for (const ch of rest) {
        const cu = unitsOf(ch)
        if (acc + cu > maxUnits) break
        acc += cu
        cut++
      }
      cut = Math.max(1, cut)
      lines.push(rest.slice(0, cut))
      rest = rest.slice(cut)
    }
    cur += rest
    used += unitsTotal(rest)
  }
  if (cur.trim()) lines.push(cur.trimEnd())
  return lines
}

function unitsTotal(text) {
  let n = 0
  for (const ch of text || '') n += unitsOf(ch)
  return n
}

/** 深层路径从尾部保留（…example\config 比 example\config\… 有用） */
function tailPath(text, maxUnits = 30) {
  if (!text) return ''
  if (unitsTotal(text) <= maxUnits) return text
  let out = ''
  let used = 0
  for (let i = text.length - 1; i >= 0; i--) {
    const u = unitsOf(text[i])
    if (used + u > maxUnits - 1) break
    out = text[i] + out
    used += u
  }
  return '…' + out
}
function truncate(text, maxUnits) {
  if (!text) return ''
  let used = 0
  let out = ''
  for (const ch of text) {
    used += unitsOf(ch)
    if (used > maxUnits) return out + '…'
    out += ch
  }
  return out
}

/* ------------------------------ 配色 ------------------------------ */

const ROLE_TEXT = {
  界面: '#4fc3f7',
  组件: '#4dd0e1',
  路由: '#9fa8da',
  状态: '#ffb74d',
  服务: '#4dd0b1',
  接口层: '#64b5f6',
  数据访问: '#b39ddb',
  数据模型: '#ffcc80',
  工具: '#80cbc4',
  配置: '#90a4ae',
  横切: '#b0a0e0',
  资源: '#aed581',
  测试: '#c5e1a5',
  异常: '#ff8a80',
  业务: '#80cbc4',
}

const ROLE_FILL = {
  界面: '#0d3444',
  组件: '#0d3b3f',
  路由: '#252b45',
  状态: '#3a2f1a',
  服务: '#0d3a34',
  接口层: '#12354a',
  数据访问: '#2f2a4a',
  数据模型: '#3a2c18',
  工具: '#1d2f2a',
  配置: '#22323d',
  横切: '#2a2340',
  资源: '#23301c',
  测试: '#0e2410',
  异常: '#3d2020',
  业务: '#152c2c',
}

const fillOf = (n) => ROLE_FILL[n.role] || '#152c2c'
const roleColor = (r) => ROLE_TEXT[r] || '#80cbc4'
const roleFill = (r) => (ROLE_TEXT[r] || '#80cbc4') + '22'
const borderColorOf = (n) => (ROLE_TEXT[n.role] || '#80cbc4') + '3d'

/* ------------------------------ 布局 ------------------------------ */

// 按依赖深度分层：被依赖的包排在右边，形成左→右的流向
function layerOf(nodes, edges) {
  const layer = new Map(nodes.map((n) => [n.id, 0]))
  for (let round = 0; round < nodes.length; round++) {
    let changed = false
    for (const e of edges) {
      if (!layer.has(e.from) || !layer.has(e.to)) continue
      const lf = layer.get(e.from)
      const lt = layer.get(e.to)
      if (lt <= lf) {
        layer.set(e.to, lf + 1)
        changed = true
      }
    }
    if (!changed) break
  }
  return layer
}

/** 聚焦模式下只保留：当前模块 + 与它直接相连的模块 */
/* ---------------- 模块层级 ----------------
   目录模块天然有父子关系（frontend 包含 frontend/src），
   但数据里它们是两条平行记录、没有任何边，所以聚焦父模块时会"关联 0"。
   这里按路径前缀把它们补成层级关系。
------------------------------------------------ */

/** 路径的父级名字，例如 a/b/c -> a/b */
function parentName(name) {
  const i = name.lastIndexOf('/')
  return i > 0 ? name.slice(0, i) : ''
}

const moduleByName = computed(() => {
  const m = new Map()
  for (const n of modules.value) m.set(n.name, n)
  return m
})

/** 当前模块的直接父模块 */
const parentModule = computed(() => {
  const sel = selected.value
  if (!sel) return null
  return moduleByName.value.get(parentName(sel.name)) || null
})

/** 当前模块的子孙模块（按路径深度排序），直接子级在前 */
const childModules = computed(() => {
  const sel = selected.value
  if (!sel) return []
  const prefix = sel.name + '/'
  return modules.value
    .filter((m) => m.name.startsWith(prefix))
    .sort((a, b) => a.name.split('/').length - b.name.split('/').length || a.name.localeCompare(b.name))
})

/** 子模块的层级缩进深度（1 = 直接子级） */
function childDepth(name) {
  const sel = selected.value
  if (!sel) return 1
  return name.slice(sel.name.length + 1).split('/').length
}

const focusNodes = computed(() => {
  const id = selectedId.value
  if (!id) return modules.value

  const keep = new Set([id])
  for (const e of relations.value) {
    if (e.from === id) keep.add(e.to)
    if (e.to === id) keep.add(e.from)
  }

  // 层级：父模块 + 直接子模块也放进画布，否则点父模块时是一片空白
  const parent = parentModule.value
  if (parent) keep.add(parent.id)
  for (const child of childModules.value) {
    if (childDepth(child.name) === 1) keep.add(child.id)
  }

  return modules.value.filter((n) => keep.has(n.id))
})

/* ---------------- 三级：模块内的文件图 ---------------- */

const fileView = ref(null)          // { moduleId, moduleName, nodes, edges, stats }
const fileLoading = ref(false)
const fileError = ref('')
const selectedFileId = ref('')
const explainHint = ref('')

/** 当前画布上的节点：三级时是文件，否则是模块 */
const canvasNodes = computed(() => {
  const view = fileView.value
  if (!view) return focusNodes.value

  const outCount = new Map()
  const inCount = new Map()
  for (const e of view.edges) {
    outCount.set(e.from, (outCount.get(e.from) || 0) + 1)
    inCount.set(e.to, (inCount.get(e.to) || 0) + 1)
  }

  return view.nodes.map((f) => ({
    id: f.id,
    name: f.name,
    shortName: f.name.replace(/\.[^.]+$/, ''),
    role: '文件',
    desc: f.purpose || '',
    statText: `${f.lineCount} 行 · ${f.language}`,
    refText: `引用 ${outCount.get(f.id) || 0} · 被引用 ${inCount.get(f.id) || 0}`,
    descState: 'structural',
    fileCount: 1,
    lineCount: f.lineCount,
    bytes: f.bytes,
    language: f.language,
    issues: [],
    advice: '',
    signatures: [],
    files: [f.name],
  }))
})

/** 当前画布上的边 */
const canvasEdges = computed(() => (fileView.value ? fileView.value.edges : displayEdges.value))

const activeNodeId = computed(() => (fileView.value ? selectedFileId.value : selectedId.value))

const selectedFile = computed(() => {
  const view = fileView.value
  if (!view || !selectedFileId.value) return null
  return view.nodes.find((n) => n.id === selectedFileId.value) || null
})

const fileRelations = computed(() => {
  const f = selectedFile.value
  const view = fileView.value
  if (!f || !view) return []
  const byId = new Map(view.nodes.map((n) => [n.id, n]))
  return view.edges
    .filter((e) => e.from === f.id || e.to === f.id)
    .map((e) => {
      const outgoing = e.from === f.id
      const other = byId.get(outgoing ? e.to : e.from)
      return { dir: outgoing ? '→' : '←', name: other ? other.name : '?', label: e.label }
    })
})

async function enterFileView(moduleId) {
  if (!moduleId) return
  fileLoading.value = true
  fileError.value = ''
  try {
    const res = await fetch(`${API}/api/moduleFiles?moduleId=${encodeURIComponent(moduleId)}`, {
      headers: { Accept: 'application/json' },
    })
    const data = await res.json()
    if (!data.success) {
      fileError.value = data.message || '无法读取文件关系'
      return
    }
    fileView.value = data
    selectedFileId.value = ''
    explainHint.value = ''
    nextTick(() => fitView())
  } catch (e) {
    fileError.value = '读取文件关系失败：' + e.message
  } finally {
    fileLoading.value = false
  }
}

function exitFileView() {
  fileView.value = null
  selectedFileId.value = ''
  explainHint.value = ''
  closeExplain()
  nextTick(() => fitView())
}

function onNodeClick(id) {
  if (fileView.value) {
    selectedFileId.value = id
    explainHint.value = ''
    closeExplain()
  } else {
    selectedId.value = id
  }
}

function onNodeDblClick(id) {
  if (fileView.value) {
    const f = fileView.value.nodes.find((n) => n.id === id)
    if (f) openInIde(fileView.value.moduleId, f.name)
    return
  }
  openInIde(id)
}

/* ---------------- 逐行解释 ---------------- */

const explainPhase = ref('idle')   // idle | running | done | error
const explainResult = ref(null)
const explainError = ref('')
const explainMessage = ref('')
let explainTimer = null

function applyExplain(status) {
  explainPhase.value = status.phase || 'idle'
  explainError.value = status.error || ''
  explainMessage.value = status.message || ''
  if (status.result) explainResult.value = status.result
}

async function explainFile() {
  if (!fileView.value || !selectedFile.value) return
  if (explainPhase.value === 'running') return

  explainPhase.value = 'running'
  explainError.value = ''
  explainMessage.value = '提交中...'
  explainResult.value = null

  const url =
    `${API}/api/explain?moduleId=${encodeURIComponent(fileView.value.moduleId)}` +
    `&file=${encodeURIComponent(selectedFile.value.name)}`

  try {
    const res = await fetch(url, { method: 'POST' })
    applyExplain(await res.json())
  } catch (e) {
    explainPhase.value = 'error'
    explainError.value = '提交失败：' + e.message
    return
  }

  clearInterval(explainTimer)
  explainTimer = setInterval(async () => {
    try {
      const res = await fetch(`${API}/api/explainStatus`)
      applyExplain(await res.json())
      if (explainPhase.value !== 'running') {
        clearInterval(explainTimer)
        explainTimer = null
      }
    } catch (e) {
      clearInterval(explainTimer)
      explainTimer = null
      explainPhase.value = 'error'
      explainError.value = '轮询失败：' + e.message
    }
  }, 1200)
}

function closeExplain() {
  clearInterval(explainTimer)
  explainTimer = null
  explainResult.value = null
  explainPhase.value = 'idle'
  explainError.value = ''
  explainMessage.value = ''
}

/** 画布上要画的边：引用关系 + 父子包含关系 */
const displayEdges = computed(() => {
  const visible = new Set(focusNodes.value.map((n) => n.id))
  const out = []

  for (const e of relations.value) {
    if (visible.has(e.from) && visible.has(e.to)) out.push(e)
  }

  for (const n of focusNodes.value) {
    const parent = moduleByName.value.get(parentName(n.name))
    if (parent && visible.has(parent.id)) {
      out.push({
        from: parent.id,
        to: n.id,
        kind: 'contain',
        label: '包含 ' + n.shortName,
      })
    }
  }

  return out
})

/** 三级：文件横排的总宽度（含左右留白） */
const fileRowWidth = computed(() => {
  const n = canvasNodes.value.length
  if (!n) return 320
  return n * CARD_W + Math.max(0, n - 1) * GAP_X + 48
})

/** 三级用固定尺寸，不缩放；容器横向滚动 */
const fileViewBox = computed(() => `0 0 ${fileRowWidth.value} ${CARD_H + 48}`)

const placed = computed(() => {
  const nodes = canvasNodes.value
  if (!nodes.length) return []

  // 三级：一行排开，固定尺寸
  if (fileView.value) {
    return nodes.map((node, i) => ({
      node,
      x: 24 + i * (CARD_W + GAP_X),
      y: 24,
      lines: wrapText(node.desc, 23).slice(0, MAX_DESC_LINES),
    }))
  }

  const layer = layerOf(nodes, relations.value)
  const byLayer = new Map()
  for (const n of nodes) {
    const l = layer.get(n.id) || 0
    if (!byLayer.has(l)) byLayer.set(l, [])
    byLayer.get(l).push(n)
  }

  // 每层按包名排序；超过 MAX_PER_COLUMN 就拆成多列，
  // 否则 10 个模块会摞成一根长条，自适应之后字小到看不清
  const columns = []
  for (const [, list] of [...byLayer.entries()].sort((a, b) => a[0] - b[0])) {
    list.sort((a, b) => a.name.localeCompare(b.name))
    for (let i = 0; i < list.length; i += MAX_PER_COLUMN) {
      columns.push(list.slice(i, i + MAX_PER_COLUMN))
    }
  }

  const result = []
  columns.forEach((col, ci) => {
    col.forEach((node, ri) => {
      result.push({
        node,
        x: ci * (CARD_W + GAP_X),
        y: (ri - (col.length - 1) / 2) * (CARD_H + GAP_Y),
        lines: wrapText(node.desc, 23).slice(0, MAX_DESC_LINES),
      })
    })
  })
  return result
})

const boxById = computed(() => {
  const m = new Map()
  for (const p of placed.value) m.set(p.node.id, p)
  return m
})

/* ------------------------------ 缩放 / 平移 ------------------------------ */

const svgEl = ref(null)
const view = reactive({ k: 1, x: 0, y: 0 })
const panning = ref(false)
let panStart = null

/** 屏幕坐标 → viewBox 坐标（含 viewBox 的缩放偏移） */
function toViewBox(evt) {
  const svg = svgEl.value
  if (!svg || !svg.getScreenCTM) return { x: 0, y: 0 }
  const pt = svg.createSVGPoint()
  pt.x = evt.clientX
  pt.y = evt.clientY
  return pt.matrixTransform(svg.getScreenCTM().inverse())
}

/** 以 (cx, cy) 为中心缩放，保持该点不动 */
function applyZoom(cx, cy, nextK) {
  const k = Math.min(4, Math.max(0.15, nextK))
  if (k === view.k) return
  view.x = cx - (cx - view.x) * (k / view.k)
  view.y = cy - (cy - view.y) * (k / view.k)
  view.k = k
}

function onWheel(evt) {
  if (fileView.value) return
  userAdjusted = true
  const p = toViewBox(evt)
  const factor = evt.deltaY < 0 ? 1.12 : 1 / 1.12
  applyZoom(p.x, p.y, view.k * factor)
}

function zoomBy(factor) {
  userAdjusted = true
  const svg = svgEl.value
  if (!svg) return
  const r = svg.getBoundingClientRect()
  const p = toViewBox({ clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 })
  applyZoom(p.x, p.y, view.k * factor)
}



function onCanvasDown(evt) {
  if (fileView.value) return
  if (evt.button !== 0) return
  // 在模块卡片上按下不拖动画布（那是点击/双击语义）
  if (evt.target.closest && evt.target.closest('.module')) return

  userAdjusted = true
  panning.value = true
  const p = toViewBox(evt)
  panStart = { px: p.x, py: p.y, ox: view.x, oy: view.y }
  window.addEventListener('mousemove', onPanMove)
  window.addEventListener('mouseup', onPanUp)
}

function onPanMove(evt) {
  if (!panStart) return
  const p = toViewBox(evt)
  view.x = panStart.ox + (p.x - panStart.px)
  view.y = panStart.oy + (p.y - panStart.py)
}

function onPanUp() {
  panning.value = false
  panStart = null
  window.removeEventListener('mousemove', onPanMove)
  window.removeEventListener('mouseup', onPanUp)
}

/* ---------------- 画布坐标模型 ----------------
   viewBox 固定等于可视区像素尺寸，因此 k=1 就是真正的 100%。
   以前 viewBox 随内容变化，等于把"自适应"当成了 1 倍，
   图一大文字就被压成几个像素。
------------------------------------------------ */

const viewport = reactive({ w: 900, h: 640 })
let resizeObserver = null
let observedEl = null
let userAdjusted = false

const contentBounds = computed(() => {
  const list = placed.value
  if (!list.length) return { minX: 0, minY: 0, w: 0, h: 0 }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of list) {
    minX = Math.min(minX, p.x)
    minY = Math.min(minY, p.y)
    maxX = Math.max(maxX, p.x + CARD_W)
    maxY = Math.max(maxY, p.y + CARD_H)
  }
  return { minX, minY, w: maxX - minX, h: maxY - minY }
})

const viewBox = computed(() => `0 0 ${viewport.w} ${viewport.h}`)

/** 把内容居中显示在给定缩放比下 */
function centerAt(k) {
  const b = contentBounds.value
  if (!b.w || !b.h) return
  view.k = k
  view.x = (viewport.w - b.w * k) / 2 - b.minX * k
  view.y = (viewport.h - b.h * k) / 2 - b.minY * k
}

/** 适应窗口，但不会缩到读不清的程度（再小就靠用户自己缩） */
const MIN_FIT = 0.7

function fitView() {
  const b = contentBounds.value
  if (!b.w || !b.h) return
  const pad = 28
  const k = Math.min(1, Math.min((viewport.w - pad * 2) / b.w, (viewport.h - pad * 2) / b.h))
  centerAt(Math.max(MIN_FIT, k))
  userAdjusted = false
}

/** 100%：按真实像素 1:1 显示 */
function resetView() {
  centerAt(1)
  userAdjusted = true
}

/* ---------------- 连线：正交折线 ---------------- */

/**
 * 分层布局里用「右出 → 竖向 → 左入」的折线，拐角带圆角。
 * 比贝塞尔曲线干净得多：不会斜穿卡片之间的空白。
 */
function routeEdge(a, b, slot = 0) {
  const ay = a.y + CARD_H / 2
  const by = b.y + CARD_H / 2
  const offset = slot * 12

  // 同一列：从右侧绕出去再回来
  if (Math.abs(a.x - b.x) < 1) {
    const x1 = a.x + CARD_W
    const bulge = 48 + offset
    return {
      d: `M ${x1} ${ay} L ${x1 + bulge} ${ay} L ${x1 + bulge} ${by} L ${x1} ${by}`,
      lx: x1 + bulge * 0.8,
      ly: (ay + by) / 2,
    }
  }

  const forward = b.x > a.x
  const x1 = forward ? a.x + CARD_W : a.x
  const x2 = forward ? b.x : b.x + CARD_W
  const dirIn = forward ? 1 : -1
  const mx = (x1 + x2) / 2 + (forward ? offset : -offset)

  // 几乎水平：直接一条直线
  if (Math.abs(by - ay) < 1.5) {
    return { d: `M ${x1} ${ay} L ${x2} ${by}`, lx: (x1 + x2) / 2, ly: ay }
  }

  const r = Math.max(2, Math.min(14, Math.abs(mx - x1) / 2, Math.abs(x2 - mx) / 2))
  const sign = by > ay ? 1 : -1

  const d = [
    `M ${x1} ${ay}`,
    `L ${mx - dirIn * r} ${ay}`,
    `Q ${mx} ${ay} ${mx} ${ay + sign * r}`,
    `L ${mx} ${by - sign * r}`,
    `Q ${mx} ${by} ${mx + dirIn * r} ${by}`,
    `L ${x2} ${by}`,
  ].join(' ')

  return { d, lx: mx, ly: (ay + by) / 2 }
}

const routed = computed(() => {
  const out = []
  const slots = new Map()
  const edges = canvasEdges.value
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i]
    const a = boxById.value.get(e.from)
    const b = boxById.value.get(e.to)
    if (!a || !b) continue

    const pairKey = `${e.from}->${e.to}`
    const slot = slots.get(pairKey) || 0
    slots.set(pairKey, slot + 1)

    const geo = routeEdge(a, b, slot)
    out.push({ key: `${pairKey}#${i}`, edge: e, d: geo.d, lx: geo.lx, ly: geo.ly })
  }
  return out
})
const selected = computed(() => modules.value.find((n) => n.id === selectedId.value) || null)
const hoveredId = ref('')

/* ------------------------------ AI ------------------------------ */

function shortLabel(label) {
  if (!label) return ''
  return truncate(label, 12)
}

const selectedRelations = computed(() => {
  const node = selected.value
  if (!node) return []
  const byId = new Map(modules.value.map((m) => [m.id, m]))
  return relations.value
    .filter((e) => e.from === node.id || e.to === node.id)
    .map((e) => {
      const outgoing = e.from === node.id
      const other = byId.get(outgoing ? e.to : e.from)
      return {
        dir: outgoing ? '→' : '←',
        id: outgoing ? e.to : e.from,
        other: other ? other.shortName : '?',
        label: e.label,
      }
    })
})

async function fetchAiStatus() {
  try {
    const res = await fetch(`${API}/api/ai/status`, { headers: { Accept: 'application/json' } })
    if (!res.ok) return
    aiStatus.value = await res.json()
  } catch (e) {
    /* 服务没起来时静默 */
  }
}

async function startAi(mode = 'incremental', moduleId = '') {
  if (aiStatus.value.phase === 'running') return
  closeMenu()
  aiStatus.value = { ...aiStatus.value, phase: 'running', error: '', message: '提交中...' }

  let url = `${API}/api/ai/analyze`
  if (mode === 'forceAll') {
    url += '?mode=forceAll'
  } else if (mode === 'forceOne') {
    url += `?mode=forceOne&moduleId=${encodeURIComponent(moduleId)}`
  }

  try {
    const res = await fetch(url, { method: 'POST' })
    aiStatus.value = await res.json()
  } catch (e) {
    aiStatus.value = { ...aiStatus.value, phase: 'error', error: '提交 AI 任务失败：' + e.message }
    return
  }

  pollAi()
}

/* ------------------------------ 右键菜单 ------------------------------ */

const menu = ref({ open: false, x: 0, y: 0, moduleId: '' })

function openMenu(event, moduleId) {
  // 贴着视口边缘时往回挪，避免菜单跑出屏幕
  const x = Math.min(event.clientX, window.innerWidth - 190)
  const y = Math.min(event.clientY, window.innerHeight - 120)
  menu.value = { open: true, x, y, moduleId: moduleId || '' }
}

function closeMenu() {
  if (menu.value.open) menu.value = { ...menu.value, open: false }
}

function regenerateOne(moduleId) {
  startAi('forceOne', moduleId)
}

function regenerateAll() {
  startAi('forceAll')
}

/* ------------------------------ 跳转源码 ------------------------------ */

const toast = ref('')
let toastTimer = null

function showToast(text) {
  toast.value = text
  if (toastTimer) clearTimeout(toastTimer)
  if (explainTimer) clearInterval(explainTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2400)
}

async function openInIde(moduleId, file = '') {
  closeMenu()
  try {
    let url = `${API}/api/open?moduleId=${encodeURIComponent(moduleId)}`
    if (file) url += `&file=${encodeURIComponent(file)}`
    const res = await fetch(url, { method: 'POST' })
    const data = await res.json()
    if (data.ok) {
      showToast(`已在编辑器打开 ${data.file || ''}`)
    } else {
      showToast(data.msg || '打开失败')
    }
  } catch (e) {
    showToast('打开失败：' + e.message)
  }
}

async function clearCache() {
  closeMenu()
  await resetAi()
}

function pollAi() {
  if (aiTimer) clearInterval(aiTimer)
  aiTimer = setInterval(async () => {
    await fetchAiStatus()
    if (aiStatus.value.phase !== 'running') {
      clearInterval(aiTimer)
      aiTimer = null
      if (aiStatus.value.phase === 'done') {
        await fetchData()
      }
    }
  }, 1200)
}

async function resetAi() {
  clearInterval(aiTimer)
  aiTimer = null
  try {
    const res = await fetch(`${API}/api/ai/reset`, { method: 'POST' })
    aiStatus.value = await res.json()
    await fetchData()
  } catch (e) {
    /* ignore */
  }
}

async function openSettings() {
  try {
    await fetch(`${API}/api/openSettings`, { method: 'POST' })
  } catch (e) {
    /* ignore */
  }
}

function isEdgeActive(edge) {
  const focus = hoveredId.value || selectedId.value
  if (!focus) return false
  return edge.from === focus || edge.to === focus
}

/* ------------------------------ 数据 ------------------------------ */

async function fetchData() {
  if (controller) controller.abort()
  controller = new AbortController()

  loading.value = true
  error.value = ''

  try {
    const res = await fetch(`${API}/api/projectGraph`, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)

    const data = await res.json()
    if (!data.success) {
      error.value = data.message || data.msg || '服务返回失败'
      modules.value = []
      relations.value = []
      notice.value = ''
      return
    }

    modules.value = data.nodes || []
    relations.value = data.edges || []
    stats.value = data.stats || { modules: 0, files: 0, lines: 0, relations: 0, languages: [] }
    projectName.value = data.projectName || ''
    notice.value = data.notice || ''

    // 深链：?focus=mod:xxx&files=mod:xxx
    const params = new URLSearchParams(location.search)
    const wantFiles = params.get('files')
    if (wantFiles) {
      selectedId.value = wantFiles
      await enterFileView(wantFiles)
      // ?explain=1 直接展开第一个文件的逐行解释（便于联调/截图）
      if (params.get('explain')) {
        const first = fileView.value && fileView.value.nodes[0]
        if (first) {
          selectedFileId.value = first.id
          explainFile()
        }
      }
      return
    }

    const wantFocus = params.get('focus')
    if (wantFocus && modules.value.some((n) => n.id === wantFocus)) {
      selectedId.value = wantFocus
    } else if (!modules.value.some((n) => n.id === selectedId.value)) {
      selectedId.value = ''
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    error.value = '连不上插件服务（' + API + '）。请确认插件已启动、右侧工具窗已打开。'
    modules.value = []
    relations.value = []
    console.error(e)
  } finally {
    loading.value = false
  }
}

watch(autoRefresh, (on) => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (on) timer = setInterval(fetchData, 5000)
})

function onGlobalKey(event) {
  if (event.key === 'Escape') {
    if (menu.value.open) closeMenu()
    else if (fileView.value) exitFileView()
    else selectedId.value = ''
  }
  if ((event.ctrlKey || event.metaKey) && event.key === '0') {
    event.preventDefault()
    resetView()
  }
}

onMounted(() => {
  fetchData()
  fetchAiStatus()
  window.addEventListener('click', closeMenu)
  window.addEventListener('keydown', onGlobalKey)

})

/**
 * 画布尺寸 → viewBox，保证 100% 就是真实像素。
 * 注意：svg 在数据到达前不存在（v-else），所以只能等 ref 出现后再绑定，
 * 不能在 onMounted 里直接读——否则拿到 null，viewport 永远停在默认值，
 * 页面会被 preserveAspectRatio 二次缩放，看起来"上方大片空白、卡片很小"。
 */
function attachCanvas(el) {
  if (!el || el === observedEl) return
  if (resizeObserver) resizeObserver.disconnect()
  observedEl = el

  viewport.w = Math.max(200, el.clientWidth || 900)
  viewport.h = Math.max(200, el.clientHeight || 640)

  resizeObserver = new ResizeObserver((entries) => {
    const box = entries[0]?.contentRect
    if (!box) return
    viewport.w = Math.max(200, box.width)
    viewport.h = Math.max(200, box.height)
    if (!userAdjusted) fitView()
  })
  resizeObserver.observe(el)

  userAdjusted = false
  fitView()
}

watch(
  svgEl,
  (el) => {
    if (el) nextTick(() => attachCanvas(el))
  },
  { flush: 'post' },
)

// 数据变化后重新适配一次
watch(
  () => modules.value.length,
  () => nextTick(() => fitView()),
)

// 进入/退出聚焦时重新适配（子图范围变了）
watch(
  () => selectedId.value,
  () => nextTick(() => fitView()),
)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (aiTimer) clearInterval(aiTimer)
  if (controller) controller.abort()
  if (toastTimer) clearTimeout(toastTimer)
  if (explainTimer) clearInterval(explainTimer)
  if (resizeObserver) resizeObserver.disconnect()
  onPanUp()
  window.removeEventListener('click', closeMenu)
  window.removeEventListener('keydown', onGlobalKey)
})
</script>

<style scoped>
/* ============================================================
   设计基调：深色石墨底 + 青色强调，信息层级靠字号/明度而非色块
   ============================================================ */

.container {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(1200px 500px at 12% -10%, rgba(53, 224, 200, 0.08), transparent 60%),
    radial-gradient(900px 400px at 92% 110%, rgba(124, 108, 240, 0.08), transparent 60%),
    #0a0f14;
  color: #e6eef0;
  font-family: 'Segoe UI', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 13px;
}

/* ---------------- 顶栏 ---------------- */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 10px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.02);
  flex-shrink: 0;
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: #eef7f6;
}

.subtitle {
  font-size: 11px;
  color: #6f878c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.ai-msg {
  font-size: 11.5px;
  color: #a78bfa;
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.auto {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: #7d9599;
  cursor: pointer;
  user-select: none;
  padding: 0 4px;
}

.auto input {
  accent-color: #35e0c8;
  cursor: pointer;
}

/* 按钮族：统一高度 28、圆角 6 */
.ai-btn,
.refresh-btn,
.cfg-btn {
  height: 28px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12.5px;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
  white-space: nowrap;
}

.ai-btn {
  padding: 0 14px;
  background: rgba(124, 108, 240, 0.14);
  border: 1px solid rgba(167, 139, 250, 0.38);
  color: #c8b6ff;
}

.ai-btn:hover:not(:disabled) {
  background: rgba(124, 108, 240, 0.24);
  border-color: rgba(167, 139, 250, 0.6);
}

.refresh-btn {
  padding: 0 16px;
  background: rgba(53, 224, 200, 0.12);
  border: 1px solid rgba(53, 224, 200, 0.4);
  color: #7ff0dd;
}

.refresh-btn:hover:not(:disabled) {
  background: rgba(53, 224, 200, 0.2);
  border-color: rgba(53, 224, 200, 0.7);
}

.cfg-btn {
  width: 28px;
  padding: 0;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #8fa6aa;
  font-size: 14px;
  line-height: 1;
}

.cfg-btn:hover {
  border-color: rgba(53, 224, 200, 0.4);
  color: #7ff0dd;
}

.ai-btn:disabled,
.refresh-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------------- 提示条 ---------------- */
.cfg-hint {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 18px;
  background: rgba(124, 108, 240, 0.07);
  border-bottom: 1px solid rgba(124, 108, 240, 0.16);
  font-size: 11.5px;
  color: #a99cce;
  flex-shrink: 0;
}

.notice-bar {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px 18px;
  background: rgba(232, 163, 61, 0.08);
  border-bottom: 1px solid rgba(232, 163, 61, 0.22);
  flex-shrink: 0;
}

.notice-icon {
  flex-shrink: 0;
  width: 15px;
  height: 15px;
  margin-top: 1px;
  border-radius: 50%;
  background: rgba(232, 163, 61, 0.9);
  color: #1a1206;
  font-size: 10px;
  font-weight: 700;
  line-height: 15px;
  text-align: center;
}

.notice-text {
  font-size: 11.5px;
  line-height: 1.55;
  color: #d8ac6b;
  word-break: break-word;
}

.ai-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 18px;
  background: rgba(239, 83, 80, 0.09);
  border-bottom: 1px solid rgba(239, 83, 80, 0.24);
  flex-shrink: 0;
}

.ai-error-text {
  flex: 1;
  font-size: 11.5px;
  color: #ffab9f;
  line-height: 1.5;
  word-break: break-word;
}

.ghost-btn {
  height: 24px;
  padding: 0 12px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 5px;
  color: #b9c9cc;
  cursor: pointer;
  font-size: 11.5px;
  white-space: nowrap;
}

.ghost-btn:hover {
  border-color: rgba(53, 224, 200, 0.5);
  color: #7ff0dd;
}

/* ---------------- 画布 ---------------- */
.stage {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 聚焦时上下按 1:2 分配：画布 1 份，详情 2 份 */
.stage.focused .canvas-wrap {
  flex: 1 1 0;
}

.stage.focused .detail-panel {
  flex: 2 1 0;
  max-height: none;
}

/* 三级：固定尺寸横排，靠横向滚动 */
.canvas-wrap.files {
  overflow-x: auto;
  overflow-y: hidden;
}

.canvas-wrap.files .canvas {
  flex: none;
  cursor: default;
}

.canvas {
  flex: 1;
  min-width: 0;
  display: block;
  cursor: grab;
  user-select: none;
}

.canvas.panning {
  cursor: grabbing;
}

.stage-hint {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  margin: 0;
  font-size: 11px;
  color: #4d6266;
  pointer-events: none;
  white-space: nowrap;
}

/* ---------------- 缩放工具条 ---------------- */
.zoom-bar {
  position: absolute;
  left: 14px;
  top: 12px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: rgba(12, 20, 26, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 7px;
  z-index: 5;
}

.zoom-btn {
  min-width: 22px;
  height: 22px;
  padding: 0 3px;
  background: transparent;
  border: none;
  border-radius: 5px;
  color: #93aaae;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.zoom-btn.wide {
  font-size: 10.5px;
}

.zoom-btn:hover {
  background: rgba(53, 224, 200, 0.14);
  color: #c9fff5;
}

.zoom-val {
  min-width: 32px;
  text-align: center;
  font-size: 10px;
  color: #6f878c;
  font-family: Consolas, monospace;
}

/* ---------------- 连线 ---------------- */
.edge {
  stroke: #294744;
  color: #2f6f69;
  stroke-width: 1.1;
  opacity: 0.5;
  fill: none;
  transition: opacity 0.15s, stroke-width 0.15s;
}

/* color 同时决定箭头颜色（marker 用 currentColor） */
.edge-contain {
  stroke: #3f6b66;
  color: #3f6b66;
  stroke-dasharray: 1 3;
  opacity: 0.5;
}

.edge-extends { stroke: #e8a33d; color: #e8a33d; }
.edge-implements { stroke: #8f7de0; color: #8f7de0; stroke-dasharray: 5 3; }
.edge-uses { stroke: #2f6f69; color: #2f6f69; stroke-dasharray: 2 3; }

.edge.active {
  opacity: 1;
  stroke-width: 2;
}

/* 标签默认隐藏，只在悬停/选中相关边时出现，避免糊成一片 */
.edge-label {
  opacity: 0;
  fill: #8fb8b3;
  font-size: 10.5px;
  paint-order: stroke;
  stroke: #0a0f14;
  stroke-width: 3px;
  stroke-linejoin: round;
  pointer-events: none;
  transition: opacity 0.15s;
}

.edge-label.active {
  opacity: 1;
  fill: #7ff0dd;
}

/* ---------------- 模块卡 ---------------- */
.module {
  cursor: pointer;
}

.module.selected rect:first-of-type {
  filter: drop-shadow(0 0 6px rgba(53, 224, 200, 0.35));
}

.m-name {
  fill: #eaf6f4;
  font-size: 15px;
  font-weight: 600;
  font-family: Consolas, 'Cascadia Mono', monospace;
  letter-spacing: 0.2px;
  pointer-events: none;
}

.m-role {
  font-size: 10.5px;
  letter-spacing: 0.4px;
  pointer-events: none;
}

.m-pkg {
  fill: #6d8b8f;
  font-size: 10px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.m-desc {
  fill: #a9c2c2;
  font-size: 11.5px;
  pointer-events: none;
}

.m-desc.ai {
  fill: #8fe3d2;
}

.m-desc.issue {
  fill: #ff9e94;
}

.m-stat {
  fill: #6d8b8f;
  font-size: 10px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.m-ref {
  fill: #4f6b6e;
  font-size: 9.5px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.m-ai {
  fill: #7c6cf0;
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
  pointer-events: none;
}

.m-ai.err {
  fill: #ef5350;
}

/* ---------------- 右侧详情（浮层） ---------------- */
.inspector {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 300px;
  padding: 16px 18px;
  overflow-y: auto;
  background: rgba(10, 16, 21, 0.97);
  border-left: 1px solid rgba(255, 255, 255, 0.07);
  box-shadow: -12px 0 28px rgba(0, 0, 0, 0.45);
  animation: slide-in 0.16s ease-out;
  z-index: 6;
}

@keyframes slide-in {
  from { transform: translateX(12px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; }
}

.ins-close {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 22px;
  height: 22px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #6f878c;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.ins-close:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #e6eef0;
}

.ins-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding-right: 20px;
}

.badge {
  padding: 2px 9px;
  border-radius: 4px;
  font-size: 10.5px;
  letter-spacing: 0.5px;
  background: rgba(255, 255, 255, 0.06);
}

.ins-name {
  margin: 0 0 12px;
  font-family: Consolas, monospace;
  font-size: 12.5px;
  color: #cfe9e5;
  word-break: break-all;
  line-height: 1.5;
}

.ins-label {
  margin: 16px 0 8px;
  font-size: 10.5px;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: #4d6669;
}

.ins-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: #b6cfcd;
}

.ins-desc.ai { color: #8fe3d2; }
.ins-desc.bad { color: #ff9e94; }

.ins-rel {
  margin: 0;
  padding: 0;
  list-style: none;
}

.ins-rel li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 0;
  font-size: 11.5px;
  line-height: 1.55;
  border-bottom: 1px solid rgba(255, 255, 255, 0.04);
}

.rel-dir {
  color: #35e0c8;
  font-family: Consolas, monospace;
  flex-shrink: 0;
}

.rel-name {
  color: #cfe9e5;
  font-family: Consolas, monospace;
  flex-shrink: 0;
  max-width: 108px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rel-note {
  color: #7d9599;
  word-break: break-word;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.chip {
  padding: 3px 9px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  color: #9db3b5;
  font-family: Consolas, monospace;
  font-size: 10.5px;
}

.chip-btn {
  cursor: pointer;
  transition: all 0.14s;
}

.chip-btn:hover {
  background: rgba(53, 224, 200, 0.14);
  border-color: rgba(53, 224, 200, 0.45);
  color: #c9fff5;
}

.ins-issues {
  margin: 0;
  padding-left: 16px;
  color: #ff9e94;
  font-size: 11.5px;
  line-height: 1.6;
}

/* ---------------- 空态 / 错误 ---------------- */
.empty,
.error-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 8px;
  padding: 24px;
  text-align: center;
}

.empty-main {
  margin: 0;
  font-size: 14px;
  color: #8fa6aa;
}

.tip {
  margin: 0;
  font-size: 11.5px;
  color: #4d6266;
}

.error-box { color: #ff9e94; }

.error-title {
  margin: 0;
  font-size: 14px;
}

.error-msg {
  margin: 0;
  max-width: 560px;
  font-size: 12px;
  color: #c98b86;
  line-height: 1.6;
  word-break: break-word;
}

/* ---------------- 底栏 ---------------- */
.legend {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 0 18px;
  height: 30px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.02);
  font-size: 11px;
  color: #6f878c;
  flex-shrink: 0;
}

.lg {
  display: inline-block;
  width: 16px;
  height: 0;
  margin-right: 6px;
  vertical-align: middle;
}

.lg-extends { border-top: 1.6px solid #e8a33d; }
.lg-implements { border-top: 1.6px dashed #8f7de0; }
.lg-uses { border-top: 1.6px dotted #2f6f69; }
.lg-contain { border-top: 1.6px dotted #3f6b66; }

.count {
  margin-left: auto;
  font-family: Consolas, monospace;
  color: #5d7a7e;
}

/* ---------------- 画布浮层 ---------------- */
.canvas-legend {
  position: absolute;
  left: 16px;
  bottom: 12px;
  display: flex;
  gap: 14px;
  font-size: 10.5px;
  color: #6f878c;
  pointer-events: none;
}

.canvas-stats {
  position: absolute;
  right: 16px;
  bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 7px 11px;
  border-radius: 7px;
  background: rgba(12, 20, 26, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.06);
  font-size: 10.5px;
  pointer-events: none;
}

.stat-row {
  display: flex;
  gap: 16px;
  justify-content: space-between;
}

.stat-k {
  color: #5d7a7e;
}

.stat-v {
  color: #a9c2c2;
  font-family: Consolas, monospace;
}

/* ---------------- 底部详情面板 ---------------- */
.detail-panel {
  flex-shrink: 0;
  max-height: 46%;
  overflow: hidden;
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 14px 26px;
  padding: 14px 18px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  background: rgba(255, 255, 255, 0.02);
}

/* 窄窗口自动换行：宽度不够时每块各占一行 */
.dp-main {
  flex: 1 1 340px;
  min-width: 240px;
}

.dp-block {
  flex: 1 1 190px;
  min-width: 170px;
}

.dp-head {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 6px;
}

.dp-title {
  font-family: Consolas, monospace;
  font-size: 15px;
  font-weight: 600;
  color: #eaf6f4;
}

.dp-close {
  margin-left: auto;
  width: 22px;
  height: 22px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #6f878c;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
}

.dp-close:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #e6eef0;
}

.dp-path {
  margin: 0 0 10px;
  font-family: Consolas, monospace;
  font-size: 10.5px;
  line-height: 1.5;
  color: #5d7a7e;
  word-break: break-all;
}

.dp-label {
  margin: 0 0 6px;
  font-size: 10.5px;
  letter-spacing: 0.6px;
  text-transform: uppercase;
  color: #4d6669;
}

.dp-desc {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.75;
  color: #b8d1cf;
}

.dp-desc.ai { color: #8fe3d2; }
.dp-desc.bad { color: #ff9e94; }

.dp-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-family: Consolas, monospace;
  font-size: 11px;
  color: #6d8b8f;
}

/* 关联：只表达关系，不可点选 */
.dp-sub {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 120px;
  overflow-y: auto;
}

.dp-sub li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 4px;
  border-radius: 4px;
  font-size: 11.5px;
  cursor: pointer;
}

.dp-sub li:hover {
  background: rgba(53, 224, 200, 0.1);
}

.sub-dir {
  color: #4d6669;
  font-family: Consolas, monospace;
}

.sub-name {
  color: #cfe9e5;
  font-family: Consolas, monospace;
}

.sub-meta {
  color: #5d7a7e;
  font-size: 10.5px;
}

.dp-rel {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 120px;
  overflow-y: auto;
}

.dp-rel li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 0;
  font-size: 11.5px;
  line-height: 1.55;
}

.rel-dir {
  flex-shrink: 0;
  color: #35e0c8;
  font-family: Consolas, monospace;
}

.rel-name {
  flex-shrink: 0;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #cfe9e5;
  font-family: Consolas, monospace;
}

.rel-note {
  color: #7d9599;
  word-break: break-word;
}

.dp-sig {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 120px;
  overflow-y: auto;
}

.dp-sig li {
  padding: 3px 0;
  font-family: Consolas, monospace;
  font-size: 11px;
  line-height: 1.55;
  color: #8fa9ac;
  word-break: break-all;
  border-bottom: 1px solid rgba(255, 255, 255, 0.03);
}

.chips-row {
  flex-wrap: nowrap;
  overflow-x: auto;
  padding-bottom: 2px;
}

.dp-issues {
  margin: 0;
  padding-left: 16px;
  color: #ff9e94;
  font-size: 11.5px;
  line-height: 1.6;
  max-height: 100px;
  overflow-y: auto;
}
/* ---------------- 逐行解释 ---------------- */
.explain-wrap {
  flex: 1 1 100%;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.line-list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.line-list li {
  display: grid;
  grid-template-columns: 44px minmax(0, 1.3fr) minmax(0, 0.95fr);
  gap: 10px;
  align-items: baseline;
  padding: 3px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.035);
  font-size: 11.5px;
  line-height: 1.5;
}

.line-list .ln {
  color: #4d6669;
  font-family: Consolas, monospace;
  text-align: right;
}

.line-list .lc {
  color: #a9c2c2;
  font-family: Consolas, monospace;
  white-space: pre;
  overflow: hidden;
  text-overflow: ellipsis;
}

.line-list .ly {
  color: #8fe3d2;
  word-break: break-word;
}

.line-list .li {
  grid-column: 2 / -1;
  color: #ff9e94;
  font-size: 11px;
  word-break: break-word;
}

.line-list li.bad .lc {
  color: #ffb4ac;
}

.act-btn.small {
  height: 22px;
  padding: 0 9px;
  font-size: 11px;
  margin-left: auto;
}

.act-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ---------------- 三级：面包屑 ---------------- */
.crumb-bar {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: rgba(12, 20, 26, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 7px;
  font-size: 11.5px;
  z-index: 5;
  max-width: 60%;
  overflow: hidden;
}

.crumb {
  padding: 0;
  background: transparent;
  border: none;
  color: #7fb3ad;
  font-size: 11.5px;
  cursor: pointer;
  white-space: nowrap;
}

.crumb:hover {
  color: #7ff0dd;
  text-decoration: underline;
}

.crumb-sep {
  color: #3f5b5e;
}

.crumb-meta {
  margin-left: 4px;
  padding-left: 8px;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  color: #4d6669;
  font-family: Consolas, monospace;
  white-space: nowrap;
}

.crumb-current {
  color: #cfe9e5;
  font-family: Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------------- 操作按钮 ---------------- */
.dp-actions {
  display: flex;
  gap: 8px;
  margin: 10px 0 8px;
}

.act-btn {
  height: 26px;
  padding: 0 12px;
  border-radius: 6px;
  border: 1px solid rgba(53, 224, 200, 0.4);
  background: rgba(53, 224, 200, 0.12);
  color: #7ff0dd;
  font-size: 11.5px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, border-color 0.15s;
}

.act-btn:hover {
  background: rgba(53, 224, 200, 0.2);
  border-color: rgba(53, 224, 200, 0.7);
}

.act-btn.ghost {
  background: transparent;
  border-color: rgba(255, 255, 255, 0.14);
  color: #9db3b5;
}

.act-btn.ghost:hover {
  border-color: rgba(53, 224, 200, 0.45);
  color: #7ff0dd;
}

.dp-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: #5d7a7e;
}

.dp-hint.error {
  color: #ff9e94;
}

.file-badge {
  color: #7ff0dd;
}

/* ---------------- 右键菜单 ---------------- */
.ctx-menu {
  position: fixed;
  z-index: 50;
  min-width: 176px;
  padding: 4px;
  background: #111a20;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 7px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.6);
  display: flex;
  flex-direction: column;
}

.ctx-item {
  padding: 7px 12px;
  background: transparent;
  border: none;
  border-radius: 5px;
  color: #c2d6d8;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
}

.ctx-item:hover {
  background: rgba(53, 224, 200, 0.12);
  color: #e6fffb;
}

.ctx-item.danger { color: #ff9e94; }

.ctx-item.danger:hover {
  background: rgba(239, 83, 80, 0.14);
}

/* ---------------- 轻提示 ---------------- */
.toast {
  position: fixed;
  left: 50%;
  bottom: 46px;
  transform: translateX(-50%);
  padding: 7px 16px;
  background: rgba(12, 20, 26, 0.96);
  border: 1px solid rgba(53, 224, 200, 0.4);
  border-radius: 6px;
  color: #9ff5e4;
  font-size: 12px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.55);
  pointer-events: none;
  white-space: nowrap;
  max-width: 80vw;
  overflow: hidden;
  text-overflow: ellipsis;
  z-index: 60;
}
</style>