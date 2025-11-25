package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

public interface PlantUMLServer {

    static McpAsyncServer async(McpServerTransportProvider serverTransportProviderProvider) {
        return McpServer.async(serverTransportProviderProvider)
                .tools(
                        PlantUMLTools.toImageSpecification(),
                        PlantUMLTools.describeDiagramFromImageSpecification()
                )
                .prompts(
                        PlantUMLPrompts.genericDiagramToPlantumlSpecification(),
                        PlantUMLPrompts.describeDiagramFromImageSpecification(),
                        PlantUMLPrompts.sequenceDiagramToPlantumlSpecification())
                .build();

    }
}
