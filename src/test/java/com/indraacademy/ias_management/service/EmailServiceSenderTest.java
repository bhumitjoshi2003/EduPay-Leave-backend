package com.indraacademy.ias_management.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class EmailServiceSenderTest {
    @Mock JavaMailSender transport;
    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService();
        ReflectionTestUtils.setField(service, "javaMailSender", transport);
        ReflectionTestUtils.setField(service, "senderResolver", new EmailSenderResolver(
                "hello@edunexify.co.in", "notifications@edunexify.co.in",
                "fees@edunexify.co.in", "noreply@edunexify.co.in", "support@edunexify.co.in"));
    }

    @ParameterizedTest
    @MethodSource("senderIdentities")
    void generatedMessageContainsResolvedAddressAndDisplayName(
            EmailPurpose purpose, String expectedAddress, String expectedDisplayName) throws Exception {
        MimeMessage message = new JavaMailSenderImpl().createMimeMessage();
        when(transport.createMimeMessage()).thenReturn(message);

        service.sendHtmlEmail(purpose, "student@example.com", "Message", "<p>Body</p>");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(transport).send(sent.capture());
        InternetAddress from = (InternetAddress) sent.getValue().getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(expectedAddress);
        assertThat(from.getPersonal()).isEqualTo(expectedDisplayName);
        assertThat(sent.getValue().getHeader("Reply-To")).isNull();
    }

    private static Stream<Arguments> senderIdentities() {
        return Stream.of(
                Arguments.of(EmailPurpose.ONBOARDING, "hello@edunexify.co.in", "Edunexify"),
                Arguments.of(EmailPurpose.NOTIFICATION, "notifications@edunexify.co.in", "Edunexify Notifications"),
                Arguments.of(EmailPurpose.FEES, "fees@edunexify.co.in", "Edunexify Fees"),
                Arguments.of(EmailPurpose.SECURITY, "noreply@edunexify.co.in", "Edunexify Security"),
                Arguments.of(EmailPurpose.SUPPORT, "support@edunexify.co.in", "Edunexify Support"));
    }
}
