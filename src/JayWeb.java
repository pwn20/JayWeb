import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.time.LocalDateTime; // [2025-07-29] Added for consistent logging
import java.time.format.DateTimeFormatter; // [2025-07-29] Added for consistent logging

public class JayWeb
{
    // Debug flag to control verbose console output
    private static final boolean DEBUG = false;

    // Record to hold application configuration properties
    public static record Config(String appName, String version, int port, String baseDir)
    {
        // No explicit body needed for a simple record with canonical constructor
    }

    private static Config appConfig;

    // [2025-07-29] New helper method to get formatted timestamp
    private static String getTimestamp()
    {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss a");
        return now.format(formatter);
    }

    public static void main(String[] args)
    {
        if (DEBUG)
        {
            System.out.println("[" + getTimestamp() + "]: Starting JayWeb Server..."); // [2025-07-29] Debug print conforms to new style
        }

        // 1. Read configuration
        readConfig();

        if (appConfig != null)
        {
            System.out.println("[" + getTimestamp() + "]: Configuration Loaded:"); // [2025-07-29] Debug print conforms to new style
            System.out.println("[" + getTimestamp() + "]:   Application Name: " + appConfig.appName()); // [2025-07-29] Debug print conforms to new style
            System.out.println("[" + getTimestamp() + "]:   Version: " + appConfig.version()); // [2025-07-29] Debug print conforms to new style
            System.out.println("[" + getTimestamp() + "]:   Listening Port: " + appConfig.port()); // [2025-07-29] Debug print conforms to new style
            System.out.println("[" + getTimestamp() + "]:   Base Directory: " + appConfig.baseDir()); // [2025-07-29] Debug print conforms to new style

            // 2. Start the server socket listener
            startServer();

            // 3. Keep the main thread alive if needed for graceful shutdown, or if startServer blocks
            // For now, startServer() will block indefinitely in its accept loop
        }
        else
        {
            // [2025-07-29] Debug print conforms to new style
            System.err.println("[" + getTimestamp() + "]: Failed to load application configuration. Exiting.");
        }
    }

    private static void readConfig()
    {
        Properties properties = new Properties();
        String configFileName = "jayweb.cfg";

        try (InputStream input = new FileInputStream(configFileName))
        {
            properties.load(input);

            String appName = properties.getProperty("appName", "JayWeb Server");
            String version = properties.getProperty("version", "1.0");
            int port = Integer.parseInt(properties.getProperty("port", "8080"));
            String baseDir = properties.getProperty("baseDir", "."); // Current directory as default

            appConfig = new Config(appName, version, port, baseDir);
        }
        catch (IOException ex)
        {
            // [2025-07-29] Debug print conforms to new style
            System.err.println("[" + getTimestamp() + "]: Error reading configuration file '" + configFileName + "': " + ex.getMessage());
            appConfig = null; // Indicate configuration loading failed
        }
        catch (NumberFormatException ex)
        {
            // [2025-07-29] Debug print conforms to new style
            System.err.println("[" + getTimestamp() + "]: Error parsing port number in configuration file: " + ex.getMessage());
            appConfig = null; // Indicate configuration loading failed due to invalid port
        }
    }

    private static void startServer()
    {
        try (ServerSocket serverSocket = new ServerSocket(appConfig.port()))
        {
            System.out.println("[" + getTimestamp() + "]: JayWeb Server listening on port " + appConfig.port() + "..."); // [2025-07-29] Debug print conforms to new style

            while (true) // Continuous loop to accept connections
            {
                Socket clientSocket = serverSocket.accept(); // Blocks until a client connects
                if (DEBUG)
                {
                    // [2025-07-29] Debug print conforms to new style
                    System.out.println("[" + getTimestamp() + " - " + clientSocket.getInetAddress().getHostAddress() + "]: Connection accepted.");
                }

                // Create a new thread to handle the client connection
                // Pass the appConfig to the ClientHandler
                new Thread(new ClientHandler(clientSocket, appConfig, DEBUG)).start();
            }
        }
        catch (IOException ex)
        {
            // [2025-07-29] Debug print conforms to new style
            System.err.println("[" + getTimestamp() + "]: Server error: " + ex.getMessage());
            if (ex.getMessage().contains("Address already in use")) {
                // [2025-07-29] Debug print conforms to new style
                System.err.println("[" + getTimestamp() + "]: The port " + appConfig.port() + " is already in use. Please choose a different port or ensure no other server is running.");
            }
        }
    }

    // Placeholder for any other utility methods
}