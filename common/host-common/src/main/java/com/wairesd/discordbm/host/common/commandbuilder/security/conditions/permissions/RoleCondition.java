package com.wairesd.discordbm.host.common.commandbuilder.security.conditions.permissions;

import com.wairesd.discordbm.host.common.commandbuilder.core.models.conditions.CommandCondition;
import com.wairesd.discordbm.host.common.commandbuilder.core.models.context.Context;

import java.util.Map;

public class RoleCondition implements CommandCondition {
    private final String requiredRoleId;

    public RoleCondition(Map<String, Object> properties) {
        this.requiredRoleId = (String) properties.getOrDefault("role_id", "");
        if (this.requiredRoleId.isEmpty()) {
            throw new IllegalArgumentException("Role ID property is required for PermissionCondition");
        }
    }

    @Override
    public boolean check(Context context) {
        if (context == null || context.getEvent() == null) {
            return false;
        }
        var member = context.getEvent().getMember();
        return member != null && member.getRoles().stream()
                .anyMatch(role -> role.getId().equals(requiredRoleId));
    }

    public String getRequiredRoleId() {
        return requiredRoleId;
    }
}
