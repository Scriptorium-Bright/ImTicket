package org.example.ticket.security.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.model.NoncePurpose;
import org.example.ticket.member.repository.MemberRepository;
import org.example.ticket.member.service.MemberService;
import org.example.ticket.member.signature.request.SignatureVerifyRequest;
import org.example.ticket.member.signature.service.SignatureService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.example.ticket.security.token.MetamaskAuthenticationToken;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetamaskAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

    private final SignatureService signatureService;
    private final MemberRepository repository;
    private final MemberService memberService;

    @Override
    public void additionalAuthenticationChecks(UserDetails userDetails, UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {
        MetamaskAuthenticationToken token = (MetamaskAuthenticationToken) authentication;
        MetamaskUserDetails metamaskUserDetails = (MetamaskUserDetails) userDetails;

        String expectedMessage = getExpectedLoginMessage(metamaskUserDetails.member());
        SignatureVerifyRequest request =
                initSignatureVerifyRequest(authentication, token, expectedMessage);

        if (!signatureService.verifySignature(request)) {
            throw new BadCredentialsException("Signature is not valid");
        }

        if (memberService.consumeNonce(token.getAddress(), metamaskUserDetails.member().getNonce(), NoncePurpose.LOGIN)) {
            throw new BadCredentialsException("Nonce challenge is expired or already used");
        }

    }

    private String getExpectedLoginMessage(Member member) {
        try {
            return memberService.buildChallengeMessage(member, NoncePurpose.LOGIN);
        } catch (IllegalStateException e) {
            throw new BadCredentialsException(e.getMessage());
        }
    }

    private static SignatureVerifyRequest initSignatureVerifyRequest(UsernamePasswordAuthenticationToken authentication, MetamaskAuthenticationToken token, String expectedMessage) {
        return SignatureVerifyRequest.builder()
                .walletAddress(token.getAddress())
                .signature(authentication.getCredentials().toString())
                .message(expectedMessage)
                .build();
    }

    @Override
    public UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {

        MetamaskAuthenticationToken auth = (MetamaskAuthenticationToken) authentication;

        Member member = repository.findByWalletAddressIgnoreCase(auth.getAddress())
                .filter(Member::isRegistered)
                .orElseThrow(() -> new UsernameNotFoundException("Registered user not found with address : " + auth.getAddress()));

        return fetchUsersData(member);

    }

    @NotNull
    private static MetamaskUserDetails fetchUsersData(Member byWalletAddress) {

        return new MetamaskUserDetails(byWalletAddress);

    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(MetamaskAuthenticationToken.class);
    }
}
