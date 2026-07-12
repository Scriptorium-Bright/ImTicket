package org.example.ticket.member.model;

public enum NoncePurpose {
    LOGIN("Sign in to ImTicket"),
    REGISTER("Register for ImTicket");

    private final String description;

    NoncePurpose(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
