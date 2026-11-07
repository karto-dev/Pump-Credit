package com.petrolpump.credit.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Delivers one SMS / WhatsApp message. app.notify.provider picks the transport:
 * LOG (default) writes the message to the server log and marks it sent, for development and until a provider
 * is signed up; WEBHOOK POSTs {channel, to, text} as JSON to app.notify.webhook-url, which a small relay or a
 * provider's HTTP API (MSG91, Gupshup, Twilio...) turns into a real message.
 */
@Component
public class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);

    private final String provider;
    private final String webhookUrl;
    private final String webhookToken;
    private final RestClient http = RestClient.create();

    public MessageSender(@Value("${app.notify.provider:LOG}") String provider,
                         @Value("${app.notify.webhook-url:}") String webhookUrl,
                         @Value("${app.notify.webhook-token:}") String webhookToken) {
        this.provider = provider.toUpperCase();
        this.webhookUrl = webhookUrl;
        this.webhookToken = webhookToken;
    }

    /** Returns the provider's reference. Throws on failure; the worker records the error and retries. */
    public String send(String channel, String to, String text) {
        if ("WEBHOOK".equals(provider)) {
            if (webhookUrl.isBlank()) throw new IllegalStateException("app.notify.webhook-url is not set");
            var req = http.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("channel", channel, "to", to, "text", text));
            if (!webhookToken.isBlank()) req = req.header("Authorization", "Bearer " + webhookToken);
            String ref = req.retrieve().body(String.class);
            return ref == null || ref.isBlank() ? "webhook" : ref.length() > 100 ? ref.substring(0, 100) : ref;
        }
        log.info("[{}] to {}: {}", channel, mask(to), text);
        return "log";
    }

    public String provider() {
        return provider;
    }

    private static String mask(String phone) {
        return phone.length() <= 4 ? phone : "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
