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
          @click="startAi"
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
      <button class="ghost-btn" @click="startAi">重试</button>
      <button class="ghost-btn" @click="resetAi">清除</button>
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

    <div v-else class="stage">
      <svg class="canvas" :viewBox="viewBox" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <marker
            id="arrow"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
          </marker>
        </defs>

        <g v-for="r in routed" :key="r.key">
          <path
            :d="r.d"
            class="edge"
            :class="[`edge-${r.edge.kind}`, { active: isEdgeActive(r.edge) }]"
            fill="none"
            marker-end="url(#arrow)"
          />
          <text
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
          :class="{ selected: m.node.id === selectedId }"
          @click="selectedId = m.node.id"
        >
          <rect
            :x="m.x"
            :y="m.y"
            :width="CARD_W"
            :height="CARD_H"
            rx="10"
            :fill="fillOf(m.node)"
            :stroke="m.node.id === selectedId ? '#00ffcc' : borderColorOf(m.node)"
            :stroke-width="m.node.id === selectedId ? 2 : 1.2"
          />

          <text :x="m.x + 16" :y="m.y + 26" class="m-name">{{ m.node.shortName }}</text>

          <rect
            :x="m.x + CARD_W - 16 - m.roleWidth"
            :y="m.y + 12"
            :width="m.roleWidth"
            :height="18"
            rx="4"
            :fill="roleFill(m.node.role)"
          />
          <text
            :x="m.x + CARD_W - 16 - m.roleWidth / 2"
            :y="m.y + 25"
            class="m-role"
            text-anchor="middle"
          >{{ m.node.role }}</text>

          <text :x="m.x + 16" :y="m.y + 44" class="m-pkg">{{ truncate(m.node.name, 30) }}</text>

          <text
            v-for="(line, i) in m.lines"
            :key="i"
            :x="m.x + 16"
            :y="m.y + 64 + i * 15"
            class="m-desc"
            :class="{ issue: m.node.descState === 'error', ai: m.node.descState === 'ai' }"
          >{{ line }}</text>

          <text :x="m.x + 16" :y="m.y + CARD_H - 12" class="m-stat">
            {{ m.node.fileCount }} 文件 · {{ m.node.lineCount }} 行
          </text>
        </g>
      </svg>

      <aside class="inspector">
        <template v-if="selected">
          <div class="ins-head">
            <span class="badge">{{ selected.role }}</span>
          </div>
          <p class="ins-name">{{ selected.name }}</p>
          <p v-if="selected.advice" class="ins-label">修改意见</p>
          <p
            class="ins-desc"
            :class="{ ai: selected.descState === 'ai', bad: selected.descState === 'error' }"
          >{{ selected.desc }}</p>

          <template v-if="selectedRelations.length">
            <p class="ins-label">关系（{{ selectedRelations.length }}）</p>
            <ul class="ins-rel">
              <li v-for="(r, i) in selectedRelations" :key="i">
                <span class="rel-dir">{{ r.dir }}</span>
                <span class="rel-name">{{ r.other }}</span>
                <span class="rel-note">{{ r.label }}</span>
              </li>
            </ul>
          </template>

          <p class="ins-label">文件（{{ selected.fileCount }}）</p>
          <div class="chips">
            <span v-for="c in selected.files" :key="c" class="chip">{{ c }}</span>
          </div>

          <p v-if="selected.issues && selected.issues.length" class="ins-label">问题</p>
          <ul v-if="selected.issues && selected.issues.length" class="ins-issues">
            <li v-for="(it, i) in selected.issues" :key="i">{{ it }}</li>
          </ul>
        </template>
        <p v-else class="ins-hint">点击任意模块查看包内详情</p>
      </aside>
    </div>

    <footer class="legend">
      <span><i class="lg lg-extends"></i>继承</span>
      <span><i class="lg lg-implements"></i>实现</span>
      <span><i class="lg lg-uses"></i>依赖</span>
      <span class="count">{{ footerStats }}{{ cacheLabel }}</span>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'

const API = 'http://127.0.0.1:8899'

const CARD_W = 272
const CARD_H = 112
const GAP_X = 96
const GAP_Y = 56
const MAX_DESC_LINES = 2

