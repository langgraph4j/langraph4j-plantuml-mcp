package org.bsc.langgraph4j.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.sourceforge.plantuml.SourceStringReader;
import org.bsc.langgraph4j.*;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

interface PlantUMLTools {

    record OutputImage(
            Path path,
            String description) {
        @JsonCreator
        public OutputImage(
                @JsonProperty("path") String path,
                @JsonProperty("description") String description) {
            this(Path.of(path), description);
        }
    }

    static Mono<McpSchema.CallToolResult> toImage(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Supplier<CompletableFuture<OutputImage>> _toImage = () -> {

            var scriptArg = request.arguments().get("script");
            if( scriptArg == null ) {
                return failedFuture( new IllegalArgumentException("script cannot be null"));
            }

            var outputPathArg = request.arguments().get("outputPath");
            if( outputPathArg == null ) {
                return failedFuture( new IllegalArgumentException("outputPath cannot be null"));
            }

            var outputPath = Path.of( outputPathArg.toString() );

            var reader = new SourceStringReader(scriptArg.toString());

            if (Files.notExists(outputPath)) {
                if (outputPath.getParent() != null) {
                    try {
                        Files.createDirectories(outputPath.getParent());
                    } catch (IOException e) {
                        return failedFuture(e);
                    }
                }
            }

            // Output the image to a file and capture its description.
            try (OutputStream out = new java.io.FileOutputStream(outputPath.toFile())) {
                var description = reader.outputImage(out);
                return completedFuture(new OutputImage(outputPath, description.getDescription()));
            } catch (IOException e) {
                return failedFuture(e);
            }
        };

        return Mono.fromFuture( _toImage.get()
                .thenApply(result ->
                        McpSchema.CallToolResult.builder()
                                .structuredContent(result)
                                .build()
                )
                .exceptionally(ex ->
                        McpSchema.CallToolResult.builder()
                                .isError(true)
                                .addTextContent(ex.getMessage())
                                .build()
                ));

    }

    static McpServerFeatures.AsyncToolSpecification toImageSpecification() {

        final var schema = McpSchema.Tool.builder()
                .description("generate png file image from the plantuml script")
                .name("plantuml_to_image")
                .inputSchema(
                        new McpSchema.JsonSchema("object",
                                Map.of("script", "string",
                                        "outputPath", "string"),
                                List.of("script",
                                        "outputPath"), false, null, null))
                .build();

        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(schema)
                .callHandler(PlantUMLTools::toImage)
                .build();
    }


    static Mono<McpSchema.CallToolResult> describeDiagramFromImage(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {

        // Check if client supports sampling
        if (exchange.getClientCapabilities().sampling() == null) {
            return Mono.just(McpSchema.CallToolResult.builder()
                                    .isError(true)
                                    .addTextContent("Client does not support AI capabilities")
                                    .build());
        }


        try {
            var compileConfig = CompileConfig.builder().build();

            var workflow = PlantUMLMainWorkflow.builder()
                    .build( exchange, request )
                    .compile( compileConfig );

            var runnableConfig = RunnableConfig.builder().build();

            var futureResult = CompletableFuture.supplyAsync( () ->
                    workflow
                    .invoke(GraphInput.noArgs(), runnableConfig)
                    .flatMap(PlantUMLMainWorkflow.State::plantUMLScript)
                    .map( script -> McpSchema.CallToolResult.builder()
                            .addTextContent( script ))
                    .orElseGet( () -> McpSchema.CallToolResult.builder()
                            .isError(true)
                            .addTextContent("error running agentic workflow"))
                    .build());

            return Mono.fromFuture( futureResult );

        } catch (GraphStateException e) {
            return Mono.just(McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("error creating agentic workflow %s".formatted(e.getMessage()))
                    .build());
        }

    }


    static McpServerFeatures.AsyncToolSpecification describeDiagramFromImageSpecification() {

        final var inputSchema = new McpSchema.JsonSchema("object",
                Map.of(),
                List.of(), false, null, null);


        final var schema = McpSchema.Tool.builder()
                .description("generate diagram description from an image")
                .name("describe_diagram_from_image")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(schema)
                .callHandler(PlantUMLTools::describeDiagramFromImage)
                .build();

    }
}
