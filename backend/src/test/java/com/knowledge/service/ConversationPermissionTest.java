package com.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.entity.Conversation;
import com.knowledge.entity.ConversationMessage;
import com.knowledge.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationPermissionTest {
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final ConversationMessageRepository messages = mock(ConversationMessageRepository.class);
    final ConversationService service = new ConversationService(conversations, messages, new ObjectMapper());

    @Test void foreignConversationCannotBeReadOrWritten() {
        when(conversations.findById(5L)).thenReturn(Optional.of(Conversation.builder().id(5L).userId(2L).build()));
        assertThrows(AccessDeniedException.class, () -> service.addMessage(5L, 1L, "user", "injected", null));
        assertThrows(AccessDeniedException.class, () -> service.getMessages(5L, 1L));
        assertThrows(AccessDeniedException.class, () -> service.renameConversation(5L, 1L, "new"));
        assertThrows(AccessDeniedException.class, () -> service.deleteConversation(5L, 1L));
        verifyNoInteractions(messages);
        verify(conversations, never()).save(any());
    }
    @Test void ownerCanAppendAndMissingConversationIsDenied() {
        when(conversations.findById(5L)).thenReturn(Optional.of(Conversation.builder().id(5L).userId(1L).build()));
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        ConversationMessage message = service.addMessage(5L, 1L, "user", "ok", null);
        assertEquals("ok", message.getContent());
        assertThrows(AccessDeniedException.class, () -> service.addMessage(999L, 1L, "user", "bad", null));
        verify(messages, times(1)).save(any());
    }
}
