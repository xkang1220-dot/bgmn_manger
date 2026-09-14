<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  period: 'daily' | 'monthly'
  balance?: number
  frozen?: number
  available?: number
  trend?: Array<{ label?: string; amount?: number }>
  sourceBreakdown?: Array<{ bizType?: string; amount?: number }>
  bizLabel: (v?: string) => string
}>()

const emit = defineEmits<{
  'update:period': [value: 'daily' | 'monthly']
}>()

const PIE_COLORS = ['#0d9488', '#2563eb', '#d97706', '#dc2626', '#64748b', '#0891b2', '#ca8a04']

const W = 320
const H = 120
const PAD = { t: 12, r: 8, b: 22, l: 8 }

function money(v: unknown) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const trendPoints = computed(() => {
  const raw = props.trend || []
  if (!raw.length) return []
  const amounts = raw.map((p) => money(p.amount))
  const min = Math.min(0, ...amounts)
  const max = Math.max(0, ...amounts)
  const span = max - min || 1
  const innerW = W - PAD.l - PAD.r
  const innerH = H - PAD.t - PAD.b
  const n = raw.length
  return raw.map((p, i) => {
    const amt = money(p.amount)
    const x = PAD.l + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW)
    const y = PAD.t + innerH - ((amt - min) / span) * innerH
    return { x, y, amount: amt, label: String(p.label || '') }
  })
})

const zeroY = computed(() => {
  const amounts = (props.trend || []).map((p) => money(p.amount))
  if (!amounts.length) return H - PAD.b
  const min = Math.min(0, ...amounts)
  const max = Math.max(0, ...amounts)
  const span = max - min || 1
  const innerH = H - PAD.t - PAD.b
  return PAD.t + innerH - ((0 - min) / span) * innerH
})

const waveLine = computed(() => {
  const pts = trendPoints.value
  if (pts.length < 2) return ''
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
})

const waveArea = computed(() => {
  const pts = trendPoints.value
  if (pts.length < 2) return ''
  const z = zeroY.value
  const line = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  const last = pts[pts.length - 1]
  const first = pts[0]
  return `${line} L ${last.x.toFixed(1)} ${z.toFixed(1)} L ${first.x.toFixed(1)} ${z.toFixed(1)} Z`
})

const axisLabels = computed(() => {
  const pts = trendPoints.value
  if (!pts.length) return []
  const pick = new Set([0, Math.floor((pts.length - 1) / 2), pts.length - 1])
  return [...pick].map((i) => ({
    x: pts[i].x,
    text: formatAxisLabel(pts[i].label),
  }))
})

function formatAxisLabel(label: string) {
  if (!label) return ''
  if (props.period === 'monthly') {
    // yyyy-MM → MM月
    const m = label.slice(5, 7)
    return m ? `${Number(m)}月` : label
  }
  // yyyy-MM-dd → MM-dd
  return label.length >= 10 ? label.slice(5) : label
}

const accountSlices = computed(() => {
  let available = Math.max(0, money(props.available))
  let frozen = Math.max(0, money(props.frozen))
  const balance = Math.max(0, money(props.balance))
  // 接口偶发缺 available/frozen 时，用余额兜底，避免指标有余额、环图空态
  if (available + frozen <= 0 && balance > 0) {
    available = balance
    frozen = 0
  }
  const total = available + frozen
  if (total <= 0) return []
  const slices: Array<{ key: string; label: string; amount: number; color: string; pct: number }> = []
  if (available > 0) {
    slices.push({
      key: 'available',
      label: '可用',
      amount: available,
      color: '#0d9488',
      pct: (available / total) * 100,
    })
  }
  if (frozen > 0) {
    slices.push({
      key: 'frozen',
      label: '冻结',
      amount: frozen,
      color: '#94a3b8',
      pct: (frozen / total) * 100,
    })
  }
  return slices
})

const sourceSlices = computed(() => {
  const rows = (props.sourceBreakdown || [])
    .map((s) => ({
      key: String(s.bizType || 'OTHER'),
      label: props.bizLabel(s.bizType),
      amount: Math.max(0, money(s.amount)),
    }))
    .filter((s) => s.amount > 0)
  const total = rows.reduce((sum, s) => sum + s.amount, 0)
  if (total <= 0) return []
  return rows.map((s, i) => ({
    ...s,
    color: PIE_COLORS[i % PIE_COLORS.length],
    pct: (s.amount / total) * 100,
  }))
})

