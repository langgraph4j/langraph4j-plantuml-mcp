package org.bsc.langgraph4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.utils.EdgeMappings;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.mcp.MCPNotificationsSupport.mcpNotifyLog;
import static org.bsc.langgraph4j.mcp.MCPNotificationsSupport.mcpNotifyProgress;
import static org.bsc.langgraph4j.mcp.PlantumlTools.sanitizeDiagramOutput;

interface PlantumlMainWorkflow {

    class State extends AgentState {

        public Optional<String> diagramDescription() {
            return value( "diagram_description");
        }

        public Optional<String> plantUMLScript() {
            return value( "plantuml_script");
        }

        public double progress() {
            return this.<Double>value("process_progress").orElse(0.0);
        }

        public State(Map<String, Object> initData) {
            super(initData);
        }
    }

    class StateSerializer extends JacksonStateSerializer<State> {

        protected StateSerializer() {
            super( State::new );
        }
    }

    class Builder  {

        final ObjectMapper mapper = new ObjectMapper();
        final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

        private AsyncNodeActionWithConfig<State> describeDiagramImage(  McpAsyncServerExchange exchange,
                                                                        McpSchema.CallToolRequest request )
        {

            final var logger = "describeDiagramImage";

            return ( state, config ) -> {

                var samplingMessage = new McpSchema.SamplingMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(PlantumlPrompts.DESCRIBE_DIAGRAM_FROM_IMAGE.get()));

                // Create a sampling request
                var messageRequest = McpSchema.CreateMessageRequest.builder()
                        .messages(List.of(samplingMessage))
                        .modelPreferences(McpSchema.ModelPreferences.builder()
                                .hints(List.of(McpSchema.ModelHint.of("vision")))
                                //.intelligencePriority(0.8)  // Prioritize intelligence
                                //.speedPriority(0.5)         // Moderate speed importance
                                .build())
                        //.systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                        //.maxTokens(100)
                        .build();

                // Request sampling from the client
                return exchange.createMessage(messageRequest)
                        .doOnSubscribe( subscription -> {
                            mcpNotifyLog(exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "start ");
                        })
                        .doOnSuccess( signal -> {
                            mcpNotifyProgress(exchange, request, state.progress()+1,  logger);
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "end" );
                        })
                        .map(result -> (McpSchema.TextContent) result.content())
                        .map( content -> Map.<String,Object>of( "diagram_description", content.text(), "process_progress", state.progress()+1) )
                        .toFuture();
            };
        }

        private AsyncNodeActionWithConfig<State> genericDiagramToPlantUML(McpAsyncServerExchange exchange,
                                                                          McpSchema.CallToolRequest request )
        {
            final var logger = "genericDiagramToPlantUML";
            return ( state, config ) -> {

                var diagramSource = state.diagramDescription();
                if( diagramSource.isEmpty() ) {
                    return CompletableFuture.failedFuture( new IllegalStateException("diagram_description is missing"));
                }

                var samplingMessage = new McpSchema.SamplingMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(PlantumlPrompts.GENERIC_DIAGRAM_TO_PLANTUML.apply(diagramSource.get())));

                // Create a sampling request
                var messageRequest = McpSchema.CreateMessageRequest.builder()
                        .messages(List.of(samplingMessage))
                        .modelPreferences(McpSchema.ModelPreferences.builder()
                                .hints(List.of(McpSchema.ModelHint.of("mini")))
                                //.intelligencePriority(0.8)  // Prioritize intelligence
                                .speedPriority(1.0)         // Moderate speed importance
                                .build())
                        //.systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                        //.maxTokens(100)
                        .build();


                // Request sampling from the client
                return exchange.createMessage(messageRequest)
                        .doOnSubscribe( subscription -> {
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "start " );
                        })
                        .doOnSuccess( signal -> {
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "end" );
                            mcpNotifyProgress(exchange, request, state.progress()+1, logger);
                        })
                        .map(result -> (McpSchema.TextContent) result.content())
                        .map( content -> {
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.DEBUG, logger.concat("content"), content.text()  );
                            var text=  PlantumlTools.sanitizeDiagramOutput( content.text() );
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.DEBUG, logger.concat("content"), text  );
                            return text;
                        })
                        .map( content -> Map.<String,Object>of( "plantuml_script", content,
                                                                        "process_progress", state.progress()+1) )
                        .toFuture();
            };
        }

        private AsyncNodeActionWithConfig<State> sequenceDiagramToPlantUML(McpAsyncServerExchange exchange,
                                                                           McpSchema.CallToolRequest request )
        {

            final var logger = "sequenceDiagramToPlantUML";

            return ( state, config ) -> {

                var diagramSource = state.diagramDescription();
                if( diagramSource.isEmpty() ) {
                    return CompletableFuture.failedFuture( new IllegalStateException("diagram_description is missing"));
                }

                var samplingMessage = new McpSchema.SamplingMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(PlantumlPrompts.SEQUENCE_DIAGRAM_TO_PLANTUML.apply(diagramSource.get())));

                // Create a sampling request
                var messageRequest = McpSchema.CreateMessageRequest.builder()
                        .messages(List.of(samplingMessage))
                        .modelPreferences(McpSchema.ModelPreferences.builder()
                                .hints(List.of(McpSchema.ModelHint.of("mini")))
                                //.intelligencePriority(0.8)  // Prioritize intelligence
                                .speedPriority(1.0)
                                .build())
                        //.systemPrompt("You are a helpful calculator assistant. Provide only the numerical answer.")
                        //.maxTokens(100)
                        .build();


                // Request sampling from the client
                return exchange.createMessage(messageRequest)
                        .doOnSubscribe( subscription -> {
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "start " );
                        })
                        .doOnSuccess( signal -> {
                            mcpNotifyLog( exchange, request, McpSchema.LoggingLevel.NOTICE, logger, "end" );
                            mcpNotifyProgress(exchange, request, state.progress()+1, logger);
                        })
                        .map(result -> (McpSchema.TextContent) result.content())
                        .map( content -> sanitizeDiagramOutput( content.text() ) )
                        .map( content -> Map.<String,Object>of( "plantuml_script", content) )
                        .toFuture();
            };
        }

        private AsyncEdgeAction<State> routeDiagramTranslation( McpAsyncServerExchange exchange,
                                                                McpSchema.CallToolRequest request)
        {
            return (state) -> {

                if( state.diagramDescription().isEmpty() ) {
                    return failedFuture( new IllegalStateException("diagram_description is missing") );
                }

                final Diagram.Model model;
                try {
                    model = jsonMapper.readValue( state.diagramDescription().get(), Diagram.Model.class);
                } catch (IOException ex) {
                    return failedFuture( new IllegalStateException("diagram_description is not a valid json", ex) );
                }

                if( "sequence".equalsIgnoreCase( model.type() ) ){
                    return completedFuture( "sequence" );
                }
                return completedFuture( "generic" );
            };
        }

        public StateGraph<State> build( McpAsyncServerExchange exchange, McpSchema.CallToolRequest request ) throws GraphStateException {

            var serializer = new StateSerializer();


            var reviewWorkflow = PlantumlReviewWorkflow.builder()
                                    .stateSerializer( serializer )
                                    .build( exchange, request )
                                    .compile(CompileConfig.builder()
                                            .recursionLimit(10)
                                            .build());

            /*
            AsyncNodeActionWithConfig<State> reviewWorkflow = ( state, config ) ->
                                                                    completedFuture( Map.of() );
            */
            return new StateGraph<>( serializer )
                            .addNode("describe_image", describeDiagramImage( exchange, request )  )
                            .addNode("sequence_to_plantuml", sequenceDiagramToPlantUML( exchange, request ) )
                            .addNode("generic_to_plantuml", genericDiagramToPlantUML( exchange, request ) )
                            .addNode( "evaluate_result", reviewWorkflow )
                            .addConditionalEdges("describe_image",
                                    routeDiagramTranslation( exchange, request ),
                                    EdgeMappings.builder()
                                            .to( "sequence_to_plantuml", "sequence" )
                                            .to( "generic_to_plantuml" , "generic" )
                                            .build()
                            )
                            .addEdge("sequence_to_plantuml", "evaluate_result")
                            .addEdge("generic_to_plantuml", "evaluate_result")
                            .addEdge( START,"describe_image")
                            .addEdge("evaluate_result", END)
                            ;
        }
    }

    static Builder builder() {
        return new Builder();
    }
}
