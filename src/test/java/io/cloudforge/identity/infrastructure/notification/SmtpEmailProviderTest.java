package io.cloudforge.identity.infrastructure.notification;

import io.cloudforge.identity.application.port.NotificationDeliveryException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpEmailProviderTest {
    @Test void sendsRealSmtpMessageToLocalCaptureWithoutLoggingContent(CapturedOutput output) throws Exception {
        try (var capture = new LocalSmtpCapture(false)) {
            var sender = sender(capture.port());
            var provider = new SmtpEmailProvider(sender, "127.0.0.1");
            provider.sendActivationEmail("fixture@example.test", "731942");
            var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                    capture.message().getBytes(StandardCharsets.UTF_8)));
            assertEquals("fixture@example.test", message.getAllRecipients()[0].toString());
            assertEquals("no-reply@cloudforge.test", message.getFrom()[0].toString());
            assertTrue(message.getContent().toString().contains("731942"));
            assertFalse(output.getAll().contains("731942"));
            assertFalse(output.getAll().contains("fixture@example.test"));
        }
    }
    @Test void smtpRejectionDoesNotLeakServerResponseOrMessage(CapturedOutput output) throws Exception {
        try (var capture = new LocalSmtpCapture(true)) {
            var provider = new SmtpEmailProvider(sender(capture.port()), "127.0.0.1");
            var failure = assertThrows(NotificationDeliveryException.class,
                    () -> provider.sendActivationEmail("fixture@example.test", "731942"));
            assertNull(failure.getCause());
            assertEquals("Notification delivery unavailable", failure.getMessage());
            assertFalse(output.getAll().contains("PRIVATE_SMTP_SENTINEL"));
            assertFalse(output.getAll().contains("731942"));
        }
    }
    @Test void refusedConnectionIsSafeAndDoesNotSendExternally() throws Exception {
        // Reserve a local port without listening, so the connection is refused.
        try (var socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            var provider = new SmtpEmailProvider(sender(socket.getLocalPort()), "127.0.0.1");
            assertThrows(NotificationDeliveryException.class,
                    () -> provider.sendActivationEmail("fixture@example.test", "731942"));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"smtp.example.org", "192.0.2.1"})
    void refusesExternalSmtpHosts(String host) {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEmailProvider(sender(1025), host));
    }
    @Test void productionAdapterCannotSend() {
        assertThrows(NotificationDeliveryException.class,
                () -> new UnavailableEmailProvider().sendActivationEmail("fixture@example.test", "731942"));
    }
    private JavaMailSenderImpl sender(int port) {
        var sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "1000");
        sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "1000");
        sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "1000");
        sender.getJavaMailProperties().setProperty("mail.debug", "false");
        return sender;
    }
    /** Captures real SMTP messages on loopback without an external provider. */
    private static class LocalSmtpCapture implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<String> captured;
        LocalSmtpCapture(boolean reject) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setSoTimeout(3000);
            captured = executor.submit(() -> {
                try (Socket client = server.accept();
                     var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    client.setSoTimeout(3000);
                    writer.print("220 localhost fixture\r\n"); writer.flush();
                    StringBuilder body = new StringBuilder();
                    boolean data = false;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (data && !line.equals(".")) { body.append(line).append("\r\n"); continue; }
                        if (data) { data = false; writer.print(reject ? "451 PRIVATE_SMTP_SENTINEL\r\n" : "250 captured\r\n"); }
                        else if (line.equals("DATA")) { data = true; writer.print("354 send data\r\n"); }
                        else if (line.equals("QUIT")) { writer.print("221 bye\r\n"); writer.flush(); break; }
                        else writer.print("250 localhost\r\n");
                        writer.flush();
                    }
                    return body.toString();
                }
            });
        }
        int port() { return server.getLocalPort(); }
        String message() throws Exception { return captured.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws IOException { server.close(); executor.shutdownNow(); }
    }
}
