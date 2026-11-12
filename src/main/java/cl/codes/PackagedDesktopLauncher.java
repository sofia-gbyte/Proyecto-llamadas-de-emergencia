package cl.codes;

import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * The native jpackage executable opens this small menu instead of requiring
 * users to run the repository's development batch file.
 */
final class PackagedDesktopLauncher {
    private static final Logger log = LoggerFactory.getLogger(PackagedDesktopLauncher.class);
    private static final int DEFAULT_PORT = 8000;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Color BACKGROUND = new Color(10, 20, 40);
    private static final Color SURFACE = new Color(15, 28, 48);
    private static final Color SURFACE_RAISED = new Color(20, 36, 60);
    private static final Color BORDER = new Color(38, 58, 88);
    private static final Color TEXT = new Color(232, 238, 251);
    private static final Color MUTED = new Color(143, 166, 204);
    private static final Color ACCENT = new Color(0, 200, 176);

    private final String[] applicationArgs;
    private final JFrame frame = new JFrame("CODES - Centro de Emergencias");
    private final JTextArea status = new JTextArea(6, 48);
    private final JButton startButton = new JButton("Iniciar CODES");
    private final JLabel stateBadge = new JLabel("LISTO PARA INICIAR");
    private final JLabel stateTitle = new JLabel("Tu entorno local está preparado");
    private final JLabel stateDetail = new JLabel("Inicia el sistema para abrir la consola de operaciones.");
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile ConfigurableApplicationContext applicationContext;
    private boolean configurationChanged;

    private PackagedDesktopLauncher(String[] applicationArgs) {
        this.applicationArgs = applicationArgs.clone();
    }

    static void launch(String[] args) {
        configureLookAndFeel();
        SwingUtilities.invokeLater(() -> new PackagedDesktopLauncher(args).show());
    }

