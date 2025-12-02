package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

public interface PlantumlMCPServer {

    static McpAsyncServer async(McpServerTransportProvider serverTransportProviderProvider) {
        return McpServer.async(serverTransportProviderProvider)
                .capabilities( McpSchema.ServerCapabilities.builder()
                        .tools( false )
                        .prompts( false )
                        .logging()
                        .build())
                .tools(
                        PlantumlTools.toImageFileSpecification(),
                        PlantumlTools.toImageUrlSpecification(),
                        PlantumlTools.describeDiagramFromImageSpecification()
                )
                .prompts(
                        PlantumlPrompts.genericDiagramToPlantumlSpecification(),
                        PlantumlPrompts.describeDiagramFromImageSpecification(),
                        PlantumlPrompts.sequenceDiagramToPlantumlSpecification())
                .build();

    }
}
