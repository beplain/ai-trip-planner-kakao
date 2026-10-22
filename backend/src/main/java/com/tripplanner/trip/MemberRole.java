package com.tripplanner.trip;

public enum MemberRole {
    OWNER, EDITOR, VIEWER;

    public boolean canEdit() {
        return this == OWNER || this == EDITOR;
    }
}
