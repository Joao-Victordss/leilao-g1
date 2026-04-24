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
import javax.swing.JPasswordField;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public class AuctionClient extends JFrame {
    private static final int DEFAULT_PORT = 5000;

    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
    private final JTextField usernameField = new JTextField(12);
    private final JPasswordField passwordField = new JPasswordField(12);

    private final JTextField itemNameField = new JTextField(16);
    private final JTextField itemValueField = new JTextField(8);
    private final JTextField bidValueField = new JTextField(10);

    private final JTextArea eventArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Desconectado");

    private final JButton connectButton = new JButton("Conectar");
    private final JButton registerItemButton = new JButton("Cadastrar Item");
    private final JButton bidButton = new JButton("Enviar Lance");
    private final JButton statusButton = new JButton("Atualizar Status");
    private final JButton closeAuctionButton = new JButton("Encerrar Leilão");
    private final JButton quitButton = new JButton("Sair");

    private Socket socket;
    private BufferedReader serverReader;
    private PrintWriter serverWriter;
    private DatagramSocket udpSocket;

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
        super("Cliente de Leilão");
        hostField.setText(host);
        portField.setText(String.valueOf(port));
        buildInterface();
        bindActions();
        updateControls(false);
    }

    private void buildInterface() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        JPanel connectionPanel = new JPanel(new GridBagLayout());
        connectionPanel.setBorder(BorderFactory.createTitledBorder("Conexão"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        connectionPanel.add(new JLabel("Host:"), c);

        c.gridx = 1;
        connectionPanel.add(hostField, c);

        c.gridx = 2;
        connectionPanel.add(new JLabel("Porta:"), c);

        c.gridx = 3;
        connectionPanel.add(portField, c);

        c.gridx = 4;
        connectionPanel.add(new JLabel("Usuário:"), c);

        c.gridx = 5;
        connectionPanel.add(usernameField, c);

        c.gridx = 6;
        connectionPanel.add(new JLabel("Senha:"), c);

        c.gridx = 7;
        connectionPanel.add(passwordField, c);

        c.gridx = 8;
        connectionPanel.add(connectButton, c);

        JPanel actionPanel = new JPanel(new GridBagLayout());
        actionPanel.setBorder(BorderFactory.createTitledBorder("Ações do Leilão"));

        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(5, 5, 5, 5);
        a.anchor = GridBagConstraints.WEST;

        a.gridx = 0;
        a.gridy = 0;
        actionPanel.add(new JLabel("Item:"), a);

        a.gridx = 1;
        a.fill = GridBagConstraints.HORIZONTAL;
        actionPanel.add(itemNameField, a);

        a.gridx = 2;
        a.fill = GridBagConstraints.NONE;
        actionPanel.add(new JLabel("Valor inicial:"), a);

        a.gridx = 3;
        actionPanel.add(itemValueField, a);

        a.gridx = 4;
        actionPanel.add(registerItemButton, a);

        a.gridx = 0;
        a.gridy = 1;
        actionPanel.add(new JLabel("Lance:"), a);

        a.gridx = 1;
        actionPanel.add(bidValueField, a);

        a.gridx = 2;
        actionPanel.add(bidButton, a);

        a.gridx = 3;
        actionPanel.add(statusButton, a);

        a.gridx = 4;
        actionPanel.add(closeAuctionButton, a);

        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.add(connectionPanel, BorderLayout.NORTH);
        topPanel.add(actionPanel, BorderLayout.CENTER);

        eventArea.setEditable(false);
        eventArea.setLineWrap(true);
        eventArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(eventArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Painel em Tempo Real"));

        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        footerPanel.add(new JLabel("Resumo atual:"));
        footerPanel.add(statusLabel);
        footerPanel.add(quitButton);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);
    }

    private void bindActions() {
        connectButton.addActionListener(e -> connect());
        registerItemButton.addActionListener(e -> registerItem());
        bidButton.addActionListener(e -> sendBid());
        statusButton.addActionListener(e -> sendCommand("STATUS"));
        closeAuctionButton.addActionListener(e -> sendCommand("CLOSE"));
        quitButton.addActionListener(e -> closeClient());
    }

    private void connect() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            appendEvent("[INFO] Cliente já está conectado.");
            return;
        }

        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (host.isEmpty() || portText.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showError("Preencha host, porta, usuário e senha antes de conectar.");
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

            updateControls(true);
            statusLabel.setText("Conectado a " + host + ":" + port);
            appendEvent("[INFO] Conexão estabelecida com o servidor.");
            appendEvent("[INFO] Porta UDP local: " + udpSocket.getLocalPort());

            sendCommand("LOGIN " + username + "|" + password);
            sendCommand("UDP " + udpSocket.getLocalPort());
            sendCommand("STATUS");
        } catch (NumberFormatException e) {
            showError("Porta inválida.");
        } catch (IOException e) {
            showError("Não foi possível conectar: " + e.getMessage());
            closeResources();
            updateControls(false);
        }
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
                    appendEvent("[ERRO] Conexão TCP encerrada: " + e.getMessage());
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
                            appendEvent("[ERRO] Erro ao receber atualização UDP: " + e.getMessage());
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
        String[] parts = rawMessage.split("\\|", 2);
        String type = parts.length > 1 ? parts[0] : "INFO";
        String message = parts.length > 1 ? parts[1] : rawMessage;

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
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    statusLabel.setText(message);
                }
            });
        }
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
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                updateControls(false);
                statusLabel.setText("Desconectado");
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

    private void updateControls(boolean connected) {
        connectButton.setEnabled(!connected);
        hostField.setEnabled(!connected);
        portField.setEnabled(!connected);
        usernameField.setEnabled(!connected);
        passwordField.setEnabled(!connected);

        registerItemButton.setEnabled(connected);
        bidButton.setEnabled(connected);
        statusButton.setEnabled(connected);
        closeAuctionButton.setEnabled(connected);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Erro", JOptionPane.ERROR_MESSAGE);
    }
}
