/*
 * ============================================================================
 *  函数(公用)：common/insur —— 广东统一医保（JSON）通用函数
 *  对应 PB：n_insur_upload_base（pf_get_funcid_data / pf_get_headparm / pf_msxml_http / pf_get_result）
 *  引用：import '@/insur/common/insur' as insurFn;  var insur = insurFn();
 * ============================================================================
 */
import 'java.util.UUID' as UUID;
import 'java.security.MessageDigest' as MessageDigest;
import 'java.lang.System' as System;
import 'java.lang.Integer' as Integer;
import 'java.lang.Math' as Math;
import 'com.fasterxml.jackson.databind.ObjectMapper' as ObjectMapper;
import 'java.lang.Object' as JObject;
import log;
import http;
import db;

var MAPPER = new ObjectMapper();

var toJson = (obj) => {
    return MAPPER.writeValueAsString(obj);
}
var parseJson = (str) => {
    return MAPPER.readValue(str, JObject);
}

/* 读取 case_interface_config（对应 PB uf_init） */
var loadConfig = () => {
    var rows = db.lower().select("""
        select config_code, config_data
          from case_interface_config
         where config_type = '1'
           and valid_flag  = 'Y'
    """);
    var cfg = {};
    for (r in rows) {
        cfg[r.config_code] = r.config_data == null ? '' : r.config_data.trim();
    }
    var required = [
        'insur_gdstyyb_infver', 'insur_gdstyyb_fixmedins_name',
        'insur_gdstyyb_address_api', 'insur_gdstyyb_fixmedins_code',
        'insur_gdstyyb_tif_paasid', 'insur_gdstyyb_secretkey'
    ];
    for (k in required) {
        if (cfg[k] == null || cfg[k] == '') {
            exit 500, '医保接口配置缺失：' + k + '，请在 case_interface_config 中配置';
        }
    }
    return cfg;
}

/*
 * 查交易字典（对应 PB pf_get_funcid_data）
 *   funcid 如 insur_goods_selinfo_in → trade_data=3505, trade_name, trade_address(可选独立地址)
 */
var getFunc = (funcid) => {
    var row = db.lower().selectOne("""
        select trade_code, trade_data, trade_name, trade_address
          from insur.insurance_trade_dict
         where trade_code = #{funcid}
    """);
    if (row == null || row.trade_data == null || row.trade_name == null) {
        exit 500, '医保交易字典 insurance_trade_dict 未配置或 trade_data/trade_name 为空：' + funcid;
    }
    return row;
}

/* msgid：机构编码(12) + yyyyMMddHHmmss(14) + 4位随机数 */
var genMsgId = (fixmedinsCode) => {
    var ts = date_format(now(), 'yyyyMMddHHmmss');
    var r4 = Math.floor(Math.random() * 10000).intValue();
    var rnd = '000' + r4;
    rnd = rnd.substring(rnd.length() - 4);
    return fixmedinsCode + ts + rnd;
}

/* SHA256 小写十六进制（对应 PB sha256.encrypt） */
var sha256Hex = (text) => {
    var md = MessageDigest.getInstance('SHA-256');
    var bytes = md.digest(text.getBytes('UTF-8'));
    var sb = '';
    for (b in bytes) {
        var h = Integer.toHexString(b & 0xFF);
        sb = sb + (h.length() == 1 ? '0' + h : h);
    }
    return sb;
}

