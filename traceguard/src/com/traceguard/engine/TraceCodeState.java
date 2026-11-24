package com.traceguard.engine;

import com.traceguard.model.LifecycleState;
import com.traceguard.model.ScanEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个追溯码的累计状态。生产环境中它放在 Flink keyed state / Redis 里，
 * 以 traceCode 为 key，随药品有效期设置 TTL。
 */
public class TraceCodeState {

    public final String traceCode;
    /** 按扫码时间排序的完整历史（一盒药通常 < 50 条，回放代价极小）。 */
    public final List<ScanEvent> history = new ArrayList<>();
    /** 已处理事件 ID，用于幂等去重。 */
    public final Set<String> seenEventIds = new HashSet<>();
    /** 已作为"问题环节"告警过的事件 ID，避免乱序重放时重复告警。 */
    public final Set<String> alertedEventIds = new HashSet<>();
    /** 最近一次回放推导出的生命周期状态，供监管端直接查询。 */
    public LifecycleState lifecycle = LifecycleState.UNKNOWN;
    /** 当前持货机构（最后一条入库/退货记录的机构）。 */
    public String currentHolderOrgId;

    public TraceCodeState(String traceCode) {
        this.traceCode = traceCode;
    }
}
