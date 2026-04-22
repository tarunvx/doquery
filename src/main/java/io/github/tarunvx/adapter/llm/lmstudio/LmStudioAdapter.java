package io.github.tarunvx.adapter.llm.lmstudio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * LM Studio LLM adapter.
 *
 * <p>LM Studio exposes an OpenAI-compatible API. We rely on Spring AI's
 * {@code spring-ai-starter-model-openai} auto-configuration: just supplying
 * the {@code spring.ai.openai.*} properties (see application.yml) is enough
 * to get a {@code ChatModel}, {@code EmbeddingModel} and a {@code ChatClient.Builder}.</p>
 *
 * <p>To swap providers (e.g. Ollama, Anthropic, real OpenAI):</p>
 * <ol>
 *   <li>Replace the starter dependency in {@code pom.xml}.</li>
 *   <li>Set {@code app.llm.provider=<name>} and switch {@code @ConditionalOnProperty} below.</li>
 *   <li>Adjust {@code spring.ai.<provider>.*} properties.</li>
 * </ol>
 *
 * The core layer never sees provider specifics — it consumes only
 * {@code ChatClient} / {@code EmbeddingModel} from Spring AI.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "lmstudio", matchIfMissing = true)
public class LmStudioAdapter {
    // No beans needed — Spring AI auto-config provides ChatModel/EmbeddingModel/ChatClient.Builder.
    // This class exists as the explicit "adapter switch" toggled by app.llm.provider.
}

