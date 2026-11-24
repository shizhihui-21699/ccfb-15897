package com.traceguard.model;

/**
 * 一条监管告警。告警必须能回答"问题出在哪个环节"：
 * offendingEvent 是触发告警的扫码记录（哪个机构、什么时间、什么环节），
 * previousEvent 是与它冲突的上一条记录，两者合起来定位问题链路。
 */
public record Alert(
        AlertType type,
        Severity severity,
        String traceCode,
        ScanEvent offendingEvent,
        ScanEvent previousEvent,
        String message) {

    public enum Severity {HIGH, MEDIUM, LOW}

    @Override
    public String toString() {
        return "[%s][%s] 追溯码=%s | %s | 问题环节: %s/%s @%s (%s)".formatted(
                severity, type.label(), traceCode, message,
                offendingEvent.orgName(), offendingEvent.eventType().label(),
                offendingEvent.scanTime(), offendingEvent.region());
    }
}
