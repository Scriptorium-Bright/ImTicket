package org.example.ticket.security.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.model.Member;
import org.example.ticket.member.repository.MemberRepository;
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

    @Override
    public void additionalAuthenticationChecks(UserDetails userDetails, UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {
        MetamaskAuthenticationToken token = (MetamaskAuthenticationToken) authentication;
        MetamaskUserDetails metamaskUserDetails = (MetamaskUserDetails) userDetails;

        SignatureVerifyRequest request =
                initSignatureVerifyRequest(authentication, token, metamaskUserDetails);

        if (!signatureService.verifySignature(request)) {
            throw new BadCredentialsException("Signature is not valid");
        }

    }

    private static SignatureVerifyRequest initSignatureVerifyRequest(UsernamePasswordAuthenticationToken authentication, MetamaskAuthenticationToken token, MetamaskUserDetails metamaskUserDetails) {
        return SignatureVerifyRequest.builder()
                .walletAddress(token.getAddress())
                .signature(authentication.getCredentials().toString())
                .message(String.valueOf(metamaskUserDetails.member().getNonce()))
                .build();
    }

    @Override
    public UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {

        MetamaskAuthenticationToken auth = (MetamaskAuthenticationToken) authentication;

        Member member = repository.findByWalletAddress(auth.getAddress())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with address : " + auth.getAddress()));

        MetamaskUserDetails metamaskUserDetails
                = fetchUsersData(member);

        log.info("나와야 하는 로그 : {}", metamaskUserDetails);
        return metamaskUserDetails;

    }

    @NotNull
    private static MetamaskUserDetails fetchUsersData(Member byWalletAddress) {

        return new MetamaskUserDetails(byWalletAddress);

    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(MetamaskAuthenticationToken.class);
    }
/*
    public boolean isSignatureValid(SignatureVerifyRequest request) {
        // Compose the message with nonce
        String message = "Signing a message to login: %s".formatted(request.getMessage());

        // Extract the ‘r’, ‘s’ and ‘v’ components
        byte[] signatureBytes = Numeric.hexStringToByteArray(request.getSignature());
        byte v = signatureBytes[64];
        if (v < 27) {
            v += 27;
        }
        byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
        byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
        Sign.SignatureData data = new Sign.SignatureData(v, r, s);

        // Retrieve public key
        BigInteger publicKey;
        try {
            publicKey = Sign.signedPrefixedMessageToKey(message.getBytes(), data);
        } catch (SignatureException e) {
            logger.debug("Failed to recover public key", e);
            return false;
        }

        // Get recovered address and compare with the initial address
        String recoveredAddress = "0x" + Keys.getAddress(publicKey);
        return request.getWalletAddress().equalsIgnoreCase(recoveredAddress);
    }*/
}
