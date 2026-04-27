package org.nvias.trashtalk.domain;

public enum ServerRole {
    OWNER,
    ADMINISTRATOR,
    VISITOR;

    public boolean isAtLeast(ServerRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
