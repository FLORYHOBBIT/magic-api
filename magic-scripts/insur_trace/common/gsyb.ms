/*
 * ============================================================================
 *  函数(模块)：gsyb —— 工伤医保追溯码上传通用函数（XML 协议 / session_id 鉴权）
 *  对应 PB 对象：n_insur_upload_gdsgsyb
 *
 *  与统一医保(common/insur, JSON)完全不同：
 *    - 报文为 XML <program>...</program>
 *    - 先登录 api_login_gdsgsyb 取 session_id（缓存，默认 6 小时）
 *    - Content-Type: application/x-www-form-urlencoded
 *    - 成功判断 <return_code>==1
 *
 *  引用：import '@/insur/common/gsyb' as gsybFn;  var gsyb = gsybFn();  （db 为默认模块）
 * ============================================================================
 */
import 'java.util.concurrent.ConcurrentHashMap' as ConcurrentHashMap;
import 'java.lang.System' as System;
import log;
import http;
import db;

/*
 * session_id 进程内缓存：借助 JVM System properties 持有一个 ConcurrentHashMap 单例，
 * 使其在多次接口请求间保持（magic-script 每次请求会重新加载模块，普通局部变量无法持久）。
 */
var holderKey = 'magicapi.gsyb.sessionCache';
var CACHE = System.getProperties().get(holderKey);
if (CACHE == null) {
    CACHE = new ConcurrentHashMap();
    System.getProperties().put(holderKey, CACHE);
}

/* XML 转义 */
var xmlEsc = (v) => {
    if (v == null) { return ''; }
    var s = v.toString();
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;');
    return s;
}

/* 取两个标签之间的内容，对应 PB：pf_getdata_between_xml */
var between = (xml, tag1, tag2) => {
    if (xml == null || xml == '') { return ''; }
    var p1 = xml.indexOf(tag1);
    if (p1 < 0) { return ''; }
    var rest = xml.substring(p1 + tag1.length());
    var p2 = rest.indexOf(tag2);
    if (p2 < 0) { return ''; }
    return rest.substring(0, p2).trim();
}

/*
 * 读取配置（对应 PB：uf_init），返回 { address_api, hospital_code, user_api, psw_api, session_interval_hours }
 */
var loadConfig = () => {
    var rows = db.lower().select("""
        select config_code, config_data
          from case_interface_config
         where config_type = '1'
           and valid_flag  = 'Y'
    """);
    var map = {};
    for (r in rows) {
        map[r.config_code] = r.config_data == null ? '' : r.config_data.trim();
    }
    var cfg = {
        address_api  : map.insur_gdsgsyb_address_api,
        hospital_code: map.insur_gdsgsyb_hospital_code,
        user_api     : map.insur_gdsgsyb_user_api,
        psw_api      : map.insur_gdsgsyb_psw_api
    };
    if (cfg.address_api == null || cfg.address_api == '') { exit 500, '未配置工伤医保【接口地址】insur_gdsgsyb_address_api'; }
    if (cfg.user_api == null || cfg.user_api == '')       { exit 500, '未配置工伤医保【用户名】insur_gdsgsyb_user_api'; }
    if (cfg.psw_api == null || cfg.psw_api == '')          { exit 500, '未配置工伤医保【用户密码】insur_gdsgsyb_psw_api'; }
    return cfg;
}

/* 取交易配置(trade_data/name/address)，对应 PB：pf_get_funcid_data */
var getFunc = (funcid) => {
    var row = db.lower().selectOne("""
        select trade_data, trade_name, trade_address
          from insur.insurance_trade_dict
         where trade_code = #{funcid}
    """);
    if (row == null || row.trade_data == null) {
        exit 500, '交易字典未配置 trade_code=' + funcid;
    }
    return row;
}

/*
 * 发送 XML 报文（对应 PB：pf_msxml_http），返回响应 XML 文本
 */
var postXml = (cfg, xml) => {
    log.info('工伤医保请求 url={}, body={}', cfg.address_api, xml);
    var resp = http.connect(cfg.address_api)
                   .header('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8')
                   .header('Accept', '*/*')
                   .body(xml)
                   .expectString()
                   .post();
    var status = resp.getStatusCodeValue();
    var text = resp.getBody();
    log.info('工伤医保响应 status={}, body={}', status, text);
    if (status != 200) {
        return { ok: false, errmsg: '请求调用失败,错误代码:' + status, raw: text };
    }
    return { ok: true, raw: text };
}

/*
 * 登录取 session_id（对应 PB：pf_insure_login）
 */
var login = (cfg) => {
    var f = getFunc('api_login_gdsgsyb');
    var xml = '<program>' +
              '<function_id>' + f.trade_data + '</function_id>' +
              '<userid>' + xmlEsc(cfg.user_api) + '</userid>' +
              '<password>' + xmlEsc(cfg.psw_api) + '</password>' +
              '</program>';
    var res = postXml(cfg, xml);
    if (!res.ok) { exit 500, '工伤登录失败: ' + res.errmsg; }
    var sid = between(res.raw, '<session_id>', '</session_id>');
    if (sid == null || sid == '') { exit 500, '工伤登录返回 session_id 为空'; }
    return sid;
}

