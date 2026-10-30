/*
 * 3505 商品销售                POST /insur/trace/upload_3505
 * 对应 PB：w_insur_send cbx_5 → pf_send_detail_new('insur_goods_selinfo_in', dw_insur_3505)
 * 取数：zhiydba.v_gjyhyb_goods_selinfo_out（PB 原样：销售取 _out 视图）
 * 回写：insur.lay_physic_trace_info.status
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/trace_upload' as engineFn;
import db;
var engine = engineFn();

return engine.run({
    funcid    : 'insur_goods_selinfo_in',
    node      : 'selinfo',
    wrapTrace : true,
    keys      : ['apply_no', 'apply_sub_no', 'operation_date'],
    params    : body,
    query     : (range) => db.lower().select("""
        select nvl(status, '0') send_flag,
               med_list_codg, fixmedins_hilist_id, fixmedins_hilist_name, fixmedins_bchno,
               prsc_dr_cert_type, prsc_dr_certno, prsc_dr_name,
               phar_cert_type, phar_certno, phar_name, phar_prac_cert_no,
               hi_feesetl_type, setl_id, mdtrt_sn, psn_no, psn_cert_type, certno, psn_name,
               manu_lotnum, manu_date, expy_end, rx_flag, trdn_flag, finl_trns_pric,
               rxno, rx_circ_flag, rtal_docno, stoout_no, bchno, drug_trac_codg,
               drug_prod_barc, shelf_posi, sel_retn_cnt, sel_retn_time, sel_retn_opter_name,
               memo, mdtrt_setl_type, operation_date, apply_no, apply_sub_no
          from zhiydba.v_gjyhyb_goods_selinfo_out
         where operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
           and operation_date <= to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
    """),
    writeback : (row, status, tradeNo, res, infno) => {
        db.update("""
            update insur.lay_physic_trace_info
               set status = #{status}
             where apply_no       = #{row.apply_no}
               and apply_sub_no   = #{row.apply_sub_no}
               and drug_trac_codg = #{row.drug_trac_codg}
        """);
    }
});
