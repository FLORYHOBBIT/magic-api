import test from 'node:test'
import assert from 'node:assert/strict'
import { createCronDescriber, translateCron } from '../src/service/cron-description.js'

test('describes daily, interval, weekdays and last-day schedules in Chinese', () => {
    assert.equal(translateCron('0 0 1 * * ?'), '在01:00, 每天')
    assert.match(translateCron('0 0/30 * * * ?'), /30 分钟/)
    assert.match(translateCron('0 0 9 * * MON-FRI'), /09:00.*星期一至星期五/)
    assert.match(translateCron('0 0 0 L * ?'), /每月的最后一天/)
    assert.match(translateCron('0 0 0 * * 7'), /星期日/)
    assert.match(translateCron('0 0 0 ? * 5#2'), /第二个 星期五/)
})

test('day-of-month and day-of-week are described as an intersection', () => {
    assert.equal(translateCron('0 0 9 11-17 * FRI'), '在09:00, 限每月的  11 号 至  17 号 之间, 仅星期五')
})

test('Spring-rejected expressions produce no description even if a translator accepts them', async () => {
    let translations = 0
    const describe = createCronDescriber(async () => null, () => { translations++; return 'wrong' })
    for (const expression of ['', null, 'bad cron', '0 1 * * *', '0 0 1 * * ? 2026', '60 0 1 * * ?']) {
        assert.equal(await describe(expression), '')
    }
    assert.equal(translations, 0)
})

test('shares validation across tasks with the same cron and expands server-normalized macros', async () => {
    let calls = 0
    const describe = createCronDescriber(async cron => { calls++; return cron === '@daily' ? '0 0 0 * * *' : cron })
    const values = await Promise.all([describe('@daily'), describe('@daily')])
    assert.equal(values[0], values[1])
    assert.match(values[0], /00:00.*每天/)
    assert.equal(calls, 1)
})

test('validation outages and unsupported translation stay hidden without caching an outage forever', async () => {
    let calls = 0
    const describe = createCronDescriber(async cron => { if (++calls === 1) throw new Error('offline'); return cron })
    assert.equal(await describe('0 0 1 * * ?'), '')
    assert.match(await describe('0 0 1 * * ?'), /01:00/)
    assert.equal(calls, 2)
    const unsupported = createCronDescriber(async cron => cron, () => { throw new Error('cannot translate') })
    assert.equal(await unsupported('0 0 1 * * ?'), '')
})
