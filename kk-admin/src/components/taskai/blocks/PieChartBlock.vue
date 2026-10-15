<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([PieChart, LegendComponent, TooltipComponent, TitleComponent, CanvasRenderer])

const props = defineProps<{
  title?: string
  slices: { name: string; value: number }[]
}>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption({
    color: ['#2563eb', '#0d9488', '#d97706', '#db2777', '#7c3aed', '#0891b2', '#ea580c', '#16a34a'],
    title: props.title
      ? { text: props.title, left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600, color: '#3f3f46' } }
      : undefined,
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: {
      bottom: 0,
      left: 'center',
      textStyle: { fontSize: 11, color: '#71717a' },
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', props.title ? '52%' : '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: 'rgba(255,255,255,0.7)', borderWidth: 2 },
        label: { fontSize: 11, color: '#3f3f46', formatter: '{b}\n{d}%' },
        data: (props.slices || []).map((s) => ({ name: s.name, value: s.value })),
      },
    ],
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
  () => [props.slices, props.title],
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
  height: 240px;
}
</style>
