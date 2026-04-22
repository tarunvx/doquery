# Doquery — Hexagonal RAG App

A local-first **Retrieval-Augmented Generation (RAG)** application for querying your own PDFs, built around a strict **ports-and-adapters (hexagonal) architecture** so each of the four layers is independently replaceable.

| Layer | Default | Swap to (any of) |
|---|---|---|
| **UI** | Vaadin Flow | REST controller (already included), CLI, gRPC, Hilla, mobile, Slack bot… |
| **Spring AI Core** *(stays as core)* | `RagService` orchestrating `ChatClient` + `VectorStore` + `TextSplitter` | — |
| **LLM Integration** | LM Studio (OpenAI-compat. via Spring AI) | Ollama, OpenAI, Anthropic, Bedrock, Azure OpenAI, vLLM… |
| **DB / Vector store** | `SimpleVectorStore` (JSON file) | PgVector, Chroma, Redis, Milvus, Pinecone, Weaviate, Qdrant… |

The **only thing UI / LLM / DB layers know about each other is the `RagFacade` port**. Swapping any layer = configuration + maybe a Maven dependency. **No core changes.**

---

## Architecture

```
                       ┌─────────────────────────────────────────┐
                       │                  CORE                   │
   UI ADAPTERS         │  (framework-agnostic orchestration)     │         OUT-PORT IMPLS
   (driving)           │                                         │         (driven)
 ┌──────────────┐      │   ┌────────────────────────┐            │     ┌──────────────────────┐
 │ Vaadin Flow  │ ───► │   │ RagFacade  (in-port)   │            │     │ DocumentParser       │
 │ (UploadView, │      │   ├────────────────────────┤            │ ◄── │   PdfDocumentParser  │
 │  ChatView)   │      │   │ RagService             │            │     │   (DocxParser …)     │
 └──────────────┘      │   │  • orchestration only  │            │     └──────────────────────┘
                       │   │  • talks to Spring AI  │            │     ┌──────────────────────┐
 ┌──────────────┐      │   │    abstractions only   │            │ ◄── │ VectorStore          │
 │ REST API     │ ───► │   └────────┬───────────────┘            │     │   SimpleVectorStore  │
 │ /api/...     │      │            │                            │     │   (PgVector …)       │
 └──────────────┘      │            ▼                            │     └──────────────────────┘
                       │   ┌────────────────────────┐            │     ┌──────────────────────┐
 ┌──────────────┐      │   │ Spring AI primitives   │            │ ◄── │ ChatClient /         │
 │ CLI / gRPC   │ ───► │   │  ChatClient            │            │     │ EmbeddingModel       │
 │ (future)     │      │   │  VectorStore           │            │     │   LM Studio adapter  │
 └──────────────┘      │   │  EmbeddingModel        │            │     │   (Ollama …)         │
                       │   │  TextSplitter          │            │     └──────────────────────┘
                       │   └────────────────────────┘            │     ┌──────────────────────┐
                       │                                         │ ◄── │ VectorStorePersister │
                       │                                         │     │   (file/no-op)       │
                       └─────────────────────────────────────────┘     └──────────────────────┘
```

### Dependency rule

```
adapter.* ──► core.port.{in,out}       ✓ allowed
adapter.* ──► core.model               ✓ allowed
adapter.* ──► core.service             ✗ FORBIDDEN
core.*    ──► adapter.*                ✗ FORBIDDEN
adapter.X ──► adapter.Y                ✗ FORBIDDEN
```

The **core** depends only on Spring AI's framework abstractions (`ChatClient`, `VectorStore`, `EmbeddingModel`, `TextSplitter`) — never on a concrete provider.

---

## Package Layout

