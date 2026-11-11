import { ref, watch } from 'vue'
import { describeCron } from './cron-description.js'

export function useCronDescription(getCron) {
    const description = ref('')
    watch(getCron, (cron, previous, onCleanup) => {
        description.value = ''
        let active = true
        const timer = setTimeout(async () => {
            const text = await describeCron(cron)
            if (active) description.value = text
        }, 200)
        onCleanup(() => { active = false; clearTimeout(timer) })
    }, { immediate: true })
    return description
}
