package com.indraacademy.ias_management.config;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentryConfigTest {
    @Test
    void scrubRemovesSensitiveDataAndKeepsOnlyAllowlistedTags() {
        SentryEvent event = new SentryEvent(new RuntimeException("student private detail"));
        Request request = new Request();
        request.setData("raw request body");
        event.setRequest(request);
        User user = new User();
        user.setEmail("person@example.com");
        event.setUser(user);
        event.setExtra("token", "secret");
        event.getContexts().put("aiPrompt", "private prompt");
        Message message = new Message();
        message.setMessage("private failure");
        event.setMessage(message);
        SentryException exception = new SentryException();
        exception.setType("RuntimeException");
        exception.setValue("private exception detail");
        event.setExceptions(List.of(exception));
        event.setTag("service", "backend");
        event.setTag("request_id", "client_req-12345");
        event.setTag("studentId", "S1");

        SentryEvent scrubbed = new SentryConfig().scrub(event, new Hint());

        assertThat(scrubbed.getRequest()).isNull();
        assertThat(scrubbed.getUser()).isNull();
        assertThat(scrubbed.getThrowable()).isNull();
        assertThat(scrubbed.getExtras()).isEmpty();
        assertThat(scrubbed.getContexts().isEmpty()).isTrue();
        assertThat(scrubbed.getTags()).containsOnlyKeys("service", "request_id");
        assertThat(scrubbed.getMessage().getMessage()).isEqualTo("Unexpected application error");
        assertThat(scrubbed.getExceptions().getFirst().getValue()).isEqualTo("Unexpected application error");
    }
}