```
io.github.tarunvx
├── Application.java                          # @SpringBootApplication
│
├── core/                                     ← Spring AI orchestration. Knows nothing about UI/DB/LLM provider.
│   ├── model/
│   │   ├── Answer.java
│   │   └── Citation.java
│   ├── port/
│   │   ├── in/
│   │   │   └── RagFacade.java                ← single entry point for ALL UIs
│   │   └── out/
│   │       ├── DocumentParser.java           ← parser plug-point
│   │       └── VectorStorePersister.java     ← persistence plug-point
│   └── service/
│       ├── RagService.java                   ← implements RagFacade, uses Spring AI abstractions
│       └── CoreConfig.java                   ← ChatClient + TextSplitter beans
│
├── adapter/
│   ├── ui/
│   │   ├── vaadin/                           ← UI ADAPTER #1 (default)
│   │   │   ├── MainLayout.java
│   │   │   ├── UploadView.java
│   │   │   └── ChatView.java
│   │   └── rest/                             ← UI ADAPTER #2 (REST API)
│   │       └── RagController.java
│   │
│   ├── llm/
│   │   └── lmstudio/                         ← LLM ADAPTER (toggle: app.llm.provider)
│   │       └── LmStudioAdapter.java
│   │
│   ├── store/
│   │   └── simple/                           ← DB ADAPTER (toggle: app.vectorstore.provider)
│   │       └── SimpleVectorStoreAdapter.java
│   │
│   └── parser/
│       └── pdf/                              ← PARSER ADAPTER (auto-routed by supports())
│           └── PdfDocumentParser.java
│
└── resources/application.yml                 ← all adapter switches live here
```

---

## How the layers stay independent

### 1. UI is just a `RagFacade` consumer

Both UI adapters get the **same** `RagFacade` bean:

```java
public UploadView(RagFacade rag) { ... }    // Vaadin
public RagController(RagFacade rag) { ... } // REST
```

Add a CLI? Just write a `CommandLineRunner` that takes `RagFacade`. No core change.

### 2. LLM provider is one Spring AI starter + one switch

```yaml
app.llm.provider: lmstudio
```

`LmStudioAdapter` is `@ConditionalOnProperty(... havingValue="lmstudio")`. Spring AI auto-config exposes `ChatClient.Builder` + `EmbeddingModel`; the core consumes only those interfaces.

To switch to **Ollama**:
1. Swap `spring-ai-starter-model-openai` → `spring-ai-starter-model-ollama` in `pom.xml`.
2. Create `adapter/llm/ollama/OllamaAdapter` mirroring `LmStudioAdapter` with `havingValue="ollama"`.
3. Set `app.llm.provider=ollama`, configure `spring.ai.ollama.*`.
4. Done. Zero `core/` changes.

### 3. Vector store is one bean behind two ports

```yaml
app.vectorstore.provider: simple
```

`SimpleVectorStoreAdapter` provides:
- a `VectorStore` bean (Spring AI abstraction the core uses), and
- a `VectorStorePersister` bean (out-port for explicit save).

To switch to **PgVector**:
1. Add `spring-ai-starter-vector-store-pgvector` + a Postgres datasource.
2. Create `adapter/store/pgvector/PgVectorStoreAdapter` with `havingValue="pgvector"` exposing a `VectorStore` bean and a no-op `VectorStorePersister` (write-through).
3. Set `app.vectorstore.provider=pgvector`. Done.

### 4. Document types are auto-discovered

`RagService` injects `List<DocumentParser>` and routes by `supports(filename)`. Add DOCX support = drop a `TikaDocxParser implements DocumentParser` in `adapter/parser/docx/`. No core change.

---

## Prerequisites

- JDK **21**, Maven **3.9+**
- **Node.js 20 LTS** + npm (Vaadin frontend build)
- **LM Studio** with an instruct model + embedding model loaded, server on `http://localhost:1234`

---

## Configuration

