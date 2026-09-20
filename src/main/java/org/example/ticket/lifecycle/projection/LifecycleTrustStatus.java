package org.example.ticket.lifecycle.projection;

/** 조회 모델이 원천 상태와 대조된 정도를 나타낸다. */
public enum LifecycleTrustStatus {
    PROCESSING,
    CONSISTENT,
    INCOMPLETE,
    MISMATCH
}
