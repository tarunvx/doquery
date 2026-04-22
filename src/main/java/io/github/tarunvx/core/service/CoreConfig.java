package io.github.tarunvx.core.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-agnostic pieces of the core layer.
 * The {@link ChatClient} is built from a {@link ChatClient.Builder} that the LLM adapter provides
 * (Spring AI auto-config). Swap LLM provider = swap LLM adapter only.
 */
@Configuration
public class CoreConfig {

    private static final String SYSTEM_PROMPT = """
            You are Doquery, a precise assistant. Answer the user's question using ONLY the
            provided CONTEXT. If the context does not contain the answer, reply exactly:
            "I cannot find the answer in the provided documents."
            Be concise. Do not invent citations.
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    @Bean
    public TextSplitter textSplitter() {
        return new TokenTextSplitter(800, 350, 5, 10000, true);
    }
}


