package cl.laboratorio.websocket;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws/chat")
public class ChatWebSocketServer {

    // Colección sincronizada para almacenar las sesiones activas concurrentes
    private static final Set<Session> sesionesActivas = 
        Collections.synchronizedSet(new HashSet<>());

    @OnOpen
    public void alAbrir(Session sesion) {
        sesionesActivas.add(sesion);
        System.out.println("Nueva sesión WebSocket conectada: " + sesion.getId());
        difundirMensaje("[Sistema]: Nuevo usuario conectado (" + sesion.getId() + ")");
    }

    @OnMessage
    public void alRecibirMensaje(String mensaje, Session remitente) {
        System.out.println("Mensaje recibido de " + remitente.getId() + ": " + mensaje);
        difundirMensaje("[" + remitente.getId() + "]: " + mensaje);
    }

    @OnClose
    public void alCerrar(Session sesion) {
        sesionesActivas.remove(sesion);
        System.out.println("Sesión finalizada: " + sesion.getId());
        difundirMensaje("[Sistema]: Usuario desconectado (" + sesion.getId() + ")");
    }

    @OnError
    public void alOcurrirError(Session sesion, Throwable error) {
        System.err.println("Error en sesión " + (sesion != null ? sesion.getId() : "desconocida") + ": " + error.getMessage());
    }

    private void difundirMensaje(String texto) {
        synchronized (sesionesActivas) {
            for (Session sesion : sesionesActivas) {
                if (sesion.isOpen()) {
                    try {
                        // Envío asíncrono o síncrono del frame de texto
                        sesion.getBasicRemote().sendText(texto);
                    } catch (IOException e) {
                        System.err.println("Error al emitir a " + sesion.getId() + ": " + e.getMessage());
                    }
                }
            }
        }
    }
}