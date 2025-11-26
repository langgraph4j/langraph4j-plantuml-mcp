package org.bsc.langgraph4j.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.String.format;

interface PlantumlPrompts {

    String DESCRIBE_DIAGRAM_SCHEMA = """
              {
              "type": "object",
              "properties": {
                "type": {
                  "type": "string",
                  "description": "Diagram typology (one word). Eg. 'sequence', 'class', 'process', etc."
                },
                "title": {
                  "type": "string",
                  "description": "Diagram summary (max one line) or title (if any)"
                },
                "participants": {
                  "type": "array",
                  "description": "list of participants in the diagrams",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "type": "string",
                        "description": "participant name"
                      },
                      "shape": {
                        "type": "string",
                        "description": "participant shape. The acceptable shapes are which ones compliant with plantuml syntax"
                      },
                      "description": {
                        "type": "string",
                        "description": "participant description"
                      }
                    },
                    "required": [
                      "name",
                      "shape",
                      "description"
                    ]
                  }
                },
                "relations": {
                  "type": "array",
                  "description": "list of relations in the diagram",
                  "items": {
                    "type": "object",
                    "properties": {
                      "source": {
                        "type": "string",
                        "description": "source participant"
                      },
                      "target": {
                        "type": "string",
                        "description": "target participant"
                      },
                      "description": {
                        "type": "string",
                        "description": "relation description"
                      }
                    },
                    "required": [
                      "source",
                      "target",
                      "description"
                    ]
                  }
                },
                "containers": {
                  "type": "array",
                  "description": "list of participants that contain other ones in the diagram",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "type": "string",
                        "description": "container name"
                      },
                      "children": {
                        "type": "array",
                        "description": "list of contained elements name",
                        "items": {
                          "type": "string"
                        }
                      },
                      "description": {
                        "type": "string",
                        "description": "container description"
                      }
                    },
                    "required": [
                      "name",
                      "children",
                      "description"
                    ]
                  }
                },
                "description": {
                  "type": "array",
                  "description": "Step by step description of the diagram with clear indication of participants and actions between them.",
                  "items": {
                    "type": "string"
                  }
                }
              },
              "required": [
                "type",
                "title",
                "participants",
                "relations",
                "description"
              ]
            }
            """;

    Supplier<String> DESCRIBE_DIAGRAM_FROM_IMAGE = () -> format("""
            Describe the diagram in the image step by step so we can translate it into diagram-as-code syntax.
            
            Result must be a JSON object that must adhere to the following <JSON SCHEMA>:
            
            <JSON-SCHEMA>
            %s
            </JSON-SCHEMA>
            
            Must not include the <JSON schema> in the response
            """, DESCRIBE_DIAGRAM_SCHEMA);


    Function<String,String> SEQUENCE_DIAGRAM_TO_PLANTUML = ( String diagramDescription ) -> format("""
            Translate the diagram description into raw plantUML syntax.
            
            Also put the diagram description in the legend in the form:
            legend
            <description with a bullet point for each steps>
            end legend
            
            <DIAGRAM_DESCRIPTION>
            %s
            </DIAGRAM_DESCRIPTION>
            
           
            """, diagramDescription);

    Function<String,String> GENERIC_DIAGRAM_TO_PLANTUML = (String diagramDescription ) -> format("""
            Translate the JSON data represented in <DIAGRAM_DESCRIPTION> into a raw plantuml script considering:
            
            1. The participants' shape must be translated in their plantuml counterpart using the following conversion rules :
                - "rectangle" shape  must be translated into  plantuml's "rectangle"
                - "circle" shape  must be translated into  plantuml's "circle"
                - "person" or  "stick man" shape  must be translated into  plantuml's  "actor"
                - "oval" or "ellipse"  shape  must be translated into  plantuml's  "usecase"
                - "cylinder" shape  must be translated into  plantuml's  "database"
                - "diamond" shape  must be translated into  plantuml's "hexagon"
            2. Each recognised participant must be written in the form: "<participant plantuml shape>"  "<name>" as <camel case name><<description>>
            3. Relations must be the arrow that connect participants
            4. Put diagram description in the legend of the diagram in the form:
                legend
                <description with a bullet point for each steps>
                end legend
            5. Put  diagram title in the form:
                 title "<diagram title>"
            
            <DIAGRAM_DESCRIPTION>
            %s
            </DIAGRAM_DESCRIPTION>
            """, diagramDescription);

