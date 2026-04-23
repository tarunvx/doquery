package io.github.tarunvx.adapter.ui.vaadin;

import com.vaadin.flow.component.combobox.ComboBox;
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
            new Paragraph("Pick (or type) a vector store, then upload PDFs (max 25 MB)."));

        ComboBox<String> storePicker = new ComboBox<>("Vector store");
        storePicker.setAllowCustomValue(true);
        storePicker.setHelperText("Select an existing store or type a new id to create one");
        storePicker.setItems(rag.listStores());
        storePicker.setValue("default");
        storePicker.addCustomValueSetListener(e -> storePicker.setValue(e.getDetail()));
        storePicker.setWidth("320px");

        MultiFileMemoryBuffer buffer = new MultiFileMemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes("application/pdf", ".pdf");
        upload.setMaxFileSize(25 * 1024 * 1024);

        upload.addSucceededListener(e -> {
            String name = e.getFileName();
            String storeId = storePicker.getValue();
            try {
                int chunks = rag.ingest(storeId, name, buffer.getInputStream(name));
                Notification n = Notification.show(
                        "Ingested " + name + " into '" + storeId + "' (" + chunks + " chunks)",
                        3000, Notification.Position.TOP_END);
                n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                storePicker.setItems(rag.listStores());
                storePicker.setValue(storeId);
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

        add(storePicker, upload);
    }
}
