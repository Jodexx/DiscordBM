package com.wairesd.discordbm.client.common.network;

import com.wairesd.discordbm.common.models.response.ResponseMessage;
import com.wairesd.discordbm.common.models.response.ResponseFlags;
public class ResponseTypeDetector {

    public static ResponseType determineResponseType(ResponseMessage respMsg) {
        if (respMsg == null) {
            return ResponseType.REPLY;
        }

        String messageType = respMsg.type();
        if (messageType != null) {
            switch (messageType.toLowerCase()) {
                case "direct_message":
                    return ResponseType.DIRECT;
                case "channel_message":
                    return ResponseType.CHANNEL;
                case "edit_message":
                    return ResponseType.EDIT_MESSAGE;
                case "form":
                    return ResponseType.MODAL;
                case "random_reply":
                    return ResponseType.RANDOM_REPLY;
                case "reply_to_message":
                    return ResponseType.REPLY_TO_MESSAGE;
            }
        }

        ResponseFlags flags = respMsg.flags();
        if (flags != null) {
            if (flags.requiresModal()) {
                return ResponseType.MODAL;
            }
            if (flags.isFormResponse()) {
                return ResponseType.REPLY_MODAL;
            }
        }

        if (respMsg.modal() != null) {
            return ResponseType.MODAL;
        }

        if (respMsg.userId() != null && !respMsg.userId().isEmpty()) {
            return ResponseType.DIRECT;
        }

        if (respMsg.channelId() != null && !respMsg.channelId().isEmpty()) {
            return ResponseType.CHANNEL;
        }

        if (flags != null && flags.getResponseType() != null) {
            try {
                return ResponseType.valueOf(flags.getResponseType().toUpperCase());
            } catch (IllegalArgumentException e) {
            }
        }

        return ResponseType.REPLY;
    }

    public static ResponseFlags updateFlagsForResponseType(ResponseMessage respMsg, ResponseType responseType) {
        ResponseFlags.Builder flagsBuilder = new ResponseFlags.Builder();
        
        if (respMsg.flags() != null) {
            flagsBuilder
                .preventMessageSend(respMsg.flags().shouldPreventMessageSend())
                .isFormResponse(respMsg.flags().isFormResponse())
                .requiresModal(respMsg.flags().requiresModal())
                .ephemeral(respMsg.flags().isEphemeral());
        }

        switch (responseType) {
            case MODAL:
                flagsBuilder
                    .requiresModal(true)
                    .preventMessageSend(true)
                    .isFormResponse(true);
                break;
            case REPLY_MODAL:
                flagsBuilder
                    .isFormResponse(true)
                    .requiresModal(false);
                break;
            case EDIT_MESSAGE:
                flagsBuilder
                    .preventMessageSend(false)
                    .isFormResponse(false)
                    .requiresModal(false);
                break;
            case DIRECT:
            case CHANNEL:
                flagsBuilder
                    .preventMessageSend(false)
                    .isFormResponse(false)
                    .requiresModal(false);
                break;
            case REPLY_TO_MESSAGE:
                flagsBuilder
                    .preventMessageSend(false)
                    .isFormResponse(false)
                    .requiresModal(false);
                break;
            case REPLY:
            default:
                flagsBuilder
                    .preventMessageSend(false)
                    .isFormResponse(false)
                    .requiresModal(false);
                break;
        }

        flagsBuilder.responseType(responseType.name());
        return flagsBuilder.build();
    }

    public enum ResponseType {
        REPLY,
        EDIT_MESSAGE,
        MODAL,
        REPLY_MODAL,
        DIRECT,
        CHANNEL,
        RANDOM_REPLY,
        REPLY_TO_MESSAGE
    }
} 