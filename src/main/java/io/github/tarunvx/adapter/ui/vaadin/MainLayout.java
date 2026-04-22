package io.github.tarunvx.adapter.ui.vaadin;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.RouterLink;

public class MainLayout extends AppLayout {
    public MainLayout() {
        H1 title = new H1("Doquery");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0 var(--lumo-space-m)");
        addToNavbar(new DrawerToggle(), title);
        VerticalLayout nav = new VerticalLayout(
                new RouterLink("Upload", UploadView.class),
                new RouterLink("Chat", ChatView.class));
        nav.setSpacing(false);
        addToDrawer(nav);
    }
}

