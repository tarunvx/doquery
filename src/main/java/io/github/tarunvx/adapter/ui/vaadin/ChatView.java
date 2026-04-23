package io.github.tarunvx.adapter.ui.vaadin;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import io.github.tarunvx.core.model.Citation;
import io.github.tarunvx.core.port.in.RagFacade;
import io.github.tarunvx.core.port.in.RagFacade.KnowledgeMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@PageTitle("Chat | Doquery")
@Route(value = "chat", layout = MainLayout.class)
public class ChatView extends VerticalLayout {

    private static final String BOT = "Assistant";
    private static final String USER = "You";
    /** Don't push to the browser more often than this while streaming. */
    private static final long UI_PUSH_INTERVAL_MS = 80;
    private static final String[] THINKING_FRAMES = {"Thinking ⠋", "Thinking ⠙", "Thinking ⠹",
            "Thinking ⠸", "Thinking ⠼", "Thinking ⠴", "Thinking ⠦", "Thinking ⠧", "Thinking ⠇", "Thinking ⠏"};

    private final RagFacade rag;
    private final List<MessageListItem> history = new ArrayList<>();
    private final MessageList messageList = new MessageList();
    private final ComboBox<String> storePicker = new ComboBox<>("Vector store");
    private final Checkbox streamToggle = new Checkbox("Stream response");
    private final Checkbox generalKnowledgeToggle = new Checkbox("Allow general knowledge");
    private final TextField input = new TextField();
    private final Button send = new Button("Send");

    public ChatView(RagFacade rag) {
        this.rag = rag;
        setSizeFull();
        setPadding(true);
        add(new H2("Ask your documents"));

        storePicker.setAllowCustomValue(true);
        storePicker.setHelperText("Pick which store to query");
        storePicker.addCustomValueSetListener(e -> storePicker.setValue(e.getDetail()));
        storePicker.setWidth("320px");

        streamToggle.setValue(rag.isStreamingEnabled());
        generalKnowledgeToggle.setValue(rag.defaultMode() == KnowledgeMode.HYBRID);
        generalKnowledgeToggle.getElement().setProperty("title",
                "If unchecked, the assistant answers ONLY from the selected vector store. " +
                "If checked, it falls back to the model's general knowledge when the documents " +
                "are insufficient (clearly marked as [general knowledge]).");

        HorizontalLayout options = new HorizontalLayout(storePicker, streamToggle, generalKnowledgeToggle);
        options.setAlignItems(FlexComponent.Alignment.END);

        messageList.setSizeFull();
        messageList.getStyle().set("flex-grow", "1");

        input.setPlaceholder("Ask a question…");
        input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.EAGER); // commit value on every keystroke
        input.setClearButtonVisible(true);

        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(e -> submit());
        send.addClickShortcut(Key.ENTER);

        HorizontalLayout bar = new HorizontalLayout(input, send);
        bar.setWidthFull();
        bar.expand(input);

        add(options, messageList, bar);
        setFlexGrow(1, messageList);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        List<String> ids = rag.listStores();
        storePicker.setItems(ids);
        storePicker.setValue(ids.contains("default") ? "default"
                : (ids.isEmpty() ? "default" : ids.get(0)));
    }

    private void submit() {
        String q = input.getValue();
        if (q == null || q.isBlank()) return;
        // Move question OUT of the input field immediately
        input.clear();
        setBusy(true);

        String storeId = storePicker.getValue();
        boolean streaming = streamToggle.getValue();
        KnowledgeMode mode = generalKnowledgeToggle.getValue() ? KnowledgeMode.HYBRID : KnowledgeMode.STRICT;

        append(USER, q);
        MessageListItem botMsg = append(BOT + " (" + storeId + (mode == KnowledgeMode.HYBRID ? " · hybrid" : "") + ")", "");

        UI ui = UI.getCurrent();
        ThinkingTicker ticker = new ThinkingTicker(ui, botMsg);
        ticker.start();

        CompletableFuture.supplyAsync(() -> {
            if (streaming) {
                StringBuilder buf = new StringBuilder();
                AtomicLong lastPush = new AtomicLong(0);
                return rag.askStreaming(storeId, q, mode, token -> {
                    ticker.stop();
                    buf.append(token);
                    long now = System.currentTimeMillis();
                    long prev = lastPush.get();
                    // Throttle UI pushes to ~UI_PUSH_INTERVAL_MS to avoid flooding the websocket
                    if (now - prev >= UI_PUSH_INTERVAL_MS && lastPush.compareAndSet(prev, now)) {
                        String snapshot = buf.toString();
                        ui.access(() -> {
                            botMsg.setText(snapshot);
                            messageList.setItems(history);
                        });
                    }
                });
            }
            return rag.ask(storeId, q, mode);
        }).whenComplete((answer, err) -> ui.access(() -> {
            ticker.stop();
            setBusy(false);
            if (err != null) {
                botMsg.setText("⚠ Error: " + rootMessage(err));
            } else {
                String text = answer.text();
                if (!answer.citations().isEmpty()) {
                    String cites = answer.citations().stream().map(this::format)
                            .collect(Collectors.joining(", "));
                    text = text + "\n\nSources: " + cites;
                }
                botMsg.setText(text);
            }
            messageList.setItems(history);
            input.focus();
        }));
    }

    private void setBusy(boolean busy) {
        input.setEnabled(!busy);
        send.setEnabled(!busy);
        send.setText(busy ? "…" : "Send");
    }

    private String format(Citation c) {
        return c.page() != null ? c.source() + " p." + c.page() : c.source();
    }

    private MessageListItem append(String user, String text) {
        MessageListItem item = new MessageListItem(text, Instant.now(), user);
        history.add(item);
        messageList.setItems(history);
        return item;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    /** Animated braille-spinner placeholder shown until first token arrives. */
    private static final class ThinkingTicker {
        private final UI ui;
        private final MessageListItem target;
        private volatile boolean running = true;
        private Thread thread;

        ThinkingTicker(UI ui, MessageListItem target) {
            this.ui = ui;
            this.target = target;
        }

        void start() {
            thread = new Thread(() -> {
                int i = 0;
                while (running) {
                    final String frame = THINKING_FRAMES[i++ % THINKING_FRAMES.length];
                    try {
                        ui.access(() -> { if (running) target.setText(frame); });
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "chat-thinking-ticker");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            if (thread != null) thread.interrupt();
        }
    }
}