const balanceTotal = computed(() => {
  const bal = money(props.balance)
  if (bal > 0 || props.balance != null) return Math.max(0, bal)
  return Math.max(0, money(props.available) + money(props.frozen))
})

function fmt(n?: number) {
  return money(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function polar(cx: number, cy: number, r: number, angleDeg: number) {
  const a = ((angleDeg - 90) * Math.PI) / 180
  return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) }
}

function donutPaths(
  slices: Array<{ key: string; amount: number; color: string }>,
  cx: number,
  cy: number,
  r: number,
  innerR: number,
) {
  const total = slices.reduce((s, x) => s + x.amount, 0)
  if (total <= 0) return []
  if (slices.length === 1) {
    // full ring: outer clockwise + inner counter-clockwise (evenodd)
    const outer = `M ${cx} ${cy - r} A ${r} ${r} 0 1 1 ${cx} ${cy + r} A ${r} ${r} 0 1 1 ${cx} ${cy - r} Z`
    const inner = `M ${cx} ${cy - innerR} A ${innerR} ${innerR} 0 1 0 ${cx} ${cy + innerR} A ${innerR} ${innerR} 0 1 0 ${cx} ${cy - innerR} Z`
    return [{ key: slices[0].key, color: slices[0].color, d: `${outer} ${inner}`, evenodd: true }]
  }
  let angle = 0
  return slices
    .map((slice, idx) => {
      let sweep = (slice.amount / total) * 360
      if (idx === slices.length - 1) sweep = Math.max(0, 360 - angle)
      if (sweep < 0.01) {
        return null
      }
      // 多扇区时夹紧，避免浮点冒出整圆（整圆只走上方 length===1 分支）
      if (sweep > 359.99) sweep = 359.99
      const start = angle
      const end = angle + sweep
      angle = end
      const large = sweep > 180 ? 1 : 0
      const o1 = polar(cx, cy, r, start)
      const o2 = polar(cx, cy, r, end)
      const i2 = polar(cx, cy, innerR, end)
      const i1 = polar(cx, cy, innerR, start)
      const d = [
        `M ${o1.x.toFixed(3)} ${o1.y.toFixed(3)}`,
        `A ${r} ${r} 0 ${large} 1 ${o2.x.toFixed(3)} ${o2.y.toFixed(3)}`,
        `L ${i2.x.toFixed(3)} ${i2.y.toFixed(3)}`,
        `A ${innerR} ${innerR} 0 ${large} 0 ${i1.x.toFixed(3)} ${i1.y.toFixed(3)}`,
        'Z',
      ].join(' ')
      return { key: slice.key, color: slice.color, d, evenodd: false }
    })
    .filter((p): p is { key: string; color: string; d: string; evenodd: boolean } => !!p)
}

const accountPaths = computed(() => donutPaths(accountSlices.value, 56, 56, 48, 30))
const sourcePaths = computed(() => donutPaths(sourceSlices.value, 56, 56, 48, 28))

function onPeriod(v: string | number | boolean | undefined) {
  if (v === 'daily' || v === 'monthly') emit('update:period', v)
}
</script>

