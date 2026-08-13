package org.example.ticket.reservation.waitingroom.pass;

/** Waiting Room entry pass의 발급과 검증을 추상화한다. */
public interface WaitingRoomPassCodec {

    /** 검증된 admitted ticket claim으로 서명된 pass를 발급한다.
     * 발급 결과는 seat map과 pre-reserve 요청의 header로 전달된다. */
    String issue(WaitingRoomPassClaims claims);

    /** 외부 pass를 검증하고 claim으로 복원한다.
     * 서명 오류와 형식 오류는 구현체가 동일한 검증 실패로 처리한다. */
    WaitingRoomPassClaims parse(String token);
}
