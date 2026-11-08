package com.mohit.screenbook.auth;

import com.mohit.screenbook.common.Emails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * An account. No roles column: there is one kind of user and inventing an
 * authority model for a system with nothing to authorise would be decoration.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /* Lowercase, enforced by a CHECK in V10. Normalising here and guaranteeing
       it there is the same split as the booking reference shape. */
    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public static AppUser of(String email, String passwordHash, String displayName, Instant now) {
        AppUser user = new AppUser();
        user.email = Emails.normalise(email);
        user.passwordHash = passwordHash;
        user.displayName = displayName.trim();
        user.createdAt = now;
        return user;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
}
