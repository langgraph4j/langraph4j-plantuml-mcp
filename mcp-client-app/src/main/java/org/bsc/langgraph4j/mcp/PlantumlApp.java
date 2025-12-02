package org.bsc.langgraph4j.mcp;

//DEPS org.bsc.langgraph4j:langgraph4j-plantuml-mcp-server:1.0-SNAPSHOT
//DEPS org.bsc.modelcontextprotocol.sdk:mcp-inmemory-transport:1.0-SNAPSHOT
//DEPS org.springframework.ai:spring-ai-bom:1.1.0@pom
//DEPS org.springframework.ai:spring-ai-client-chat
//DEPS org.springframework.ai:spring-ai-openai
//DEPS org.springframework.ai:spring-ai-ollama

import io.javelit.components.media.FileUploaderComponent;
import io.javelit.core.Jt;
import io.javelit.core.JtComponent;
import io.javelit.core.JtUploadedFile;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.inmemory.InMemoryClientTransport;
import io.modelcontextprotocol.transport.inmemory.InMemoryServerTransportProvider;
import io.modelcontextprotocol.transport.inmemory.InMemoryTransport;
import org.bsc.async.AsyncGenerator;
import org.bsc.async.AsyncGeneratorQueue;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;


public class PlantumlApp {
    public static void main(String[] args) {

        var app = new PlantumlApp();

        app.view();
    }


    enum AIModel {

        OPENAI(name ->
                OpenAiChatModel.builder()
                        .openAiApi(OpenAiApi.builder()
                                //.baseUrl("https://api.openai.com")
                                .apiKey(System.getProperty("OPENAI_API_KEY"))
                                .build())
                        .defaultOptions(OpenAiChatOptions.builder()
                                .model(name)
                                .logprobs(false)
                                .temperature(0.1)
                                .build())
                        .build()),
        OLLAMA(name ->
                OllamaChatModel.builder()
                        .retryTemplate(RetryTemplate.builder()
                                .maxAttempts(10)
                                .build())
                        .ollamaApi(OllamaApi.builder()
                                .baseUrl("http://localhost:11434").build())
                        .defaultOptions(OllamaChatOptions.builder()
                                .model(name)
                                .temperature(0.1)
                                .build())
                        .build());


        private final Function<String, ChatModel> model;

        public ChatModel model(String name) {
            return model.apply(name);
        }

        AIModel(Function<String, ChatModel> model) {
            this.model = model;
        }
    }

    InMemoryTransport transport() {
        return (InMemoryTransport) Jt.cache()
                .computeIfAbsent("mcp_transport", key -> new InMemoryTransport());
    }

    McpAsyncServer mcpServer() {
        return (McpAsyncServer) Jt.cache().computeIfAbsent("mcp_server", key -> {
            System.out.println("CREATE MCP SERVER");
            var serverProvider = new InMemoryServerTransportProvider(transport());

            return PlantumlMCPServer.async(serverProvider);
        });
    }

    static DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    String now() {
        return LocalTime.now().format(dateFormatter);
    }

    @SuppressWarnings("unchecked")
    AsyncGenerator.WithResult<Notification> processProgress() {

        var generator = new AsyncGeneratorQueue.Generator<Notification>(new ArrayBlockingQueue<>(10));
        return (AsyncGenerator.WithResult<Notification>) Jt.sessionState().computeIfAbsent("process_progress", b ->
                new AsyncGenerator.WithResult<>(generator));
    }

    BlockingQueue<AsyncGenerator.Data<Notification>> processProgressQueue() {
        return ((AsyncGeneratorQueue.Generator<Notification>) processProgress().delegate()).queue();
    }


