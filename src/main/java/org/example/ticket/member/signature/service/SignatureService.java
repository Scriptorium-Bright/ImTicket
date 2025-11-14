package org.example.ticket.member.signature.service;

import lombok.extern.slf4j.Slf4j;
import org.example.ticket.member.signature.request.SignatureVerifyRequest;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
@Service
public class SignatureService {

    public boolean verifySignature(SignatureVerifyRequest verifyRequest) {
        return isValidSignature(
                verifyRequest.getWalletAddress(),
                getMessageHash(String.valueOf(verifyRequest.getMessage())),
                getSignatureData(verifyRequest.getSignature())
        );
    }

    private boolean isValidSignature(String walletAddress, byte[] msgHash, Sign.SignatureData sigData) {
        for (int i = 0; i < 4; i++) {
            BigInteger publicKey = Sign.recoverFromSignature(
                    (byte) i,
                    new ECDSASignature(
                            new BigInteger(1, sigData.getR()),
                            new BigInteger(1, sigData.getS())
                    ),
                    msgHash
            );
            if (publicKey != null) {
                String recoveredAddress = "0x" + Keys.getAddress(publicKey);
                if (recoveredAddress.equalsIgnoreCase(walletAddress)) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] getMessageHash(String message) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        String prefixedMessage = "\u0019Ethereum Signed Message:\n" + messageBytes.length + message;
        return Hash.sha3(prefixedMessage.getBytes(StandardCharsets.UTF_8));
    }

    private Sign.SignatureData getSignatureData(String signature) {
        byte[] signatureBytes = Numeric.hexStringToByteArray(signature);
        if (signatureBytes.length != 65) {
            throw new IllegalArgumentException("Invalid signature length: " + signatureBytes.length);
        }
        byte v = signatureBytes[64];
        if (v < 27) {
            v += 27;
        }
        return new Sign.SignatureData(
                v,
                Arrays.copyOfRange(signatureBytes, 0, 32),
                Arrays.copyOfRange(signatureBytes, 32, 64)
        );
    }
}