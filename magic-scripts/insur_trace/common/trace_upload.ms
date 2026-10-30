/*
 * ============================================================================
 *  函数(公用)：common/trace_upload —— 统一医保进销存上传引擎
 *  对应 PB：w_insur_send.pf_send_detail_new + n_insur_upload_base.of_insur_goods_send
 *
 *  每个交易一个接口脚本（upload_3501 … upload_3506），脚本里只声明：
 *    取数 SQL、上传字段、回写逻辑；检索/过滤/逐行上传/日志/汇总都在这里。
 *
 *  引用：import '@/insur/common/trace_upload' as engineFn;  var engine = engineFn();
 * ============================================================================
 */
import '@/insur/common/insur'    as insurFn;
import '@/insur/common/tradelog' as tradelogFn;
import 'java.util.Date' as Date;
import 'java.lang.System' as System;
import 'java.lang.Integer' as Integer;
import log;
import db;

var insur = insurFn();
var tradelog = tradelogFn();

/*
 * 解析时间范围参数。接口调用取 body，定时任务里 body 为空时使用默认值。
 *   startDate / endDate : 'yyyy-MM-dd HH:mm:ss'
 *   days                : 未传 startDate 时向前取 N 天（默认 0 = 仅今天）
 */
var dateRange = (params) => {
    var p = params == null ? {} : params;
    var days = p.days != null ? Integer.parseInt(p.days + '') : 0;
    var startDate = p.startDate;
    var endDate = p.endDate;
    if (startDate == null || startDate == '') {
        var d = new Date(System.currentTimeMillis() - days * 86400000L);
        startDate = date_format(d, 'yyyy-MM-dd') + ' 00:00:00';
    }
    if (endDate == null || endDate == '') {
        endDate = date_format(now(), 'yyyy-MM-dd') + ' 23:59:59';
    }
    return { startDate: startDate, endDate: endDate, resend: (p.resend == true || p.resend == 'true') };
}

/* 日期值转字符串，其余原样（Jackson 默认会把日期序列化成毫秒数） */
var normalize = (row) => {
    var out = {};
    for (key, val in row) {
        if (val instanceof Date) {
            out[key] = date_format(val, 'yyyy-MM-dd HH:mm:ss');
        } else {
            out[key] = val;
        }
    }
    return out;
}

/*
 * 3501/3503/3504 共用回写：insur_goods_upload_record 有则改、无则插
 * 对应 PB of_insur_goods_send 的 case else 分支
 */
var recordUpload = (infno, row, status, tradeNo, res) => {
    var exists = db.selectInt("""
        select count(1) from insur.insur_goods_upload_record a
         where a.trade_type = #{infno} and a.pk_key = #{row.pk_value} and a.status <> '9'
    """);
    if (exists != null && exists > 0) {
        db.update("""
            update insur.insur_goods_upload_record a
               set a.status = #{status},
                   a.error_times = decode(#{status}, '0', a.error_times + 1, a.error_times),
                   a.modify_time = sysdate,
                   a.insur_trade_log_no = #{tradeNo}
             where a.trade_type = #{infno} and a.pk_key = #{row.pk_value} and a.status <> '9'
        """);
    } else {
        db.update("""
            insert into insur.insur_goods_upload_record
                (trade_type, pk_key, goods_code, goods_name, status, create_time,
                 insur_trade_log_no, in_parm, result_parm)
            values
                (#{infno}, #{row.pk_value}, #{row.fixmedins_hilist_id}, #{row.fixmedins_hilist_name}, #{status}, sysdate,
                 #{tradeNo}, #{res.request}, #{res.response})
        """);
    }
}

/*
 * 执行一个交易的上传
 *   spec.funcid     交易字典编码，如 'insur_goods_selinfo_in'（→ trade_data=3505）
 *   spec.node       input 下数据节点名：invinfo / purcinfo / selinfo
 *   spec.wrapTrace  是否追加 drugtracinfo 节点（3501/3502/3505/3506）
 *   spec.query      (range) => List<Map>   取数 SQL，需返回 send_flag 列；其余列默认都进报文
 *   spec.keys       仅用于回写、不进报文的列名列表，如 ['pk_value'] / ['apply_no','apply_sub_no']
 *   spec.writeback  (row, status, tradeNo, res, infno) => {}  回写来源表
 *   spec.params     body（可为 null）
 */
var run = (spec) => {
    var range = dateRange(spec.params);
    var cfg = insur.loadConfig();
    var func = insur.getFunc(spec.funcid);
    var infno = func.trade_data;
    var operator = cfg.insur_gdstyyb_userid != null ? cfg.insur_gdstyyb_userid : 'magic-api';
    var skip = ['send_flag', 'result_text'];
    if (spec.keys != null) { skip.addAll(spec.keys); }

    var rows = spec.query(range);
    if (rows == null) { rows = []; }
    var pending = range.resend ? rows : rows.filter(r => r.send_flag != '1');

    var okCnt = 0;
    var failCnt = 0;
    var details = [];

    for (r in pending) {
        // 组单行报文数据（SQL 列去掉 send_flag 和回写键）
        var one = {};
        for (key, val in r) {
            if (!skip.contains(key)) { one[key] = val; }
        }
        one = normalize(one);

        var res;
        var tradeNo = null;
        try {
            tradeNo = tradelog.createLog(spec.funcid, func.trade_name, null, operator, 'GDSTYYB');
            res = insur.invoke(cfg, func, spec.node, one, spec.wrapTrace);
        } catch (e) {
            res = { success: false, errmsg: '调用异常: ' + e.getMessage() };
        }
        tradelog.completeLog(tradeNo, res.success ? res.response : res.errmsg, res.success ? '2' : '9');

        var status = res.success ? '1' : '0';
        try {
            spec.writeback(r, status, tradeNo, res, infno);
        } catch (e) {
            log.error('交易{}回写失败: {}', infno, e.getMessage());
            res.errmsg = (res.errmsg != null ? res.errmsg + '；' : '') + '回写失败: ' + e.getMessage();
        }

        if (res.success) { okCnt = okCnt + 1; } else { failCnt = failCnt + 1; }
        details.add({ tradeNo: tradeNo, key: r.pk_value != null ? r.pk_value : r.drug_trac_codg, success: res.success, errmsg: res.errmsg });
    }

    var summary = {
        infno    : infno,
        funcname : func.trade_name,
        startDate: range.startDate,
        endDate  : range.endDate,
        total    : rows.size(),
        pending  : pending.size(),
        success  : okCnt,
        fail     : failCnt,
        rows     : details
    };
    log.info('交易{} 完成: 检索{} 待传{} 成功{} 失败{}', infno, rows.size(), pending.size(), okCnt, failCnt);
    return summary;
}

return {
    dateRange    : dateRange,
    normalize    : normalize,
    recordUpload : recordUpload,
    run          : run
};
