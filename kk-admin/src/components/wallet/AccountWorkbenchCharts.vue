<script setup lang="ts">
import { computed } from 'vue'

const emit = defineEmits<{ 'open-freeze': [] }>()

/** 先全部用 mock 填满图表，后续接接口再替换 */
const MOCK_COMPOSE = [
  { key: 'SETTLE', label: '项目分成', amount: 28600, color: '#0d9488' },
  { key: 'SALARY', label: '工资', amount: 18400, color: '#2563eb' },
  { key: 'REIMBURSE', label: '报销到账', amount: 6200, color: '#d97706' },
  { key: 'OTHER', label: '其他来源', amount: 3800, color: '#64748b' },
]

const MOCK_ASSET = {
  total: 48600,
  available: 39200,
  frozen: 9400,
}

const MOCK_FLOW = {
  income: 26800,
  expense: 12400,
}

const COLORS = {
  available: '#0d9488',
  frozen: '#f59e0b',
  total: '#2563eb',
  income: '#0d9488',
  expense: '#e11d48',
}

function money(v: unknown) {
  const n = Number(v)
  return Number.isFinite(n) ? Math.max(0, n) : 0
}

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
    const outer = `M ${cx} ${cy - r} A ${r} ${r} 0 1 1 ${cx} ${cy + r} A ${r} ${r} 0 1 1 ${cx} ${cy - r} Z`
    const inner = `M ${cx} ${cy - innerR} A ${innerR} ${innerR} 0 1 0 ${cx} ${cy + innerR} A ${innerR} ${innerR} 0 1 0 ${cx} ${cy - innerR} Z`
    return [{ key: slices[0].key, color: slices[0].color, d: `${outer} ${inner}`, evenodd: true }]
  }
  let angle = 0
  return slices
    .map((slice, idx) => {
      let sweep = (slice.amount / total) * 360
      if (idx === slices.length - 1) sweep = Math.max(0, 360 - angle)
      if (sweep < 0.01) return null
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

const composeTotal = computed(() => MOCK_COMPOSE.reduce((s, x) => s + x.amount, 0))
const composeSlices = computed(() => {
  const total = composeTotal.value || 1
  return MOCK_COMPOSE.map((s) => ({ ...s, pct: (s.amount / total) * 100 }))
})
const composePaths = computed(() => donutPaths(MOCK_COMPOSE, 70, 70, 58, 38))

const assetTotal = computed(() => MOCK_ASSET.total)
const assetSlices = computed(() => [
  { key: 'available', label: '可提现金额', amount: MOCK_ASSET.available, color: COLORS.available },
  { key: 'frozen', label: '冻结金额', amount: MOCK_ASSET.frozen, color: COLORS.frozen },
])
const assetPaths = computed(() => donutPaths(assetSlices.value, 70, 70, 58, 38))

const income = computed(() => MOCK_FLOW.income)
const expense = computed(() => MOCK_FLOW.expense)
const flowNet = computed(() => income.value - expense.value)
const flowMax = computed(() => Math.max(income.value, expense.value, 1))
const incomeBarW = computed(() => `${(income.value / flowMax.value) * 100}%`)
const expenseBarW = computed(() => `${(expense.value / flowMax.value) * 100}%`)
</script>

<template>
  <div class="aw-charts">
    <article class="aw-card">
      <header class="aw-card__head">
        <h4>账户组成</h4>
      </header>
      <div class="aw-card__body">
        <div class="aw-donut">
          <svg viewBox="0 0 140 140" role="img" aria-label="账户组成">
            <circle cx="70" cy="70" r="58" class="aw-donut__track" />
            <path
              v-for="p in composePaths"
              :key="p.key"
              :d="p.d"
              :fill="p.color"
              :fill-rule="p.evenodd ? 'evenodd' : 'nonzero'"
            />
          </svg>
          <div class="aw-donut__center">
            <em>合计</em>
            <strong>{{ fmt(composeTotal) }}</strong>
          </div>
        </div>
        <ul class="aw-legend">
          <li v-for="s in composeSlices" :key="s.key">
            <i :style="{ background: s.color }" />
            <span>{{ s.label }}</span>
            <em>{{ s.pct.toFixed(0) }}%</em>
            <b>¥{{ fmt(s.amount) }}</b>
          </li>
        </ul>
      </div>
    </article>

    <article class="aw-card">
      <header class="aw-card__head">
        <h4>资产配额</h4>
      </header>
      <div class="aw-card__body">
        <div class="aw-donut">
          <svg viewBox="0 0 140 140" role="img" aria-label="资产配额">
            <circle cx="70" cy="70" r="58" class="aw-donut__track" />
            <path
              v-for="p in assetPaths"
              :key="p.key"
              :d="p.d"
              :fill="p.color"
              :fill-rule="p.evenodd ? 'evenodd' : 'nonzero'"
            />
          </svg>
          <div class="aw-donut__center">
            <em>总资产</em>
            <strong>{{ fmt(assetTotal) }}</strong>
          </div>
        </div>
        <ul class="aw-legend">
          <li>
            <i :style="{ background: COLORS.total }" />
            <span>个人总资产</span>
            <em />
            <b>¥{{ fmt(assetTotal) }}</b>
          </li>
          <li
            v-for="s in assetSlices"
            :key="s.key"
            :class="{ 'is-link': s.key === 'frozen' && s.amount > 0 }"
            @click="s.key === 'frozen' && s.amount > 0 && emit('open-freeze')"
          >
            <i :style="{ background: s.color }" />
            <span>{{ s.label }}</span>
            <em />
            <b>¥{{ fmt(s.amount) }}</b>
          </li>
        </ul>
      </div>
    </article>

    <article class="aw-card">
      <header class="aw-card__head">
        <h4>收支明细</h4>
      </header>
      <div class="aw-card__body aw-card__body--flow">
        <div class="aw-flow-net">
          <em>净流入</em>
          <strong :class="flowNet >= 0 ? 'is-in' : 'is-out'">¥{{ fmt(Math.abs(flowNet)) }}</strong>
        </div>
        <div class="aw-flow-bars" role="img" aria-label="入账出账对比">
          <div class="aw-flow-row">
            <div class="aw-flow-row__meta">
              <span><i style="background: #0d9488" />入账</span>
              <b class="is-in">¥{{ fmt(income) }}</b>
            </div>
            <div class="aw-flow-row__track">
              <i class="is-in" :style="{ width: incomeBarW }" />
            </div>
          </div>
          <div class="aw-flow-row">
            <div class="aw-flow-row__meta">
              <span><i style="background: #e11d48" />出账</span>
              <b class="is-out">¥{{ fmt(expense) }}</b>
            </div>
            <div class="aw-flow-row__track">
              <i class="is-out" :style="{ width: expenseBarW }" />
            </div>
          </div>
        </div>
      </div>
    </article>
  </div>
</template>

<style scoped>
.aw-charts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  min-width: 0;
}

.aw-card {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 16px 14px;
  border-radius: var(--kk-radius-sm, 14px);
  border: 1px solid rgba(255, 255, 255, 0.58);
  background: rgba(255, 255, 255, 0.28);
  box-shadow: none;
}

.aw-card__head {
  margin-bottom: 12px;
}

.aw-card__head h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--kk-text);
}