/* 报文头（对应 PB pf_get_headparm）；区划优先读配置，无配置回落 440100 */
var buildHead = (cfg, infno) => {
    var admvs = (cfg.insur_gdstyyb_mdtrtarea_admvs != null && cfg.insur_gdstyyb_mdtrtarea_admvs != '')
                 ? cfg.insur_gdstyyb_mdtrtarea_admvs : '440100';
    return {
        infno          : infno,
        msgid          : genMsgId(cfg.insur_gdstyyb_fixmedins_code),
        insuplc_admdvs : admvs,
        mdtrtarea_admvs: admvs,
        recer_admdvs   : '',
        dev_no         : '',
        dev_safe_info  : '',
        signtype       : (cfg.insur_gdstyyb_signtype != null && cfg.insur_gdstyyb_signtype != '') ? cfg.insur_gdstyyb_signtype : 'S',
        cainfo         : '',
        infver         : cfg.insur_gdstyyb_infver,
        opter_type     : '1',
        opter          : cfg.insur_gdstyyb_userid,
        opter_name     : cfg.insur_gdstyyb_fixmedins_name,
        inf_time       : date_format(now(), 'yyyy-MM-dd HH:mm:ss'),
        fixmedins_code : cfg.insur_gdstyyb_fixmedins_code,
        fixmedins_name : cfg.insur_gdstyyb_fixmedins_name,
        recer_sys_code : (cfg.insur_gdstyyb_recer_sys_code != null && cfg.insur_gdstyyb_recer_sys_code != '') ? cfg.insur_gdstyyb_recer_sys_code : 'admit',
        sign_no        : ''
    };
}

/*
 * HTTP 调用（对应 PB pf_msxml_http）
 *   url：交易字典 trade_address 有值则用它，否则 address_api + 交易编码
 */
var doInvoke = (cfg, func, bodyMap) => {
    var url = (func.trade_address != null && func.trade_address != '')
              ? func.trade_address
              : cfg.insur_gdstyyb_address_api + func.trade_data;
    var timestamp = '' + (System.currentTimeMillis() / 1000);
    var nonce     = UUID.randomUUID().toString();
    var signature = sha256Hex(timestamp + cfg.insur_gdstyyb_secretkey + nonce + timestamp);

    log.info('医保请求 url={}', url);
    var resp = http.connect(url)
                   .header('x-tif-paasid',    cfg.insur_gdstyyb_tif_paasid)
                   .header('x-tif-signature', signature)
                   .header('x-tif-timestamp', timestamp)
                   .header('x-tif-nonce',     nonce)
                   .body(bodyMap)
                   .expectString()
                   .post();
    var status = resp.getStatusCodeValue();
    var text   = resp.getBody();
    log.info('医保响应 status={}, body={}', status, text);
    if (status != 200) {
        return { ok: false, raw: text, errmsg: '请求调用失败,错误代码:' + status };
    }
    return { ok: true, raw: text };
}

/* 结果解析（对应 PB pf_get_result）：infcode==0 成功 */
var parseResult = (raw) => {
    if (raw == null || raw == '') {
        return { success: false, errmsg: '接口返回为空' };
    }
    var json;
    try {
        json = parseJson(raw);
    } catch (e) {
        return { success: false, errmsg: '解析返回json参数出错: ' + raw };
    }
    if (json.infcode == null) {
        return { success: false, errmsg: raw };
    }
    var code = Integer.parseInt('' + json.infcode);
    if (code != 0) {
        return { success: false, infcode: code, errmsg: '【接口返回】' + (json.err_msg != null ? json.err_msg : '交易失败'), output: json.output };
    }
    return { success: true, infcode: 0, output: json.output };
}

/*
 * 上传（对应 PB of_insur_goods_send 单行：组头 + input.<node> + POST + 解析）
 *   wrapTrace=true 时追加 drugtracinfo:[{drug_trac_codg}]，原 drug_trac_codg 字段保留（与 PB 非批量版一致）
 */
var invoke = (cfg, func, dataNode, row, wrapTrace) => {
    if (wrapTrace == true) {
        var trac = row.drug_trac_codg;
        if (trac != null && trac != '') {
            row.drugtracinfo = [ { drug_trac_codg: trac } ];
        }
    }
    var head = buildHead(cfg, func.trade_data);
    var input = {};
    input[dataNode] = row;
    head.input = input;

    var reqJson = toJson(head);
    var httpRes = doInvoke(cfg, func, head);
    if (!httpRes.ok) {
        return { success: false, errmsg: httpRes.errmsg, request: reqJson, response: httpRes.raw };
    }
    var res = parseResult(httpRes.raw);
    res.request = reqJson;
    res.response = httpRes.raw;
    return res;
}

return {
    loadConfig  : loadConfig,
    getFunc     : getFunc,
    buildHead   : buildHead,
    sha256Hex   : sha256Hex,
    toJson      : toJson,
    parseJson   : parseJson,
    parseResult : parseResult,
    invoke      : invoke
};
