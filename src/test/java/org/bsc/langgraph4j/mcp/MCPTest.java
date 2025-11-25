package org.bsc.langgraph4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.error.PSystemError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

public class MCPTest {


    final ObjectMapper mapper = new ObjectMapper();
    InMemoryTransport transport;
    final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);
    McpAsyncServer server;

    @BeforeEach
    public void createSyncMCPServer() {
        transport = new InMemoryTransport();

        var serverProvider = new InMemoryServerTransportProvider(transport);

        server = PlantUMLServer.async(serverProvider);

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
    void jsonDescriptionToDiagramModelTest() throws IOException {

        var resource = new ClassPathResource("ReAct_image.json");

        var model = jsonMapper.readValue( resource.getContentAsString( Charset.defaultCharset() ), Diagram.Model.class);

        assertNotNull(model);
        assertEquals("process", model.type());
        assertEquals("Agent Interaction Diagram", model.title());
        assertEquals(4, model.participants().size());
        assertEquals(5, model.relations().size());
        assertEquals(1, model.containers().size());
        assertEquals(5, model.description().size());
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
    void plantUMLScriptEvaluationErrorTest() throws IOException {
        final var resourceScript = new ClassPathResource( "React_error.plantuml");

        SourceStringReader reader = new SourceStringReader(resourceScript.getContentAsString( Charset.defaultCharset()) );


        final List<BlockUml> blocks = reader.getBlocks();


        assertEquals( 1, blocks.size());

        var block = blocks.get(0);

        var system = block.getDiagram();

        assertNotNull( system );
        assertInstanceOf( PSystemError.class, system );

        var systemError = (PSystemError)system;

        var errorUml = systemError.getFirstError();

        assertNotNull( errorUml );
        assertEquals( "EXECUTION_ERROR 15 Syntax error: LLM", errorUml.toString() );
        assertEquals( "Syntax error: LLM (Assumed diagram type: component)", errorUml.getError());


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
    void describeDiagramFromImageTest() throws Exception  {

        var clientTransport = new InMemoryClientTransport(transport);

        final var imageResource = new ClassPathResource( "React_image.png");

        final var mimeType = MimeType.valueOf( "image/png");

        final var chatVisionModel = AIModel.OLLAMA_VISION.model("qwen3-vl:latest");
        final var chatMiniModel = AIModel.OLLAMA_VISION.model("qwen3:8b");
        //final var chatVisionModel = AIModel.OPENAI_VISION.model("gpt-4o");

        var client = McpClient.async(clientTransport)
                .requestTimeout(Duration.ofMinutes(10))
                .capabilities( McpSchema.ClientCapabilities.builder()
                        .sampling()
                        .build())
                .sampling( request -> {

                    final var instruction =  (McpSchema.TextContent)request.messages().get(0).content();

                    var response = request.modelPreferences().hints().stream()
                            .filter( h -> "vision".equalsIgnoreCase(h.name()))
                            .findFirst()
                            .map( h -> {
                                var userMessage = UserMessage.builder()
                                                .text(instruction.text())
                                                .media(new Media(mimeType, imageResource))
                                                .build();
                                return ChatClient.builder(chatVisionModel)
                                        .build()
                                        .prompt()
                                        .messages(userMessage)
                                        .call()
                                        .chatResponse();
                            }).orElseGet( () -> {
                                var userMessage = UserMessage.builder()
                                        .text(instruction.text())
                                        .build();
                                return ChatClient.builder(chatMiniModel)
                                        .build()
                                        .prompt()
                                        .messages(userMessage)
                                        .call()
                                        .chatResponse();
                            });

                    var result =  McpSchema.CreateMessageResult.builder()
                                    .message( requireNonNull(response, "response cannot be null")
                                            .getResult()
                                            .getOutput()
                                            .getText())
                                    .build();
                    return Mono.just(result);
                })
                .build();

            var result = client.initialize().flatMap( init -> {
                var callToolRequest = new McpSchema.CallToolRequest("describe_diagram_from_image", Map.of());

                return client.callTool(callToolRequest);

            })
            .doFinally( signal -> client.closeGracefully().subscribe() )
            .block()
            ;

            assertNotNull( result );
            assertFalse(result.isError() );
            assertFalse( result.content().isEmpty());
            assertEquals( 1, result.content().size() );
            assertInstanceOf(McpSchema.TextContent.class, result.content().get(0) );

            System.out.println( result.content().get(0) );
    }

}
