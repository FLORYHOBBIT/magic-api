/*
 * 定时任务：商品采购退货
 * 建议 cron：0 30 1 * * ?   （在 magic-api【定时任务】里配置，enabled 开关控制启停）
 * 执行接口：POST /insur/trace/upload_3504
 *
 * 任务执行时没有 HTTP 请求体，这里用 body 变量传参（被调接口会读到）：
 *   days   : 向前补传 N 天（0 = 只传今天）
 *   resend : 是否重传已上传数据
 */
import '@post:/insur/trace/upload_3504' as upload;

var body = { days: 1, resend: false };
return upload();