package org.example.ticket.lifecycle.projection;

/** 사건이 조회 모델에 반영된 상태다. */
public enum LifecycleApplicationStatus {
    PENDING,
    APPLIED,
    DUPLICATE,
    FAILED
}
