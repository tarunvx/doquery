package io.github.tarunvx.core.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-agnostic pieces of the core layer.
 * NOTE: no default system prompt here — {@link RagService} sets it per call so it can switch
 * between strict (context-only) and hybrid (context + general-knowledge) modes.
 */
@Configuration
public class CoreConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public TextSplitter textSplitter(
            @Value("${app.rag.chunk-size:400}") int chunkSize,
            @Value("${app.rag.chunk-overlap:80}") int overlap,
            @Value("${app.rag.min-chunk-chars:5}") int minChars,
            @Value("${app.rag.max-chunks-per-doc:10000}") int maxChunks) {
        return new TokenTextSplitter(chunkSize, overlap, minChars, maxChunks, true);
    }
}
