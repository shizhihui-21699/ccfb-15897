package com.traceguard.engine;

import com.traceguard.model.Alert;
import com.traceguard.model.AlertType;
import com.traceguard.model.LifecycleState;
import com.traceguard.model.ScanEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.traceguard.model.Alert.Severity.HIGH;
import static com.traceguard.model.Alert.Severity.MEDIUM;

/**
 * 追溯码监管引擎。
 *
 * <p>核心思路：不为"同一盒药被合法扫多次"硬编码次数上限，而是为每个追溯码维护一条
 * 按扫码时间排序的事件时间线，用生命周期状态机回放整条时间线——合法链路
 * （生产→批发→零售→销售）天然通过，只有违反状态机的事件才产生告警，且告警直接
 * 携带触发事件的机构与环节，实现"定位到具体环节"。
 *
 * <p>乱序处理：新事件按扫码时间插入时间线后整体回放，告警只针对"尚未告警过的事件"
 * 发出，因此迟到数据会修正结论而不会重复报警。重复上报（同一 eventId）直接幂等丢弃。
 */
public class TraceCodeEngine {

    private final CodeStateStore store;
    private final RuleConfig config;

    public TraceCodeEngine(CodeStateStore store, RuleConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 处理一条扫码记录，返回本次新产生的告警（可能为空）。
     * 引擎无状态（状态全在 store），可任意水平扩展，按 traceCode 分区即可。
     */
    public List<Alert> process(ScanEvent event) {
        TraceCodeState state = store.getOrCreate(event.traceCode());
        if (!state.seenEventIds.add(event.eventId())) {
            return List.of(); // 重复上报，幂等丢弃
        }
        state.history.add(event);
        state.history.sort(Comparator.comparing(ScanEvent::scanTime));

        ReplayResult result = replay(state.history);

        List<Alert> fresh = new ArrayList<>();
        for (Alert alert : result.alerts) {
            // 乱序重放会重新算出历史告警，只发没发过的
            if (state.alertedEventIds.add(alert.offendingEvent().eventId())) {
                fresh.add(alert);
            }
        }
        state.lifecycle = result.finalState;
        state.currentHolderOrgId = result.holderOrgId;
        store.put(state);
        return fresh;
    }

    /** 查询某追溯码当前状态（监管端"码画像"接口用）。 */
    public TraceCodeState inspect(String traceCode) {
        return store.getOrCreate(traceCode);
    }

    // ------------------------------------------------------------------

    private record ReplayResult(List<Alert> alerts, LifecycleState finalState, String holderOrgId) {
    }

    /** 从时间线起点回放状态机，返回全部违规点。 */
    private ReplayResult replay(List<ScanEvent> timeline) {
        List<Alert> alerts = new ArrayList<>();
        LifecycleState state = LifecycleState.UNKNOWN;
        String holderOrg = null;      // 当前持货机构
        String holderOrgName = null;
        String soldByOrg = null;      // 售出机构
        String soldByOrgName = null;
        java.time.Instant soldAt = null;
        boolean insuranceSold = false;
        int splitAccum = 0;           // 本盒累计拆零数量
        ScanEvent prev = null;

        for (ScanEvent e : timeline) {
            // 销毁后任何扫码都说明码被复制
            if (state == LifecycleState.DESTROYED) {
                alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                        "该码对应药品已销毁，仍出现%s扫码，疑似假码".formatted(e.eventType().label())));
                prev = e;
                continue;
            }

            switch (e.eventType()) {
                case PRODUCE_SHIP -> {
                    if (state == LifecycleState.SOLD || state == LifecycleState.PARTIALLY_SOLD) {
                        alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                "生产企业重新启用已售药品的追溯码，疑似旧码回收造假"));
                    } else if (state != LifecycleState.UNKNOWN) {
                        alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                "重复生产出库扫码"));
                    }
                    state = LifecycleState.PRODUCED;
                    holderOrg = e.orgId();
                    holderOrgName = e.orgName();
                }
                case DISTRIBUTOR_IN -> {
                    if (state == LifecycleState.SOLD || state == LifecycleState.PARTIALLY_SOLD) {
                        alerts.add(reflow(e, prev, soldByOrgName, soldAt, insuranceSold,
                                "售出药品重新进入批发环节"));
                    } else if (state == LifecycleState.AT_RETAIL) {
                        alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                "药店在架药品无退货出库记录直接进入批发企业，流向断链"));
                    }
                    state = LifecycleState.IN_DISTRIBUTION;
                    holderOrg = e.orgId();
                    holderOrgName = e.orgName();
                }
                case DISTRIBUTOR_OUT -> {
                    if (state == LifecycleState.SOLD || state == LifecycleState.PARTIALLY_SOLD) {
                        alerts.add(reflow(e, prev, soldByOrgName, soldAt, insuranceSold,
                                "售出药品在批发环节再次出库"));
                    } else if (state != LifecycleState.IN_DISTRIBUTION && state != LifecycleState.PRODUCED) {
                        alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                "批发企业出库前无对应入库记录"));
                    }
                    state = LifecycleState.IN_DISTRIBUTION;
                }
                case RETAIL_IN -> {
                    if (state == LifecycleState.SOLD || state == LifecycleState.PARTIALLY_SOLD) {
                        // 最典型的回流药：医保结算售出后，又出现在另一家药店入库
                        alerts.add(reflow(e, prev, soldByOrgName, soldAt, insuranceSold,
                                "已售出药品重新在零售终端入库"));
                    } else if (state == LifecycleState.AT_RETAIL && holderOrg != null
                            && !holderOrg.equals(e.orgId())) {
                        alerts.add(new Alert(AlertType.REFLOW_DRUG, MEDIUM, e.traceCode(), e, prev,
                                "药品在[%s]在架期间无出库/退货记录，直接出现在[%s]入库，疑似店间非法调拨或回收"
                                        .formatted(holderOrgName, e.orgName())));
                    }
                    state = LifecycleState.AT_RETAIL;
                    holderOrg = e.orgId();
                    holderOrgName = e.orgName();
                }
                case SALE -> {
                    if (state == LifecycleState.SOLD) {
                        alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                "同一追溯码重复销售：首次售出于[%s](%s)，本次[%s]，疑似一码多卖/假码"
                                        .formatted(soldByOrgName, soldAt, e.orgName())));
                    } else {
                        if (state == LifecycleState.AT_RETAIL && holderOrg != null
                                && !holderOrg.equals(e.orgId())) {
                            alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                    "该盒药在[%s]在架，却在[%s]发生销售扫码，疑似扫真码卖假药"
                                            .formatted(holderOrgName, e.orgName())));
                        }
                        if (state == LifecycleState.PRODUCED || state == LifecycleState.IN_DISTRIBUTION) {
                            alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                    "未经零售入库直接销售，可能漏扫或串货"));
                        }
                        state = LifecycleState.SOLD;
                        soldByOrg = e.orgId();
                        soldByOrgName = e.orgName();
                        soldAt = e.scanTime();
                        insuranceSold = e.insuranceSettled();
                    }
                }
                case SPLIT_SALE -> {
                    if (state == LifecycleState.SOLD) {
                        alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                "整盒已售出后又发生拆零扫码，疑似一码多用"));
                    } else if (state == LifecycleState.AT_RETAIL || state == LifecycleState.PARTIALLY_SOLD) {
                        if (holderOrg != null && !holderOrg.equals(e.orgId())) {
                            alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                    "该盒药在[%s]拆零销售中，却在[%s]出现拆零扫码".formatted(holderOrgName, e.orgName())));
                        }
                        splitAccum += e.quantity();
                        int unitsPerBox = e.unitsPerBox() > 0 ? e.unitsPerBox() : config.defaultUnitsPerBox();
                        if (splitAccum > unitsPerBox) {
                            alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                                    "拆零累计%d单位超过每盒%d单位，超量部分疑似用真码销售假药"
                                            .formatted(splitAccum, unitsPerBox)));
                        }
                        if (splitAccum >= unitsPerBox) {
                            state = LifecycleState.SOLD;
                            soldByOrg = e.orgId();
                            soldByOrgName = e.orgName();
                            soldAt = e.scanTime();
                            insuranceSold = e.insuranceSettled();
                        } else {
                            state = LifecycleState.PARTIALLY_SOLD;
                        }
                    } else {
                        alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                "药品未进入零售环节即发生拆零销售"));
                    }
                }
                case PATIENT_RETURN -> {
                    if (state != LifecycleState.SOLD) {
                        alerts.add(new Alert(AlertType.ABNORMAL_RETURN, MEDIUM, e.traceCode(), e, prev,
                                "药品无销售记录却发生患者退货"));
                    } else if (!e.orgId().equals(soldByOrg)) {
                        // 回收药贩子常伪装成"退货"把药洗回药店
                        alerts.add(new Alert(AlertType.ABNORMAL_RETURN, HIGH, e.traceCode(), e, prev,
                                "药品售出于[%s]，退货却发生在[%s]，疑似回收药伪装退货"
                                        .formatted(soldByOrgName, e.orgName())));
                        state = LifecycleState.AT_RETAIL;
                        holderOrg = e.orgId();
                        holderOrgName = e.orgName();
                        soldByOrg = null;
                        insuranceSold = false;
                        splitAccum = 0;
                    } else if (Duration.between(soldAt, e.scanTime()).toDays() > config.returnWindowDays()) {
                        alerts.add(new Alert(AlertType.ABNORMAL_RETURN, MEDIUM, e.traceCode(), e, prev,
                                "售出%d天后退货，超出%d天合理退货窗口"
                                        .formatted(Duration.between(soldAt, e.scanTime()).toDays(),
                                                config.returnWindowDays())));
                        state = LifecycleState.AT_RETAIL;
                        holderOrg = e.orgId();
                        holderOrgName = e.orgName();
                        soldByOrg = null;
                        insuranceSold = false;
                        splitAccum = 0;
                    } else {
                        // 合法退货：回到在架状态，允许再次销售
                        state = LifecycleState.AT_RETAIL;
                        holderOrg = e.orgId();
                        holderOrgName = e.orgName();
                        soldByOrg = null;
                        insuranceSold = false;
                        splitAccum = 0;
                    }
                }
                case RETURN_TO_SUPPLIER -> {
                    if (state == LifecycleState.SOLD || state == LifecycleState.PARTIALLY_SOLD) {
                        alerts.add(reflow(e, prev, soldByOrgName, soldAt, insuranceSold,
                                "已销售药品退回供应商，典型回流路径"));
                    } else if (state != LifecycleState.AT_RETAIL) {
                        alerts.add(new Alert(AlertType.SEQUENCE_VIOLATION, MEDIUM, e.traceCode(), e, prev,
                                "非在架药品发生退供应商扫码"));
                    }
                    state = LifecycleState.IN_DISTRIBUTION;
                    holderOrg = null;
                    holderOrgName = null;
                }
                case DESTROY -> state = LifecycleState.DESTROYED;
            }

            // 异地瞬移：同一码在不可能的时间间隔内出现在两个地区，说明码被复制
            if (prev != null && prev.region() != null && e.region() != null
                    && !prev.region().equals(e.region())
                    && Duration.between(prev.scanTime(), e.scanTime()).toMinutes() < config.impossibleTravelMinutes()) {
                alerts.add(new Alert(AlertType.CODE_REUSE, HIGH, e.traceCode(), e, prev,
                        "追溯码%d分钟内在[%s]和[%s]先后出现，物理上不可能，疑似码被复制"
                                .formatted(Duration.between(prev.scanTime(), e.scanTime()).toMinutes(),
                                        prev.region(), e.region())));
            }
            prev = e;
        }
        return new ReplayResult(alerts, state, holderOrg);
    }

    /** 构造回流药告警：已退出流通的药品重新进入流通环节。 */
    private Alert reflow(ScanEvent e, ScanEvent prev, String soldByOrgName,
                         java.time.Instant soldAt, boolean insuranceSold, String what) {
        String insurance = insuranceSold ? "（医保结算）" : "";
        return new Alert(AlertType.REFLOW_DRUG, HIGH, e.traceCode(), e, prev,
                "%s：该药已于%s在[%s]售出%s，现又在[%s]出现%s扫码"
                        .formatted(what, soldAt, soldByOrgName, insurance, e.orgName(), e.eventType().label()));
    }
}
