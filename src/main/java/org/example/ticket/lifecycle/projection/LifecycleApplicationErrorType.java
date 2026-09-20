package org.example.ticket.lifecycle.projection;

/** 사건 적용 실패를 자동 재시도할 수 있는지 구분한다. */
public enum LifecycleApplicationErrorType {
    NONE,
    TRANSIENT,
    CONTRACT
}
