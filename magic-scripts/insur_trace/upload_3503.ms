/*
 * 3503 商品采购                POST /insur/trace/upload_3503
 * 对应 PB：w_insur_send cbx_3 → pf_send_detail_new('insur_goods_purcinfo_in', dw_insur_3503)
 * 取数：zhiydba.v_gjyhyb_goods_purcinfo_in   回写：insur.insur_goods_upload_record
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/trace_upload' as engineFn;
import db;
var engine = engineFn();

return engine.run({
    funcid    : 'insur_goods_purcinfo_in',
    node      : 'purcinfo',
    wrapTrace : false,
    keys      : ['pk_value'],
    params    : body,
    query     : (range) => db.lower().select("""
        select nvl((select a.status from insur.insur_goods_upload_record a
                     where a.trade_type = '3503' and a.pk_key = v.pk_value
                       and a.status <> '9' and rownum = 1), '0') send_flag,
               v.med_list_codg, v.fixmedins_hilist_id, v.fixmedins_hilist_name, v.dynt_no,
               v.fixmedins_bchno, v.spler_name, v.spler_pmtno, v.manu_lotnum, v.prodentp_name,
               v.aprvno, v.manu_date, v.expy_end, v.finl_trns_pric, v.purc_retn_cnt,
               v.purc_invo_codg, v.purc_invo_no, v.rx_flag, v.purc_retn_stoin_time,
               v.purc_retn_opter_name, v.prod_geay_flag, v.memo, v.pk_value
          from zhiydba.v_gjyhyb_goods_purcinfo_in v
         where v.operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
           and v.operation_date <  to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
    """),
    writeback : (row, status, tradeNo, res, infno) => {
        engine.recordUpload(infno, row, status, tradeNo, res);
    }
});
