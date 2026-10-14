<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  title?: string
  xLabel?: string
  yLabel?: string
  series: { name: string; points: { x: string; y: number }[] }[]
}>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  const categories = Array.from(
    new Set((props.series || []).flatMap((s) => (s.points || []).map((p) => p.x))),
  )
  chart.setOption({
    color: ['#2563eb', '#0d9488', '#d97706', '#e11d48', '#7c3aed'],
    title: props.title
      ? { text: props.title, left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600, color: '#3f3f46' } }
      : undefined,
    tooltip: { trigger: 'axis' },
    legend: { top: props.title ? 24 : 0, right: 0, textStyle: { fontSize: 11 } },
    grid: { left: 36, right: 12, top: props.title ? 52 : 28, bottom: props.xLabel ? 36 : 24 },
    xAxis: {
      type: 'category',
      data: categories,
      name: props.xLabel,
      axisLabel: { fontSize: 11, color: '#71717a' },
    },
    yAxis: {
      type: 'value',
      name: props.yLabel,
      splitLine: { lineStyle: { color: 'rgba(24,24,27,0.06)' } },
      axisLabel: { fontSize: 11, color: '#71717a' },
    },
    series: (props.series || []).map((s) => ({
      name: s.name,
      type: 'line',
      smooth: true,
      showSymbol: categories.length <= 12,
      data: categories.map((x) => s.points?.find((p) => p.x === x)?.y ?? 0),
    })),
  })
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})

watch(
  () => [props.series, props.title, props.xLabel, props.yLabel],
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
