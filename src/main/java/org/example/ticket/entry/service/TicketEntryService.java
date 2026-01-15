package org.example.ticket.entry.service;

import lombok.RequiredArgsConstructor;
import org.example.ticket.entry.model.EntryLog;
import org.example.ticket.entry.repository.EntryLogRepository;
import org.example.ticket.reservation.model.Reservation;
import org.example.ticket.reservation.repository.ReservationRepository;
import org.example.ticket.util.constant.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class TicketEntryService {

    private final ReservationRepository reservationRepository;
    private final EntryLogRepository entryLogRepository;

    // In a real app, this should be in properties
    private static final String SECRET_KEY = "my-secret-entry-key-change-me-in-prod";
    private static final long TOKEN_VALIDITY_MS = 60000; // 1 minute validity for dynamic QR

    public String generateEntryToken(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));

        if (reservation.getReservationStatus() != ReservationStatus.SUCCESS) {
            throw new IllegalStateException("Ticket is not valid for entry (Status: " + reservation.getReservationStatus() + ")");
        }

        long timestamp = System.currentTimeMillis();
        String data = reservationId + ":" + timestamp;
        String signature = sign(data);

        return Base64.getEncoder().encodeToString((data + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public void verifyEntry(String token, String gateName) {
        try {
            String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid token format");
            }

            Long reservationId = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);
            String receivedSignature = parts[2];

            // 1. Verify Signature
            String expectedSignature = sign(reservationId + ":" + timestamp);
            if (!expectedSignature.equals(receivedSignature)) {
                throw new IllegalArgumentException("Invalid signature");
            }

            // 2. Verify Expiration (Dynamic QR)
            if (System.currentTimeMillis() - timestamp > TOKEN_VALIDITY_MS) {
                throw new IllegalArgumentException("QR Code expired. Please refresh.");
            }

            // 3. Verify Reservation Status
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));

            if (reservation.getReservationStatus() != ReservationStatus.SUCCESS) {
                throw new IllegalStateException("Ticket is not valid for entry");
            }

            // 4. Check Duplicate Entry
            if (entryLogRepository.existsByReservation(reservation)) {
                throw new IllegalStateException("Already entered ticket");
            }

            // 5. Record Entry
            EntryLog entryLog = EntryLog.builder()
                    .reservation(reservation)
                    .gateName(gateName)
                    .enteredAt(LocalDateTime.now())
                    .build();
            entryLogRepository.save(entryLog);

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid token data");
        }
    }

    private String sign(String data) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            return Base64.getEncoder().encodeToString(sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Error signing token", e);
        }
    }
}