/*
 * 取 session_id（带缓存，默认 6 小时），对应 PB：pf_get_session_id
 */
var getSessionId = (cfg) => {
    var intervalHours = 6;
    var nowMs = System.currentTimeMillis();
    var sid = CACHE.get('sid');
    var cachedTs = CACHE.get('ts');
    if (sid != null && cachedTs != null && (nowMs - cachedTs) < intervalHours * 3600 * 1000) {
        return sid;
    }
    sid = login(cfg);
    CACHE.put('sid', sid);
    CACHE.put('ts', nowMs);
    return sid;
}

/*
 * 解析返回结果（对应 PB：pf_get_result）：<return_code>==1 成功
 *   funcid 为追溯码上传且 return_code=-2 且含"追溯码已上传" → 视为已上传(非错误)
 */
var parseResult = (raw, funcid) => {
    if (raw == null || raw == '') { return { success: false, errmsg: '返回内容为空' }; }
    var code = between(raw, '<return_code>', '</return_code>');
    var msg  = between(raw, '<return_code_message>', '</return_code_message>');
    if (code == '1') {
        return { success: true, code: code, msg: msg };
    }
    if (code == '-2' && funcid == 'api_gdgsyb_tracecode_selinfo' && msg != null && msg.indexOf('追溯码已上传') >= 0) {
        return { success: false, alreadyUploaded: true, code: code, errmsg: msg };
    }
    return { success: false, code: code, errmsg: msg };
}

/*
 * 统一调用：写本地日志省略，这里只做 POST + 解析（交易日志由接口脚本统一处理）。
 * 对应 PB：pf_invoke 的 http + 结果解析部分。
 */
var invoke = (cfg, funcid, xml) => {
    var res = postXml(cfg, xml);
    if (!res.ok) {
        return { success: false, errmsg: res.errmsg, request: xml, response: res.raw };
    }
    var pr = parseResult(res.raw, funcid);
    pr.request = xml;
    pr.response = res.raw;
    return pr;
}

/*
 * 解析 feebatchinfo，返回 bka001 列表（对应 PB：pf_analyze_xml_feeinfo1）
 * 简单起见用正则提取所有 <bka001>..</bka001>
 */
var parseBka001List = (xml) => {
    var list = [];
    if (xml == null) { return list; }
    var idx = 0;
    while (true) {
        var p1 = xml.indexOf('<bka001>', idx);
        if (p1 < 0) { break; }
        var p2 = xml.indexOf('</bka001>', p1);
        if (p2 < 0) { break; }
        list.add(xml.substring(p1 + 8, p2).trim());
        idx = p2 + 9;
    }
    return list;
}

/*
 * 两段式获取费别序号 aaz213（对应 PB：pf_get_feeinfo + pf_analyze_xml_feeinfo1/2）
 *   as_bka896 = aaz218(就医登记号)，as_akb020 = 医疗机构编码
 *   返回 Map<bka071, aaz213>，供上传时按 bka071 回填
 */
var getFeeinfo = (cfg, sessionId, bka896, akb020) => {
    var f = getFunc('api_gdgsyb_tracecode_feeinfo');
    var baseXml = '<program>' +
                  '<session_id>' + sessionId + '</session_id>' +
                  '<function_id>' + f.trade_data + '</function_id>' +
                  '<bka895>aaz218</bka895>' +
                  '<bka896>' + xmlEsc(bka896) + '</bka896>' +
                  '<akb020>' + xmlEsc(akb020) + '</akb020>' +
                  '<bka006>410</bka006>';
    // 第一次：bka001=0 取批次列表
    var xml1 = baseXml + '<bka001>0</bka001></program>';
    var r1 = invoke(cfg, f.trade_code != null ? f.trade_code : 'api_gdgsyb_tracecode_feeinfo', xml1);
    var result = {};
    var bka001List = parseBka001List(r1.response);
    for (bka001 in bka001List) {
        if (bka001 == null || bka001 == '') { continue; }
        var xml2 = baseXml + '<bka001>' + xmlEsc(bka001) + '</bka001></program>';
        var r2 = invoke(cfg, 'api_gdgsyb_tracecode_feeinfo', xml2);
        // 解析 <feeinfo> 下每个 <row> 的 bka071/aaz213
        var raw = r2.response;
        if (raw == null) { continue; }
        var idx = 0;
        while (true) {
            var rp = raw.indexOf('<row>', idx);
            if (rp < 0) { break; }
            var re = raw.indexOf('</row>', rp);
            if (re < 0) { break; }
            var rowXml = raw.substring(rp, re);
            var bka071 = between(rowXml, '<bka071>', '</bka071>');
            var aaz213 = between(rowXml, '<aaz213>', '</aaz213>');
            if (bka071 != null && bka071 != '') {
                result[bka071] = aaz213;
            }
            idx = re + 6;
        }
    }
    return result;
}

return {
    xmlEsc         : xmlEsc,
    between        : between,
    loadConfig     : loadConfig,
    getFunc        : getFunc,
    postXml        : postXml,
    getSessionId   : getSessionId,
    parseResult    : parseResult,
    invoke         : invoke,
    getFeeinfo     : getFeeinfo
};
