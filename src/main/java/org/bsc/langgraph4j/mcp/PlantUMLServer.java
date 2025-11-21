package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

public interface PlantUMLServer {

    static McpSyncServer sync(McpServerTransportProvider serverTransportProviderProvider) {
        return McpServer.sync(serverTransportProviderProvider)
                .tools(
                        PlantUMLTools.toImageSpecification(),
                        PlantUMLTools.describeDiagramFromImageSpecification()
                )
                .prompts(
                        PlantUMLPrompts.syncGenericDiagramToPlantumlSpecification(),
                        PlantUMLPrompts.syncDescribeDiagramFromImageSpecification())
                .build();

    }
}
