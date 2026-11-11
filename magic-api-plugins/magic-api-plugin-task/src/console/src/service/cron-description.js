import cronstrue from 'cronstrue'
import 'cronstrue/locales/zh_CN.js'

export function translateCron(expression) {
    return cronstrue.toString(expression, {
        locale: 'zh_CN', use24HourTimeFormat: true,
        dayOfWeekStartIndexZero: true, logicalAndDayFields: true,
        throwExceptionOnParseError: true, verbose: true
    })
}

export function createCronDescriber(validate, translate = translateCron) {
    const cache = new Map()
    return expression => {
        const cron = typeof expression === 'string' ? expression.trim() : ''
        if (!cron) return Promise.resolve('')
        if (cache.has(cron)) return cache.get(cron)
        const pending = Promise.resolve().then(() => validate(cron)).then(normalized => {
            if (typeof normalized !== 'string' || !normalized) return ''
            try { return translate(normalized) } catch (_) { return '' }
        }).catch(() => { cache.delete(cron); return '' })
        if (cache.size >= 256) cache.delete(cache.keys().next().value)
        cache.set(cron, pending)
        return pending
    }
}

let describe = () => Promise.resolve('')
export function setCronRequest(request) {
    describe = createCronDescriber(expression => new Promise((resolve, reject) => {
        request.sendGet('/task/cron/validate', { expression }).success(resolve)
            .exception(() => reject(new Error('cron validation failed')))
            .error(() => reject(new Error('cron validation unavailable')))
    }))
}
export const describeCron = expression => describe(expression)
