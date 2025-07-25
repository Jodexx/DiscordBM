package com.wairesd.discordbm.host.common.commandbuilder.security.buttons.checker;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public class RoleChecker {
    public boolean hasPermission(ButtonInteractionEvent event, String requiredRoleId) {
        if (requiredRoleId == null) return true;
        return event.getMember().getRoles().stream()
                .anyMatch(role -> role.getId().equals(requiredRoleId));
    }
}
