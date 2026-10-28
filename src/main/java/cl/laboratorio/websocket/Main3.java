package cl.laboratorio.websocket;

import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.tyrus.server.Server;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main3 {

    private static final String HOST = "0.0.0.0";
    private static final int PUERTO_HTTP = 8080;
    private static final int PUERTO_WS = 8081;

    public static void main(String[] args) {
        // 1. Configurar servidor HTTP en el puerto 8080
        HttpServer httpServer = new HttpServer();
        NetworkListener httpListener = new NetworkListener("grizzly-http", HOST, PUERTO_HTTP);
        httpServer.addListener(httpListener);

        ServerConfiguration config = httpServer.getServerConfiguration();
        CLStaticHttpHandler staticHandler = new CLStaticHttpHandler(
                Main3.class.getClassLoader(),
                "static/"
        );
        staticHandler.setFileCacheEnabled(false);
        config.addHttpHandler(staticHandler, "/");

        // 2. Configurar servidor WebSocket en el puerto 8081
        Server tyrusServer = new Server(HOST, PUERTO_WS, "/", null, ChatWebSocketServer3.class);

        try {
            httpServer.start();
            tyrusServer.start();

            System.out.println("=================================================");
            System.out.println("Servicios iniciados (Main3):");
            System.out.println(" - Web HTTP:        http://localhost:" + PUERTO_HTTP + "/index3.html");
            System.out.println(" - WebSocket URI:   ws://localhost:" + PUERTO_WS + "/ws/chat");
            System.out.println("=================================================");
            System.out.println("Presiona ENTER para detener...");

            new BufferedReader(new InputStreamReader(System.in)).readLine();

        } catch (Exception e) {
            System.err.println("Error al iniciar el servidor: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                tyrusServer.stop();
            } catch (Exception ignored) {}
            httpServer.shutdownNow();
            System.out.println("Servidores detenidos.");
        }
    }
}