package io.github.tarunvx.adapter.ui.vaadin;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import io.github.tarunvx.core.model.Answer;
import io.github.tarunvx.core.model.Citation;
import io.github.tarunvx.core.port.in.RagFacade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@PageTitle("Chat | Doquery")
@Route(value = "chat", layout = MainLayout.class)
public class ChatView extends VerticalLayout {

    private final RagFacade rag;
    private final List<MessageListItem> history = new ArrayList<>();
    private final MessageList messageList = new MessageList();

    public ChatView(RagFacade rag) {
        this.rag = rag;
        setSizeFull();
        setPadding(true);
        add(new H2("Ask your documents"));

        messageList.setSizeFull();
        messageList.getStyle().set("flex-grow", "1");

        TextField input = new TextField();
        input.setPlaceholder("Ask a question…");
        input.setWidthFull();

        Button send = new Button("Send", e -> submit(input));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickShortcut(Key.ENTER);

        HorizontalLayout bar = new HorizontalLayout(input, send);
        bar.setWidthFull();
        bar.expand(input);

        add(messageList, bar);
        setFlexGrow(1, messageList);
    }

    private void submit(TextField input) {
        String q = input.getValue();
        if (q == null || q.isBlank()) return;
        input.clear();
        append("You", q);
        try {
            Answer a = rag.ask(q);
            String text = a.text();
            if (!a.citations().isEmpty()) {
                String cites = a.citations().stream().map(this::format).collect(Collectors.joining(", "));
                text = text + "\n\nSources: " + cites;
            }
            append("Doquery", text);
        } catch (Exception ex) {
            Notification.show("Error: " + ex.getMessage(), 4000, Notification.Position.TOP_END);
        }
    }

    private String format(Citation c) {
        return c.page() != null ? c.source() + " p." + c.page() : c.source();
    }

    private void append(String user, String text) {
        history.add(new MessageListItem(text, Instant.now(), user));
        messageList.setItems(history);
    }
}

