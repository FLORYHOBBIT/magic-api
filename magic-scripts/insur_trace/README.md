# 医保追溯码上传：PB(insur_send) → magic-api

原程序 `insur_send.pbt` 对接两套医保：
- **广东统一医保**（`n_insur_upload_base`，JSON + x-tif 签名）：3501~3506 上传（3512/3513 查询暂未迁移）
- **广东工伤医保**（`n_insur_upload_gdsgsyb`，XML + session_id）：药品追溯码上传

改造后**一个交易一个接口脚本**，可单独调用、单独挂定时任务、单独启停；公共逻辑放在 `common/` 下的【函数】里。

## 1. 目录与发布方式

```
insur_trace/
├── common/                      → magic-api【函数】，分组路径 /insur/common
│   ├── insur.ms                 统一医保：配置、交易字典、报文头、签名、HTTP、结果解析
│   ├── gsyb.ms                  工伤医保：配置、session_id 登录/缓存、XML、费别序号
│   ├── tradelog.ms              insur_trade_log 建/回填（两套共用）
│   └── trace_upload.ms          统一医保上传引擎：取数→过滤→逐行上传→日志→回写→汇总
├── upload_3501.ms … upload_3506.ms   → 【接口】POST /insur/trace/upload_35xx
├── upload_gsyb.ms                    → 【接口】POST /insur/trace/upload_gsyb
└── tasks/task_xxxx.ms                → 【定时任务】，每个上传接口一个
```

发布要点：
- `common/*.ms` 建成【函数】，放在路径 `/insur/common` 的分组下，函数路径分别为 `insur`、`gsyb`、`tradelog`、`trace_upload`。脚本里用 `import '@/insur/common/insur' as insurFn; var insur = insurFn();` 引用。
- 接口脚本建成【接口】，分组路径 `/insur/trace`，接口路径同文件名，方法 POST。
- 定时任务通过 `import '@post:/insur/trace/upload_3505' as upload; return upload();` 调用对应接口。启停只需在【定时任务】里切换 enabled，互不影响。

如果分组路径不是 `/insur/common`、`/insur/trace`，把脚本里的 `import '@/insur/common/...'` 和 `'@post:/insur/trace/...'` 改成实际路径。

## 2. 接口清单

| 脚本 | 交易字典 funcid | 交易 | 取数 | 回写 |
|---|---|---|---|---|
| upload_3501 | insur_goods_invinfo | 3501 商品盘存 | zhiydba.v_gjyhyb_goods_invinfo | insur_goods_upload_record |
| upload_3502 | insur_goods_invinfo_modify | 3502 库存变更 | zhiydba.v_gjyhyb_goods_invinfo_modify | physic_drug_invinfodetail.status |
| upload_3503 | insur_goods_purcinfo_in | 3503 采购 | zhiydba.v_gjyhyb_goods_purcinfo_in | insur_goods_upload_record |
| upload_3504 | insur_goods_purcinfo_out | 3504 采购退货 | zhiydba.v_gjyhyb_goods_purcinfo_out | insur_goods_upload_record |
| upload_3505 | insur_goods_selinfo_in | 3505 销售 | zhiydba.v_gjyhyb_goods_selinfo_out | lay_physic_trace_info.status |
| upload_3506 | insur_goods_selinfo_out | 3506 销售退货 | zhiydba.v_gjyhyb_goods_selinfo_in | lay_physic_return_trace_info.status |
| upload_gsyb | api_gdgsyb_tracecode_selinfo | 工伤追溯码 | V_BIZH113505 | lay_physic_trace_info.status / aaz213 |

3505 取 `_out` 视图、3506 取 `_in` 视图，是照搬 PB 的 DataWindow，不是笔误。

上传接口通用参数（JSON body，全部可选）：

| 参数 | 说明 |
|---|---|
| startDate / endDate | `yyyy-MM-dd HH:mm:ss`，默认今天 00:00:00 ~ 23:59:59 |
| days | 未传 startDate 时向前补 N 天，默认 0 |
| resend | true 时连已上传（send_flag=1）的也重传 |

返回汇总：`total`(检索) / `pending`(待传) / `success` / `fail` / `rows`(每行 tradeNo 与错误原因)。

## 3. 统一医保上传流程（对应 PB of_insur_goods_send）

每个 `upload_35xx.ms` 只声明三件事：取数 SQL、不进报文的回写键 `keys`、回写逻辑 `writeback`。引擎 `trace_upload.run` 负责：

1. `insur.loadConfig()` 读 `case_interface_config`；`insur.getFunc(funcid)` 读 `insurance_trade_dict` 得到交易编码、名称、可选独立地址 `trade_address`。
2. 执行取数 SQL；`resend=false` 时过滤 `send_flag='1'`。
3. **逐行**上传（与 PB `pf_send_detail_new` 一致）：
   - 报文字段 = SQL 列 − `send_flag` − `keys`；日期转成 `yyyy-MM-dd HH:mm:ss`。
   - 3501/3502/3505/3506 追加 `drugtracinfo:[{drug_trac_codg}]`。
   - 报文头同 PB `pf_get_headparm`；签名头 `x-tif-signature = SHA256(timestamp+secretkey+nonce+timestamp)`。
   - URL：`trade_address` 有值用它，否则 `insur_gdstyyb_address_api + 交易编码`。
   - 成功判断 `infcode == 0`，失败取 `err_msg`。
