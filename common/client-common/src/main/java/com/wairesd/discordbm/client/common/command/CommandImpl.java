package com.wairesd.discordbm.client.common.command;

import com.wairesd.discordbm.api.command.Command;
import com.wairesd.discordbm.api.command.CommandOption;
import com.wairesd.discordbm.api.command.CommandCondition;

import java.util.ArrayList;
import java.util.List;

public class CommandImpl implements Command {
    
    private final String name;
    private final String description;
    private final String pluginName;
    private final String context;
    private final List<CommandOption> options;
    private final List<CommandCondition> conditions;

    private CommandImpl(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.pluginName = builder.pluginName;
        this.context = builder.context;
        this.options = builder.options != null ? builder.options : new ArrayList<>();
        this.conditions = builder.conditions != null ? List.copyOf(builder.conditions) : List.of();
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public String getPluginName() {
        return pluginName;
    }
    
    @Override
    public String getContext() {
        return context;
    }
    
    @Override
    public List<CommandOption> getOptions() {
        return options;
    }

    @Override
    public List<CommandCondition> getConditions() {
        return conditions;
    }

    public static class Builder implements Command.Builder {
        private String name;
        private String description;
        private String pluginName;
        private String context;
        private List<CommandOption> options;
        private List<CommandCondition> conditions = new ArrayList<>();
        
        @Override
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        @Override
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        @Override
        public Builder pluginName(String pluginName) {
            this.pluginName = pluginName;
            return this;
        }
        
        @Override
        public Builder context(String context) {
            this.context = context;
            return this;
        }
        
        @Override
        public Builder options(List<CommandOption> options) {
            this.options = options;
            return this;
        }
        
        @Override
        public Builder addCondition(CommandCondition condition) {
            this.conditions.add(condition);
            return this;
        }

        @Override
        public Builder conditions(List<CommandCondition> conditions) {
            this.conditions = new ArrayList<>(conditions);
            return this;
        }
        
        @Override
        public Command build() {
            return new CommandImpl(this);
        }
    }
} 