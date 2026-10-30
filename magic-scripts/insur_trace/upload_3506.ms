/*
 * 3506 商品销售退货            POST /insur/trace/upload_3506
 * 对应 PB：w_insur_send cbx_6 → pf_send_detail_new('insur_goods_selinfo_out', dw_insur_3506)
 * 取数：zhiydba.v_gjyhyb_goods_selinfo_in（PB 原样：退货取 _in 视图）
 * 回写：insur.lay_physic_return_trace_info.status
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/trace_upload' as engineFn;
import db;
var engine = engineFn();

return engine.run({
    funcid    : 'insur_goods_selinfo_out',
    node      : 'selinfo',
    wrapTrace : true,
    keys      : ['apply_no', 'apply_sub_no', 'operation_date'],
    params    : body,
    query     : (range) => db.lower().select("""
        select nvl(status, '0') send_flag,
               med_list_codg, fixmedins_hilist_id, fixmedins_hilist_name, fixmedins_bchno,
               setl_id, psn_no, psn_cert_type, certno, psn_name,
               manu_lotnum, manu_date, expy_end, rx_flag, trdn_flag, finl_trns_pric,
               sel_retn_cnt, sel_retn_time, sel_retn_opter_name, memo, medins_prod_sel_no,
               drug_trac_codg, mdtrt_sn, operation_date, apply_no, apply_sub_no
          from zhiydba.v_gjyhyb_goods_selinfo_in
         where operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
           and operation_date <  to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
    """),
    writeback : (row, status, tradeNo, res, infno) => {
        db.update("""
            update insur.lay_physic_return_trace_info
               set status = #{status}
             where apply_no       = #{row.apply_no}
               and apply_sub_no   = #{row.apply_sub_no}
               and drug_trac_codg = #{row.drug_trac_codg}
        """);
    }
});
