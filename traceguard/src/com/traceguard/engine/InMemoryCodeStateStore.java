package com.traceguard.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现，用于单机部署、演示和单元测试。 */
public class InMemoryCodeStateStore implements CodeStateStore {

    private final Map<String, TraceCodeState> states = new ConcurrentHashMap<>();

    @Override
    public TraceCodeState getOrCreate(String traceCode) {
        return states.computeIfAbsent(traceCode, TraceCodeState::new);
    }

    @Override
    public void put(TraceCodeState state) {
        states.put(state.traceCode, state);
    }
}
