/*
 * 3502 商品库存变更            POST /insur/trace/upload_3502
 * 对应 PB：w_insur_send cbx_2 → pf_send_detail_new('insur_goods_invinfo_modify', dw_insur_3502)
 * 取数：zhiydba.v_gjyhyb_goods_invinfo_modify   回写：insur.physic_drug_invinfodetail.status
 * 参数(可选)：startDate / endDate / days / resend
 */
import '@/insur/common/trace_upload' as engineFn;
import db;
var engine = engineFn();

return engine.run({
    funcid    : 'insur_goods_invinfo_modify',
    node      : 'invinfo',
    wrapTrace : true,
    keys      : ['apply_no'],
    params    : body,
    query     : (range) => db.lower().select("""
        select nvl(status, '0') send_flag,
               med_list_codg, inv_chg_type, fixmedins_hilist_id, fixmedins_hilist_name,
               fixmedins_bchno, pric, cnt, rx_flag, inv_chg_time, inv_chg_opter_name,
               memo, trdn_flag, drug_trac_codg, trac_codg_pac_hery, apply_no
          from zhiydba.v_gjyhyb_goods_invinfo_modify
         where operation_date >= to_date(#{range.startDate}, 'yyyy-mm-dd hh24:mi:ss')
           and operation_date <  to_date(#{range.endDate},   'yyyy-mm-dd hh24:mi:ss')
    """),
    writeback : (row, status, tradeNo, res, infno) => {
        db.update("""
            update insur.physic_drug_invinfodetail
               set status = #{status}
             where fixmedins_bchno = #{row.fixmedins_bchno}
               and med_list_codg   = #{row.med_list_codg}
               and inv_chg_type    = #{row.inv_chg_type}
               and drug_trac_codg  = #{row.drug_trac_codg}
        """);
    }
});
