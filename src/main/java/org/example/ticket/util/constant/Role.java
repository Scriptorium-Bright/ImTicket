package org.example.ticket.util.constant;

public enum Role {

    USER("ROLE_USER"),
    ORGANIZER("USER_ORGANIZER");

    public final String role;

    Role(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

}