All adapter switches live in [`application.yml`](src/main/resources/application.yml):

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:1234        # Spring AI auto-appends /v1/...
      api-key: lm-studio
      chat:      { options: { model: ${LMSTUDIO_CHAT_MODEL:local-model}, temperature: 0.2 } }
      embedding: { options: { model: ${LMSTUDIO_EMBEDDING_MODEL:nomic-embed-text-v1.5} } }

app:
  llm:         { provider: lmstudio }                      # ← swap LLM here
  vectorstore: { provider: simple, path: ./data/vectorstore.json }   # ← swap DB here
  ingest:      { max-bytes: 26214400 }
  rag:         { top-k: 4, similarity-threshold: 0.5 }
```

Override via env: `SPRING_AI_OPENAI_BASE_URL`, `LMSTUDIO_CHAT_MODEL`, `APP_VECTORSTORE_PROVIDER`, etc.

---

## Build & Run

```bash
# Dev (hot reload)
mvn spring-boot:run

# Production jar (bundles Vaadin frontend)
mvn -Pproduction clean package
java -jar target/doquery-1.0-SNAPSHOT.jar
```

Open <http://localhost:8080> for the Vaadin UI. The REST endpoints are live in parallel:

```bash
# Upload
curl -F "file=@handbook.pdf" http://localhost:8080/api/documents

# Ask
curl -H "Content-Type: application/json" \
     -d '{"question":"What is the leave policy?"}' \
     http://localhost:8080/api/ask
```

Both call the **same** `RagFacade` bean.

---

## Adding a new adapter — minimal recipes

**New UI**
```java
@Component
class CliRunner implements CommandLineRunner {
    private final RagFacade rag;
    CliRunner(RagFacade rag) { this.rag = rag; }
    public void run(String... args) { System.out.println(rag.ask(args[0]).text()); }
}
```

**New LLM provider**
```java
@Configuration
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "ollama")
class OllamaAdapter { /* Spring AI Ollama starter does the heavy lifting */ }
```

**New vector DB**
```java
@Configuration
@ConditionalOnProperty(prefix = "app.vectorstore", name = "provider", havingValue = "pgvector")
class PgVectorAdapter {
    @Bean VectorStore vectorStore(JdbcTemplate j, EmbeddingModel em) { /* … */ }
    @Bean VectorStorePersister persister() { return () -> { /* no-op, write-through */ }; }
}
```

**New document type**
```java
@Component
class DocxParser implements DocumentParser {
    public boolean supports(String n) { return n.toLowerCase().endsWith(".docx"); }
    public List<Document> parse(String n, InputStream in) { /* Tika */ }
}
```

---

## Security & Validation (enforced in core, not UI)

| Concern | Mitigation |
|---|---|
| Path traversal in filename | `Path.getFileName()` + regex allowlist `[A-Za-z0-9._-]` |
| Non-PDF upload | `DocumentParser.supports()` + `%PDF` magic-byte check |
| Oversized upload | Bounded `InputStream` enforced inside `RagService` — UI cannot bypass |
| Hallucination | Strict grounded system prompt + similarity threshold + refusal phrase |
| State leaks | All beans constructor-injected; `final` fields; no static state |

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Connection refused` to `127.0.0.1:1234` | LM Studio server not started | Start Server in LM Studio Developer tab |
| Doubled path `/v1/v1/embeddings` | `base-url` includes `/v1` | Use `http://localhost:1234` only |
| `Theme directory not found` | Missing theme folder | Provided at `src/main/frontend/themes/doquery/` |
| `npm not found` | System Node missing | `brew install node@20` and add to PATH |
| Refusal answers for known content | Threshold too high | Lower `app.rag.similarity-threshold` |

---

## Roadmap

- Streaming responses (`chatClient...stream()`) into Vaadin `MessageList`
- Conversation memory via `MessageChatMemoryAdvisor`
- Per-user partitioning + Spring Security
- Reranker step between retrieval and prompt
- Additional adapters: `ollama`, `pgvector`, `chroma`, `tika-docx`

## License

## Developer
Tarun Vishwakarma