<template>
  <div class="board-charts">
    <div class="board-charts__period">
      <el-radio-group :model-value="period" size="small" @change="onPeriod">
        <el-radio-button value="daily">近30天</el-radio-button>
        <el-radio-button value="monthly">近12月</el-radio-button>
      </el-radio-group>
    </div>

    <div class="board-charts__grid">
      <div class="chart-card chart-card--trend">
        <div class="chart-card__title">资金走势</div>
        <svg class="wave" :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="资金走势波形图">
          <line
            class="wave-zero"
            :x1="PAD.l"
            :x2="W - PAD.r"
            :y1="zeroY"
            :y2="zeroY"
          />
          <path v-if="waveArea" class="wave-area" :d="waveArea" />
          <path v-if="waveLine" class="wave-line" :d="waveLine" fill="none" />
          <g v-for="(p, i) in trendPoints" :key="`${p.label}-${i}`">
            <circle
              v-if="Math.abs(p.amount) > 0.0001"
              class="wave-dot"
              :cx="p.x"
              :cy="p.y"
              r="2.2"
            >
              <title>{{ p.label }}：¥ {{ fmt(p.amount) }}</title>
            </circle>
          </g>
          <text
            v-for="(lab, i) in axisLabels"
            :key="`ax-${i}`"
            class="wave-axis"
            :x="lab.x"
            :y="H - 4"
            text-anchor="middle"
          >
            {{ lab.text }}
          </text>
        </svg>
      </div>

      <div class="chart-card">
        <div class="chart-card__title">账户组成</div>
        <div v-if="accountSlices.length" class="pie-row">
          <svg viewBox="0 0 112 112" class="pie" aria-label="账户组成">
            <path
              v-for="p in accountPaths"
              :key="p.key"
              :d="p.d"
              :fill="p.color"
              :fill-rule="p.evenodd ? 'evenodd' : 'nonzero'"
            />
            <text x="56" y="52" text-anchor="middle" class="pie-center-label">余额</text>
            <text x="56" y="68" text-anchor="middle" class="pie-center-value">¥{{ fmt(balanceTotal) }}</text>
          </svg>
          <ul class="pie-legend">
            <li v-for="s in accountSlices" :key="s.key">
              <i :style="{ background: s.color }" />
              <span>{{ s.label }}</span>
              <b>¥ {{ fmt(s.amount) }}</b>
            </li>
          </ul>
        </div>
        <el-empty v-else description="暂无余额" :image-size="48" />
      </div>

      <div class="chart-card">
        <div class="chart-card__title">入账构成</div>
        <div v-if="sourceSlices.length" class="pie-row">
          <svg viewBox="0 0 112 112" class="pie" aria-label="入账构成">
            <path
              v-for="p in sourcePaths"
              :key="p.key"
              :d="p.d"
              :fill="p.color"
              :fill-rule="p.evenodd ? 'evenodd' : 'nonzero'"
            />
            <text x="56" y="58" text-anchor="middle" class="pie-center-label">入账</text>
          </svg>
          <ul class="pie-legend">
            <li v-for="s in sourceSlices" :key="s.key">
              <i :style="{ background: s.color }" />
              <span>{{ s.label }} {{ s.pct.toFixed(0) }}%</span>
              <b>¥ {{ fmt(s.amount) }}</b>
            </li>
          </ul>
        </div>
        <el-empty v-else description="暂无入账" :image-size="48" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.board-charts {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.board-charts__period {
  display: flex;
  justify-content: flex-end;
}

.board-charts__grid {
  display: grid;
  grid-template-columns: 1.5fr 1fr 1fr;
  gap: 12px;
}

.chart-card {
  min-height: 148px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.05);
  background: rgba(255, 255, 255, 0.28);
}

.chart-card__title {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--kk-text-secondary);
}

.wave {
  width: 100%;
  height: 120px;
  display: block;
}

.wave-zero {
  stroke: rgba(24, 24, 27, 0.12);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.wave-area {
  fill: rgba(13, 148, 136, 0.14);
}

.wave-line {
  stroke: #0d9488;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.wave-dot {
  fill: #0d9488;
}

.wave-axis {
  fill: var(--kk-text-muted);
  font-size: 9px;
}

.pie-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 112px;
}

.pie {
  width: 96px;
  height: 96px;
  flex-shrink: 0;
}

.pie-center-label {
  fill: var(--kk-text-muted);
  font-size: 10px;
}

.pie-center-value {
  fill: var(--kk-text);
  font-size: 9px;
  font-weight: 600;
}

.pie-legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.pie-legend li {
  display: grid;
  grid-template-columns: 8px 1fr auto;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--kk-text-secondary);
}

.pie-legend i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.pie-legend span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pie-legend b {
  font-weight: 600;
  color: var(--kk-text);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .board-charts__grid {
    grid-template-columns: 1fr 1fr;
  }
  .chart-card--trend {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .board-charts__grid {
    grid-template-columns: 1fr;
  }
}
</style>
