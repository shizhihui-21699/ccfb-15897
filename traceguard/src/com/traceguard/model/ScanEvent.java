package com.traceguard.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条追溯码扫码记录。
 *
 * @param eventId          事件唯一 ID（用于幂等去重，防止上报重试造成误判）
 * @param traceCode        药品追溯码（一盒一码）
 * @param scanTime         扫码时间（事件时间，不是入库时间）
 * @param orgId            扫码机构 ID
 * @param orgName          扫码机构名称（告警定位用）
 * @param orgType          机构类型
 * @param region           扫码发生地（市/区县，用于异地瞬移检测）
 * @param eventType        扫码环节
 * @param quantity         数量（拆零销售时为最小单位数，其余场景为 1）
 * @param insuranceSettled 本事件是否走了医保结算
 * @param unitsPerBox      每盒含最小单位数（仅拆零场景需要，0 表示未知，用规则默认值）
 */
public record ScanEvent(
        String eventId,
        String traceCode,
        Instant scanTime,
        String orgId,
        String orgName,
        OrgType orgType,
        String region,
        EventType eventType,
        int quantity,
        boolean insuranceSettled,
        int unitsPerBox) {

    public ScanEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(traceCode, "traceCode");
        Objects.requireNonNull(scanTime, "scanTime");
        Objects.requireNonNull(eventType, "eventType");
        if (quantity <= 0) {
            quantity = 1;
        }
    }

    /** 非拆零事件的便捷构造。 */
    public static ScanEvent of(String eventId, String traceCode, Instant scanTime,
                               String orgId, String orgName, OrgType orgType, String region,
                               EventType eventType, boolean insuranceSettled) {
        return new ScanEvent(eventId, traceCode, scanTime, orgId, orgName, orgType, region,
                eventType, 1, insuranceSettled, 0);
    }

    /** 拆零事件的便捷构造。 */
    public static ScanEvent split(String eventId, String traceCode, Instant scanTime,
                                  String orgId, String orgName, OrgType orgType, String region,
                                  int quantity, int unitsPerBox, boolean insuranceSettled) {
        return new ScanEvent(eventId, traceCode, scanTime, orgId, orgName, orgType, region,
                EventType.SPLIT_SALE, quantity, insuranceSettled, unitsPerBox);
    }
}