.is-in { color: #0d9488; }
.is-out { color: #e11d48; }

.aw-card__body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.aw-donut {
  position: relative;
  width: 168px;
  height: 168px;
  flex: none;
}

.aw-donut svg {
  width: 100%;
  height: 100%;
  display: block;
}

.aw-donut__track {
  fill: none;
  stroke: rgba(24, 24, 27, 0.06);
  stroke-width: 18;
}

.aw-donut__center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  pointer-events: none;
}

.aw-donut__center em {
  font-style: normal;
  font-size: 11px;
  color: var(--kk-text-muted);
}

.aw-donut__center strong {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--kk-text);
  line-height: 1.15;
}

.aw-card__body--flow {
  align-items: stretch;
  justify-content: center;
  gap: 22px;
  padding: 8px 4px 4px;
}

.aw-flow-net {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.aw-flow-net em {
  font-style: normal;
  font-size: 12px;
  color: var(--kk-text-muted);
}

.aw-flow-net strong {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.aw-flow-bars {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
}

.aw-flow-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.aw-flow-row__meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.aw-flow-row__meta span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--kk-text-secondary);
}

.aw-flow-row__meta span i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.aw-flow-row__meta b {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.aw-flow-row__track {
  height: 10px;
  border-radius: 999px;
  background: rgba(24, 24, 27, 0.06);
  overflow: hidden;
}

.aw-flow-row__track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  min-width: 8px;
  transition: width 0.25s ease;
}

.aw-flow-row__track i.is-in { background: #0d9488; }
.aw-flow-row__track i.is-out { background: #e11d48; }

.aw-legend {
  list-style: none;
  margin: auto 0 0;
  padding: 0 4px;
  width: 100%;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 72px;
  row-gap: 10px;
  align-items: end;
}

.aw-legend li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.3;
  color: var(--kk-text-secondary);
}

.aw-legend li.is-link {
  cursor: pointer;
  border-radius: 6px;
  margin: 0 -4px;
  padding: 2px 4px;
}

.aw-legend li.is-link:hover {
  background: rgba(24, 24, 27, 0.04);
  color: var(--kk-text);
}

.aw-legend i {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
}

.aw-legend span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aw-legend em {
  display: none;
}

.aw-legend b {
  flex: none;
  margin-left: auto;
  padding-left: 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--kk-text);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .aw-charts {
    grid-template-columns: 1fr;
  }
  .aw-card__body:not(.aw-card__body--flow) {
    flex-direction: row;
    align-items: center;
  }
  .aw-legend {
    flex: 1;
  }
}

@media (prefers-reduced-transparency: reduce) {
  .aw-card {
    background: #fff;
    border-color: var(--kk-card-border, #dedee0);
  }
}
</style>
