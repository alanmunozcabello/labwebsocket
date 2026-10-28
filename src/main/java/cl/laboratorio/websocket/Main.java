package cl.laboratorio.websocket;

import org.glassfish.tyrus.server.Server;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main {
    public static void main(String[] args) {
        // host, puerto, ruta base, clases de endpoints registradas
        Server servidor = new Server("localhost", 8080, "/", null, ChatWebSocketServer.class);

        try {
            servidor.start();
            System.out.println("Servidor WebSocket iniciado en ws://localhost:8080/ws/chat");
            System.out.println("Presiona ENTER para detener el servidor...");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            servidor.stop();
            System.out.println("Servidor detenido.");
        }
    }
}