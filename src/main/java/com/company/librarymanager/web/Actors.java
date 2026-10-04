package com.company.librarymanager.web;

import com.company.librarymanager.domain.User;
import com.company.librarymanager.security.AppUserPrincipal;

/**
 * Turns a signed-in identity into the detached {@link User} the audit trail
 * expects.
 *
 * <p>Audit rows are written with {@code MANDATORY} propagation inside the
 * caller's transaction, so the actor has to be attached to that transaction.
 * Handing over a real {@code User} loaded from the database would be wrong here:
 * it would arrive attached, be flushed at the end of someone else's transaction
 * and, worse, be written back with whatever stale fields it carried. A fresh
 * instance holding only the identity avoids both.
 */
final class Actors {

    private Actors() {
    }

    static User of(AppUserPrincipal principal) {
        if (principal == null) {
            return null;
        }
        User user = new User();
        user.setId(principal.id());
        user.setName(principal.name());
        user.setEmail(principal.email());
        user.setRole(principal.role());
        user.setActive(principal.isEnabled());
        return user;
    }
}