4. 日志：`insurance_trade_dict.remote_flag='1'` 的交易才写 `insur_trade_log`（status 1 新开 → 2 成功 / 9 失败，失败写 memo），与 PB `pf_record_log` 一致。
5. 回写：调用脚本里的 `writeback(row, status, tradeNo, res, infno)`，成功 status=1，失败 status=0。

### 上传完成回写（与 PB choose case 一致）

| 交易 | 回写 | 匹配键 |
|---|---|---|
| 3501/3503/3504 | `insur_goods_upload_record` 存在则 update(status、error_times、modify_time、insur_trade_log_no)，否则 insert(含 in_parm/result_parm) | trade_type + pk_key |
| 3502 | `physic_drug_invinfodetail.status` | fixmedins_bchno + med_list_codg + inv_chg_type + drug_trac_codg |
| 3505 | `lay_physic_trace_info.status` | apply_no + apply_sub_no + drug_trac_codg |
| 3506 | `lay_physic_return_trace_info.status` | apply_no + apply_sub_no + drug_trac_codg |

3501/3503/3504 的已上传状态来自 `insur_goods_upload_record`，3502/3505/3506 来自视图自身的 `status`，这也是照搬 PB 的 DataWindow。

## 4. 工伤医保上传流程（对应 PB n_insur_upload_gdsgsyb）

1. 读 `insur_gdsgsyb_address_api / hospital_code / user_api / psw_api`。
2. 取 session_id：缓存 6 小时，过期调 `api_login_gdsgsyb` 登录。
3. 取 `V_BIZH113505`，逐行处理：
   - `aaz213` 为空时两段式调 `api_gdgsyb_tracecode_feeinfo`：先 `bka001=0` 取批次，再逐批取 `bka071→aaz213`，回写 `lay_physic_trace_info.aaz213`。
   - 组 `<program>` XML（头部 akb020…bka079 + `<feeinfo><row>`），`application/x-www-form-urlencoded` 提交。
   - `<return_code>=1` 成功；`-2` 且含"追溯码已上传"时日志记 9，但按成功回写（PB 同样处理）。
   - 成功回写 `lay_physic_trace_info.status='1'`。

## 5. 配置项

`case_interface_config`（config_type='1' and valid_flag='Y'）：

| config_code | 用途 |
|---|---|
| insur_gdstyyb_address_api | 统一医保 API 地址前缀 |
| insur_gdstyyb_fixmedins_code / _name | 定点机构编码 / 名称 |
| insur_gdstyyb_tif_paasid / _secretkey | 签名用 paasid / 私钥 |
| insur_gdstyyb_infver | 接口版本 |
| insur_gdstyyb_userid | 报文头 opter |
| insur_gdstyyb_mdtrtarea_admvs | 区划；PB 写死 440100，这里优先读配置，空则回落 440100 |
| insur_gdstyyb_signtype / _recer_sys_code | 可选，默认 S / admit |
| insur_gdsgsyb_address_api / _user_api / _psw_api / _hospital_code | 工伤医保 |

`insur.insurance_trade_dict`：每个 funcid 需配置 `trade_data`(交易编码)、`trade_name`，按需配置 `trade_address` 和 `remote_flag`。

## 6. 定时任务

`tasks/` 下每个上传接口一个任务脚本，默认 `days: 1`（补传昨天和今天），建议 cron：

| 任务 | cron |
|---|---|
| task_3501 ~ task_3504 | 每天 01:00 起错开 10 分钟 |
| task_3505 / task_3506 / task_gsyb | 每 30 分钟，分钟位错开 0 / 5 / 15 |

任务里修改 `var body = { days: 1, resend: false };` 即可调整范围。

## 7. 与 PB 的差异

- 区划从配置读取（PB 写死 440100）。
- SHA256 改在 JDK 内计算，不再依赖 Oracle 的 `sha256.encrypt`。
- 工伤 session_id 缓存在 JVM 内存，PB 存在 `f_getvalvaluex` 的缓存表；应用重启后会重新登录一次。
- 报文不再带 `result_text`、`pk_value`、`apply_no` 等非医保字段（PB 的 dwsetJson 只排除了 send_flag）。

## 8. 验证情况

用本机 `magic-script-1.9.0.jar` 搭了一个模拟环境（db、http 为 mock，函数/接口导入按 magic-api 的 `FunctionMagicDynamicRegistry` / `RequestMagicDynamicRegistry` 实现）实际执行了全部 9 个接口脚本和 2 个任务脚本：报文结构、签名、字段剔除、日期转换、日志、各表回写 SQL、工伤费别两段式、任务传参都按预期执行。

没有连真实 Oracle 和医保前置机，联调时需要核对：
- 视图里的列名与上表 SQL 一致；
- 医保返回的报文格式（尤其工伤 `feebatchinfo/feeinfo` 的节点层级）；
- 日期字段格式（当前统一为 `yyyy-MM-dd HH:mm:ss`）是否符合医保要求。

编写时踩到的 magic-script 规则（改脚本时注意）：
- 在【函数】里用 `db` 必须显式 `import db;`，自动导入只对接口生效，否则被其他脚本调用时 `db` 为空。
- 不要用 `x as string / as int` 转换数字，改用 `'' + x`、`Integer.parseInt(...)`。
- 不能写 `java.lang.Object.class` 这类全限定名，要先 `import` 再用别名。
- `if (...) return/break/continue/exit` 必须加花括号；`ts` 是保留字。
