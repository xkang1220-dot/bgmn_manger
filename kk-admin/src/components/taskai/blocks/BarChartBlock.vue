<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  title?: string
  categories: string[]
  series: { name: string; values: number[] }[]
}>()

const PALETTE = ['#2563eb', '#0d9488', '#d97706', '#db2777', '#7c3aed', '#0891b2', '#ea580c', '#16a34a']

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  const multiSeries = (props.series || []).length > 1
  chart.setOption({
    color: PALETTE,
    title: props.title
      ? { text: props.title, left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600, color: '#3f3f46' } }
      : undefined,
    tooltip: { trigger: 'axis' },
    legend: multiSeries
      ? { top: props.title ? 24 : 0, right: 0, textStyle: { fontSize: 11 } }
      : { show: false },
    grid: { left: 36, right: 12, top: props.title ? (multiSeries ? 52 : 36) : 28, bottom: 36 },
    xAxis: {
      type: 'category',
      data: props.categories || [],
      axisLabel: { fontSize: 11, color: '#71717a', interval: 0, rotate: (props.categories?.length || 0) > 4 ? 24 : 0 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(24,24,27,0.06)' } },
      axisLabel: { fontSize: 11, color: '#71717a' },
    },
    series: (props.series || []).map((s, seriesIdx) => ({
      name: s.name,
      type: 'bar',
      barMaxWidth: 28,
      // 单系列：每个类目不同色；多系列：按系列取色
      data: (s.values || []).map((v, i) => ({
        value: v,
        itemStyle: {
          color: multiSeries ? PALETTE[seriesIdx % PALETTE.length] : PALETTE[i % PALETTE.length],
          borderRadius: [6, 6, 2, 2],
        },
      })),
    })),
  }, true)
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})

watch(
  () => [props.categories, props.series, props.title],
  () => render(),
  { deep: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="chart-shell">
    <div ref="el" class="chart-el" />
  </div>
</template>

<style scoped>
.chart-shell {
  width: 100%;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.65);
  padding: 8px 4px 4px;
}

.chart-el {
  width: 100%;
  height: 220px;
}
</style>
