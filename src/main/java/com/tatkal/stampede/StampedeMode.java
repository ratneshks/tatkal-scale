package com.tatkal.stampede;

public enum StampedeMode {
    /** Every thread wants the SAME seat (worst-case hot row). */
    SAME_SEAT,
    /** Every thread wants ANY free seat (flash sale of the last N tickets). */
    ANY_SEAT
}