const modules = ref([])
const relations = ref([])
const stats = ref({ modules: 0, files: 0, lines: 0, relations: 0, languages: [] })
const projectName = ref('')
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
  const lines = []
  let cur = ''
  let used = 0
  for (const ch of text || '') {
    const u = unitsOf(ch)
    if (used + u > maxUnits && cur) {
      lines.push(cur)
      cur = ''
      used = 0
    }
    cur += ch
    used += u
  }
  if (cur) lines.push(cur)
  return lines
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
const roleFill = (r) => (ROLE_TEXT[r] || '#80cbc4') + '22'
const borderColorOf = (n) => (ROLE_TEXT[n.role] || '#80cbc4') + '66'

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

const placed = computed(() => {
  const nodes = modules.value
  if (!nodes.length) return []

  const layer = layerOf(nodes, relations.value)
  const columns = new Map()
  for (const n of nodes) {
    const l = layer.get(n.id) || 0
    if (!columns.has(l)) columns.set(l, [])
    columns.get(l).push(n)
  }

  const result = []
  for (const [l, list] of [...columns.entries()].sort((a, b) => a[0] - b[0])) {
    // 同层内按包名排序，保证多次刷新位置稳定
    list.sort((a, b) => a.name.localeCompare(b.name))
    const total = list.length
    list.forEach((node, i) => {
      result.push({
        node,
        x: l * (CARD_W + GAP_X),
        y: (i - (total - 1) / 2) * (CARD_H + GAP_Y),
        lines: wrapText(node.desc, 24).slice(0, MAX_DESC_LINES),
        roleWidth: 14 + node.role.length * 11,
      })
    })
  }
  return result
})

const boxById = computed(() => {
  const m = new Map()
  for (const p of placed.value) m.set(p.node.id, p)
  return m
})

const viewBox = computed(() => {
  const list = placed.value
  if (!list.length) return '0 0 900 600'
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
  const pad = 90
  const w = Math.max(420, maxX - minX + pad * 2)
  const h = Math.max(300, maxY - minY + pad * 2)
  return `${minX - pad} ${minY - pad} ${w} ${h}`
})

function borderPoint(box, dx, dy) {
  const hw = CARD_W / 2
  const hh = CARD_H / 2
  const cx = box.x + hw
  const cy = box.y + hh
  if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return { x: cx, y: cy }
  const sx = Math.abs(dx) < 0.001 ? Infinity : hw / Math.abs(dx)
  const sy = Math.abs(dy) < 0.001 ? Infinity : hh / Math.abs(dy)
  const s = Math.min(sx, sy)
  return { x: cx + dx * s, y: cy + dy * s }
}

