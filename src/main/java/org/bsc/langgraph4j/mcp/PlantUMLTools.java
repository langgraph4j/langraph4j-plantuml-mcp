package org.bsc.langgraph4j.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.sourceforge.plantuml.SourceStringReader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

public interface PlantUMLTools {


    record OutputImage(
            Path path,
            String description ) {
        @JsonCreator
        public OutputImage(
                @JsonProperty("path") String path,
                @JsonProperty("description") String description) {
            this(Path.of(path), description);
        }
    }


    static  McpSchema.CallToolResult toImage(McpSyncServerExchange exchange, McpSchema.CallToolRequest request ) {
        var script = String.valueOf(request.arguments().get("script"));
        var outputPath = String.valueOf(request.arguments().get("outputPath"));

        final var builder = McpSchema.CallToolResult.builder();

        return toImage( script, Path.of(outputPath) )
                .thenApply( result ->
                    builder.structuredContent( result )
                            .build()
                )
                .exceptionally( ex ->
                    builder.isError(true)
                            .addTextContent( ex.getMessage() )
                            .build()
                )
                .join();

    }

    static CompletableFuture<OutputImage> toImage( String script, Path outputPath )  {
        requireNonNull( outputPath, "outputPath cannot be null");
        var reader = new SourceStringReader( requireNonNull( script, "script cannot be null"));

        if( Files.notExists(outputPath) ) {
            if( outputPath.getParent() != null ) {
                try {
                    Files.createDirectories( outputPath.getParent() );
                } catch (IOException e) {
                    return failedFuture(e);
                }
            }
        }

        // Output the image to a file and capture its description.
        try(OutputStream out = new java.io.FileOutputStream(outputPath.toFile())) {
            var description = reader.outputImage(out);
            return completedFuture(new OutputImage(outputPath, description.getDescription()));
        }
        catch (IOException e) {
            return failedFuture(e);
        }
    }

    static McpServerFeatures.SyncToolSpecification toImageSpecification() {

        final var schema = McpSchema.Tool.builder()
                .description("generate png file image from the plantuml script")
                .name("plantuml_to_image")
                .inputSchema(
                        new McpSchema.JsonSchema("object",
                                Map.of("script", "string",
                                        "outputPath", "string"),
                                List.of( "script",
                                        "outputPath"), false, null, null))
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool( schema )
                .callHandler(PlantUMLTools::toImage)
                .build();
    }

    static  McpSchema.CallToolResult describeDiagramFromImage(McpSyncServerExchange exchange, McpSchema.CallToolRequest request ) {

        final var builder = McpSchema.CallToolResult.builder();

        try {
            var base64Data = requireNonNull(request.arguments().get("image_data"), "image_data is required");

            var pMimeType = requireNonNull(request.arguments().get("mime_type"), "mime_type is required");

            var mimeType = MimeType.valueOf(pMimeType.toString());

            byte[] dataBytes = Base64.getDecoder().decode(base64Data.toString());

            var chatModel = AIModel.OLLAMA_VISION.model("qwen3-vl:latest");
            //var chatModel = AiModel.OPENAI_VISION.model("gpt-4o");

            var userMessage = UserMessage.builder()
                    .text(PlantUMLPrompts.DESCRIBE_DIAGRAM_FROM_IMAGE.get())
                    .media(new Media(mimeType, new ByteArrayResource(dataBytes)))
                    .build();

            var response = ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .messages(userMessage)
                    .call()
                    .chatResponse()
                    ;

            return builder
                    .addTextContent( requireNonNull(response, "response cannot be null")
                            .getResult()
                            .getOutput()
                            .getText() )
                    .build();
        }
        catch( Throwable ex  ) {
            return builder.isError(true).addTextContent(ex.toString()).build();
        }

    }

    static McpServerFeatures.SyncToolSpecification describeDiagramFromImageSpecification() {

        final var inputSchema = new McpSchema.JsonSchema("object",
                Map.of("image_data",
                        Map.of("type", "string",
                                "description", "Base64-encoded image data")
                        ,
                        "mime_type",
                        Map.of( "type", "string",
                                "description", "MIME type of the image (e.g., 'image/jpeg', 'image/png",
                                "enum", List.of("image/jpeg", "image/png"))
                ),
                List.of( "image_data"), false, null, null);


        final var schema = McpSchema.Tool.builder()
                .description("generate diagram description from an image")
                .name("describe_diagram_from_image")
                .inputSchema( inputSchema )
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool( schema )
                .callHandler(PlantUMLTools::describeDiagramFromImage)
                .build();

    }
}