    // the Javelit webapp
    void view() {

        var mcpServer = mcpServer();

        Jt.title("PlantUML App").use();

        var selectModelCols = Jt.columns(2).key("select-model-cols").use();

        boolean cloud = Jt.toggle("Select Cloud/Local Model").use(selectModelCols.col(0));
        Jt.text(cloud ? "cloud" : "local").use(selectModelCols.col(1));

        String model = null;
        if( cloud ) {
            var cloudModelCols = Jt.columns(2).key("cloud-model-cols").use();
            model = Jt.radio("Available models",
                    List.of("gpt-4o-mini")).use(cloudModelCols.col(0));
            var apikey = Jt.textInput("API KEY:")
                        .type("password")
                        .labelVisibility(JtComponent.LabelVisibility.HIDDEN)
                        .placeholder("api key")
                        .width(600)
                        .use( cloudModelCols.col(1));
            if( apikey == null ) {
                Jt.error("API KEY cannot be null").use();
            }
            else {
                System.setProperty("OPENAI_API_KEY", apikey);
            }
        }
        else {
            model = Jt.radio("Available models",
                    List.of("qwen2.5:7b", "qwen3:8b", "gpt-oss:20b")).use();
        }

        Jt.divider("hr3").use();

        var uploadedImage = Jt.fileUploader("Diagram Image")
                .acceptMultipleFiles(FileUploaderComponent.MultipleFiles.FALSE)
                .type(List.of("image/png", "image/jpeg"))
                .use();

        Jt.divider("hr1").use();
        var imageSource = Jt.empty().key("imageSource").use();

        Jt.divider("hr2").use();

        var plantumlResult = Jt.empty().key("plantumlResult").use();

        if (!uploadedImage.isEmpty()) {

            JtUploadedFile image = uploadedImage.getFirst();
            Jt.image(image.content()).use(imageSource);

            var buttonState = Jt.button("Process Image")
                                .disabled( model == null )
                                .use();
            if (buttonState) {
                var processProgressInfo = Jt.container().key("processProgressInfo").use();

                Disposable disposable = null;
                try {

                    ChatModel chatModel;
                    if( "gpt-4o-mini".equals(model)) {
                        chatModel = AIModel.OPENAI.model(model);
                    }
                    else {
                        chatModel = AIModel.OLLAMA.model(model);
                    }
                    disposable = describeDiagramFromImageTest(chatModel, image, processProgressQueue());

                    for (var notification : processProgress()) {
                        if (notification instanceof Notification.Logging(McpSchema.LoggingMessageNotification value)) {

                            final var log = "[%s] - %s::%s ".formatted(now(), value.logger(), value.data());
                            Jt.text(log).use(processProgressInfo);

                        }
                    }

                    var result = processProgress().resultValue().orElse("@startuml\n@enduml\n");

                    var imageUrl = PlantumlTools.toImageUrl( String.valueOf(result) ).block();
                    Jt.image(imageUrl).use(plantumlResult);

                } catch (Exception ex) {
                    Jt.error(ex.getMessage()).use(processProgressInfo);
                } finally {
                    if( disposable != null ) {
                        disposable.dispose();
                    }
                }
            }

        }

    }

    private Mono<Void> sendProgressCompleteNotification( BlockingQueue<AsyncGenerator.Data<Notification>> notificationQueue, Object result) {

        return Mono.fromRunnable(() -> {
            System.out.printf("%s - SEND PROGRESS COMPLETE NOTIFICATION Thread [%s]%n",
                    now(),
                    Thread.currentThread().getName());
            notificationQueue.offer(AsyncGenerator.Data.done(result));
        });
    }

    private Mono<Void> sendProgressErrorNotification(BlockingQueue<AsyncGenerator.Data<Notification>> notificationQueue, Throwable ex) {

        return Mono.fromRunnable(() -> {
            System.out.printf("%s - SEND PROGRESS ERROR NOTIFICATION Thread [%s]%n",
                    now(),
                    Thread.currentThread().getName());
            notificationQueue.offer(AsyncGenerator.Data.error(ex));
        });


    }

    private Function<McpSchema.ProgressNotification, Mono<Void>> sendProgressNotification(BlockingQueue<AsyncGenerator.Data<Notification>> notificationQueue) {

        return (progress) ->
                Mono.fromRunnable(() -> {
/*
                System.out.printf("%s - SEND PROGRESS '%s' NOTIFICATION thread [%s]%n",
                        now(),
                        "%s-%f".formatted(progress.message(), progress.progress()),
                        Thread.currentThread().getName());
*/
                    notificationQueue.offer(AsyncGenerator.Data.of(Notification.progress(progress)));
                    notificationQueue.offer(AsyncGenerator.Data.of(Notification.ACK));
                });
    }

    private Function<McpSchema.LoggingMessageNotification, Mono<Void>> sendLogNotification(BlockingQueue<AsyncGenerator.Data<Notification>> notificationQueue) {

        return (loggingMessage) ->
                Mono.fromRunnable(() -> {
/*
                System.out.printf("%s - LOG NOTIFICATION [%s:%s] thread [%s]%n",
                        now(),
                        loggingMessage.logger(),
                        loggingMessage.data(),
                        Thread.currentThread().getName());
*/
                    notificationQueue.offer(AsyncGenerator.Data.of(Notification.logging(loggingMessage)));
                    notificationQueue.offer(AsyncGenerator.Data.of(Notification.ACK));
                });
    }


