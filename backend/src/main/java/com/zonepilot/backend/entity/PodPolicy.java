package com.zonepilot.backend.entity;

import com.zonepilot.backend.exception.ValidationException;

/** Per-stop proof-of-delivery gate enforced server-side in TourService.deliver. */
public enum PodPolicy {
    NONE,
    PIN,
    PHOTO,
    SIG;

    public static PodPolicy from(String name) {
        if (name == null) {
            return NONE;
        }
        try {
            return PodPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown pod policy '" + name + "'");
        }
    }
}
