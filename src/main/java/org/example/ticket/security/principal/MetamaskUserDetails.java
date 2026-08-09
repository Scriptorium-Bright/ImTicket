package org.example.ticket.security.principal;

import org.example.ticket.member.model.Member;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;


public record MetamaskUserDetails(Member member) implements UserDetails {

    public MetamaskUserDetails {
        Objects.requireNonNull(member, "member must not be null");
    }

    public long getMemberId() {
        Long memberId = member.getId();
        if (memberId == null || memberId <= 0) {
            throw new IllegalStateException("Authenticated memberId must be positive");
        }
        return memberId;
    }

    public String getAddress() {
        return getUsername();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Collection<GrantedAuthority> collection = new ArrayList<>();

        collection.add((GrantedAuthority) member::getRole);

        return collection;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return member().getWalletAddress();
    }

}
