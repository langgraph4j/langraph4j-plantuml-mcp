package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.sourceforge.plantuml.*;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.error.PSystemError;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.bsc.langgraph4j.GraphDefinition.START;

interface PlantumlReviewWorkflow {

    class EvaluationResultException extends Exception  {
        public final ErrorUml errorUml;

        public EvaluationResultException(ErrorUml errorUml) {
            super(errorUml.toString());
            this.errorUml = errorUml;

        }
    }

    enum EvaluationResult {
        OK, ERROR, EVALUATION_ERROR
    }


    class Builder {

        public static <T> CompletableFuture<T> validatePlantUMLScript(String script) {
            SourceStringReader reader = new SourceStringReader(script);

            final List<BlockUml> blocks = reader.getBlocks();
            if (blocks.size() != 1) {
                return failedFuture( new IllegalArgumentException( "Invalid PlantUML script (block.size = %d)".formatted( blocks.size())) );
            }

            final Diagram system = blocks.get(0).getDiagram();

            if (system instanceof PSystemError errors) {
                ErrorUml err = errors.getFirstError();

                return failedFuture( new EvaluationResultException(err) );
            }

            return completedFuture(null);
        }

        private AsyncNodeActionWithConfig<PlantumlMainWorkflow.State> evaluateResult(McpAsyncServerExchange exchange,
                                                                                     McpSchema.CallToolRequest request )
        {

            return ( state, config ) -> {

                if( state.plantUMLScript().isEmpty() ) {
                    return failedFuture( new IllegalStateException("plantuml_script attribute is missing"));

                }

                return validatePlantUMLScript( state.plantUMLScript().get() )
                    .thenApply( v -> Map.<String,Object>of( "evaluation_result", EvaluationResult.OK) )
                    .exceptionally( e -> {
                        if( e.getCause() instanceof EvaluationResultException ex ) {
                            return Map.of("evaluation_result", EvaluationResult.EVALUATION_ERROR,
                                    "evaluation_error",  ex.getMessage());
                        }
                        return Map.of("evaluation_result", EvaluationResult.ERROR,
                                "evaluation_error",  e.getCause().getMessage());
                    });
            };

        }

        private AsyncNodeActionWithConfig<PlantumlMainWorkflow.State> reviewResult(McpAsyncServerExchange exchange,
                                                                                   McpSchema.CallToolRequest request )
        {
            return ( state, config ) -> {

                if( state.plantUMLScript().isEmpty() ) {
                    return failedFuture( new IllegalStateException("plantuml_script attribute is missing"));

                }
                var evaluationError = state.<String>value( "evaluation_error" );
                if( evaluationError.isEmpty() ) {
                    return failedFuture( new IllegalStateException("evaluation_error attribute is missing"));

                }

                var samplingMessage = new McpSchema.SamplingMessage(
                        McpSchema.Role.ASSISTANT,
                        new McpSchema.TextContent(PlantumlPrompts.REVIEW_DIAGRAM.apply(state.plantUMLScript().get(), evaluationError.get())));

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
                        .map(result -> (McpSchema.TextContent) result.content())
                        .map( content -> PlantumlTools.sanitizeDiagramOutput( content.text() ) )
                        .map( content -> Map.<String,Object>of( "plantuml_script", content) )
                        .toFuture();
            };
        }

        private AsyncEdgeAction<PlantumlMainWorkflow.State> routeEvaluationResult(McpAsyncServerExchange exchange,
                                                                                  McpSchema.CallToolRequest request )
        {
            return  state  -> {
                Optional<EvaluationResult> evaluationResult = state.value("evaluation_result");
                if( evaluationResult.isEmpty() ) {
                    return failedFuture( new IllegalStateException("evaluation_result attribute is missing"));
                }

                if( EvaluationResult.ERROR.equals( evaluationResult.get() ) ) {
                    return failedFuture( new Exception( state.<String>value("evaluation_error").orElse("unknown error") ));
                }

                return completedFuture(evaluationResult.get().name());

            };
        }

        StateSerializer<PlantumlMainWorkflow.State> serializer;

        public StateGraph<PlantumlMainWorkflow.State> build(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request ) throws GraphStateException {

            return new StateGraph<>( requireNonNull( serializer, "serializer cannot be null" ) )
                        .addNode("evaluate_result", evaluateResult( exchange, request ))
                        .addNode("review_result", reviewResult( exchange, request) )
                        .addEdge("review_result", "evaluate_result")
                        .addConditionalEdges("evaluate_result",
                                routeEvaluationResult( exchange, request ),
                                EdgeMappings.builder()
                                        .toEND("OK" )
                                        .to( "review_result","EVALUATION_ERROR" )
                                        .build())
                        .addEdge(START, "evaluate_result");

        }


        public PlantumlReviewWorkflow.Builder stateSerializer(StateSerializer<PlantumlMainWorkflow.State> serializer) {
            this.serializer = serializer;
            return this;
        }
    }

    static PlantumlReviewWorkflow.Builder builder() {
        return new PlantumlReviewWorkflow.Builder();
    }

}
