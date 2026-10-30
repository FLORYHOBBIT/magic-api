import db;
/*
 * ============================================================================
 *  函数(公用)：common/tradelog —— 医保交易日志 insur_trade_log
 *  对应 PB：pf_record_log / pf_log_create / pf_log_complete / pf_get_insur_trade_no
 *  引用：import '@/insur/common/tradelog' as tradelogFn;  var tradelog = tradelogFn();
 * ============================================================================
 */

/* 截断到 3000 字符（PB 同样处理） */
var cut = (text) => {
    if (text == null) { return ''; }
    var s = text.toString();
    if (s.length() > 3000) {
        return s.substring(0, 3000) + '...[还有' + (s.length() - 3000) + '个字符串已被截取]';
    }
    return s;
}

/* 该交易是否需要记库日志：insurance_trade_dict.remote_flag='1'（对应 pf_record_log） */
var needLog = (funcid) => {
    var flag = db.selectValue("""
        select nvl(remote_flag, '0') from insur.insurance_trade_dict where trade_code = #{funcid}
    """);
    return flag == '1';
}

/* 交易流水号：COMM.INSUR_GDSGSYB_TRADE_SEQ，左补零到 11 位 */
var nextTradeNo = () => {
    var seq = db.selectValue("select COMM.INSUR_GDSGSYB_TRADE_SEQ.nextval from dual").toString();
    var s = '00000000000' + seq;
    return s.substring(s.length() - 11);
}

/*
 * 新建日志（status=1 新开），返回 trade_no；不需要记日志的交易返回 null
 *   balanceInterface：统一医保 'GDSTYYB'，工伤医保 'GDSGSYB'
 */
var createLog = (funcid, funcname, parmText, operator, balanceInterface) => {
    if (!needLog(funcid)) { return null; }
    var tradeNo = nextTradeNo();
    var text = cut(parmText);
    db.update("""
        insert into insur_trade_log
            (trade_no, trade_text, trade_type, trade_name, settle_mode,
             operator, operation_date, status, balance_interface)
        values
            (#{tradeNo}, #{text}, #{funcid}, #{funcname}, '1',
             #{operator}, sysdate, '1', #{balanceInterface})
    """);
    return tradeNo;
}

/* 回填日志：logStatus '2' 成功 / '9' 失败；失败时 memo 记前 700 字符 */
var completeLog = (tradeNo, resultText, logStatus) => {
    if (tradeNo == null) { return; }
    var text = cut(resultText);
    var memo = text.length() > 700 ? text.substring(0, 700) : text;
    db.update("""
        update insur_trade_log
           set return_text = #{text},
               memo        = decode(#{logStatus}, '9', #{memo}, memo),
               return_date = sysdate,
               status      = #{logStatus}
         where trade_no = #{tradeNo}
    """);
}

return {
    needLog     : needLog,
    nextTradeNo : nextTradeNo,
    createLog   : createLog,
    completeLog : completeLog
};
