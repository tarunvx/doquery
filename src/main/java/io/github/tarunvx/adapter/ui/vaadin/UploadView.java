package io.github.tarunvx.adapter.ui.vaadin;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MultiFileMemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import io.github.tarunvx.core.port.in.RagFacade;

@PageTitle("Upload | Doquery")
@Route(value = "", layout = MainLayout.class)
public class UploadView extends VerticalLayout {

    public UploadView(RagFacade rag) {
        setSizeFull();
        setPadding(true);
        add(new H2("Upload PDF Documents"),
            new Paragraph("Upload PDFs (max 25 MB). Parsed, embedded and stored for retrieval."));

        MultiFileMemoryBuffer buffer = new MultiFileMemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes("application/pdf", ".pdf");
        upload.setMaxFileSize(25 * 1024 * 1024);

        upload.addSucceededListener(e -> {
            String name = e.getFileName();
            try {
                int chunks = rag.ingest(name, buffer.getInputStream(name));
                Notification n = Notification.show("Ingested " + name + " (" + chunks + " chunks)",
                        3000, Notification.Position.TOP_END);
                n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                Notification n = Notification.show("Failed: " + name + " — " + ex.getMessage(),
                        5000, Notification.Position.TOP_END);
                n.addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });

        upload.addFileRejectedListener(e -> {
            Notification n = Notification.show("Rejected: " + e.getErrorMessage(),
                    4000, Notification.Position.TOP_END);
            n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        });

        add(upload);
    }
}

