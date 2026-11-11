<template>
    <div class="task-summary">
        <span class="task-enabled-state" :class="item.enabled === true ? 'enabled' : 'disabled'"
              :title="item.enabled === true ? '定时任务配置已启用' : '定时任务配置未启用'">
            <i></i>{{ item.enabled === true ? '已启用' : '未启用' }}
        </span>
        <span v-if="description" class="task-schedule-description" :title="description">{{ description }}</span>
    </div>
</template>
<script setup>
import { useCronDescription } from '../service/use-cron-description.js'
const props = defineProps({ item: { type: Object, required: true } })
const description = useCronDescription(() => props.item.cron)
</script>
<style scoped>
.task-summary{display:flex;align-items:center;gap:9px;line-height:18px;padding:0 8px 5px 34px;font-size:11px}
.task-enabled-state{display:inline-flex;align-items:center;gap:4px;flex-shrink:0}
.task-enabled-state.enabled{color:#23865a}
.task-enabled-state.disabled{color:var(--resource-span-color,#85909e)}
.task-enabled-state i{width:5px;height:5px;border-radius:50%;background:currentColor}
.task-schedule-description{color:var(--resource-span-color,#85909e);max-width:390px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