    private void show() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(buildMainPanel());
        loadWindowIcon();
        updateDashboard();
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setPreferredSize(new Dimension(1040, 700));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                exit();
            }
        });
        frame.setVisible(true);
    }

    private JPanel buildMainPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BACKGROUND);
        root.add(buildHeader(), BorderLayout.NORTH);
        JPanel content = new JPanel(new BorderLayout(22, 0));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        content.add(buildNavigation(), BorderLayout.WEST);
        content.add(buildDashboard(), BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(new Color(9, 17, 32));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(15, 24, 15, 24)));

        JPanel brand = new JPanel(new BorderLayout(13, 0));
        brand.setOpaque(false);
        JLabel logo = new JLabel();
        ImageIcon icon = loadLogo();
        if (icon != null) logo.setIcon(new ImageIcon(icon.getImage().getScaledInstance(46, 46, Image.SCALE_SMOOTH)));
        brand.add(logo, BorderLayout.WEST);

        JPanel wordmark = new JPanel();
        wordmark.setOpaque(false);
        wordmark.setLayout(new BoxLayout(wordmark, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("CODES");
        name.setForeground(TEXT);
        name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 21));
        JLabel description = new JLabel("Centro de Operaciones y Despacho de Emergencias");
        description.setForeground(MUTED);
        description.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        wordmark.add(name);
        wordmark.add(Box.createVerticalStrut(3));
        wordmark.add(description);
        brand.add(wordmark, BorderLayout.CENTER);
        header.add(brand, BorderLayout.WEST);

        JLabel localBadge = new JLabel("  ●  SOLO EN ESTE EQUIPO  ");
        localBadge.setOpaque(true);
        localBadge.setBackground(new Color(15, 45, 54));
        localBadge.setForeground(new Color(99, 222, 207));
        localBadge.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        localBadge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(27, 88, 85), 1, true),
                BorderFactory.createEmptyBorder(8, 4, 8, 4)));
        header.add(localBadge, BorderLayout.EAST);
        return header;
    }

    private JPanel buildNavigation() {
        JPanel navigation = new JPanel();
        navigation.setBackground(BACKGROUND);
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));
        navigation.setPreferredSize(new Dimension(258, 0));
        JLabel section = sectionLabel("MENÚ PRINCIPAL");
        section.setBorder(BorderFactory.createEmptyBorder(0, 2, 12, 0));
        navigation.add(section);

        styleButton(startButton, true);
        startButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        startButton.setPreferredSize(new Dimension(258, 56));
        startButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        startButton.setToolTipText("Iniciar o detener el servidor local de CODES.");
        startButton.addActionListener(event -> toggleServer());
        navigation.add(startButton);
        navigation.add(Box.createVerticalStrut(18));

        JButton integrations = navigationButton("Configuración", "Claves, mapa y servicios externos");
        integrations.addActionListener(event -> runSafely(this::configureIntegrations));
        navigation.add(integrations);
        navigation.add(Box.createVerticalStrut(8));

        JButton data = navigationButton("Datos y respaldos", "Copias de seguridad y limpieza");
        data.addActionListener(event -> runSafely(this::manageLocalData));
        navigation.add(data);
        navigation.add(Box.createVerticalStrut(8));

        JButton diagnostics = navigationButton("Diagnóstico", "Revisar el estado de CODES");
        diagnostics.addActionListener(event -> runSafely(this::showDiagnostics));
        navigation.add(diagnostics);
        navigation.add(Box.createVerticalGlue());

        JButton exit = new JButton("Cerrar CODES");
        styleButton(exit, false);
        exit.setAlignmentX(Component.LEFT_ALIGNMENT);
        exit.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        exit.addActionListener(event -> exit());
        navigation.add(exit);
        return navigation;
    }

    private JPanel buildDashboard() {
        JPanel dashboard = new JPanel();
        dashboard.setBackground(BACKGROUND);
        dashboard.setLayout(new BoxLayout(dashboard, BoxLayout.Y_AXIS));
        dashboard.add(buildStatusCard());
        dashboard.add(Box.createVerticalStrut(14));

        JPanel dataCard = cardPanel();
        dataCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        dataCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        dataCard.setLayout(new BoxLayout(dataCard, BoxLayout.Y_AXIS));
        dataCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(17, 18, 17, 18)));
        dataCard.add(sectionLabel("ALMACENAMIENTO LOCAL"));
        dataCard.add(Box.createVerticalStrut(8));
        JLabel dataPath = new JLabel("<html><div style='width:520px'>" + escapeHtml(dataDirectory().toString())
                + "</div></html>");
        dataPath.setForeground(TEXT);
        dataPath.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        dataCard.add(dataPath);
        JLabel dataHint = new JLabel("Tus datos se conservan aquí al actualizar CODES.");
        dataHint.setForeground(MUTED);
        dataHint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dataHint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        dataCard.add(dataHint);
        dashboard.add(dataCard);
        dashboard.add(Box.createVerticalStrut(14));

        JPanel activityCard = cardPanel();
        activityCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        activityCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        activityCard.setLayout(new BorderLayout(0, 10));
        activityCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        activityCard.add(sectionLabel("ACTIVIDAD"), BorderLayout.NORTH);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setRows(5);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        status.setForeground(new Color(190, 207, 232));
        status.setBackground(SURFACE);
        status.setCaretColor(ACCENT);
        status.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        status.setFocusable(false);
        status.setText("CODES está listo. El servidor aún no se ha iniciado.");
        JScrollPane activityScroll = new JScrollPane(status);
        activityScroll.setBorder(BorderFactory.createEmptyBorder());
        activityScroll.getViewport().setBackground(SURFACE);
        activityCard.add(activityScroll, BorderLayout.CENTER);
        dashboard.add(activityCard);
        dashboard.add(Box.createVerticalGlue());
        JLabel safetyNote = new JLabel("CODES no admite conexiones desde otros equipos.");
        safetyNote.setForeground(MUTED);
        safetyNote.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        safetyNote.setBorder(BorderFactory.createEmptyBorder(12, 2, 0, 0));
        dashboard.add(safetyNote);
        return dashboard;
    }

    private JPanel buildStatusCard() {
        JPanel card = cardPanel();
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.setLayout(new BorderLayout(14, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(19, 20, 19, 20)));

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        stateBadge.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        stateBadge.setForeground(new Color(99, 222, 207));
        stateBadge.setAlignmentX(Component.LEFT_ALIGNMENT);
        stateTitle.setForeground(TEXT);
        stateTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        stateTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        stateTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 5, 0));
        stateDetail.setForeground(MUTED);
        stateDetail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        stateDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(stateBadge);
        copy.add(stateTitle);
        copy.add(stateDetail);
        card.add(copy, BorderLayout.CENTER);
        return card;
    }

    private JButton navigationButton(String title, String detail) {
        JButton button = new JButton("<html><div style='text-align:left'><b>" + escapeHtml(title)
                + "</b><br><span style='font-size:10px'>" + escapeHtml(detail) + "</span></div></html>");
        styleButton(button, false);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setPreferredSize(new Dimension(258, 54));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        return button;
    }

    private static JPanel cardPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(SURFACE);
        return panel;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        return label;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void styleButton(JButton button, boolean primary) {
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, primary ? 14 : 12));
        button.setFocusPainted(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setOpaque(true);
        if (primary) {
            button.setBackground(ACCENT);
            button.setForeground(new Color(6, 17, 31));
            button.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        } else {
            button.setBackground(SURFACE_RAISED);
            button.setForeground(TEXT);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER, 1, true),
                    BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        }
    }

    private static void configureLookAndFeel() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("TabbedPane.background", SURFACE);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", SURFACE_RAISED);
        UIManager.put("TextField.background", SURFACE_RAISED);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("PasswordField.background", SURFACE_RAISED);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("CheckBox.background", SURFACE);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("Button.background", SURFACE_RAISED);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("ScrollPane.background", SURFACE);
    }

    private static ImageIcon loadLogo() {
        try (InputStream stream = PackagedDesktopLauncher.class.getResourceAsStream("/static/codes-logo.png")) {
            if (stream == null) return null;
            return new ImageIcon(ImageIO.read(stream));
        } catch (IOException exception) {
            log.warn("Could not load the packaged CODES logo: {}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private void loadWindowIcon() {
        ImageIcon logo = loadLogo();
        if (logo != null) frame.setIconImage(logo.getImage());
    }

    private void updateDashboard() {
        if (configurationChanged) {
            stateBadge.setText("REINICIO REQUERIDO");
            stateBadge.setForeground(new Color(251, 191, 36));
            stateTitle.setText("Configuración guardada");
            stateDetail.setText("Cierra y vuelve a abrir CODES para aplicar los cambios.");
        } else if (starting.get()) {
            stateBadge.setText("INICIANDO");
            stateBadge.setForeground(new Color(251, 191, 36));
            stateTitle.setText("Iniciando el servidor local");
            stateDetail.setText("Esto puede tardar unos segundos; mantén esta ventana abierta.");
        } else if (applicationContext != null) {
            stateBadge.setText("SISTEMA ACTIVO");
            stateBadge.setForeground(ACCENT);
            stateTitle.setText("CODES está en funcionamiento");
            stateDetail.setText("La consola está disponible en http://127.0.0.1:" + serverPort());
        } else {
            stateBadge.setText("LISTO PARA INICIAR");
            stateBadge.setForeground(new Color(99, 222, 207));
            stateTitle.setText("Tu entorno local está preparado");
            stateDetail.setText("Inicia el sistema para abrir la consola de operaciones.");
        }
    }

    private void toggleServer() {
        if (configurationChanged) {
            showMessage("La configuración cambió. Cierre y vuelva a abrir CODES para aplicarla.",
                    "Reinicio requerido", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (applicationContext != null) {
            stopServer();
            return;
        }
        if (!starting.compareAndSet(false, true)) return;
        startButton.setEnabled(false);
        startButton.setText("Iniciando...");
        updateDashboard();
        appendStatus("Iniciando CODES; el navegador se abrirá cuando el servicio local esté listo.");
        Thread starter = new Thread(() -> {
            try {
                ConfigurableApplicationContext context = CodesApplication.startServer(applicationArgs);
                applicationContext = context;
                SwingUtilities.invokeLater(() -> {
                    startButton.setText("Detener CODES");
                    startButton.setEnabled(true);
                    updateDashboard();
                    appendStatus("CODES está activo en http://127.0.0.1:" + serverPort() + ".");
                });
            } catch (RuntimeException exception) {
                String reference = UUID.randomUUID().toString().substring(0, 8);
                String safeMessage = startupFailureMessage(exception, serverPort());
                log.error("Packaged CODES startup failed [{}]: {} ({})",
                        reference, safeMessage, exception.getClass().getSimpleName());
                SwingUtilities.invokeLater(() -> {
                    startButton.setText("Iniciar CODES");
                    startButton.setEnabled(true);
                    updateDashboard();
                    appendStatus(safeMessage + " Referencia: " + reference + ".");
                    showMessage(safeMessage + "\n\nReferencia local: " + reference, "Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                starting.set(false);
            }
        }, "codes-packaged-startup");
        starter.setDaemon(true);
        starter.start();
    }

    private void stopServer() {
        ConfigurableApplicationContext context = applicationContext;
        if (context == null) return;
        applicationContext = null;
        startButton.setText("Deteniendo...");
        startButton.setEnabled(false);
        updateDashboard();
        Thread stopper = new Thread(() -> {
            try {
                context.close();
            } catch (RuntimeException exception) {
                log.warn("CODES shutdown reported an error: {}", exception.getClass().getSimpleName());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    startButton.setText("Iniciar CODES");
                    startButton.setEnabled(true);
                    updateDashboard();
                    appendStatus("CODES se detuvo.");
                });
            }
        }, "codes-packaged-shutdown");
        stopper.setDaemon(true);
        stopper.start();
    }

    private void showDiagnostics() {
        StringBuilder report = new StringBuilder("Diagnóstico local (sin conexiones a terceros)\n\n");
        report.append("Runtime incluido: Java ").append(System.getProperty("java.version")).append('\n');
        report.append("App-image: ").append(isPackagedRuntime() ? "sí" : "no detectado").append('\n');
        report.append("Puerto local ").append(serverPort()).append(": ")
                .append(isPortInUse(serverPort()) ? "ocupado" : "disponible").append('\n');
        report.append("Directorio de datos: ").append(dataDirectory()).append('\n');
        report.append("Directorio de datos escribible: ").append(isWritableDataDirectory() ? "sí" : "no").append('\n');
        report.append("Servicios externos: ").append(externalServicesStatus()).append('\n');
        report.append("GROQ_API_KEY: ").append(hasConfiguredSecret("GROQ_API_KEY") ? "configurada" : "no configurada").append('\n');
        report.append("GEOAPIFY_API_KEY: ").append(hasConfiguredSecret("GEOAPIFY_API_KEY") ? "configurada" : "no configurada").append('\n');
        report.append("GEOAPIFY_PLACES_API_KEY: ")
                .append(hasConfiguredSecret("GEOAPIFY_PLACES_API_KEY") || hasConfiguredSecret("GEOAPIFY_API_KEY")
                        ? "configurada o heredada de Geocoding" : "no configurada").append('\n');
        report.append("GEOAPIFY_ROUTING_API_KEY: ")
                .append(hasConfiguredSecret("GEOAPIFY_ROUTING_API_KEY") ? "configurada" : "no configurada").append('\n');
        report.append("GEOAPIFY_MAPS_API_KEY: ")
                .append(hasConfiguredSecret("GEOAPIFY_MAPS_API_KEY") ? "configurada" : "no configurada").append('\n');
        report.append("Java y Maven del sistema no son necesarios para ejecutar el app-image.\n")
                .append("Las integraciones externas no se prueban ni se contactan desde este diagnóstico.");
        showMessage(report.toString(), "Diagnóstico de CODES", JOptionPane.INFORMATION_MESSAGE);
    }

    private String externalServicesStatus() {
        String value = cl.codes.config.LocalSecretBootstrap.getLocalSetting("CODES_EXTERNAL_SERVICES_ENABLED");
        return Boolean.parseBoolean(value)
                ? "habilitados; el servidor y navegador pueden enviar datos a proveedores configurados"
                : "deshabilitados por defecto; no se envían datos a proveedores externos";
    }

    private boolean hasConfiguredSecret(String key) {
        String value = cl.codes.config.LocalSecretBootstrap.getLocalSetting(key);
        return value != null && !value.isBlank();
    }

    private void manageLocalData() {
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel explanation = new JLabel("<html>Gestiona la información guardada en este equipo. "
                + "Los respaldos contienen datos sensibles: consérvalos en un lugar protegido.</html>");
        explanation.setForeground(MUTED);
        content.add(explanation, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 1, 0, 9));
        actions.setOpaque(false);
        JButton openFolder = new JButton("Abrir carpeta de datos");
        styleButton(openFolder, false);
        openFolder.addActionListener(event -> runSafely(this::openDataDirectory));
        actions.add(openFolder);
        JButton backup = new JButton("Crear respaldo");
        styleButton(backup, false);
        backup.addActionListener(event -> runSafely(this::createBackup));
        actions.add(backup);
        JButton cleanup = new JButton("Liberar espacio");
        styleButton(cleanup, false);
        cleanup.addActionListener(event -> runSafely(this::manageCleanup));
        actions.add(cleanup);
        content.add(actions, BorderLayout.CENTER);

        showMessage(content, "Datos y respaldos", JOptionPane.PLAIN_MESSAGE);
    }

    private void openDataDirectory() {
        try {
            Files.createDirectories(dataDirectory());
            if (!Desktop.isDesktopSupported()) {
                showMessage("No se puede abrir el explorador en este sistema. Carpeta de datos:\n"
                                + dataDirectory(), "Carpeta de datos", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Desktop.getDesktop().open(dataDirectory().toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            log.warn("Could not open the local data directory: {}", exception.getClass().getSimpleName());
            showMessage("No se pudo abrir la carpeta de datos. Ubicación:\n" + dataDirectory(),
                    "Carpeta de datos", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void configureIntegrations() {
        if (!requireStoppedService("Detenga CODES y cierre cualquier instancia de su servidor antes de cambiar "
                + "la configuración de integraciones.")) return;

        JCheckBox enabled = new JCheckBox("Habilitar proveedores externos (pueden recibir audio, direcciones, "
                + "coordenadas y correos)", Boolean.parseBoolean(
                cl.codes.config.LocalSecretBootstrap.getLocalSetting("CODES_EXTERNAL_SERVICES_ENABLED")));
        JTextField mapTiles = settingField("CODES_MAP_TILES_URL", "");
        JTextField mapAttribution = settingField("CODES_MAP_ATTRIBUTION", "&copy; Esri");
        JTextField routingUrl = settingField("CODES_ROUTING_URL", "https://router.project-osrm.org");
        JComboBox<String> routingProvider = new JComboBox<>(new String[]{"OSRM público", "Geoapify Routing"});
        String savedRoutingProvider = cl.codes.config.LocalSecretBootstrap.getLocalSetting("CODES_ROUTING_PROVIDER");
        routingProvider.setSelectedItem("geoapify".equalsIgnoreCase(savedRoutingProvider)
                ? "Geoapify Routing" : "OSRM público");
        JTextField geoapifyBaseUrl = settingField("GEOAPIFY_BASE_URL",
                "https://api.geoapify.com/v1/geocode/search");
        JTextField geocoderCenterLatitude = settingField("CODES_GEOCODER_CENTER_LAT", "-33.4489");
        JTextField geocoderCenterLongitude = settingField("CODES_GEOCODER_CENTER_LNG", "-70.6693");
        JTextField geocoderRadiusKm = settingField("CODES_GEOCODER_RADIUS_KM", "8");
        JPasswordField geoapifyKey = new JPasswordField(32);
        JPasswordField geoapifyPlacesKey = new JPasswordField(32);
        JPasswordField geoapifyRoutingKey = new JPasswordField(32);
        JPasswordField geoapifyMapsKey = new JPasswordField(32);
        String savedMapProvider = cl.codes.config.LocalSecretBootstrap.getLocalSetting("CODES_MAP_PROVIDER");
        if (savedMapProvider == null || savedMapProvider.isBlank()) {
            savedMapProvider = mapTiles.getText().isBlank() ? "esri" : "custom";
        }
        JComboBox<String> mapProvider = new JComboBox<>(new String[]{
                "Esri World Imagery", "Geoapify OSM Carto", "Geoapify OSM Bright",
                "Geoapify Toner", "URL personalizada"
        });
        mapProvider.setSelectedItem(mapProviderLabel(savedMapProvider));
        JTextField groqBaseUrl = settingField("GROQ_BASE_URL", "https://api.groq.com/openai/v1");
        JTextField groqModel = settingField("GROQ_MODEL", "whisper-large-v3-turbo");
        JTextField groqLanguage = settingField("GROQ_LANGUAGE", "es-CL");
        JPasswordField groqKey = new JPasswordField(32);
        JTextField smtpHost = settingField("CODES_SMTP_HOST", "");
        JTextField smtpPort = settingField("CODES_SMTP_PORT", "587");
        JTextField smtpUsername = settingField("CODES_SMTP_USERNAME", "");
        JPasswordField smtpPassword = new JPasswordField(32);
        JTextField mailFrom = settingField("CODES_MAIL_FROM", "");
        JTextField publicBaseUrl = settingField("CODES_PUBLIC_BASE_URL", "http://localhost:8000");

        JPanel mapsPanel = new JPanel(new GridBagLayout());
        int row = 0;
        row = addSettingRow(mapsPanel, row, "Proveedor del mapa", mapProvider,
                "Geoapify Maps se consulta mediante el servidor local; su clave no se expone al navegador.");
        row = addSettingRow(mapsPanel, row, "URL de teselas", mapTiles,
                "Solo URL personalizada; debe incluir {z}, {x}, {y}. No incluya claves privadas: se envía al navegador.");
        row = addSettingRow(mapsPanel, row, "Atribución del mapa", mapAttribution, "Texto de atribución exigido por el proveedor.");
        row = addSettingRow(mapsPanel, row, "Proveedor de rutas", routingProvider,
                "Geoapify Routing envía el origen GPS y el destino del incidente a Geoapify.");
        row = addSettingRow(mapsPanel, row, "URL de rutas OSRM", routingUrl,
                "Se usa solo con OSRM y se solicita desde el navegador; no incluya claves privadas.");
        row = addSettingRow(mapsPanel, row, "URL de Geoapify", geoapifyBaseUrl, "Endpoint HTTPS de geocodificación.");
        row = addSettingRow(mapsPanel, row, "Latitud del centro operativo", geocoderCenterLatitude,
                "Centro de respaldo para geocodificación cuando no se ha habilitado la ubicación del dispositivo.");
        row = addSettingRow(mapsPanel, row, "Longitud del centro operativo", geocoderCenterLongitude,
                "Junto con la latitud, se usa como centro de respaldo si no hay ubicación del dispositivo.");
        row = addSettingRow(mapsPanel, row, "Radio de cobertura (km)", geocoderRadiusKm,
                "Radio alrededor del dispositivo tras pulsar «Ubicación actual» (mayor que 0 y hasta 100 km).");
        row = addSettingRow(mapsPanel, row, "Clave Geoapify Geocoding", geoapifyKey,
                secretHint("GEOAPIFY_API_KEY") + " Se guarda localmente y nunca se muestra.");
        row = addSettingRow(mapsPanel, row, "Clave Geoapify Places", geoapifyPlacesKey,
                secretHint("GEOAPIFY_PLACES_API_KEY") + " Opcional; si se deja vacía, se reutiliza la clave de Geocoding.");
        row = addSettingRow(mapsPanel, row, "Clave Geoapify Routing", geoapifyRoutingKey,
                secretHint("GEOAPIFY_ROUTING_API_KEY") + " La clave permanece en el servidor local.");
        addSettingRow(mapsPanel, row, "Clave Geoapify Maps", geoapifyMapsKey,
                secretHint("GEOAPIFY_MAPS_API_KEY") + " La clave permanece en el servidor local.");

        JPanel transcriptionPanel = new JPanel(new GridBagLayout());
        row = 0;
        row = addSettingRow(transcriptionPanel, row, "URL de Groq", groqBaseUrl, "Endpoint HTTPS de la API compatible con OpenAI.");
        row = addSettingRow(transcriptionPanel, row, "Modelo", groqModel, "Modelo de transcripción de audio.");
        row = addSettingRow(transcriptionPanel, row, "Idioma", groqLanguage, "Código de idioma enviado a Groq.");
        addSettingRow(transcriptionPanel, row, "Clave Groq", groqKey,
                secretHint("GROQ_API_KEY") + " El audio de las llamadas se enviará al proveedor.");

        JPanel mailPanel = new JPanel(new GridBagLayout());
        row = 0;
        row = addSettingRow(mailPanel, row, "Servidor SMTP", smtpHost, "Servidor de correo saliente.");
        row = addSettingRow(mailPanel, row, "Puerto SMTP", smtpPort, "Normalmente 587 con STARTTLS.");
        row = addSettingRow(mailPanel, row, "Usuario SMTP", smtpUsername, "Cuenta de envío.");
        row = addSettingRow(mailPanel, row, "Contraseña SMTP", smtpPassword,
                secretHint("CODES_SMTP_PASSWORD") + " Nunca se muestra después de guardarla.");
        row = addSettingRow(mailPanel, row, "Correo remitente", mailFrom, "Dirección usada en los mensajes.");
        addSettingRow(mailPanel, row, "URL pública local", publicBaseUrl,
                "Dirección incluida en los enlaces de recuperación; use localhost.");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Mapa y geocodificación", new JScrollPane(mapsPanel));
        tabs.addTab("Transcripción", new JScrollPane(transcriptionPanel));
        tabs.addTab("Correo", new JScrollPane(mailPanel));
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(enabled, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(new JLabel("Los cambios se guardan en el perfil local de Windows, con permisos restringidos. "
                + "Requieren cerrar y volver a abrir CODES."), BorderLayout.SOUTH);

        JDialog dialog = new JDialog(frame, "Configurar integraciones y API", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                geoapifyKey.setText("");
                geoapifyPlacesKey.setText("");
                geoapifyRoutingKey.setText("");
                geoapifyMapsKey.setText("");
                groqKey.setText("");
                smtpPassword.setText("");
            }
        });
        dialog.add(content, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancelar");
        JButton save = new JButton("Guardar");
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            try {
                validateProviderSettings(mapTiles, mapProvider, routingUrl, routingProvider,
                        geoapifyBaseUrl, geocoderCenterLatitude, geocoderCenterLongitude, geocoderRadiusKm,
                        groqBaseUrl,
                        smtpHost, smtpPort, publicBaseUrl);
                boolean wasEnabled = Boolean.parseBoolean(
                        cl.codes.config.LocalSecretBootstrap.getLocalSetting("CODES_EXTERNAL_SERVICES_ENABLED"));
                if (enabled.isSelected() && !wasEnabled) {
                    int consent = JOptionPane.showConfirmDialog(dialog,
                            "Al habilitar proveedores, los datos pueden salir de este equipo: audio a Groq, "
                                    + "direcciones a Geoapify, coordenadas a los proveedores de mapa/rutas y "
                                    + "correos a SMTP. ¿Confirma que cuenta con autorización?",
                            "Confirmar uso de servicios externos", JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (consent != JOptionPane.YES_OPTION) return;
                }
                Map<String, String> settings = new LinkedHashMap<>();
                settings.put("CODES_EXTERNAL_SERVICES_ENABLED", Boolean.toString(enabled.isSelected()));
                putOptionalSetting(settings, "CODES_MAP_TILES_URL", mapTiles.getText());
                putOptionalSetting(settings, "CODES_MAP_ATTRIBUTION", mapAttribution.getText());
                settings.put("CODES_MAP_PROVIDER", mapProviderCode(String.valueOf(mapProvider.getSelectedItem())));
                putOptionalSetting(settings, "CODES_ROUTING_URL", routingUrl.getText());
                settings.put("CODES_ROUTING_PROVIDER",
                        "Geoapify Routing".equals(String.valueOf(routingProvider.getSelectedItem()))
                                ? "geoapify" : "osrm");
                putOptionalSetting(settings, "GEOAPIFY_BASE_URL", geoapifyBaseUrl.getText());
                putOptionalSetting(settings, "CODES_GEOCODER_CENTER_LAT", geocoderCenterLatitude.getText());
                putOptionalSetting(settings, "CODES_GEOCODER_CENTER_LNG", geocoderCenterLongitude.getText());
                putOptionalSetting(settings, "CODES_GEOCODER_RADIUS_KM", geocoderRadiusKm.getText());
                putOptionalSetting(settings, "GROQ_BASE_URL", groqBaseUrl.getText());
                putOptionalSetting(settings, "GROQ_MODEL", groqModel.getText());
                putOptionalSetting(settings, "GROQ_LANGUAGE", groqLanguage.getText());
                putOptionalSetting(settings, "CODES_SMTP_HOST", smtpHost.getText());
                putOptionalSetting(settings, "CODES_SMTP_PORT", smtpPort.getText());
                putOptionalSetting(settings, "CODES_SMTP_USERNAME", smtpUsername.getText());
                putOptionalSetting(settings, "CODES_MAIL_FROM", mailFrom.getText());
                putOptionalSetting(settings, "CODES_PUBLIC_BASE_URL", publicBaseUrl.getText());
                putPasswordSetting(settings, "GEOAPIFY_API_KEY", geoapifyKey);
                putPasswordSetting(settings, "GEOAPIFY_PLACES_API_KEY", geoapifyPlacesKey);
                putPasswordSetting(settings, "GEOAPIFY_ROUTING_API_KEY", geoapifyRoutingKey);
                putPasswordSetting(settings, "GEOAPIFY_MAPS_API_KEY", geoapifyMapsKey);
                putPasswordSetting(settings, "GROQ_API_KEY", groqKey);
                putPasswordSetting(settings, "CODES_SMTP_PASSWORD", smtpPassword);
                cl.codes.config.LocalSecretBootstrap.saveLocalSettings(settings);
                configurationChanged = true;
                startButton.setEnabled(false);
                updateDashboard();
                dialog.dispose();
                showMessage("Configuración guardada en:\n" + dataDirectory().resolve(".codes-secrets.properties")
                                + "\n\nCierre CODES desde el menú y vuelva a abrirlo para aplicar los cambios. "
                                + "Las claves no se muestran ni se escriben en la carpeta de instalación.",
                        "Configuración guardada", JOptionPane.INFORMATION_MESSAGE);
            } catch (IllegalArgumentException exception) {
                showMessage(exception.getMessage(), "Revisar configuración", JOptionPane.WARNING_MESSAGE);
            } catch (RuntimeException exception) {
                log.warn("Could not save provider settings: {}", exception.getClass().getSimpleName());
                showMessage("No se pudo guardar la configuración. Revise permisos y espacio disponible.",
                        "Error", JOptionPane.ERROR_MESSAGE);
            } finally {
                geoapifyKey.setText("");
                geoapifyPlacesKey.setText("");
                geoapifyRoutingKey.setText("");
                geoapifyMapsKey.setText("");
                groqKey.setText("");
                smtpPassword.setText("");
            }
        });
        buttons.add(cancel);
        buttons.add(save);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(760, 500));
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private JTextField settingField(String name, String fallback) {
        String value = cl.codes.config.LocalSecretBootstrap.getLocalSetting(name);
        return new JTextField(value == null || value.isBlank() ? fallback : value, 36);
    }

    private String secretHint(String name) {
        return hasConfiguredSecret(name) ? "Clave ya configurada; " : "Clave no configurada; ";
    }

    private String mapProviderLabel(String provider) {
        return switch (provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "geoapify-osm-carto" -> "Geoapify OSM Carto";
            case "geoapify-osm-bright" -> "Geoapify OSM Bright";
            case "geoapify-toner" -> "Geoapify Toner";
            case "custom" -> "URL personalizada";
            default -> "Esri World Imagery";
        };
    }

    private String mapProviderCode(String label) {
        return switch (label) {
            case "Geoapify OSM Carto" -> "geoapify-osm-carto";
            case "Geoapify OSM Bright" -> "geoapify-osm-bright";
            case "Geoapify Toner" -> "geoapify-toner";
            case "URL personalizada" -> "custom";
            default -> "esri";
        };
    }

    private int addSettingRow(JPanel panel, int row, String label, JComponent field, String hint) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(6, 8, 2, 8);
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
        constraints.gridx = 1;
        constraints.gridy = row + 1;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 8, 6, 8);
        panel.add(new JLabel(hint), constraints);
        return row + 2;
    }

    private void validateProviderSettings(JTextField mapTiles, JComboBox<String> mapProvider,
                                          JTextField routingUrl, JComboBox<String> routingProvider,
                                          JTextField geoapifyBaseUrl,
                                          JTextField geocoderCenterLatitude, JTextField geocoderCenterLongitude,
                                          JTextField geocoderRadiusKm,
                                          JTextField groqBaseUrl, JTextField smtpHost, JTextField smtpPort,
                                          JTextField publicBaseUrl) {
        String tileTemplate = mapTiles.getText().trim();
        if ("URL personalizada".equals(String.valueOf(mapProvider.getSelectedItem())) && tileTemplate.isEmpty()) {
            throw new IllegalArgumentException("La URL de teselas es obligatoria para el proveedor personalizado.");
        }
        if (!tileTemplate.isEmpty()) {
            validateHttpsUrl(tileTemplate.replace("{z}", "1").replace("{x}", "1").replace("{y}", "1"),
                    "URL de teselas");
            if (!tileTemplate.contains("{z}") || !tileTemplate.contains("{x}") || !tileTemplate.contains("{y}")) {
                throw new IllegalArgumentException("La URL de teselas debe incluir {z}, {x} y {y}.");
            }
        }
        if ("OSRM público".equals(String.valueOf(routingProvider.getSelectedItem()))) {
            validateHttpsUrl(routingUrl.getText(), "URL de rutas");
        }
        validateHttpsUrl(geoapifyBaseUrl.getText(), "URL de Geoapify");
        validateGeocoderCoverage(geocoderCenterLatitude.getText(),
                geocoderCenterLongitude.getText(), geocoderRadiusKm.getText());
        validateHttpsUrl(groqBaseUrl.getText(), "URL de Groq");
        String host = smtpHost.getText().trim();
        String port = smtpPort.getText().trim();
        if (!port.isEmpty()) {
            try {
                int parsed = Integer.parseInt(port);
                if (parsed < 1 || parsed > 65535) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("El puerto SMTP debe ser un número entre 1 y 65535.");
            }
        }
        if (!host.isEmpty() && host.contains("://")) {
            throw new IllegalArgumentException("El servidor SMTP debe ser un nombre de host, no una URL.");
        }
        String recoveryUrl = publicBaseUrl.getText().trim();
        if (!recoveryUrl.isEmpty()) {
            try {
                java.net.URI uri = java.net.URI.create(recoveryUrl);
                String hostName = uri.getHost();
                if (hostName == null || !Set.of("localhost", "127.0.0.1", "::1").contains(hostName)
                        || !Set.of("http", "https").contains(uri.getScheme())) {
                    throw new IllegalArgumentException("La URL de recuperación debe apuntar a localhost.");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("La URL de recuperación debe apuntar a localhost, por ejemplo "
                        + "http://localhost:8000.");
            }
        }
    }

    private void validateGeocoderCoverage(String latitudeText, String longitudeText, String radiusText) {
        double latitude;
        double longitude;
        double radiusKm;
        try {
            latitude = Double.parseDouble(latitudeText.trim());
            longitude = Double.parseDouble(longitudeText.trim());
            radiusKm = Double.parseDouble(radiusText.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("El centro y el radio de cobertura deben ser números válidos.");
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("La latitud del centro operativo debe estar entre -90 y 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("La longitud del centro operativo debe estar entre -180 y 180.");
        }
        if (!Double.isFinite(radiusKm) || radiusKm <= 0 || radiusKm > 100) {
            throw new IllegalArgumentException("El radio de cobertura debe ser mayor que 0 y no superar 100 km.");
        }
    }

    private void validateHttpsUrl(String value, String label) {
        try {
            java.net.URI uri = java.net.URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException(label + " debe ser una URL HTTPS válida.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " debe ser una URL HTTPS válida.");
        }
    }

    private void putOptionalSetting(Map<String, String> settings, String key, String value) {
        String normalized = value == null ? "" : value.trim();
        settings.put(key, normalized.isEmpty() ? null : normalized);
    }

    private void putPasswordSetting(Map<String, String> settings, String key, JPasswordField field) {
        char[] chars = field.getPassword();
        String value = new String(chars).trim();
        if (!value.isEmpty()) settings.put(key, value);
        java.util.Arrays.fill(chars, '\0');
    }

    private boolean isWritableDataDirectory() {
        try {
            Files.createDirectories(dataDirectory());
            return Files.isWritable(dataDirectory());
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private void createBackup() {
        if (!requireStoppedService("Para crear un respaldo consistente, detenga CODES primero.")) return;
        int answer = JOptionPane.showConfirmDialog(frame,
                "Se copiarán la base de datos, secretos, audios, caché y registros locales.\n"
                        + "El respaldo contiene información sensible. Guárdelo con acceso restringido.\n\n¿Continuar?",
                "Confirmar respaldo sensible", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        try {
            Path data = dataDirectory();
            if (!Files.isDirectory(data)) {
                showMessage("No hay datos locales para respaldar.", "Respaldo", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Path backup = data.getParent().resolve("backups").resolve("codes_" + timestamp());
            Files.createDirectories(backup);
            copyDirectory(data, backup.resolve("data"));
            writeBackupManifest(backup);
            cl.codes.config.LocalDataDirectory.prepare(backup, null);
            appendStatus("Respaldo creado: " + backup + " (incluye información sensible).");
            showMessage("Respaldo creado:\n" + backup + "\n\nIncluye secretos y datos personales; no lo comparta.",
                    "Respaldo local", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | SecurityException exception) {
            log.warn("Local backup failed: {}", exception.getClass().getSimpleName());
            showMessage("No se pudo completar el respaldo. Revise permisos y espacio disponible.",
                    "Error de respaldo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void manageCleanup() {
        if (!requireStoppedService("Detenga CODES antes de limpiar archivos.")) return;
        int includeLogsAnswer = JOptionPane.showOptionDialog(frame,
                "¿Incluir registros locales en la limpieza? Los registros pueden ser útiles para investigar problemas.",
                "Registros opcionales", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"No, conservarlos", "Sí, incluirlos", "Cancelar"}, "No, conservarlos");
        if (includeLogsAnswer == 2 || includeLogsAnswer == JOptionPane.CLOSED_OPTION) return;
        List<Path> candidates = cleanupCandidates(includeLogsAnswer == 1);
        long bytes = candidates.stream().mapToLong(PackagedDesktopLauncher::sizeOf).sum();
        StringBuilder preview = new StringBuilder("Vista previa: archivos redescargables o regenerables\n\n");
        if (candidates.isEmpty()) preview.append("No se encontraron elementos que limpiar.\n");
        for (Path candidate : candidates) {
            preview.append(String.format(Locale.ROOT, "%7.1f MB  %s%n", sizeOf(candidate) / (1024d * 1024d), candidate));
        }
        preview.append(String.format(Locale.ROOT, "\nEspacio estimado: %.1f MB%n", bytes / (1024d * 1024d)));
        preview.append("\nNunca se eliminan la base de datos, secretos ni audios. Los registros solo se incluyen si se eligieron expresamente.");
        Object[] options = {"Solo vista previa", "Borrar elementos listados", "Borrar y crear ZIP del app-image", "Volver"};
        int choice = JOptionPane.showOptionDialog(frame, preview.toString(), "Limpieza / liberar espacio",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0 || choice == 3 || choice == JOptionPane.CLOSED_OPTION) return;
        if (candidates.isEmpty() && choice != 2) {
            showMessage("No hay elementos que limpiar.", "Limpieza", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(frame,
                "Se eliminarán únicamente los elementos regenerables enumerados en la vista previa.\n¿Continuar?",
                "Confirmar limpieza", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            for (Path candidate : candidates) deleteTree(candidate);
            String result = "Limpieza completada; espacio estimado liberado: "
                    + String.format(Locale.ROOT, "%.1f MB.", bytes / (1024d * 1024d));
            if (choice == 2) {
                Path archive = exportAppImage();
                result += "\nCopia comprimida del app-image: " + archive;
            }
            appendStatus(result);
            showMessage(result, "Limpieza", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | SecurityException exception) {
            log.warn("Local cleanup failed: {}", exception.getClass().getSimpleName());
            showMessage("No se pudo completar la limpieza. Revise permisos y espacio disponible.",
                    "Error de limpieza", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<Path> cleanupCandidates(boolean includeLogs) {
        List<Path> candidates = new ArrayList<>();
        Path dataCache = dataDirectory().resolve("geocache.json");
        if (Files.exists(dataCache)) candidates.add(dataCache);
        if (includeLogs) {
            Path dataLogs = dataDirectory().resolve("logs");
            if (Files.exists(dataLogs)) candidates.add(dataLogs);
        }
        Path projectRoot = findProjectRoot();
        if (projectRoot != null) {
            for (String relative : List.of("target", ".idea", ".vscode")) {
                Path candidate = projectRoot.resolve(relative);
                if (Files.exists(candidate)) candidates.add(candidate);
            }
            Path projectLogs = projectRoot.resolve("logs");
            if (includeLogs && Files.exists(projectLogs)) candidates.add(projectLogs);
            try {
                Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        Path relative = projectRoot.relativize(directory);
                        if (relative.getNameCount() > 0
                                && Set.of("target", "dist", "backups", "data", "logs", ".git")
                                .contains(relative.getName(0).toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (directory.getFileName() != null
                                && directory.getFileName().toString().equals("__pycache__")) {
                            candidates.add(directory);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) {
                log.debug("Could not enumerate optional local caches: {}", exception.getClass().getSimpleName());
            }
        }
        return candidates;
    }

    private boolean requireStoppedService(String warning) {
        if (applicationContext != null || isPortInUse(serverPort())) {
            showMessage(warning, "CODES en ejecución", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    private List<Path> findManifestFiles(Path backup) throws IOException {
        try (Stream<Path> files = Files.walk(backup)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private void writeBackupManifest(Path backup) throws IOException {
        StringBuilder manifest = new StringBuilder("{\n  \"createdAt\": \"")
                .append(java.time.Instant.now()).append("\",\n  \"source\": \"data\",\n  \"files\": [");
        List<Path> files = findManifestFiles(backup.resolve("data"));
        for (int i = 0; i < files.size(); i++) {
            String relative = backup.resolve("data").relativize(files.get(i)).toString().replace('\\', '/');
            manifest.append(i == 0 ? "\n    \"" : ",\n    \"").append(jsonEscape(relative)).append('"');
        }
        if (!files.isEmpty()) manifest.append('\n').append("  ");
        manifest.append("]\n}\n");
        Files.writeString(backup.resolve("manifest.json"), manifest.toString());
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private Path exportAppImage() throws IOException {
        Path appImage = appImageDirectory();
        if (appImage == null || !Files.isDirectory(appImage)) {
            throw new IOException("Cannot locate the current app-image.");
        }
        Path archive = appImage.getParent().resolve("CODES_liviano_" + timestamp() + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
             Stream<Path> paths = Files.walk(appImage)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = appImage.getParent().relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return archive;
    }

    private void exit() {
        if (starting.get()) {
            showMessage("CODES todavía está iniciando. Espere a que finalice el inicio antes de salir.",
                    "Inicio en curso", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (applicationContext != null) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    "CODES está activo. Al salir, el servidor local también se detendrá. ¿Continuar?",
                    "Salir de CODES", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            ConfigurableApplicationContext context = applicationContext;
            applicationContext = null;
            try {
                context.close();
            } catch (RuntimeException exception) {
                log.warn("CODES shutdown reported an error: {}", exception.getClass().getSimpleName());
            }
        }
        frame.dispose();
    }

    private static Path dataDirectory() {
        String configured = System.getProperty("CODES_DATA_DIR", System.getenv("CODES_DATA_DIR"));
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return cl.codes.config.LocalDataDirectory.defaultPackagedDirectory(
                System.getenv("LOCALAPPDATA"), Path.of(System.getProperty("user.home")));
    }

    private int serverPort() {
        String port = System.getProperty("server.port", System.getenv("CODES_SERVER_PORT"));
        for (String argument : applicationArgs) {
            if (argument.startsWith("--server.port=")) port = argument.substring("--server.port=".length());
        }
        try {
            int parsed = Integer.parseInt(port == null || port.isBlank() ? String.valueOf(DEFAULT_PORT) : port);
            return parsed >= 1 && parsed <= 65535 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException exception) {
            return DEFAULT_PORT;
        }
    }

    private static boolean isPortInUse(int port) {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isPackagedRuntime() {
        return Boolean.getBoolean("codes.packaged")
                && Files.isDirectory(appImageDirectory() == null ? Path.of(".") : appImageDirectory().resolve("runtime"));
    }

    static Path appImageDirectory() {
        try {
            Path location = Path.of(PackagedDesktopLauncher.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path appDirectory = Files.isDirectory(location) ? location : location.getParent();
            return appDirectory == null ? null : appDirectory.getParent();
        } catch (Exception exception) {
            return null;
        }
    }

    private static String startupFailureMessage(Throwable exception, int port) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.BindException) {
                return "No se pudo iniciar CODES porque el puerto local " + port + " está ocupado. Cierre la otra "
                        + "aplicación que lo utiliza y vuelva a intentarlo.";
            }
            if (cause instanceof IllegalStateException
                    && cl.codes.config.LocalServerConfiguration.INVALID_ADDRESS_MESSAGE.equals(cause.getMessage())) {
                return "La configuración de red no es local. CODES solo permite conexiones desde este equipo; "
                        + "use 127.0.0.1.";
            }
        }
        return "No se pudo iniciar CODES. Revise la configuración y los permisos del directorio de datos; "
                + "consulte los registros locales para más ayuda.";
    }

    private static Path findProjectRoot() {
        Path current = appImageDirectory();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("tools"))) return current;
            current = current.getParent();
        }
        return null;
    }

    private static long sizeOf(Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); } catch (IOException exception) { return 0L; }
            }).sum();
        } catch (IOException exception) {
            return 0;
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("Desktop menu action failed: {}", exception.getClass().getSimpleName());
            showMessage("La operación no pudo completarse. Revise permisos, configuración y registros locales.",
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void appendStatus(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        status.append("\n" + timestamp + "  " + message);
        if (status.getDocument().getLength() > 5000) {
            try {
                status.getDocument().remove(0, status.getDocument().getLength() - 4000);
            } catch (javax.swing.text.BadLocationException exception) {
                log.warn("Could not trim the local activity panel.");
            }
        }
        status.setCaretPosition(status.getDocument().getLength());
    }

    private void showMessage(String message, String title, int messageType) {
        showMessage((Object) message, title, messageType);
    }

    private void showMessage(Object message, String title, int messageType) {
        JOptionPane.showMessageDialog(frame, message, title, messageType);
    }
}
