<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  companyTaskShareApi,
  type PublicCompanyTaskBoard,
  type PublicCompanyTaskItem,
} from '@/api/companyTaskShare'

const route = useRoute()
const loading = ref(false)
const failed = ref(false)
const board = ref<PublicCompanyTaskBoard | null>(null)
const collapsedKeys = ref<Set<string>>(new Set())
let requestSeq = 0

const priorityMap: Record<number, string> = { 1: '高', 2: '中', 3: '低' }
const statusType: Record<number, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  0: 'info',
  1: 'warning',
  2: 'success',
  3: 'info',
}

const sections = computed(() => {
  const people = board.value?.people || []
  const tasks = board.value?.tasks || []
  return people.map((person) => ({
    person,
    tasks: tasks.filter((task) => task.relatedUserIds?.some((id) => Number(id) === Number(person.userId))),
  }))
})

watch(
  () => String(route.params.token || ''),
  () => {
    void load()
  },
  { immediate: true },
)

async function load() {
  const seq = ++requestSeq
  const token = String(route.params.token || '')
  if (!token) return
  loading.value = true
  failed.value = false
  try {
    const next = await companyTaskShareApi.board(token)
    if (seq !== requestSeq) return
    board.value = next
    collapsedKeys.value = new Set()
  } catch (error) {
    if (seq !== requestSeq) return
    const message = error instanceof Error ? error.message : ''
    if (!board.value || message.includes('链接无效')) {
      board.value = null
      failed.value = true
    }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function cardKey(personId: number, taskId: number) {
  return `${personId}-${taskId}`
}

function isOpen(personId: number, taskId: number) {
  return !collapsedKeys.value.has(cardKey(personId, taskId))
}

function toggle(personId: number, taskId: number) {
  const key = cardKey(personId, taskId)
  const next = new Set(collapsedKeys.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedKeys.value = next
}

function period(task: PublicCompanyTaskItem) {
  return `${task.startDate || '—'} ~ ${task.dueDate || '—'}`
}
</script>

<template>
  <div class="board">
    <header class="glass-panel board-head">
      <div>
        <p class="kicker">今日工作</p>
        <h1>{{ board?.companyName || '工作外链' }}</h1>
      </div>
      <p v-if="board" class="range-text">{{ board.from }} 至 {{ board.to }}</p>
    </header>

    <div v-if="failed" class="glass-panel empty-panel">链接无效或已作废</div>

    <div v-else v-loading="loading" class="board-body">
      <p v-if="!loading && !sections.length" class="glass-panel empty-panel">还没有指定人员</p>
      <section v-for="section in sections" :key="section.person.userId" class="person-block">
        <div class="person-head">
          <strong>{{ section.person.name }}</strong>
          <span>{{ section.tasks.length }} 条任务</span>
          <span v-if="section.person.overdueCount">逾期 {{ section.person.overdueCount }}</span>
        </div>
        <p v-if="!section.tasks.length" class="glass-panel empty-panel">这个时间范围内没有任务</p>
        <article
          v-for="task in section.tasks"
          :key="`${section.person.userId}-${task.id}`"
          class="glass-panel task-card"
          @click="toggle(section.person.userId, task.id)"
        >
          <div class="task-top">
            <h2>{{ task.title || '未命名任务' }}</h2>
            <div class="tags">
              <el-tag :type="statusType[task.status ?? 0]" size="small">{{ task.statusLabel || '—' }}</el-tag>
              <el-tag v-if="task.priority" size="small" :type="task.priority === 1 ? 'danger' : task.priority === 2 ? 'warning' : 'info'">
                {{ priorityMap[task.priority] }}
              </el-tag>
              <el-tag v-if="task.overdue" type="danger" size="small">逾期</el-tag>
            </div>
          </div>
          <div class="meta">
            <span>{{ task.projectName || '未挂项目' }}</span>
            <span>{{ period(task) }}</span>
            <span>{{ task.participantNames?.length ? task.participantNames.join('、') : '无参与人' }}</span>
          </div>
          <el-progress :percentage="task.progress ?? 0" :stroke-width="8" :status="task.status === 2 ? 'success' : task.overdue ? 'exception' : undefined" />
          <div v-if="isOpen(section.person.userId, task.id)" class="detail">
            <p class="content">{{ task.content?.trim() || '暂无说明' }}</p>
            <h3>最近评论</h3>
            <p v-if="!task.comments?.length" class="muted">暂无评论</p>
            <ul v-else class="comments">
              <li v-for="(comment, index) in task.comments" :key="index">
                <div class="comment-head">
                  <span>{{ comment.authorName || '—' }}</span>
                  <span>{{ comment.createTime || '' }}</span>
                </div>
                <p>{{ comment.content }}</p>
              </li>
            </ul>
          </div>
        </article>
      </section>
    </div>
  </div>
</template>

<style scoped>
.board {
  min-height: 100%;
  padding: 28px 24px 40px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.board-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
}

.kicker {
  margin: 0 0 6px;
  color: var(--kk-text-secondary);
  font-size: 13px;
}

.board-head h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.range-text {
  margin: 0;
  color: var(--kk-text-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.board-body {
  display: flex;
  flex-direction: column;
  gap: 22px;
  min-height: 320px;
}

.person-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.person-head {
  display: flex;
  gap: 12px;
  align-items: baseline;
  color: var(--kk-text-secondary);
  padding: 0 4px;
}

.person-head strong {
  color: var(--kk-text);
  font-size: 18px;
}

.people {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: calc(100vh - 180px);
}

.people-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}

.person {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  border-radius: 12px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
}

.person:hover,
.person.is-active {
  background: rgba(255, 255, 255, 0.55);
}

.person-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.counts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.count {
  min-width: 22px;
  text-align: center;
  color: var(--kk-text-secondary);
  font-variant-numeric: tabular-nums;
}

.overdue-count {
  color: var(--kk-danger);
  font-size: 12px;
}

.people-empty,
.muted,
.empty-panel {
  color: var(--kk-text-secondary);
}

.task-column {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.task-summary {
  display: flex;
  gap: 12px;
  align-items: baseline;
  color: var(--kk-text-secondary);
  padding: 0 4px;
}

.task-summary strong {
  color: var(--kk-text);
  font-size: 16px;
}

.task-card {
  cursor: pointer;
}

.task-card h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 620;
}

.task-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin: 10px 0 12px;
  color: var(--kk-text-secondary);
  font-size: 13px;
}

.detail {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--kk-hairline);
}

.content {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.detail h3 {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 620;
}

.comments {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comments li {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.38);
}

.comment-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
  color: var(--kk-text-secondary);
  font-size: 12px;
}

.comments p {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.55;
}

@media (max-width: 860px) {
  .board {
    padding: 16px 12px 28px;
  }

  .board-head {
    flex-direction: column;
    align-items: stretch;
  }

  .range-text {
    white-space: normal;
  }

  .board-body {
    grid-template-columns: 1fr;
  }

  .people {
    position: static;
    max-height: none;
  }

  .people-list {
    flex-direction: row;
    overflow-x: auto;
  }

  .person {
    width: auto;
    flex-shrink: 0;
  }
}
</style>
