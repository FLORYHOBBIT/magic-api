/*
 * 3501 商品盘存上传            POST /insur/trace/upload_3501
 * 对应 PB：w_insur_send cbx_1 → pf_send_detail_new('insur_goods_invinfo', dw_insur_3501)
 * 取数：zhiydba.v_gjyhyb_goods_invinfo   回写：insur.insur_goods_upload_record
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/trace_upload' as engineFn;
import db;
var engine = engineFn();

return engine.run({
    funcid    : 'insur_goods_invinfo',
    node      : 'invinfo',
    wrapTrace : true,
    keys      : ['pk_value'],
    params    : body,
    query     : (range) => db.lower().select("""
        select nvl((select a.status from insur.insur_goods_upload_record a
                     where a.trade_type = '3501' and a.pk_key = v.pk_value
                       and a.status <> '9' and rownum = 1), '0') send_flag,
               v.med_list_codg, v.fixmedins_hilist_id, v.fixmedins_hilist_name, v.rx_flag,
               v.invdate, v.inv_cnt, v.manu_lotnum, v.fixmedins_bchno, v.manu_date, v.expy_end,
               v.memo, v.drug_trac_codg, v.pk_value
          from zhiydba.v_gjyhyb_goods_invinfo v
         where v.operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
           and v.operation_date <  to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
    """),
    writeback : (row, status, tradeNo, res, infno) => {
        engine.recordUpload(infno, row, status, tradeNo, res);
    }
});
