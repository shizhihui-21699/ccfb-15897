package com.traceguard.demo;

import com.traceguard.engine.InMemoryCodeStateStore;
import com.traceguard.engine.RuleConfig;
import com.traceguard.engine.TraceCodeEngine;
import com.traceguard.model.Alert;
import com.traceguard.model.AlertType;
import com.traceguard.model.EventType;
import com.traceguard.model.OrgType;
import com.traceguard.model.ScanEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.traceguard.model.EventType.*;
import static com.traceguard.model.OrgType.*;

/**
 * 用 6 个典型场景验证引擎：正常流通不报警，异常链路报警且能定位到问题环节。
 * 运行：java -cp out com.traceguard.demo.Demo
 */
public class Demo {

    private static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        normalChainNoAlert();
        reflowAfterInsuranceSale();
        oneCodeSoldTwice();
        splitSaleOverflow();
        legitReturnThenResale();
        abnormalReturnFromOtherStore();

        System.out.println("%n========================================%n结果: %d 通过, %d 失败".formatted(passed, failed));
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 场景1：生产→批发→药店→医保销售，合法链路应零告警。 */
    static void normalChainNoAlert() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-NORMAL-001";
        List<Alert> all = new ArrayList<>();
        all.addAll(engine.process(ev("e1", code, 0, "M1", "制药厂A", MANUFACTURER, "杭州", PRODUCE_SHIP, false)));
        all.addAll(engine.process(ev("e2", code, 1, "D1", "医药批发B", DISTRIBUTOR, "杭州", DISTRIBUTOR_IN, false)));
        all.addAll(engine.process(ev("e3", code, 2, "D1", "医药批发B", DISTRIBUTOR, "杭州", DISTRIBUTOR_OUT, false)));
        all.addAll(engine.process(ev("e4", code, 3, "P1", "老百姓大药房", PHARMACY, "宁波", RETAIL_IN, false)));
        all.addAll(engine.process(ev("e5", code, 10, "P1", "老百姓大药房", PHARMACY, "宁波", SALE, true)));
        expect("正常流通链路(含医保销售)", all, List.of());
    }

    /** 场景2：医保结算售出后，药品又出现在另一家药店入库 → 回流药，定位到入库药店。 */
    static void reflowAfterInsuranceSale() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-REFLOW-002";
        feedNormalChain(engine, code);
        List<Alert> alerts = engine.process(
                ev("r1", code, 20, "P9", "某小药店", PHARMACY, "温州", RETAIL_IN, false));
        expect("医保售出后异地药店重新入库", alerts, List.of(AlertType.REFLOW_DRUG));
    }

    /** 场景3：药店A在架的药，药店B却完成了销售 → 一码多卖/扫真码卖假药。 */
    static void oneCodeSoldTwice() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-FAKE-003";
        engine.process(ev("f1", code, 0, "M1", "制药厂A", MANUFACTURER, "杭州", PRODUCE_SHIP, false));
        engine.process(ev("f2", code, 1, "P1", "药店甲", PHARMACY, "杭州", RETAIL_IN, false));
        engine.process(ev("f3", code, 2, "P1", "药店甲", PHARMACY, "杭州", SALE, true));
        List<Alert> alerts = engine.process(
                ev("f4", code, 3, "P2", "药店乙", PHARMACY, "金华", SALE, true));
        expect("同一追溯码在两家药店各卖一次", alerts, List.of(AlertType.CODE_REUSE));
    }

    /** 场景4：每盒10单位，拆零卖了14单位 → 超出的4单位是拿真码卖的假药。 */
    static void splitSaleOverflow() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-SPLIT-004";
        engine.process(ev("s1", code, 0, "M1", "制药厂A", MANUFACTURER, "杭州", PRODUCE_SHIP, false));
        engine.process(ev("s2", code, 1, "P1", "社区药店", PHARMACY, "杭州", RETAIL_IN, false));
        engine.process(ScanEvent.split("s3", code, hours(2), "P1", "社区药店", PHARMACY, "杭州", 5, 10, true));
        engine.process(ScanEvent.split("s4", code, hours(3), "P1", "社区药店", PHARMACY, "杭州", 5, 10, true));
        List<Alert> alerts = engine.process(
                ScanEvent.split("s5", code, hours(4), "P1", "社区药店", PHARMACY, "杭州", 4, 10, true));
        expect("拆零累计14单位超过每盒10单位", alerts, List.of(AlertType.CODE_REUSE));
    }

    /** 场景5：售出→患者在同店退货→再次销售，合法退货链路应零告警。 */
    static void legitReturnThenResale() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-RETURN-005";
        feedNormalChain(engine, code);
        List<Alert> all = new ArrayList<>();
        all.addAll(engine.process(ev("t1", code, 12, "P1", "老百姓大药房", PHARMACY, "宁波", PATIENT_RETURN, false)));
        all.addAll(engine.process(ev("t2", code, 15, "P1", "老百姓大药房", PHARMACY, "宁波", SALE, false)));
        expect("同店退货后再销售", all, List.of());
    }

    /** 场景6：在药店甲售出的药，却在药店乙被"退货" → 回收药伪装退货洗白。 */
    static void abnormalReturnFromOtherStore() {
        TraceCodeEngine engine = newEngine();
        String code = "CODE-RETURN-006";
        feedNormalChain(engine, code);
        List<Alert> alerts = engine.process(
                ev("x1", code, 12, "P9", "某小药店", PHARMACY, "温州", PATIENT_RETURN, false));
        expect("非原销售门店接收退货", alerts, List.of(AlertType.ABNORMAL_RETURN));
    }

    // ------------------------------------------------------------------

    static TraceCodeEngine newEngine() {
        return new TraceCodeEngine(new InMemoryCodeStateStore(), RuleConfig.defaults());
    }

    /** 正常链路：生产→批发进出→药店入库→医保销售。 */
    static void feedNormalChain(TraceCodeEngine engine, String code) {
        engine.process(ev("n1", code, 0, "M1", "制药厂A", MANUFACTURER, "杭州", PRODUCE_SHIP, false));
        engine.process(ev("n2", code, 1, "D1", "医药批发B", DISTRIBUTOR, "杭州", DISTRIBUTOR_IN, false));
        engine.process(ev("n3", code, 2, "D1", "医药批发B", DISTRIBUTOR, "杭州", DISTRIBUTOR_OUT, false));
        engine.process(ev("n4", code, 3, "P1", "老百姓大药房", PHARMACY, "宁波", RETAIL_IN, false));
        engine.process(ev("n5", code, 10, "P1", "老百姓大药房", PHARMACY, "宁波", SALE, true));
    }

    static ScanEvent ev(String id, String code, int plusHours, String orgId, String orgName,
                        OrgType orgType, String region, EventType type, boolean insurance) {
        return ScanEvent.of(id, code, hours(plusHours), orgId, orgName, orgType, region, type, insurance);
    }

    static Instant hours(long h) {
        return T0.plus(h, ChronoUnit.HOURS);
    }

    static void expect(String scenario, List<Alert> actual, List<AlertType> expectedTypes) {
        List<AlertType> actualTypes = actual.stream().map(Alert::type).toList();
        boolean ok = actualTypes.equals(expectedTypes);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println("%s %s%n   期望告警: %s%n   实际告警: %s".formatted(
                ok ? "✓" : "✗", scenario, expectedTypes, actualTypes));
        actual.forEach(a -> System.out.println("     → " + a));
    }
}
