<template>
  <div class="container">
    <header class="header">
      <div class="brand">
        <h1 class="title">AI 代码可视化画布</h1>
        <span class="subtitle">{{ subtitle }}</span>
      </div>
      <div class="actions">
        <label class="auto">
          <input type="checkbox" v-model="autoRefresh" />
          自动刷新
        </label>
        <button class="refresh-btn" :disabled="loading" @click="fetchData">
          {{ loading ? '解析中...' : '刷新解析' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="error-box">
      <p class="error-title">无法获取代码结构</p>
      <p class="error-msg">{{ error }}</p>
      <button class="ghost-btn" @click="fetchData">重试</button>
    </div>

    <div v-else-if="!nodes.length" class="empty">
      <p class="empty-main">等待解析 PSI 数据...</p>
      <p class="tip">请在左侧打开一个 Java 文件，点击右上角「刷新解析」</p>
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
            <path d="M 0 0 L 10 5 L 0 10 z" fill="#00e5ff" fill-opacity="0.7" />
          </marker>
        </defs>

        <path
          v-for="r in routed"
          :key="r.key"
          :d="r.d"
          class="edge"
          :class="[`edge-${r.edge.kind}`, { active: isEdgeActive(r.edge) }]"
          fill="none"
          marker-end="url(#arrow)"
        />

        <g
          v-for="p in placed"
          :key="p.node.id"
          class="node"
          :class="{ selected: p.node.id === selectedId, dim: isDim(p.node) }"
          :transform="`translate(${p.box.x}, ${p.box.y})`"
          @click="selectedId = p.node.id"
        >
          <title>{{ tipOf(p.node) }}</title>
          <rect
            :x="-p.box.w / 2"
            :y="-p.box.h / 2"
            :width="p.box.w"
            :height="p.box.h"
            rx="8"
            :fill="colorOf(p.node.type)"
            :stroke="p.node.id === selectedId ? '#00ffcc' : '#123a36'"
            stroke-width="1.5"
          />
          <text text-anchor="middle" dy="4" class="node-label">{{ p.node.label }}</text>
          <text :x="-p.box.w / 2 + 9" :y="-p.box.h / 2 - 7" class="node-type">
            {{ p.node.type.toUpperCase() }}
          </text>
          <circle
            v-if="p.node.state === 'pending'"
            :cx="p.box.w / 2 - 9"
            :cy="-p.box.h / 2 + 9"
            r="3.5"
            class="dot-pending"
          >
            <title>AI 语义待生成</title>
          </circle>
        </g>
      </svg>

      <aside class="inspector">
        <template v-if="selected">
          <div class="ins-head">
            <span class="badge" :class="`badge-${selected.type}`">{{ selected.type }}</span>
            <span class="ins-title">{{ selected.label }}</span>
          </div>

          <div class="ins-row" v-if="selected.signature">
            <span class="ins-key">签名</span>
            <span class="ins-val mono">{{ selected.signature }}</span>
          </div>

          <div class="ins-row">
            <span class="ins-key">语义</span>
            <span class="ins-val" :class="{ muted: selected.state === 'pending' }">
              {{ selected.state === 'pending' ? '待 AI 生成' : selected.summary }}
            </span>
          </div>

          <div class="ins-row" v-if="selected.calls && selected.calls.length">
            <span class="ins-key">调用</span>
            <span class="ins-val mono">{{ selected.calls.join(' · ') }}</span>
          </div>

          <pre v-if="selected.source" class="ins-src">{{ selected.source }}</pre>
        </template>
        <p v-else class="ins-hint">点击任意节点查看详情</p>
      </aside>
    </div>

    <footer class="legend">
      <span><i class="dot class"></i>类</span>
      <span><i class="dot method"></i>方法</span>
      <span><i class="dot field"></i>字段</span>
      <span><i class="line"></i>调用</span>
      <span class="count">节点 {{ nodes.length }} · 连线 {{ edges.length }}</span>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'

const API = 'http://127.0.0.1:8899'

const nodes = ref([])
const edges = ref([])
const fileName = ref('')
const loading = ref(false)
const error = ref('')
const selectedId = ref('')
const autoRefresh = ref(false)

let timer = null
let controller = null

const COLORS = { class: '#00695c', method: '#01579b', field: '#4a148c' }
const FALLBACK_BOX = { x: 0, y: 0, w: 120, h: 40 }

const colorOf = (type) => COLORS[type] || '#37474f'

const subtitle = computed(() => {
  const name = fileName.value || '未打开 Java 文件'
  return `${name} · ${nodes.value.length} 节点 / ${edges.value.length} 连线`
})

/* ---------------------------------------------------------------
 * 契约归一化
 *   新契约：{ nodes: [...], edges: [...] }              直接使用
 *   旧契约：{ packages: [{ classes: [{ methods, fields }] }] }  就地拍平
 * 这样无论插件端是否已重新编译，前端都能出图。
 * ------------------------------------------------------------- */
function splitSummary(raw) {
  const text = (raw || '').trim()
  if (!text || text.includes('【AI待生成】')) {
    return { text: '', state: 'pending' }
  }
  return { text, state: 'ai' }
}

function fromLegacy(data) {
  const outNodes = []
  const outEdges = []
  const seen = new Set()

  const push = (node) => {
    if (seen.has(node.id)) return
    seen.add(node.id)
    outNodes.push(node)
  }

  for (const pkg of data.packages || []) {
    const pkgName = pkg.packageName || 'default'
    for (const cls of pkg.classes || []) {
      const classId = `c:${pkgName}.${cls.className}`
      const s = splitSummary(cls.summary)

      push({
        id: classId,
        type: 'class',
        label: cls.className,
        signature: `${pkgName}.${cls.className}`,
        summary: s.text,
        state: s.state,
      })

      for (const fieldName of cls.fields || []) {
        const fieldId = `f:${classId}#${fieldName}`
        push({
          id: fieldId,
          type: 'field',
          label: fieldName,
          owner: classId,
          summary: '',
          state: 'pending',
        })
        outEdges.push({ from: classId, to: fieldId, kind: 'field' })
      }

      const byName = new Map()
      for (const m of cls.methods || []) {
        const methodId = `m:${classId}#${m.methodName}`
        byName.set(m.methodName, methodId)
        const ms = splitSummary(m.summary)
        push({
          id: methodId,
          type: 'method',
          label: m.methodName,
          owner: classId,
          signature: m.methodName,
          summary: ms.text,
          state: ms.state,
          calls: m.callList || [],
          vars: m.varList || [],
          source: m.methodSource || '',
        })
        outEdges.push({ from: classId, to: methodId, kind: 'method' })
      }

      // 调用边：javaparser 只能给出被调方法名，同文件同名即视为一条边
      for (const n of outNodes) {
        if (n.type !== 'method' || !n.owner || n.owner !== classId) continue
        for (const callee of n.calls || []) {
          const target = byName.get(callee)
          if (target && target !== n.id) {
            outEdges.push({ from: n.id, to: target, kind: 'call' })
          }
        }
      }
    }
  }

  return { nodes: outNodes, edges: outEdges }
}

function normalize(data) {
  if (Array.isArray(data.nodes) && data.nodes.length > 0) {
    return {
      nodes: data.nodes.map((n) => ({ ...n, state: n.summaryState || n.state || 'pending' })),
      edges: data.edges || [],
    }
  }
  return fromLegacy(data)
}

/* --------------------------------------------------------------- */
/* 分层布局：字段在左、类居中、方法在右，每个类独立一块           */
/* --------------------------------------------------------------- */
const boxWidth = (label) => Math.min(250, Math.max(112, (label || '').length * 8.6 + 46))

const layout = computed(() => {
  const pos = new Map()
  const all = nodes.value
  const roots = all.filter((n) => !n.owner)
  const rowH = 52
  let cursorY = 0

  for (const root of roots) {
    const children = all.filter((n) => n.owner === root.id)
    const fields = children.filter((n) => n.type === 'field')
    const methods = children.filter((n) => n.type === 'method')

    const rows = Math.max(methods.length, fields.length, 1)
    const blockH = Math.max(56, rows * rowH)
    const centerY = cursorY + blockH / 2

    pos.set(root.id, { x: 0, y: centerY, w: boxWidth(root.label), h: 54 })

    const firstY = centerY - ((rows - 1) * rowH) / 2
    methods.forEach((n, i) => {
      pos.set(n.id, { x: 340, y: firstY + i * rowH, w: boxWidth(n.label), h: 40 })
    })
    fields.forEach((n, i) => {
      pos.set(n.id, { x: -340, y: firstY + i * rowH, w: boxWidth(n.label), h: 40 })
    })

    cursorY += blockH + 96
  }

  // 兜底：没有归属的节点单独排一列
  let orphanY = cursorY + 40
  for (const n of all) {
    if (pos.has(n.id)) continue
    pos.set(n.id, { x: 0, y: orphanY, w: boxWidth(n.label), h: 44 })
    orphanY += 70
  }

  return pos
})

const placed = computed(() =>
  nodes.value.map((node) => ({ node, box: layout.value.get(node.id) || FALLBACK_BOX })),
)

const viewBox = computed(() => {
  const boxes = [...layout.value.values()]
  if (!boxes.length) return '0 0 800 600'

  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity

  for (const b of boxes) {
    minX = Math.min(minX, b.x - b.w / 2)
    maxX = Math.max(maxX, b.x + b.w / 2)
    minY = Math.min(minY, b.y - b.h / 2)
    maxY = Math.max(maxY, b.y + b.h / 2)
  }

  const pad = 72
  const w = Math.max(360, maxX - minX + pad * 2)
  const h = Math.max(260, maxY - minY + pad * 2)
  return `${minX - pad} ${minY - pad} ${w} ${h}`
})

function anchor(box, towardX) {
  const dir = towardX >= box.x ? 1 : -1
  return { x: box.x + (dir * box.w) / 2, y: box.y }
}

function pathFor(edge) {
  const a = layout.value.get(edge.from)
  const b = layout.value.get(edge.to)
  if (!a || !b) return ''

  // 同一列（方法调方法）向右绕一段，避免压在方块上
  if (Math.abs(a.x - b.x) < 1) {
    const x1 = a.x + a.w / 2
    const x2 = b.x + b.w / 2
    const bulge = 78
    return `M ${x1} ${a.y} C ${x1 + bulge} ${a.y}, ${x2 + bulge} ${b.y}, ${x2} ${b.y}`
  }

  const p1 = anchor(a, b.x)
  const p2 = anchor(b, a.x)
  const midX = (p1.x + p2.x) / 2
  return `M ${p1.x} ${p1.y} C ${midX} ${p1.y}, ${midX} ${p2.y}, ${p2.x} ${p2.y}`
}

const routed = computed(() =>
  edges.value
    .map((edge, i) => ({ edge, key: `${edge.from}->${edge.to}#${i}`, d: pathFor(edge) }))
    .filter((r) => r.d !== ''),
)

const selected = computed(() => nodes.value.find((n) => n.id === selectedId.value) || null)

const hoveredId = ref('')

function isEdgeActive(edge) {
  const focus = hoveredId.value || selectedId.value
  if (!focus) return false
  return edge.from === focus || edge.to === focus
}

function isDim(node) {
  const focus = hoveredId.value || selectedId.value
  if (!focus) return false
  if (node.id === focus) return false
  return !edges.value.some(
    (e) => (e.from === focus && e.to === node.id) || (e.to === focus && e.from === node.id),
  )
}

function tipOf(node) {
  const head = `${node.type} · ${node.label}`
  return node.state === 'pending' ? `${head}\n语义待 AI 生成` : `${head}\n${node.summary}`
}

/* --------------------------------------------------------------- */
async function fetchData() {
  if (controller) controller.abort()
  controller = new AbortController()

  loading.value = true
  error.value = ''

  try {
    const res = await fetch(`${API}/parseCurrentFile`, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) throw new Error(`接口返回 HTTP ${res.status}`)

    const data = await res.json()
    if (!data.success) {
      error.value = data.msg || '解析失败'
      nodes.value = []
      edges.value = []
      return
    }

    const normalized = normalize(data)
    nodes.value = normalized.nodes
    edges.value = normalized.edges
    fileName.value = data.filePath || ''

    if (!selectedId.value || !nodes.value.some((n) => n.id === selectedId.value)) {
      selectedId.value = ''
    }
  } catch (e) {
    if (e.name === 'AbortError') return
    error.value = '无法连接插件服务，请确认插件已启动且左侧打开了 Java 文件'
    nodes.value = []
    edges.value = []
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

onMounted(fetchData)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (controller) controller.abort()
})
</script>

<style scoped>
.container {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #0a1929 0%, #0d2818 100%);
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
  padding: 12px 20px;
  border-bottom: 1px solid rgba(0, 229, 255, 0.18);
  background: rgba(0, 0, 0, 0.32);
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  background: linear-gradient(90deg, #00e5ff, #00ffcc);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.subtitle {
  font-size: 11px;
  color: #5f8a86;
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
  opacity: 0.5;
  cursor: not-allowed;
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
  cursor: default;
}

/* ---- 边 ---- */
.edge {
  stroke: #1f4a45;
  stroke-width: 1.4;
  transition: stroke 0.2s, stroke-width 0.2s;
}

.edge-method {
  stroke: #1d5f57;
}

.edge-field {
  stroke: #3a3163;
}

.edge-call {
  stroke: #0e6f78;
  stroke-dasharray: 5 4;
}

.edge.active {
  stroke: #00ffcc;
  stroke-width: 2.2;
}

/* ---- 节点 ---- */
.node {
  cursor: pointer;
}

.node .node-label {
  fill: #e6fffb;
  font-size: 13px;
  font-family: Consolas, 'Cascadia Mono', monospace;
  pointer-events: none;
}

.node .node-type {
  fill: #4f8f88;
  font-size: 9.5px;
  letter-spacing: 0.8px;
  font-family: Consolas, monospace;
  pointer-events: none;
}

.node.dim {
  opacity: 0.34;
}

.node.selected rect {
  filter: drop-shadow(0 0 6px rgba(0, 255, 204, 0.55));
}

.dot-pending {
  fill: #ffb74d;
}

/* ---- 详情侧栏 ---- */
.inspector {
  width: 268px;
  flex-shrink: 0;
  border-left: 1px solid rgba(0, 229, 255, 0.16);
  background: rgba(0, 0, 0, 0.28);
  padding: 14px 16px;
  overflow-y: auto;
  font-size: 12.5px;
}

.ins-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.badge {
  padding: 1px 7px;
  border-radius: 3px;
  font-size: 10px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: #eafffb;
}

.badge-class { background: #00695c; }
.badge-method { background: #01579b; }
.badge-field { background: #4a148c; }

.ins-title {
  font-family: Consolas, monospace;
  font-size: 14px;
  color: #7ffce8;
  word-break: break-all;
}

.ins-row {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
  line-height: 1.55;
}

.ins-key {
  flex-shrink: 0;
  width: 34px;
  color: #4f8f88;
  font-size: 11.5px;
}

.ins-val {
  color: #cdeee9;
  word-break: break-word;
}

.ins-val.muted {
  color: #8a6d3b;
}

.mono {
  font-family: Consolas, monospace;
  font-size: 11.5px;
}

.ins-src {
  margin: 14px 0 0;
  padding: 10px;
  max-height: 300px;
  overflow: auto;
  background: rgba(0, 0, 0, 0.45);
  border: 1px solid rgba(0, 229, 255, 0.12);
  border-radius: 5px;
  font-family: Consolas, monospace;
  font-size: 11px;
  line-height: 1.5;
  color: #9fd8d2;
  white-space: pre-wrap;
  word-break: break-word;
}

.ins-hint {
  margin: 0;
  color: #4f8f88;
  font-size: 12px;
  text-align: center;
  padding-top: 40px;
}

/* ---- 空态 / 错误态 ---- */
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
  font-size: 17px;
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
  font-size: 16px;
}

.error-msg {
  margin: 0;
  font-size: 12.5px;
  color: #c98b86;
  max-width: 520px;
  line-height: 1.6;
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

/* ---- 底部图例 ---- */
.legend {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 7px 20px;
  border-top: 1px solid rgba(0, 229, 255, 0.16);
  font-size: 11.5px;
  color: #6fa8a2;
  background: rgba(0, 0, 0, 0.32);
}

.dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-right: 6px;
  vertical-align: middle;
}

.dot.class { background: #00695c; }
.dot.method { background: #01579b; }
.dot.field { background: #4a148c; }

.line {
  display: inline-block;
  width: 16px;
  height: 0;
  border-top: 1.5px dashed #0e6f78;
  margin-right: 6px;
  vertical-align: middle;
}

.count {
  margin-left: auto;
  color: #00e5ff;
}
</style>
