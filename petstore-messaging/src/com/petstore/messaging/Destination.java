package com.petstore.messaging;

/**
 * A JMS destination — its name plus whether it is a topic (pub/sub, fan-out to
 * every subscriber) or a queue (point-to-point, one consumer). Encoding the kind
 * here means callers can't accidentally send a queue message to a topic or wire a
 * queue listener to a topic — the {@link MessagePublisher} and listener factories
 * read {@link #topic()} to route correctly.
 */
public record Destination(String name, boolean topic) {

    public static Destination queue(String name) {
        return new Destination(name, false);
    }

    public static Destination topic(String name) {
        return new Destination(name, true);
    }
}
