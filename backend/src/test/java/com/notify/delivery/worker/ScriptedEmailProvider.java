package com.notify.delivery.worker;

import com.notify.delivery.model.DeliveryResult;
import com.notify.delivery.provider.EmailProvider;
import com.notify.delivery.provider.EmailSendRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test double for the email provider SPI. Records every send and answers from a script, so integration tests control
 * (and can block) the provider call without any network or credentials. It is the {@code @Primary} provider in tests
 * that import {@link ScriptedProviderConfig}.
 */
public class ScriptedEmailProvider implements EmailProvider {

    private final List<EmailSendRequest> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Function<EmailSendRequest, DeliveryResult> script = ScriptedEmailProvider::defaultSuccess;

    private static DeliveryResult defaultSuccess(EmailSendRequest request) {
        return new DeliveryResult.Success("scripted_" + request.idempotencyKey());
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public DeliveryResult send(EmailSendRequest request) {
        sent.add(request);
        calls.incrementAndGet();
        return script.apply(request);
    }

    public void script(Function<EmailSendRequest, DeliveryResult> script) {
        this.script = script;
    }

    public void reset() {
        sent.clear();
        calls.set(0);
        script = ScriptedEmailProvider::defaultSuccess;
    }

    public int calls() {
        return calls.get();
    }

    public List<EmailSendRequest> sent() {
        return List.copyOf(sent);
    }

    /** Number of sends whose idempotency key starts with the given notification id. */
    public long callsFor(java.util.UUID notificationId) {
        return sent.stream().filter(r -> r.idempotencyKey().startsWith(notificationId.toString())).count();
    }
}
