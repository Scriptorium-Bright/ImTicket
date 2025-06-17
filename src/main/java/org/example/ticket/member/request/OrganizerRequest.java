package org.example.ticket.member.request;

import lombok.*;
import org.example.ticket.util.constant.OrganizerType;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrganizerRequest {

    private OrganizerType organizerType;
    private String organizerName;
    private String address;
    private String contactEmail;
    private String businessNumber;

}