const routed = computed(() => {
  const out = []
  for (let i = 0; i < relations.value.length; i++) {
    const e = relations.value[i]
    const a = boxById.value.get(e.from)
    const b = boxById.value.get(e.to)
    if (!a || !b) continue

    const acx = a.x + CARD_W / 2
    const acy = a.y + CARD_H / 2
    const bcx = b.x + CARD_W / 2
    const bcy = b.y + CARD_H / 2
    const dx = bcx - acx
    const dy = bcy - acy

    const p1 = borderPoint(a, dx, dy)
    const p2 = borderPoint(b, -dx, -dy)

    let d
    let lx
    let ly
    if (Math.abs(bcx - acx) < 1) {
      // 同一列：向右绕一段
      const bulge = 70
      d = `M ${a.x + CARD_W} ${acy} C ${a.x + CARD_W + bulge} ${acy}, ${b.x + CARD_W + bulge} ${bcy}, ${b.x + CARD_W} ${bcy}`
      lx = a.x + CARD_W + bulge * 0.75
      ly = (acy + bcy) / 2
    } else {
      const mx = (p1.x + p2.x) / 2
      d = `M ${p1.x} ${p1.y} C ${mx} ${p1.y}, ${mx} ${p2.y}, ${p2.x} ${p2.y}`
      lx = mx
      ly = (p1.y + p2.y) / 2 - 4
    }

    out.push({ key: `${e.from}->${e.to}#${i}`, edge: e, d, lx, ly })
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

async function startAi() {
  if (aiStatus.value.phase === 'running') return
  aiStatus.value = { ...aiStatus.value, phase: 'running', error: '', message: '提交中...' }

  try {
    const res = await fetch(`${API}/api/ai/analyze`, { method: 'POST' })
    aiStatus.value = await res.json()
  } catch (e) {
    aiStatus.value = { ...aiStatus.value, phase: 'error', error: '提交 AI 任务失败：' + e.message }
    return
  }

  pollAi()
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
      return
    }

    modules.value = data.nodes || []
    relations.value = data.edges || []
    stats.value = data.stats || { modules: 0, files: 0, lines: 0, relations: 0, languages: [] }
    projectName.value = data.projectName || ''

    if (!modules.value.some((n) => n.id === selectedId.value)) selectedId.value = ''
  } catch (e) {
    if (e.name === 'AbortError') return
    error.value = '连不上插件服务（127.0.0.1:8899）。请确认插件已启动、右侧工具窗已打开。'
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

onMounted(() => {
  fetchData()
  fetchAiStatus()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (aiTimer) clearInterval(aiTimer)
  if (controller) controller.abort()
})
</script>

<style scoped>
.container {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #08161f 0%, #0b211a 100%);
  color: #e0f7fa;
  display: flex;
  flex-direction: column;
  font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
  overflow: hidden;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 11px 20px;
  border-bottom: 1px solid rgba(0, 229, 255, 0.16);
  background: rgba(0, 0, 0, 0.34);
  flex-shrink: 0;
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  background: linear-gradient(90deg, #00e5ff, #00ffcc);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  white-space: nowrap;
}

.subtitle {
  font-size: 11px;
  color: #5a8a84;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.auto {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #7fb3ad;
  cursor: pointer;
  user-select: none;
}

.auto input {
  accent-color: #00ffcc;
  cursor: pointer;
}

.refresh-btn {
  padding: 6px 18px;
  background: linear-gradient(135deg, #00695c, #004d40);
  border: 1px solid #00ffcc;
  border-radius: 5px;
  color: #e0f7fa;
  cursor: pointer;
  font-size: 12.5px;
  transition: all 0.25s;
}

.refresh-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #00897b, #00695c);
  box-shadow: 0 0 12px rgba(0, 255, 204, 0.4);
}

.refresh-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.ai-btn {
  padding: 6px 16px;
  background: linear-gradient(135deg, #3b2a63, #241a3d);
  border: 1px solid #b39ddb;
  border-radius: 5px;
  color: #e6ddff;
  cursor: pointer;
  font-size: 12.5px;
  transition: all 0.25s;
  white-space: nowrap;
}

.ai-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #4b3680, #2e2150);
  box-shadow: 0 0 12px rgba(179, 157, 219, 0.35);
}

.ai-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ai-msg {
  font-size: 11.5px;
  color: #b39ddb;
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 20px;
  background: rgba(211, 47, 47, 0.12);
  border-bottom: 1px solid rgba(211, 47, 47, 0.3);
  flex-shrink: 0;
}

.cfg-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(0, 229, 255, 0.2);
  border-radius: 5px;
  color: #7fb3ad;
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  flex-shrink: 0;
}

.cfg-btn:hover {
  background: rgba(0, 229, 255, 0.1);
  color: #e0f7fa;
}

.cfg-hint {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 20px;
  background: rgba(179, 157, 219, 0.08);
  border-bottom: 1px solid rgba(179, 157, 219, 0.2);
  font-size: 12px;
  color: #b0a0e0;
  flex-shrink: 0;
}

.ai-error-text {
  flex: 1;
  font-size: 12px;
  color: #ff9e94;
  line-height: 1.5;
  word-break: break-word;
}

.stage {
  flex: 1;
  display: flex;
  min-height: 0;
}

.canvas {
  flex: 1;
  min-width: 0;
  display: block;
}

.edge {
  stroke: #1d5f57;
  stroke-width: 1.3;
  color: #00e5ff;
  opacity: 0.55;
  transition: opacity 0.2s, stroke-width 0.2s;
}

.edge-extends {
  stroke: #ffb74d;
  color: #ffb74d;
}

.edge-implements {
  stroke: #b39ddb;
  color: #b39ddb;
  stroke-dasharray: 6 3;
}

.edge-uses {
  stroke: #2e8b84;
  color: #2e8b84;
  stroke-dasharray: 3 3;
}

.edge.active {
  opacity: 1;
  stroke-width: 2.2;
}

.edge-label {
  fill: #6fa8a2;
  font-size: 10.5px;
  paint-order: stroke;
  stroke: #08161f;
  stroke-width: 3px;
  stroke-linejoin: round;
  pointer-events: none;
}

.edge-label.active {
  fill: #00ffcc;
}

.module {
  cursor: pointer;
}

.module.selected rect:first-of-type {
  filter: drop-shadow(0 0 8px rgba(0, 255, 204, 0.5));
}

.m-name {
  fill: #7ffce8;
  font-size: 14.5px;
  font-weight: 600;
  font-family: Consolas, 'Cascadia Mono', monospace;
  pointer-events: none;
}

.m-role {
  fill: #cdeee9;
  font-size: 10px;
  pointer-events: none;
}

.m-pkg {
  fill: #4f8f88;
  font-size: 9.5px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.m-desc {
  fill: #a8ccc7;
  font-size: 11px;
  pointer-events: none;
}

.m-desc.issue {
  fill: #ff8a80;
}

.m-desc.ai {
  fill: #8fe8cf;
}

.m-stat {
  fill: #3f6b66;
  font-size: 9.5px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.inspector {
  width: 262px;
  flex-shrink: 0;
  border-left: 1px solid rgba(0, 229, 255, 0.14);
  background: rgba(0, 0, 0, 0.3);
  padding: 14px 16px;
  overflow-y: auto;
  font-size: 12.5px;
}

.ins-head {
  margin-bottom: 8px;
}

.badge {
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 10px;
  background: rgba(0, 255, 204, 0.14);
  color: #4dd0b1;
}

.ins-name {
  margin: 0 0 8px;
  font-family: Consolas, monospace;
  font-size: 12.5px;
  color: #7ffce8;
  word-break: break-all;
  line-height: 1.5;
}

.ins-desc {
  margin: 0 0 16px;
  color: #a8ccc7;
  line-height: 1.6;
}

.ins-label {
  margin: 0 0 8px;
  font-size: 11px;
  color: #4f8f88;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 2px 8px;
  border-radius: 3px;
  background: rgba(0, 229, 255, 0.07);
  border: 1px solid rgba(0, 229, 255, 0.14);
  color: #9fd8d2;
  font-family: Consolas, monospace;
  font-size: 10.5px;
}

.ins-issues {
  margin: 0;
  padding-left: 16px;
  color: #ff8a80;
  line-height: 1.6;
}

.ins-desc.ai {
  color: #8fe8cf;
}

.ins-desc.bad {
  color: #ff9e94;
}

.ins-rel {
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
}

.ins-rel li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 0;
  line-height: 1.5;
  font-size: 11.5px;
}

.rel-dir {
  color: #00ffcc;
  font-family: Consolas, monospace;
  flex-shrink: 0;
}

.rel-name {
  color: #7ffce8;
  font-family: Consolas, monospace;
  flex-shrink: 0;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rel-note {
  color: #6f9d98;
  font-size: 11px;
  word-break: break-word;
}

.ins-hint {
  margin: 0;
  color: #4f8f88;
  font-size: 12px;
  text-align: center;
  padding-top: 40px;
}

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
  font-size: 16px;
  color: #80cbc4;
}

.tip {
  margin: 0;
  font-size: 12.5px;
  color: #4f6f6b;
}

.error-box {
  color: #ff8a80;
}

.error-title {
  margin: 0;
  font-size: 15px;
}

.error-msg {
  margin: 0;
  font-size: 12.5px;
  color: #c98b86;
  max-width: 560px;
  line-height: 1.6;
  word-break: break-word;
}

.ghost-btn {
  margin-top: 8px;
  padding: 6px 16px;
  background: transparent;
  border: 1px solid rgba(255, 138, 128, 0.5);
  border-radius: 5px;
  color: #ff8a80;
  cursor: pointer;
  font-size: 12.5px;
}

.ghost-btn:hover {
  background: rgba(255, 138, 128, 0.1);
}

.legend {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 7px 20px;
  border-top: 1px solid rgba(0, 229, 255, 0.14);
  font-size: 11.5px;
  color: #6fa8a2;
  background: rgba(0, 0, 0, 0.34);
  flex-shrink: 0;
}

.lg {
  display: inline-block;
  width: 16px;
  height: 0;
  margin-right: 6px;
  vertical-align: middle;
}

.lg-extends { border-top: 1.6px solid #ffb74d; }
.lg-implements { border-top: 1.6px dashed #b39ddb; }
.lg-uses { border-top: 1.6px dotted #2e8b84; }

.count {
  margin-left: auto;
  color: #00e5ff;
}
</style>
