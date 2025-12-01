package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.Map;

import static java.util.Optional.ofNullable;

public interface MCPNotificationsSupport {

    static void mcpNotifyProgress(McpAsyncServerExchange exchange,
                                        McpSchema.CallToolRequest request,
                                        double progress,
                                        Double total,
                                        String message) {
        ofNullable(request.progressToken()).map(
                token -> {
                    var notification = new McpSchema.ProgressNotification(token, progress, total, message, Map.of());
                    return exchange.progressNotification(notification) ;
                }).orElseGet(Mono::empty)
                .block();

    }
    static void mcpNotifyProgress(McpAsyncServerExchange exchange,
                                        McpSchema.CallToolRequest request,
                                        double progress,
                                        String message) {
        mcpNotifyProgress( exchange, request, progress, null, message);
    }

    static void mcpNotifyLog(McpAsyncServerExchange exchange,
                                   McpSchema.CallToolRequest request,
                                   McpSchema.LoggingLevel level,
                                   String logger,
                                   String data) {

        var notification = new McpSchema.LoggingMessageNotification(level, logger, data, Map.of());
        exchange.loggingNotification(notification).block();
    }

}