    BiFunction<String,String,String> REVIEW_DIAGRAM = ( script, error ) -> format("""
            You are my PlantUML reviewer:
            
            In the PlantUML <DIAGRAM_SCRIPT> there is an <EVALUATION_ERROR>
            
            <DIAGRAM_SCRIPT>
            %s
            </DIAGRAM_SCRIPT>
            
            <EVALUATION_ERROR>
            %s
            </EVALUATION_ERROR>
            
            Error sample
            ----------------
            EXECUTION_ERROR 15 Syntax error: LLM
            
            In the sample above the error indicates that there is at line "15" a "Syntax error" concerning element "LLM"
            
            Rewrite the PlantUML diagram script, correcting the error and applying this correction to any similar errors you find in the script itself.
            You MUST return only PlantUML script as plain text not using markdown notation
            """, script, error);

    static McpServerFeatures.AsyncPromptSpecification describeDiagramFromImageSpecification() {
        final var description = "describe an image of a diagram into a structured JSON content ";
        return new McpServerFeatures.AsyncPromptSpecification(
                new McpSchema.Prompt("describe_diagram_from_image",
                        description,
                        List.of()),
                (exchange, request) -> {

                    // Prompt implementation
                    var result =  new McpSchema.GetPromptResult(description,
                            List.of( new McpSchema.PromptMessage( McpSchema.Role.ASSISTANT,
                                    new McpSchema.TextContent(DESCRIBE_DIAGRAM_FROM_IMAGE.get()) )));

                    return Mono.just( result );
                });
    }

    static McpServerFeatures.AsyncPromptSpecification genericDiagramToPlantumlSpecification() {
        final var description = "convert a generic diagram description to plantuml syntax";
        return new McpServerFeatures.AsyncPromptSpecification(
                new McpSchema.Prompt("generic_diagram_to_plantuml",
                        description,
                        List.of(
                                new McpSchema.PromptArgument(
                                        "diagram_description",
                                        "diagram description in JSON format", true)
                        )),
                (exchange, request) -> {


                    var diagramDescription = request.arguments().get("diagram_description");
                    if( diagramDescription == null ) {
                        return Mono.error( () -> new IllegalArgumentException("missing diagram_description param"));
                    }

                    // Prompt implementation
                    var result = new McpSchema.GetPromptResult(description,
                            List.of( new McpSchema.PromptMessage( McpSchema.Role.ASSISTANT,
                                    new McpSchema.TextContent(GENERIC_DIAGRAM_TO_PLANTUML.apply( diagramDescription.toString() )) )));

                    return Mono.just( result );
                });
    }

    static McpServerFeatures.AsyncPromptSpecification sequenceDiagramToPlantumlSpecification() {
        final var description = "convert a sequence diagram description to plantuml syntax";
        return new McpServerFeatures.AsyncPromptSpecification(
                new McpSchema.Prompt("sequence_diagram_to_plantuml",
                        description,
                        List.of(
                                new McpSchema.PromptArgument(
                                        "diagram_description",
                                        "diagram description in JSON format", true)
                        )),
                (exchange, request) -> {


                    var diagramDescription = request.arguments().get("diagram_description");
                    if( diagramDescription == null ) {
                        return Mono.error( () -> new IllegalArgumentException("missing diagram_description param"));
                    }

                    // Prompt implementation
                    var result = new McpSchema.GetPromptResult(description,
                            List.of( new McpSchema.PromptMessage( McpSchema.Role.ASSISTANT,
                                    new McpSchema.TextContent(SEQUENCE_DIAGRAM_TO_PLANTUML.apply( diagramDescription.toString() )) )));

                    return Mono.just( result );
                });
    }

}
