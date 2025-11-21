package org.bsc.langgraph4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MCPTest {


    InMemoryTransport transport;
    final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    McpSyncServer server;

    @BeforeEach
    public void createSyncMCPServer() {
        transport = new InMemoryTransport();

        var serverProvider = new InMemoryServerTransportProvider(transport);

        server = PlantUMLServer.sync(serverProvider);

    }

    @AfterEach
    public void closeSyncMCPServer() {
        if( server != null ) {
            server.close();
        }
     }

    @Test
    void jsonSchemaTest() throws IOException {
        var schema = new McpSchema.JsonSchema("object",
                Map.of("image_data", Map.of("type", "string",
                                                "description", "Base64-encoded image data")
                        ,
                        "mime_type", Map.of( "type", "string",
                                                "description", "MIME type of the image (e.g., 'image/jpeg', 'image/png",
                                                "enum", List.of("image/jpeg", "image/png"))
                ),
                List.of( "image_data"), false, null, null);

        var schemaString = jsonMapper.writeValueAsString(schema);
        System.out.println(schemaString);

    }
    @Test
    void toImageTest() {

        var clientTransport = new InMemoryClientTransport(transport);

        try (var client = McpClient.sync(clientTransport).build()) {

            client.initialize();
            var toolList = client.listTools();

            assertFalse(toolList.tools().isEmpty());
            assertEquals(2, toolList.tools().size());

            var callToolRequest  = new McpSchema.CallToolRequest("plantuml_to_image",
                    Map.of("script", """
                                    @startuml test
                                    title UML test
                                    @enduml
                                    """,
                            "outputPath", "/tmp/test.png")
                    );


            var result = client.callTool( callToolRequest );

            assertNotNull( result );
            assertFalse(result.isError() );

            var structuredContext = result.structuredContent();

            assertNotNull(structuredContext);
            assertInstanceOf( Map.class, structuredContext);
            var output = jsonMapper.convertValue( structuredContext, PlantUMLTools.OutputImage.class );
            assertInstanceOf( PlantUMLTools.OutputImage.class, output);


        }
    }

    @Test
    void genericDiagramToPlantumlPromptTest() throws Exception  {

        var clientTransport = new InMemoryClientTransport(transport);

        try (var client = McpClient.sync(clientTransport)
                .capabilities( McpSchema.ClientCapabilities.builder()
                        .build())
                .build())
        {

            client.initialize();
            var listPromptsResult = client.listPrompts();

            assertFalse(listPromptsResult.prompts().isEmpty());
            assertEquals(1, listPromptsResult.prompts().size());

            var promptRequest = listPromptsResult.prompts().stream()
                    .filter( p -> p.name().equals("generic_diagram_to_plantuml"))
                    .findFirst()
                    .map( p -> new McpSchema.GetPromptRequest(
                            p.name(),
                            Map.of( p.arguments().get(0).name(), "NOTHING TO DO")) )
                    .orElseThrow();

            assertNotNull(promptRequest);

            var promptComplete = client.getPrompt(promptRequest);

            assertNotNull(promptComplete);
        }
    }

    @Test
    void asyncDescribeDiagramFromImageTest() throws Exception  {

        var clientTransport = new InMemoryClientTransport(transport);

        var client = McpClient.async(clientTransport)
                .requestTimeout(Duration.ofMinutes(5))
                .capabilities( McpSchema.ClientCapabilities.builder()
                        //.sampling()
                        .build())
                .build();

        var imageResource = new ClassPathResource( "React_image.png");

        var bytes = imageResource.
                getInputStream().
                readAllBytes();

        final var imageData = Base64.getEncoder().encodeToString(bytes);
        final var mimeType = "image/png";

        var result = client.initialize()
            .flatMap( initResult -> client.listTools() )
            .flatMap( toolList -> {

                var callToolRequest = new McpSchema.CallToolRequest("describe_diagram_from_image",
                        Map.of("image_data", imageData, "mime_type", mimeType)
                );

                return client.callTool( callToolRequest );
            })
            .doFinally( signalType -> client.closeGracefully().subscribe() )
            .block();

         assertNotNull( result );

        System.out.println( result );


    }

}
