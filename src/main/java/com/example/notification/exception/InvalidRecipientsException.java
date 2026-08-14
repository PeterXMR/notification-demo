package com.example.notification.exception;

import java.util.List;

/**
 * Any unknown or inactive recipient rejects the whole campaign (all-or-nothing):
 * partial acceptance would make the reported "total" ambiguous and hide caller typos.
 */
public class InvalidRecipientsException extends RuntimeException {

    private final List<String> unknown;
    private final List<String> inactive;

    public InvalidRecipientsException(List<String> unknown, List<String> inactive) {
        super("Campaign rejected: unknown=" + unknown + ", inactive=" + inactive);
        this.unknown = List.copyOf(unknown);
        this.inactive = List.copyOf(inactive);
    }

    public List<String> getUnknown() {
        return unknown;
    }

    public List<String> getInactive() {
        return inactive;
    }
}
