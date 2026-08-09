package org.example.ticket.security.principal;

import org.example.ticket.member.model.Member;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetamaskUserDetailsTest {

    @Test
    void exposesPositiveAuthenticatedMemberId() {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .id(42L)
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());

        assertThat(principal.getMemberId()).isEqualTo(42L);
    }

    @Test
    void rejectsPrincipalWithoutPersistedMemberId() {
        MetamaskUserDetails principal = new MetamaskUserDetails(Member.builder()
                .walletAddress("0xowner")
                .role("ROLE_USER")
                .build());

        assertThatThrownBy(principal::getMemberId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memberId");
    }
}
