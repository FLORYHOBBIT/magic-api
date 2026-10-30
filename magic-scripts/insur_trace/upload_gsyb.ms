/*
 * 工伤医保 药品追溯码上传（XML）           POST /insur/trace/upload_gsyb
 * 对应 PB：w_insur_send cbx_7 → pf_send_detail_gdsgsyb(dw_insur_bz113505)
 *          → n_insur_upload_gdsgsyb.of_insur_goods_send
 * 交易：api_gdgsyb_tracecode_selinfo（上传）/ api_gdgsyb_tracecode_feeinfo（费别序号）
 * 取数：V_BIZH113505        回写：insur.lay_physic_trace_info（status / aaz213）
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/gsyb'         as gsybFn;
import '@/insur/common/tradelog'     as tradelogFn;
import '@/insur/common/trace_upload' as engineFn;
import db;
var gsyb = gsybFn();
var tradelog = tradelogFn();
var engine = engineFn();

var FUNCID = 'api_gdgsyb_tracecode_selinfo';
var range = engine.dateRange(body);

// 1) 配置 + 交易字典 + session_id（对应 uf_init + pf_get_session_id）
var cfg = gsyb.loadConfig();
var func = gsyb.getFunc(FUNCID);
var sessionId = gsyb.getSessionId(cfg);

// 2) 取数（对应 dw_insur_bz113505）
var rows = db.lower().select("""
    select nvl(status, '0') send_flag,
           akb020, aaz218, aka130, bka070, bka074, bka075, bka076, bka077, bka078, bka079,
           ake005, ake006, bka052, bka053, bka054, bka051, bka055, bka056, bka057, bka058,
           aaz213, bka099, bka098, bka071, settle_mode, apply_no, apply_sub_no
      from v_bizh113505
     where operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
       and operation_date <  to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
""");
var pending = range.resend ? rows : rows.filter(r => r.send_flag != '1');

var okCnt = 0;
var failCnt = 0;
var details = [];
var x = gsyb.xmlEsc;

for (r in pending) {
    // 2.1 费别序号为空先两段式获取（对应 pf_get_feeinfo），并回写 aaz213
    var aaz213 = r.aaz213;
    if (aaz213 == null || aaz213 == '') {
        var feemap = gsyb.getFeeinfo(cfg, sessionId, r.aaz218, r.akb020);
        if (r.bka071 != null && feemap[r.bka071] != null) {
            aaz213 = feemap[r.bka071];
            if (r.bka099 != null && r.bka099 != '') {
                db.update("""
                    update insur.lay_physic_trace_info set aaz213 = #{aaz213}
                     where drug_trac_codg = #{r.bka099}
                """);
            }
        }
    }

    // 2.2 组 XML（对应 of_insur_goods_send 报文拼装）
    var rowXml = '<row>' +
        '<ake005>' + x(r.ake005) + '</ake005>' + '<ake006>' + x(r.ake006) + '</ake006>' +
        '<aaz213>' + x(aaz213)   + '</aaz213>' + '<bka052>' + x(r.bka052) + '</bka052>' +
        '<bka053>' + x(r.bka053) + '</bka053>' + '<bka054>' + x(r.bka054) + '</bka054>' +
        '<bka051>' + x(r.bka051) + '</bka051>' + '<bka055>' + x(r.bka055) + '</bka055>' +
        '<bka056>' + x(r.bka056) + '</bka056>' + '<bka057>' + x(r.bka057) + '</bka057>' +
        '<bka058>' + x(r.bka058) + '</bka058>' + '<bka099>' + x(r.bka099) + '</bka099>' +
        '<bka098>' + x(r.bka098) + '</bka098>' + '</row>';
    var xml = '<program>' +
        '<session_id>' + sessionId + '</session_id>' +
        '<function_id>' + func.trade_data + '</function_id>' +
        '<akb020>' + x(r.akb020) + '</akb020>' + '<aaz218>' + x(r.aaz218) + '</aaz218>' +
        '<aka130>' + x(r.aka130) + '</aka130>' + '<bka070>' + x(r.bka070) + '</bka070>' +
        '<bka074>' + x(r.bka074) + '</bka074>' + '<bka075>' + x(r.bka075) + '</bka075>' +
        '<bka076>' + x(r.bka076) + '</bka076>' + '<bka077>' + x(r.bka077) + '</bka077>' +
        '<bka078>' + x(r.bka078) + '</bka078>' + '<bka079>' + x(r.bka079) + '</bka079>' +
        '<feeinfo>' + rowXml + '</feeinfo>' +
        '</program>';

    // 2.3 日志 → 调用 → 回填日志
    var tradeNo = tradelog.createLog(FUNCID, func.trade_name, xml, cfg.user_api, 'GDSGSYB');
    var res = gsyb.invoke(cfg, FUNCID, xml);
    // PB：return_code=-2 且"追溯码已上传"时日志记 9，但不报错，按成功回写
    var ok = res.success || res.alreadyUploaded == true;
    tradelog.completeLog(tradeNo, ok ? res.response : res.errmsg, res.success ? '2' : '9');

    // 2.4 成功回写 lay_physic_trace_info.status（PB 仅在成功时回写）
    if (ok) {
        db.update("""
            update insur.lay_physic_trace_info
               set status = '1'
             where apply_no       = #{r.apply_no}
               and apply_sub_no   = #{r.apply_sub_no}
               and drug_trac_codg = #{r.bka099}
        """);
        okCnt = okCnt + 1;
    } else {
        failCnt = failCnt + 1;
    }
    details.add({ tradeNo: tradeNo, bka099: r.bka099, success: ok, errmsg: res.errmsg });
}

return {
    funcname : func.trade_name,
    startDate: range.startDate,
    endDate  : range.endDate,
    total    : rows.size(),
    pending  : pending.size(),
    success  : okCnt,
    fail     : failCnt,
    rows     : details
};