    Disposable describeDiagramFromImageTest( ChatModel chatModel,
            JtUploadedFile uploadedImage,
            BlockingQueue<AsyncGenerator.Data<Notification>> notificationQueue)
    {

        var clientTransport = new InMemoryClientTransport(transport());

        final var imageResource = new ByteArrayResource(uploadedImage.content());
        final var mimeType = MimeType.valueOf(uploadedImage.contentType());

        final var chatVisionModel = AIModel.OLLAMA.model("qwen3-vl:latest");

        var client = McpClient.async(clientTransport)
                .requestTimeout(Duration.ofMinutes(10))
                //.progressConsumer( sendProgressNotification(notificationQueue) )
                .loggingConsumer(sendLogNotification(notificationQueue))
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .sampling()
                        .build())
                .sampling(request -> {

                    final var instruction = (McpSchema.TextContent) request.messages().getFirst().content();

                    var response = request.modelPreferences().hints().stream()
                            .filter(h -> "vision".equalsIgnoreCase(h.name()))
                            .findFirst()
                            .map(h -> {
                                /*
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

                                 */
                                return ChatResponse.builder()
                                        .generations(List.of(new Generation(AssistantMessage.builder()
                                                .content("""
                                                        {
                                                          "type": "process",
                                                          "title": "Agent Interaction Diagram",
                                                          "participants": [
                                                            {
                                                              "name": "Task",
                                                              "shape": "rectangle",
                                                              "description": "Initial task to be processed"
                                                            },
                                                            {
                                                              "name": "LLM",
                                                              "shape": "rectangle",
                                                              "description": "Large Language Model for reasoning"
                                                            },
                                                            {
                                                              "name": "Tools",
                                                              "shape": "rectangle",
                                                              "description": "Tools used by the agent"
                                                            },
                                                            {
                                                              "name": "Environment",
                                                              "shape": "rectangle",
                                                              "description": "External environment where actions take place"
                                                            }
                                                          ],
                                                          "relations": [
                                                            {
                                                              "source": "Task",
                                                              "target": "LLM",
                                                              "description": "Task is sent to LLM"
                                                            },
                                                            {
                                                              "source": "LLM",
                                                              "target": "Tools",
                                                              "description": "LLM uses tools"
                                                            },
                                                            {
                                                              "source": "Tools",
                                                              "target": "Environment",
                                                              "description": "Action is performed in the environment"
                                                            },
                                                            {
                                                              "source": "Environment",
                                                              "target": "Tools",
                                                              "description": "Environment returns result to tools"
                                                            },
                                                            {
                                                              "source": "Tools",
                                                              "target": "LLM",
                                                              "description": "Tools provide feedback to LLM"
                                                            }
                                                          ],
                                                          "containers": [
                                                            {
                                                              "name": "Agent",
                                                              "children": ["LLM", "Tools"],
                                                              "description": "Agent containing LLM and Tools"
                                                            }
                                                          ],
                                                          "description": [
                                                            "1. Task is sent to the LLM.",
                                                            "2. LLM processes the task and uses Tools.",
                                                            "3. Tools perform an Action in the Environment.",
                                                            "4. Environment returns a Result to the Tools.",
                                                            "5. Tools provide feedback to the LLM for further Reasoning."
                                                          ]
                                                        }
                                                        
                                                        """).build())))
                                        .build();
                            }).orElseGet(() -> {
                                var userMessage = UserMessage.builder()
                                        .text(instruction.text())
                                        .build();
                                return ChatClient.builder(chatModel)
                                        .build()
                                        .prompt()
                                        .messages(userMessage)
                                        .call()
                                        .chatResponse();
                            });


                    return Mono.just(McpSchema.CreateMessageResult.builder()
                            .message(requireNonNull(response)
                                    .getResult()
                                    .getOutput()
                                    .getText())
                            .build())
                            ;

                })
                .build();

        return client.initialize()
                //.flatMap( init -> client.setLoggingLevel( McpSchema.LoggingLevel.DEBUG ))
                .flatMap(init -> {
                    var callToolRequest = McpSchema.CallToolRequest.builder()
                            .name("describe_diagram_from_image")
                            .progressToken("describe_diagram_from_image")
                            .build();

                    System.out.printf("CALL TOOL [%s]%n", Thread.currentThread().getName());
                    return client.callTool(callToolRequest);

                })
                .map(result -> result.content().stream()
                        .filter(content -> content instanceof McpSchema.TextContent)
                        .map(content -> ((McpSchema.TextContent) content))
                        .map(McpSchema.TextContent::text)
                        .collect(Collectors.joining("\n")))
                //.subscribeOn( Schedulers.newSingle("call-tool-thread"))
                .doFinally(signal -> client.closeGracefully().subscribe())
                .subscribe(value -> {
                    sendProgressCompleteNotification(notificationQueue, value).block();
                }, ex -> {
                    sendProgressErrorNotification(notificationQueue, ex).block();
                });

    }

}
