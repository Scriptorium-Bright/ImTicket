package org.example.ticket.member.request;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;
import org.example.ticket.util.constant.organizerType;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrganizerRequest {

    private String walletAddress;
    private organizerType organizerType;
    private String organizerName;
    private String address;
    private String contactEmail;
    private String businessNumber;
    private String contactPhone;

}

