import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JSplitPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public class AuctionServer {
    private static final int DEFAULT_PORT = 5000;

    private final int port;
    private final AuctionState auctionState = new AuctionState();
    private final UserStore userStore = new UserStore();
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<ClientHandler>();
    private final CopyOnWriteArrayList<ServerObserver> observers = new CopyOnWriteArrayList<ServerObserver>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    public AuctionServer(int port) {
        this.port = port;
        userStore.ensureDefaultAdmin();
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        boolean noGui = false;

        for (String arg : args) {
            if ("--nogui".equalsIgnoreCase(arg)) {
                noGui = true;
            } else {
                port = Integer.parseInt(arg);
            }
        }

        final AuctionServer server = new AuctionServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.persistHistoryOnShutdown();
            }
        }));

        if (noGui || GraphicsEnvironment.isHeadless()) {
            server.start();
            return;
        }

        final int finalPort = port;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ServerDashboard dashboard = new ServerDashboard(server, finalPort);
                dashboard.setVisible(true);
                server.startAsync();
            }
        });
    }

    public void startAsync() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                start();
            }
        }, "auction-server-main");
        thread.setDaemon(true);
        thread.start();
    }

    public void start() {
        notifyLog("Servidor de leilão iniciado na porta " + port);
        notifyServerState("Escutando na porta " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.add(handler);
                notifyClientCount();
                notifyLog("Novo cliente conectado: " + clientSocket.getInetAddress().getHostAddress());
                clientPool.submit(handler);
            }
        } catch (IOException e) {
            notifyLog("Erro no servidor: " + e.getMessage());
        } finally {
            clientPool.shutdownNow();
            notifyServerState("Servidor encerrado");
        }
    }

    public void addObserver(ServerObserver observer) {
        observers.add(observer);
        observer.onUsersChanged(userStore.listUsers());
        observer.onAuctionSnapshot(auctionState.snapshot());
        observer.onConnectedClientsChanged(clients.size());
        observer.onServerStateChanged("Inicializando");
    }

    public void addUserFromDashboard(String username, String password, String roleText) {
        UserRole role = UserRole.valueOf(roleText.toUpperCase(Locale.ROOT));
        userStore.addUser(username, password, role);
        notifyUsersChanged();
        notifyLog("Usuário cadastrado pelo painel do servidor: " + username + " (" + role.name() + ")");
    }

    private void persistHistoryOnShutdown() {
        Path file = auctionState.persistHistory("encerramento-do-servidor");
        if (file != null) {
            notifyLog("Histórico salvo em: " + file.toAbsolutePath());
        }
    }

    private void removeClient(ClientHandler client) {
        clients.remove(client);
        notifyClientCount();
    }

    private void broadcastEvent(String message) {
        for (ClientHandler client : clients) {
            client.sendEvent(message);
            client.sendUdpEvent(message);
        }
        notifyLog(message);
    }

    private void notifyUsersChanged() {
        List<UserRecord> users = userStore.listUsers();
        for (ServerObserver observer : observers) {
            observer.onUsersChanged(users);
        }
    }

    private void notifyAuctionSnapshot() {
        String snapshot = auctionState.snapshot();
        for (ServerObserver observer : observers) {
            observer.onAuctionSnapshot(snapshot);
        }
    }

    private void notifyClientCount() {
        int count = clients.size();
        for (ServerObserver observer : observers) {
            observer.onConnectedClientsChanged(count);
        }
    }

    private void notifyLog(String message) {
        System.out.println(message);
        for (ServerObserver observer : observers) {
            observer.onLog(message);
        }
    }

    private void notifyServerState(String state) {
        for (ServerObserver observer : observers) {
            observer.onServerStateChanged(state);
        }
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private UserRecord authenticatedUser;
        private UserRecord pendingAuthUser;
        private String authNonce;
        private SecretKey sessionKey;
        private InetAddress udpAddress;
        private int udpPort = -1;
        private boolean connectionClosed;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                sendInfo("Conexão estabelecida com o servidor de leilão.");
                sendInfo("Autentique-se pelo cliente para estabelecer o canal seguro.");
                sendInfo("Roles disponíveis: ADMIN, COMPRADOR, OBSERVADOR.");

                String line;
                while ((line = reader.readLine()) != null) {
                    String decodedLine = decodeIncomingMessage(line.trim());
                    if (decodedLine != null) {
                        handleCommand(decodedLine.trim());
                    }
                }
            } catch (IOException e) {
                notifyLog("Conexão encerrada: " + e.getMessage());
            } finally {
                closeConnection();
            }
        }

        private String decodeIncomingMessage(String rawMessage) {
            if (rawMessage.isEmpty()) {
                return null;
            }

            if (AuctionCrypto.isSecureEnvelope(rawMessage)) {
                if (sessionKey == null) {
                    sendPlainError("Canal seguro ainda nao foi estabelecido.");
                    return null;
                }

                try {
                    return AuctionCrypto.decryptMessage(rawMessage, sessionKey);
                } catch (IllegalStateException e) {
                    sendError("Nao foi possivel descriptografar a mensagem recebida.");
                    return null;
                }
            }

            if (sessionKey != null && authenticatedUser != null && !rawMessage.startsWith("AUTH ")) {
                sendError("Apos autenticar, envie comandos apenas pelo canal seguro.");
                return null;
            }

            return rawMessage;
        }

        private void handleCommand(String line) {
            if (line.trim().isEmpty()) {
                return;
            }

            String[] parts = line.split(" ", 2);
            String command = parts[0].toUpperCase(Locale.ROOT);
            String argument = parts.length > 1 ? parts[1].trim() : "";

            switch (command) {
                case "AUTH":
                    handleAuthentication(argument);
                    break;
                case "UDP":
                    handleUdpRegistration(argument);
                    break;
                case "ITEM":
                    handleItemRegistration(argument);
                    break;
                case "BID":
                    handleBid(argument);
                    break;
                case "STATUS":
                    sendStatus(auctionState.snapshot());
                    break;
                case "CLOSE":
                    handleCloseAuction();
                    break;
                case "HELP":
                    sendHelp();
                    break;
                case "QUIT":
                    sendInfo("Conexão encerrada pelo cliente.");
                    closeConnection();
                    break;
                default:
                    sendError("Comando desconhecido. Use HELP para ver os comandos disponíveis.");
                    break;
            }
        }

        private void handleAuthentication(String argument) {
            String[] values = argument.split("\\|", 3);
            if (values.length < 2) {
                sendAuthFailure("Use AUTH REQUEST|usuario ou AUTH RESPONSE|usuario|prova.");
                return;
            }

            String action = values[0].trim().toUpperCase(Locale.ROOT);
            String username = values[1].trim();

            if ("REQUEST".equals(action)) {
                startAuthentication(username);
                return;
            }

            if ("RESPONSE".equals(action)) {
                String proof = values.length > 2 ? values[2].trim() : "";
                finishAuthentication(username, proof);
                return;
            }

            sendAuthFailure("Etapa de autenticacao desconhecida.");
        }

        private void startAuthentication(String username) {
            if (username.isEmpty()) {
                sendAuthFailure("Usuario e obrigatorio.");
                return;
            }

            clearPendingAuthentication();
            UserRecord user = userStore.findUser(username);
            if (user == null) {
                sendAuthFailure("Usuario ou senha invalidos.");
                return;
            }

            pendingAuthUser = user;
            authNonce = AuctionCrypto.generateNonce();
            sendPlain("AUTH", "CHALLENGE|" + username + "|" + authNonce);
        }

        private void finishAuthentication(String username, String proof) {
            if (pendingAuthUser == null || authNonce == null) {
                sendAuthFailure("Solicite um desafio de autenticacao antes de responder.");
                return;
            }

            if (proof.isEmpty() || !Objects.equals(pendingAuthUser.getUsername(), username)) {
                clearPendingAuthentication();
                sendAuthFailure("Resposta de autenticacao invalida.");
                return;
            }

            SecretKey loginKey = AuctionCrypto.deriveLoginKey(pendingAuthUser.getPassword(), authNonce);
            String expectedProof = AuctionCrypto.createProof(username, loginKey);
            if (!AuctionCrypto.proofMatches(expectedProof, proof)) {
                clearPendingAuthentication();
                sendAuthFailure("Usuario ou senha invalidos.");
                return;
            }

            authenticatedUser = pendingAuthUser;
            sessionKey = AuctionCrypto.deriveSessionKey(loginKey);
            clearPendingAuthentication();

            sendAuthSuccess(authenticatedUser);
            sendInfo("Canal seguro estabelecido para " + authenticatedUser.getUsername() + ".");
            sendStatus(auctionState.snapshot());

            String event = auctionState.recordParticipantEvent(
                    authenticatedUser.getUsername() + " entrou no leilão como " + authenticatedUser.getRole().name() + "."
            );
            broadcastEvent(event);
        }

        private void clearPendingAuthentication() {
            pendingAuthUser = null;
            authNonce = null;
        }

        private void handleUdpRegistration(String portText) {
            if (!ensureAuthenticated()) {
                return;
            }
            try {
                udpPort = Integer.parseInt(portText);
                udpAddress = socket.getInetAddress();
                sendInfo("Porta UDP registrada com sucesso: " + udpPort);
            } catch (NumberFormatException e) {
                sendError("Porta UDP inválida.");
            }
        }

        private void handleItemRegistration(String argument) {
            if (!ensureRole(UserRole.ADMIN, "Apenas usuários ADMIN podem cadastrar itens.")) {
                return;
            }

            String[] values = argument.split("\\|", 2);
            if (values.length < 2) {
                sendError("Use ITEM nome_do_item|valor_inicial");
                return;
            }

            String itemName = values[0].trim();
            String openingBidText = values[1].trim();

            try {
                double openingBid = Double.parseDouble(openingBidText);
                AuctionAction action = auctionState.registerItem(itemName, openingBid, authenticatedUser);
                respondToAction(action);
            } catch (NumberFormatException e) {
                sendError("Valor inicial inválido.");
            }
        }

        private void handleBid(String argument) {
            if (!ensureRole(UserRole.COMPRADOR, "Apenas usuários COMPRADOR podem enviar lances.")) {
                return;
            }

            try {
                double bidValue = Double.parseDouble(argument);
                AuctionAction action = auctionState.placeBid(authenticatedUser, bidValue);
                respondToAction(action);
            } catch (NumberFormatException e) {
                sendError("Lance inválido. Use um número, por exemplo: BID 120.50");
            }
        }

        private void handleCloseAuction() {
            if (!ensureRole(UserRole.ADMIN, "Apenas usuários ADMIN podem encerrar o leilão.")) {
                return;
            }

            AuctionAction action = auctionState.closeAuction(authenticatedUser);
            respondToAction(action);
        }

        private boolean ensureAuthenticated() {
            if (authenticatedUser == null) {
                sendError("Autentique-se antes de usar esse comando.");
                return false;
            }
            return true;
        }

        private boolean ensureRole(UserRole expectedRole, String errorMessage) {
            if (!ensureAuthenticated()) {
                return false;
            }
            if (authenticatedUser.getRole() != expectedRole) {
                sendError(errorMessage);
                return false;
            }
            return true;
        }

        private void respondToAction(AuctionAction action) {
            if (!action.success()) {
                sendError(action.message());
                return;
            }

            sendInfo(action.message());

            if (action.broadcastMessage() != null) {
                broadcastEvent(action.broadcastMessage());
            }

            if (action.persistFile() != null) {
                broadcastEvent("Histórico salvo em " + action.persistFile().toAbsolutePath());
            }

            notifyAuctionSnapshot();
        }

        private void closeConnection() {
            if (connectionClosed) {
                return;
            }
            connectionClosed = true;

            removeClient(this);

            if (authenticatedUser != null) {
                String event = auctionState.recordParticipantEvent(authenticatedUser.getUsername() + " saiu do leilão.");
                notifyLog(event);
            }

            if (!socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void sendHelp() {
            sendInfo("Comandos disponíveis:");
            sendInfo("AUTH REQUEST|usuario");
            sendInfo("AUTH RESPONSE|usuario|prova");
            sendInfo("ITEM nome_do_item|valor_inicial");
            sendInfo("BID valor");
            sendInfo("STATUS");
            sendInfo("CLOSE");
            sendInfo("HELP");
            sendInfo("QUIT");
        }

        private void sendInfo(String message) {
            send("INFO", message);
        }

        private void sendAuthSuccess(UserRecord user) {
            sendPlain("AUTH", "SUCCESS|" + user.getUsername() + "|" + user.getRole().name());
        }

        private void sendAuthFailure(String message) {
            clearPendingAuthentication();
            sessionKey = null;
            authenticatedUser = null;
            udpAddress = null;
            udpPort = -1;
            sendPlain("AUTH", "ERROR|" + message);
        }

        private void sendError(String message) {
            send("ERROR", message);
        }

        private void sendPlainError(String message) {
            sendPlain("ERROR", message);
        }

        private void sendStatus(String message) {
            send("STATUS", message);
        }

        private void sendEvent(String message) {
            send("EVENT", message);
        }

        private void send(String type, String message) {
            String payload = type + "|" + message;
            if (writer == null) {
                return;
            }
            if (sessionKey != null && authenticatedUser != null && !"AUTH".equals(type)) {
                writer.println(AuctionCrypto.encryptMessage(sessionKey, payload));
                return;
            }
            writer.println(payload);
        }

        private void sendPlain(String type, String message) {
            if (writer != null) {
                writer.println(type + "|" + message);
            }
        }

        private void sendUdpEvent(String message) {
            if (udpAddress == null || udpPort <= 0) {
                return;
            }

            byte[] data = AuctionCrypto.encryptMessage(sessionKey, "EVENT|" + message).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, udpAddress, udpPort);

            try (DatagramSocket datagramSocket = new DatagramSocket()) {
                datagramSocket.send(packet);
            } catch (IOException e) {
                sendError("Não foi possível enviar atualização UDP: " + e.getMessage());
            }
        }
    }

    private static final class AuctionState {
        private static final DateTimeFormatter EVENT_TIME_FORMAT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        private static final DateTimeFormatter FILE_TIME_FORMAT =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

        private String itemName;
        private double currentBid;
        private String currentWinner = "sem vencedor";
        private boolean itemRegistered;
        private boolean open;
        private boolean historySaved;
        private String closedBy = "não encerrado";
        private final List<EventRecord> history = new ArrayList<EventRecord>();

        public synchronized AuctionAction registerItem(String itemName, double openingBid, UserRecord author) {
            if (itemRegistered) {
                return AuctionAction.error("O item do leilão já foi cadastrado.");
            }

            if (itemName.trim().isEmpty()) {
                return AuctionAction.error("O nome do item não pode ser vazio.");
            }

            if (openingBid < 0) {
                return AuctionAction.error("O valor inicial não pode ser negativo.");
            }

            this.itemName = itemName;
            this.currentBid = openingBid;
            this.currentWinner = "sem vencedor";
            this.itemRegistered = true;
            this.open = true;
            this.historySaved = false;
            this.closedBy = "não encerrado";
            this.history.clear();

            String event = recordEvent("Item cadastrado por " + author.getUsername() + ": " + itemName
                    + " (valor inicial " + formatMoney(openingBid) + ")");

            return AuctionAction.success("Item cadastrado com sucesso.", event, null);
        }

        public synchronized AuctionAction placeBid(UserRecord bidder, double bidValue) {
            if (!itemRegistered) {
                return AuctionAction.error("Cadastre um item antes de receber lances.");
            }

            if (!open) {
                return AuctionAction.error("O leilão já foi encerrado.");
            }

            if (bidValue <= currentBid) {
                return AuctionAction.error("O lance deve ser maior que o valor atual (" + formatMoney(currentBid) + ").");
            }

            currentBid = bidValue;
            currentWinner = bidder.getUsername();

            String event = recordEvent("Novo lance: " + bidder.getUsername() + " ofereceu " + formatMoney(bidValue));
            return AuctionAction.success("Lance registrado com sucesso.", event, null);
        }

        public synchronized AuctionAction closeAuction(UserRecord author) {
            if (!itemRegistered) {
                return AuctionAction.error("Nenhum item foi cadastrado.");
            }

            if (!open) {
                return AuctionAction.error("O leilão já está encerrado.");
            }

            open = false;
            closedBy = author.getUsername();

            String event = recordEvent("Leilão encerrado por " + author.getUsername() + ". Vencedor: "
                    + currentWinner + ". Valor final: " + formatMoney(currentBid));

            Path persistFile = persistHistory("encerramento-do-leilao");
            return AuctionAction.success("Leilão encerrado com sucesso.", event, persistFile);
        }

        public synchronized String recordParticipantEvent(String description) {
            return recordEvent(description);
        }

        public synchronized String snapshot() {
            if (!itemRegistered) {
                return "Nenhum item cadastrado ainda.";
            }

            String status = open ? "aberto" : "encerrado";
            return "Item: " + itemName
                    + " | Status: " + status
                    + " | Lance atual: " + formatMoney(currentBid)
                    + " | Maior ofertante: " + currentWinner
                    + " | Encerrado por: " + closedBy;
        }

        public synchronized Path persistHistory(String reason) {
            if (history.isEmpty() || historySaved) {
                return null;
            }

            try {
                Files.createDirectories(Paths.get("historico"));
                String fileName = "historico/leilao-" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".json";
                Path file = Paths.get(fileName);
                Files.write(file, buildHistoryJson(reason).getBytes(StandardCharsets.UTF_8));
                historySaved = true;
                return file;
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível persistir o histórico: " + e.getMessage(), e);
            }
        }

        private String buildHistoryJson(String reason) {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            builder.append("  \"reason\": \"").append(JsonUtil.escape(reason)).append("\",\n");
            builder.append("  \"item\": \"").append(JsonUtil.escape(itemName == null ? "" : itemName)).append("\",\n");
            builder.append("  \"status\": \"").append(open ? "aberto" : "encerrado").append("\",\n");
            builder.append("  \"winner\": \"").append(JsonUtil.escape(currentWinner)).append("\",\n");
            builder.append("  \"finalBid\": ").append(String.format(Locale.US, "%.2f", currentBid)).append(",\n");
            builder.append("  \"closedBy\": \"").append(JsonUtil.escape(closedBy)).append("\",\n");
            builder.append("  \"events\": [\n");

            for (int i = 0; i < history.size(); i++) {
                EventRecord record = history.get(i);
                builder.append("    {");
                builder.append("\"timestamp\": \"").append(JsonUtil.escape(record.getTimestamp())).append("\", ");
                builder.append("\"message\": \"").append(JsonUtil.escape(record.getMessage())).append("\"");
                builder.append("}");
                if (i < history.size() - 1) {
                    builder.append(",");
                }
                builder.append("\n");
            }

            builder.append("  ]\n");
            builder.append("}\n");
            return builder.toString();
        }

        private String recordEvent(String description) {
            String timestamp = EVENT_TIME_FORMAT.format(LocalDateTime.now());
            history.add(new EventRecord(timestamp, description));
            return "[" + timestamp + "] " + description;
        }

        private String formatMoney(double value) {
            return String.format(Locale.US, "R$ %.2f", value);
        }
    }

    private static final class UserStore {
        private static final Path USERS_FILE = Paths.get("usuarios.json");
        private final Map<String, UserRecord> users = new LinkedHashMap<String, UserRecord>();

        private UserStore() {
            load();
        }

        public synchronized void ensureDefaultAdmin() {
            if (!users.containsKey("admin")) {
                users.put("admin", new UserRecord("admin", "admin123", UserRole.ADMIN));
                save();
            }
        }

        public synchronized void addUser(String username, String password, UserRole role) {
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalArgumentException("O usuário não pode ser vazio.");
            }
            if (password == null || password.trim().isEmpty()) {
                throw new IllegalArgumentException("A senha não pode ser vazia.");
            }
            if (users.containsKey(username)) {
                throw new IllegalArgumentException("Já existe um usuário com esse nome.");
            }

            users.put(username, new UserRecord(username, password, role));
            save();
        }

        public synchronized UserRecord findUser(String username) {
            return users.get(username);
        }

        public synchronized List<UserRecord> listUsers() {
            return new ArrayList<UserRecord>(users.values());
        }

        private void load() {
            if (!Files.exists(USERS_FILE)) {
                return;
            }

            try {
                String json = new String(Files.readAllBytes(USERS_FILE), StandardCharsets.UTF_8);
                Pattern pattern = Pattern.compile("\\{\\s*\"username\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"password\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"role\"\\s*:\\s*\"(.*?)\"\\s*\\}");
                Matcher matcher = pattern.matcher(json);

                while (matcher.find()) {
                    String username = JsonUtil.unescape(matcher.group(1));
                    String password = JsonUtil.unescape(matcher.group(2));
                    String role = JsonUtil.unescape(matcher.group(3));
                    users.put(username, new UserRecord(username, password, UserRole.valueOf(role)));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível ler usuarios.json: " + e.getMessage(), e);
            }
        }

        private synchronized void save() {
            try {
                StringBuilder builder = new StringBuilder();
                builder.append("{\n");
                builder.append("  \"users\": [\n");

                int index = 0;
                for (UserRecord user : users.values()) {
                    builder.append("    {");
                    builder.append("\"username\": \"").append(JsonUtil.escape(user.getUsername())).append("\", ");
                    builder.append("\"password\": \"").append(JsonUtil.escape(user.getPassword())).append("\", ");
                    builder.append("\"role\": \"").append(user.getRole().name()).append("\"");
                    builder.append("}");

                    if (index < users.size() - 1) {
                        builder.append(",");
                    }
                    builder.append("\n");
                    index++;
                }

                builder.append("  ]\n");
                builder.append("}\n");

                Files.write(USERS_FILE, builder.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível salvar usuarios.json: " + e.getMessage(), e);
            }
        }
    }

    private static final class ServerDashboard extends JFrame implements ServerObserver {
        private final AuctionServer server;
        private final JTextArea logArea = new JTextArea();
        private final DefaultListModel<String> userListModel = new DefaultListModel<String>();
        private final JList<String> userList = new JList<String>(userListModel);
        private final JTextField usernameField = new JTextField(12);
        private final JPasswordField passwordField = new JPasswordField(12);
        private final JComboBox<String> roleBox = new JComboBox<String>(new String[] {"ADMIN", "COMPRADOR", "OBSERVADOR"});
        private final JLabel serverStatusLabel = new JLabel("Inicializando...");
        private final JLabel clientsLabel = new JLabel("0");
        private final JTextArea auctionArea = new JTextArea("Nenhum item cadastrado ainda.");

        private ServerDashboard(AuctionServer server, int port) {
            super("Servidor de Leilão");
            this.server = server;
            server.addObserver(this);
            buildInterface(port);
        }

        private void buildInterface(int port) {
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            setSize(new Dimension(1280, 760));
            setMinimumSize(new Dimension(1100, 680));
            setLocationRelativeTo(null);
            setLayout(new BorderLayout(12, 12));

            JPanel contentPanel = new JPanel(new BorderLayout(12, 12));
            contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            setContentPane(contentPanel);

            JPanel topPanel = new JPanel(new GridBagLayout());
            topPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Status do Servidor"),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
            ));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;

            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 0;
            topPanel.add(new JLabel("Porta:"), c);

            c.gridx = 1;
            c.weightx = 0.2;
            topPanel.add(new JLabel(String.valueOf(port)), c);

            c.gridx = 2;
            c.weightx = 0;
            topPanel.add(new JLabel("Estado:"), c);

            c.gridx = 3;
            c.weightx = 1.0;
            topPanel.add(serverStatusLabel, c);

            c.gridx = 4;
            c.weightx = 0;
            topPanel.add(new JLabel("Clientes conectados:"), c);

            c.gridx = 5;
            c.weightx = 0.2;
            topPanel.add(clientsLabel, c);

            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 6;
            c.weightx = 1.0;
            c.fill = GridBagConstraints.HORIZONTAL;

            auctionArea.setEditable(false);
            auctionArea.setLineWrap(true);
            auctionArea.setWrapStyleWord(true);
            auctionArea.setRows(4);
            auctionArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JScrollPane auctionScrollPane = new JScrollPane(auctionArea);
            auctionScrollPane.setBorder(BorderFactory.createTitledBorder("Resumo Atual do Leilao"));
            topPanel.add(auctionScrollPane, c);

            JPanel usersPanel = new JPanel(new BorderLayout(10, 10));
            usersPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Cadastro de Usuarios"),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
            ));

            JPanel formPanel = new JPanel(new GridBagLayout());
            GridBagConstraints form = new GridBagConstraints();
            form.insets = new Insets(6, 6, 6, 6);
            form.anchor = GridBagConstraints.WEST;
            form.fill = GridBagConstraints.HORIZONTAL;

            form.gridx = 0;
            form.gridy = 0;
            form.weightx = 0;
            formPanel.add(new JLabel("Usuário:"), form);

            form.gridx = 1;
            form.weightx = 1.0;
            formPanel.add(usernameField, form);

            form.gridx = 0;
            form.gridy = 1;
            form.weightx = 0;
            formPanel.add(new JLabel("Senha:"), form);

            form.gridx = 1;
            form.weightx = 1.0;
            formPanel.add(passwordField, form);

            form.gridx = 0;
            form.gridy = 2;
            form.weightx = 0;
            formPanel.add(new JLabel("Role:"), form);

            form.gridx = 1;
            form.weightx = 1.0;
            formPanel.add(roleBox, form);

            JButton addUserButton = new JButton("Cadastrar Usuário");
            addUserButton.addActionListener(e -> addUser());
            form.gridx = 0;
            form.gridy = 3;
            form.gridwidth = 2;
            form.weightx = 1.0;
            formPanel.add(addUserButton, form);

            userList.setVisibleRowCount(12);
            usersPanel.add(formPanel, BorderLayout.NORTH);
            usersPanel.add(new JScrollPane(userList), BorderLayout.CENTER);

            logArea.setEditable(false);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            logArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JScrollPane logPane = new JScrollPane(logArea);
            logPane.setBorder(BorderFactory.createTitledBorder("Eventos do Servidor"));

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, usersPanel, logPane);
            splitPane.setResizeWeight(0.34);
            splitPane.setDividerLocation(420);
            splitPane.setContinuousLayout(true);
            splitPane.setBorder(null);

            contentPanel.add(topPanel, BorderLayout.NORTH);
            contentPanel.add(splitPane, BorderLayout.CENTER);
        }

        private void addUser() {
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();
            String role = (String) roleBox.getSelectedItem();

            try {
                server.addUserFromDashboard(username, password, role);
                usernameField.setText("");
                passwordField.setText("");
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void onLog(final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    logArea.append(message + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            });
        }

        @Override
        public void onUsersChanged(final List<UserRecord> users) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    userListModel.clear();
                    for (UserRecord user : users) {
                        userListModel.addElement(user.getUsername() + " - " + user.getRole().name());
                    }
                }
            });
        }

        @Override
        public void onAuctionSnapshot(final String snapshot) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    auctionArea.setText(snapshot);
                }
            });
        }

        @Override
        public void onConnectedClientsChanged(final int count) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    clientsLabel.setText(String.valueOf(count));
                }
            });
        }

        @Override
        public void onServerStateChanged(final String state) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    serverStatusLabel.setText(state);
                }
            });
        }
    }

    private interface ServerObserver {
        void onLog(String message);
        void onUsersChanged(List<UserRecord> users);
        void onAuctionSnapshot(String snapshot);
        void onConnectedClientsChanged(int count);
        void onServerStateChanged(String state);
    }

    private enum UserRole {
        ADMIN,
        COMPRADOR,
        OBSERVADOR;
    }

    private static final class UserRecord {
        private final String username;
        private final String password;
        private final UserRole role;

        private UserRecord(String username, String password, UserRole role) {
            this.username = username;
            this.password = password;
            this.role = role;
        }

        private String getUsername() {
            return username;
        }

        private String getPassword() {
            return password;
        }

        private UserRole getRole() {
            return role;
        }
    }

    private static final class EventRecord {
        private final String timestamp;
        private final String message;

        private EventRecord(String timestamp, String message) {
            this.timestamp = timestamp;
            this.message = message;
        }

        private String getTimestamp() {
            return timestamp;
        }

        private String getMessage() {
            return message;
        }
    }

    private static final class AuctionAction {
        private final boolean success;
        private final String message;
        private final String broadcastMessage;
        private final Path persistFile;

        private AuctionAction(boolean success, String message, String broadcastMessage, Path persistFile) {
            this.success = success;
            this.message = message;
            this.broadcastMessage = broadcastMessage;
            this.persistFile = persistFile;
        }

        private static AuctionAction success(String message, String broadcastMessage, Path persistFile) {
            return new AuctionAction(true, message, broadcastMessage, persistFile);
        }

        private static AuctionAction error(String message) {
            return new AuctionAction(false, message, null, null);
        }

        private boolean success() {
            return success;
        }

        private String message() {
            return message;
        }

        private String broadcastMessage() {
            return broadcastMessage;
        }

        private Path persistFile() {
            return persistFile;
        }
    }

    private static final class JsonUtil {
        private JsonUtil() {
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

        private static String unescape(String value) {
            return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }
}
