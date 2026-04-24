import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public class AuctionClient extends JFrame {
    private static final int DEFAULT_PORT = 5000;

    private final JTextField hostField = new JTextField("localhost", 18);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 8);
    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);

    private final JTextField itemNameField = new JTextField(18);
    private final JTextField itemValueField = new JTextField(10);
    private final JTextField bidValueField = new JTextField(12);

    private final JTextArea eventArea = new JTextArea();
    private final JTextArea auctionSummaryArea = new JTextArea("Nenhum item cadastrado ainda.");

    private final JLabel connectionStatusLabel = new JLabel("Desconectado");
    private final JLabel authStatusLabel = new JLabel("Pendente");
    private final JLabel roleStatusLabel = new JLabel("-");

    private final JButton connectButton = new JButton("Conectar ao Servidor");
    private final JButton authenticateButton = new JButton("Autenticar");
    private final JButton registerItemButton = new JButton("Cadastrar Item");
    private final JButton bidButton = new JButton("Enviar Lance");
    private final JButton statusButton = new JButton("Atualizar Status");
    private final JButton closeAuctionButton = new JButton("Encerrar Leilao");
    private final JButton quitButton = new JButton("Sair");

    private Socket socket;
    private BufferedReader serverReader;
    private PrintWriter serverWriter;
    private DatagramSocket udpSocket;

    private boolean authenticated;
    private String authenticatedRole = "";

    public static void main(String[] args) {
        final String host = args.length > 0 ? args[0] : "localhost";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                AuctionClient client = new AuctionClient(host, port);
                client.setVisible(true);
            }
        });
    }

    public AuctionClient(String host, int port) {
        super("Cliente de Leilao");
        hostField.setText(host);
        portField.setText(String.valueOf(port));
        buildInterface();
        bindActions();
        updateControlState();
    }

    private void buildInterface() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(new Dimension(1180, 760));
        setMinimumSize(new Dimension(1020, 680));
        setLocationRelativeTo(null);

        JPanel contentPanel = new JPanel(new BorderLayout(12, 12));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(contentPanel);

        JPanel sidebar = new JPanel(new GridBagLayout());
        sidebar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

        GridBagConstraints sidebarConstraints = new GridBagConstraints();
        sidebarConstraints.gridx = 0;
        sidebarConstraints.weightx = 1.0;
        sidebarConstraints.fill = GridBagConstraints.HORIZONTAL;
        sidebarConstraints.insets = new Insets(0, 0, 10, 0);

        sidebarConstraints.gridy = 0;
        sidebar.add(buildConnectionPanel(), sidebarConstraints);

        sidebarConstraints.gridy = 1;
        sidebar.add(buildAuthenticationPanel(), sidebarConstraints);

        sidebarConstraints.gridy = 2;
        sidebar.add(buildItemPanel(), sidebarConstraints);

        sidebarConstraints.gridy = 3;
        sidebar.add(buildBidPanel(), sidebarConstraints);

        sidebarConstraints.gridy = 4;
        sidebarConstraints.weighty = 1.0;
        sidebarConstraints.fill = GridBagConstraints.BOTH;
        sidebar.add(new JPanel(), sidebarConstraints);

        JPanel rightPanel = new JPanel(new BorderLayout(12, 12));
        rightPanel.add(buildSummaryPanel(), BorderLayout.NORTH);
        rightPanel.add(buildEventsPanel(), BorderLayout.CENTER);

        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rightPanel);
        mainSplitPane.setResizeWeight(0.34);
        mainSplitPane.setDividerLocation(390);
        mainSplitPane.setContinuousLayout(true);
        mainSplitPane.setBorder(null);

        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        footerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Status da Sessao"),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        footerPanel.add(new JLabel("Conexao:"));
        footerPanel.add(connectionStatusLabel);
        footerPanel.add(new JLabel("Autenticacao:"));
        footerPanel.add(authStatusLabel);
        footerPanel.add(new JLabel("Perfil:"));
        footerPanel.add(roleStatusLabel);
        footerPanel.add(quitButton);

        contentPanel.add(mainSplitPane, BorderLayout.CENTER);
        contentPanel.add(footerPanel, BorderLayout.SOUTH);
    }

    private JPanel buildConnectionPanel() {
        JPanel panel = createSectionPanel("Conexao com o Servidor");
        GridBagConstraints c = createDefaultConstraints();

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Host:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(hostField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Porta:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(portField, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(connectButton, c);

        return panel;
    }

    private JPanel buildAuthenticationPanel() {
        JPanel panel = createSectionPanel("Autenticacao");
        GridBagConstraints c = createDefaultConstraints();

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Usuario:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(usernameField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Senha:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(passwordField, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(authenticateButton, c);

        JLabel helpLabel = new JLabel("<html>1. Conecte ao servidor.<br>2. Informe usuario e senha.<br>3. Clique em Autenticar para liberar as acoes do seu perfil.</html>");
        c.gridy = 3;
        panel.add(helpLabel, c);

        return panel;
    }

    private JPanel buildItemPanel() {
        JPanel panel = createSectionPanel("Cadastro do Item");
        GridBagConstraints c = createDefaultConstraints();

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Nome do item:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(itemNameField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Valor inicial:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(itemValueField, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(registerItemButton, c);

        return panel;
    }

    private JPanel buildBidPanel() {
        JPanel panel = createSectionPanel("Lances e Controle");
        GridBagConstraints c = createDefaultConstraints();

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Valor do lance:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(bidValueField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(bidButton, c);

        c.gridy = 2;
        panel.add(statusButton, c);

        c.gridy = 3;
        panel.add(closeAuctionButton, c);

        return panel;
    }

    private JScrollPane buildSummaryPanel() {
        auctionSummaryArea.setEditable(false);
        auctionSummaryArea.setLineWrap(true);
        auctionSummaryArea.setWrapStyleWord(true);
        auctionSummaryArea.setRows(4);
        auctionSummaryArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(auctionSummaryArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Resumo Atual do Leilao"));
        scrollPane.setPreferredSize(new Dimension(200, 140));
        return scrollPane;
    }

    private JScrollPane buildEventsPanel() {
        eventArea.setEditable(false);
        eventArea.setLineWrap(true);
        eventArea.setWrapStyleWord(true);
        eventArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(eventArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Painel em Tempo Real"));
        return scrollPane;
    }

    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        return panel;
    }

    private GridBagConstraints createDefaultConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private void bindActions() {
        connectButton.addActionListener(e -> connect());
        authenticateButton.addActionListener(e -> authenticate());
        registerItemButton.addActionListener(e -> registerItem());
        bidButton.addActionListener(e -> sendBid());
        statusButton.addActionListener(e -> sendCommand("STATUS"));
        closeAuctionButton.addActionListener(e -> sendCommand("CLOSE"));
        quitButton.addActionListener(e -> closeClient());
    }

    private void connect() {
        if (isConnected()) {
            appendEvent("[INFO] Cliente ja esta conectado.");
            return;
        }

        String host = hostField.getText().trim();
        String portText = portField.getText().trim();

        if (host.isEmpty() || portText.isEmpty()) {
            showError("Preencha host e porta antes de conectar.");
            return;
        }

        try {
            int port = Integer.parseInt(portText);

            socket = new Socket(host, port);
            serverReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            serverWriter = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            udpSocket = new DatagramSocket();

            startTcpListener();
            startUdpListener();

            authenticated = false;
            authenticatedRole = "";
            appendEvent("[INFO] Conexao estabelecida com o servidor.");
            appendEvent("[INFO] Porta UDP local: " + udpSocket.getLocalPort());
            appendEvent("[INFO] Autentique-se para liberar as acoes do leilao.");

            sendCommand("UDP " + udpSocket.getLocalPort());
            sendCommand("STATUS");
            updateControlState();
        } catch (NumberFormatException e) {
            showError("Porta invalida.");
        } catch (IOException e) {
            showError("Nao foi possivel conectar: " + e.getMessage());
            closeResources();
            updateControlState();
        }
    }

    private void authenticate() {
        if (!isConnected()) {
            showError("Conecte-se ao servidor antes de autenticar.");
            return;
        }

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Informe usuario e senha para autenticar.");
            return;
        }

        sendCommand("LOGIN " + username + "|" + password);
    }

    private void startTcpListener() {
        Thread tcpListener = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = serverReader.readLine()) != null) {
                        handleServerMessage(line, false);
                    }
                } catch (IOException e) {
                    appendEvent("[ERRO] Conexao TCP encerrada: " + e.getMessage());
                } finally {
                    handleDisconnection();
                }
            }
        }, "tcp-listener");

        tcpListener.setDaemon(true);
        tcpListener.start();
    }

    private void startUdpListener() {
        Thread udpListener = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[2048];

                while (udpSocket != null && !udpSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                    try {
                        udpSocket.receive(packet);
                        String message = new String(
                                packet.getData(),
                                packet.getOffset(),
                                packet.getLength(),
                                StandardCharsets.UTF_8
                        );
                        handleServerMessage(message, true);
                    } catch (IOException e) {
                        if (udpSocket != null && !udpSocket.isClosed()) {
                            appendEvent("[ERRO] Erro ao receber atualizacao UDP: " + e.getMessage());
                        }
                        return;
                    }
                }
            }
        }, "udp-listener");

        udpListener.setDaemon(true);
        udpListener.start();
    }

    private void registerItem() {
        String itemName = itemNameField.getText().trim();
        String initialValue = itemValueField.getText().trim();

        if (itemName.isEmpty() || initialValue.isEmpty()) {
            showError("Informe o nome do item e o valor inicial.");
            return;
        }

        sendCommand("ITEM " + itemName + "|" + initialValue);
    }

    private void sendBid() {
        String bidValue = bidValueField.getText().trim();

        if (bidValue.isEmpty()) {
            showError("Informe o valor do lance.");
            return;
        }

        sendCommand("BID " + bidValue);
    }

    private void sendCommand(String command) {
        if (serverWriter == null) {
            showError("Conecte-se ao servidor antes de enviar comandos.");
            return;
        }

        serverWriter.println(command);
    }

    private void handleServerMessage(String rawMessage, boolean udpMessage) {
        String[] parts = rawMessage.split("\\|");
        String type = parts.length > 0 ? parts[0] : "INFO";

        if ("AUTH".equals(type)) {
            handleAuthMessage(parts);
            return;
        }

        String message = rawMessage.contains("|") ? rawMessage.substring(rawMessage.indexOf('|') + 1) : rawMessage;

        String prefix;
        if ("ERROR".equals(type)) {
            prefix = "[ERRO]";
        } else if ("STATUS".equals(type)) {
            prefix = "[STATUS]";
        } else if ("EVENT".equals(type)) {
            prefix = udpMessage ? "[AO VIVO - UDP]" : "[EVENTO]";
        } else {
            prefix = "[INFO]";
        }

        appendEvent(prefix + " " + message);

        if ("STATUS".equals(type)) {
            updateAuctionSummary(message);
        }
    }

    private void handleAuthMessage(String[] parts) {
        if (parts.length >= 4 && "SUCCESS".equals(parts[1])) {
            authenticated = true;
            authenticatedRole = parts[3];
            appendEvent("[INFO] Autenticacao concluida para " + parts[2] + " com perfil " + authenticatedRole + ".");
        } else if (parts.length >= 3 && "ERROR".equals(parts[1])) {
            authenticated = false;
            authenticatedRole = "";
            appendEvent("[ERRO] " + parts[2]);
            showErrorAsync(parts[2]);
        } else {
            appendEvent("[ERRO] Resposta de autenticacao invalida recebida do servidor.");
        }

        updateControlState();
    }

    private void updateAuctionSummary(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                auctionSummaryArea.setText(message);
                auctionSummaryArea.setCaretPosition(0);
            }
        });
    }

    private void appendEvent(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                eventArea.append(message + "\n");
                eventArea.setCaretPosition(eventArea.getDocument().getLength());
            }
        });
    }

    private void handleDisconnection() {
        closeResources();
        authenticated = false;
        authenticatedRole = "";

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                auctionSummaryArea.setText("Nenhum item cadastrado ainda.");
                updateControlState();
            }
        });
    }

    private void closeClient() {
        if (serverWriter != null) {
            serverWriter.println("QUIT");
        }
        closeResources();
        dispose();
    }

    private void closeResources() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        socket = null;
        serverReader = null;
        serverWriter = null;
        udpSocket = null;
    }

    private void updateControlState() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                boolean connected = isConnected();
                boolean admin = connected && authenticated && "ADMIN".equalsIgnoreCase(authenticatedRole);
                boolean buyer = connected && authenticated && "COMPRADOR".equalsIgnoreCase(authenticatedRole);

                connectButton.setEnabled(!connected);
                hostField.setEnabled(!connected);
                portField.setEnabled(!connected);

                usernameField.setEnabled(!authenticated);
                passwordField.setEnabled(!authenticated);
                authenticateButton.setEnabled(connected && !authenticated);

                registerItemButton.setEnabled(admin);
                bidButton.setEnabled(buyer);
                statusButton.setEnabled(connected);
                closeAuctionButton.setEnabled(admin);

                connectionStatusLabel.setText(connected ? "Conectado" : "Desconectado");
                authStatusLabel.setText(authenticated ? "Autenticado" : (connected ? "Aguardando login" : "Pendente"));
                roleStatusLabel.setText(authenticated ? authenticatedRole : "-");
            }
        });
    }

    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Erro", JOptionPane.ERROR_MESSAGE);
    }

    private void showErrorAsync(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showError(message);
            }
        });
    }
}
