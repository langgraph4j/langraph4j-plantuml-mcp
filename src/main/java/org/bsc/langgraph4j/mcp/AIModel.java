package org.bsc.langgraph4j.mcp;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.util.function.Function;

public enum AIModel {

    OPENAI_VISION( name ->
            OpenAiChatModel.builder()
            .openAiApi(OpenAiApi.builder()
                    //.baseUrl("https://api.openai.com")
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .build())
            .defaultOptions(OpenAiChatOptions.builder()
                    .model(name)
                    .logprobs(false)
                    .temperature(0.1)
                    .build())
            .build()),
    OLLAMA_VISION( name ->
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

    public ChatModel model( String name ) {
        return model.apply( name );
    }

    AIModel(Function<String,ChatModel> model) {
        this.model = model;
    }
}
