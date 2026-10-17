<script setup lang="ts">
import type { ClarifyField, ConfirmTaskItem, UiBlock } from '@/api/taskAi'
import MarkdownBlock from './blocks/MarkdownBlock.vue'
import ClarifyBlock from './blocks/ClarifyBlock.vue'
import ConfirmCardBlock from './blocks/ConfirmCardBlock.vue'
import TaskListBlock from './blocks/TaskListBlock.vue'
import TimelineBlock from './blocks/TimelineBlock.vue'
import LineChartBlock from './blocks/LineChartBlock.vue'
import BarChartBlock from './blocks/BarChartBlock.vue'
import PieChartBlock from './blocks/PieChartBlock.vue'
import StatRowBlock from './blocks/StatRowBlock.vue'
import DoneBlock from './blocks/DoneBlock.vue'
import UnknownBlock from './blocks/UnknownBlock.vue'

defineProps<{
  blocks: UiBlock[]
  interactive?: boolean
  busy?: boolean
}>()

const emit = defineEmits<{
  clarify: [payload: { field: ClarifyField; optionId: string; label: string }]
  confirm: [items: ConfirmTaskItem[]]
  revise: []
  cancelConfirm: []
  openTask: []
}>()

function asRecord(block: UiBlock): Record<string, unknown> {
  return block as unknown as Record<string, unknown>
}
</script>

<template>
  <div class="block-stack">
    <template v-for="(block, idx) in blocks" :key="idx">
      <MarkdownBlock
        v-if="block.type === 'markdown'"
        :content="String((block as any).content || '')"
      />
      <ClarifyBlock
        v-else-if="block.type === 'clarify'"
        :field="(block as any).field"
        :prompt="String((block as any).prompt || '')"
        :options="(block as any).options"
        :disabled="!interactive || busy"
        @select="emit('clarify', $event)"
      />
      <ConfirmCardBlock
        v-else-if="block.type === 'confirm_card'"
        :items="(block as any).items || []"
        :disabled="!interactive || busy"
        @confirm="emit('confirm', $event)"
        @revise="emit('revise')"
        @cancel="emit('cancelConfirm')"
      />
      <TaskListBlock
        v-else-if="block.type === 'task_list'"
        :title="(block as any).title"
        :items="(block as any).items || []"
        :empty-text="(block as any).emptyText"
        @open-task="emit('openTask')"
      />
      <TimelineBlock
        v-else-if="block.type === 'timeline'"
        :title="(block as any).title"
        :events="(block as any).events || []"
        @open-task="emit('openTask')"
      />
      <LineChartBlock
        v-else-if="block.type === 'line_chart'"
        :title="(block as any).title"
        :x-label="(block as any).xLabel"
        :y-label="(block as any).yLabel"
        :series="(block as any).series || []"
      />
      <BarChartBlock
        v-else-if="block.type === 'bar_chart'"
        :title="(block as any).title"
        :categories="(block as any).categories || []"
        :series="(block as any).series || []"
      />
      <PieChartBlock
        v-else-if="block.type === 'pie_chart'"
        :title="(block as any).title"
        :slices="(block as any).slices || []"
      />
      <StatRowBlock
        v-else-if="block.type === 'stat_row'"
        :items="(block as any).items || []"
      />
      <DoneBlock
        v-else-if="block.type === 'done'"
        :task-ids="(block as any).taskIds || []"
        :message="(block as any).message"
      />
      <UnknownBlock v-else :block="asRecord(block)" />
    </template>
  </div>
</template>

<style scoped>
.block-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
