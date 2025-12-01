package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.spec.McpSchema;

public sealed interface Notification permits Notification.Progress, Notification.Ack {

    Notification ACK = new Ack();

    static Notification progress(McpSchema.ProgressNotification value ) {
        return new Progress( value );
    }

    static Notification ack() {
        return ACK;
    }

    record Progress( McpSchema.ProgressNotification value ) implements Notification {};

    record Ack( ) implements Notification {};
}

