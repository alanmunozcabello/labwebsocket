package cl.laboratorio.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servidor WebSocket con Heartbeat basado en actividad del usuario.
 *
 * Regla de negocio:
 * Se considera "señal de vida" cualquier acción explícita del usuario:
 *   - Enviar un mensaje de chat.
 *   - Pulsar el botón "Estoy aquí" (envía el texto especial "__pong__").
 *
 * Si no se recibe ninguna acción en TIMEOUT_INACTIVIDAD_MS milisegundos,
 * el servidor cierra la sesión y notifica al resto de la sala.
 */
@ServerEndpoint("/ws/chat")
public class ChatWebSocketServer3 {

    /** Intervalo de revisión: cada cuántos segundos se evalúan las sesiones inactivas. */
    public static final long INTERVALO_REVISION_SEGUNDOS = 10;

    /** Tiempo máximo sin actividad antes de cerrar la sesión (milisegundos). */
    public static final long TIMEOUT_INACTIVIDAD_MS = 30_000; // 30 segundos

    /** Texto especial que el cliente envía al pulsar "Estoy aquí". */
    private static final String PONG_TEXTO = "__pong__";

    // Mapa session → nombre de usuario
    private static final Map<Session, String> usuariosActivos = new ConcurrentHashMap<>();

    // Mapa session → timestamp de la última actividad recibida
    private static final Map<Session, Long> ultimaActividad = new ConcurrentHashMap<>();

    // Hilo de revisión periódica (daemon)
    private static final ScheduledExecutorService revisor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-inactividad-revisor");
                t.setDaemon(true);
                return t;
            });

    static {
        revisor.scheduleAtFixedRate(
                ChatWebSocketServer3::revisarInactividad,
                INTERVALO_REVISION_SEGUNDOS,
                INTERVALO_REVISION_SEGUNDOS,
                TimeUnit.SECONDS
        );
        System.out.println("[Heartbeat] Revisor iniciado: revision cada "
                + INTERVALO_REVISION_SEGUNDOS + "s | timeout: "
                + (TIMEOUT_INACTIVIDAD_MS / 1000) + "s sin actividad.");
    }

    /** Detiene el revisor al apagar el servidor. */
    public static void detenerHeartbeat() {
        revisor.shutdownNow();
        System.out.println("[Heartbeat] Revisor detenido.");
    }

    // -------------------------------------------------------------------------
    // Revisión periódica de inactividad
    // -------------------------------------------------------------------------

    private static void revisarInactividad() {
        long ahora = System.currentTimeMillis();

        for (Map.Entry<Session, String> entrada : usuariosActivos.entrySet()) {
            Session sesion = entrada.getKey();
            String nombre  = entrada.getValue();

            if (!sesion.isOpen()) {
                limpiarSesion(sesion, nombre, "Sesion ya cerrada");
                continue;
            }

            Long marca   = ultimaActividad.get(sesion);
            long inactivo = (marca != null) ? (ahora - marca) : Long.MAX_VALUE;

            if (inactivo > TIMEOUT_INACTIVIDAD_MS) {
                System.out.println("[Inactividad] " + nombre + " lleva "
                        + (inactivo / 1000) + "s sin actividad -> cerrando sesion.");
                cerrarPorInactividad(sesion, nombre);
            } else {
                System.out.println("[Inactividad] " + nombre
                        + " activo (ultima actividad hace " + (inactivo / 1000) + "s).");
            }
        }
    }

    private static void cerrarPorInactividad(Session sesion, String nombre) {
        try {
            if (sesion.isOpen()) {
                synchronized (sesion) {
                    if (sesion.isOpen()) {
                        sesion.getBasicRemote().sendText(
                                "[Sistema]: Has sido desconectado por inactividad ("
                                        + (TIMEOUT_INACTIVIDAD_MS / 1000) + "s sin actividad)."
                        );
                    }
                }
                sesion.close(new CloseReason(
                        CloseReason.CloseCodes.GOING_AWAY,
                        "Timeout de inactividad: " + (TIMEOUT_INACTIVIDAD_MS / 1000) + "s"
                ));
            }
        } catch (IOException e) {
            System.err.println("[Inactividad] Error al cerrar sesion de " + nombre + ": " + e.getMessage());
        } finally {
            limpiarSesion(sesion, nombre, "Timeout de inactividad");
        }
    }

    private static void limpiarSesion(Session sesion, String nombre, String motivo) {
        boolean eliminado = (usuariosActivos.remove(sesion) != null);
        ultimaActividad.remove(sesion);
        if (eliminado) {
            System.out.println("[Recursos] Sesion liberada: " + nombre + " (" + motivo + ")");
            difundirPresencia();
            difundir("[Sistema]: " + nombre + " ha sido desconectado (" + motivo + ").");
        }
    }

    // -------------------------------------------------------------------------
    // Handlers WebSocket
    // -------------------------------------------------------------------------

    @OnOpen
    public void alAbrir(Session sesion) {
        Map<String, List<String>> params = sesion.getRequestParameterMap();
        String nombre = "Anonimo";
        if (params.containsKey("nombre") && !params.get("nombre").isEmpty()) {
            String v = params.get("nombre").get(0).trim();
            if (!v.isEmpty()) nombre = v;
        }

        usuariosActivos.put(sesion, nombre);
        ultimaActividad.put(sesion, System.currentTimeMillis());

        System.out.println("[Conexion] " + nombre + " conectado [" + sesion.getId() + "]");
        difundirPresencia();
        difundir("[Sistema]: " + nombre + " se ha unido a la sala.");
    }

    /**
     * Unico @OnMessage: maneja mensajes de chat normales y el texto especial
     * "__pong__" que el cliente envía al pulsar "Estoy aquí".
     */
    @OnMessage
    public void alRecibirMensaje(String mensaje, Session sesion) {
        ultimaActividad.put(sesion, System.currentTimeMillis());
        String nombre = usuariosActivos.getOrDefault(sesion, "Anonimo");

        if (PONG_TEXTO.equals(mensaje)) {
            System.out.println("[Heartbeat] Senal de vida de " + nombre + " [" + sesion.getId() + "]");
        } else {
            System.out.println("[Chat] [" + nombre + "]: " + mensaje);
            difundir("[" + nombre + "]: " + mensaje);
        }
    }

    @OnClose
    public void alCerrar(Session sesion, CloseReason reason) {
        ultimaActividad.remove(sesion);
        String nombre = usuariosActivos.remove(sesion);
        if (nombre != null) {
            String motivo = (reason != null && reason.getReasonPhrase() != null
                    && !reason.getReasonPhrase().isEmpty())
                    ? reason.getReasonPhrase() : "Cierre normal";
            System.out.println("[Desconexion] " + nombre + " [Motivo: " + motivo + "]");
            difundirPresencia();
            difundir("[Sistema]: " + nombre + " ha salido de la sala.");
        }
    }

    @OnError
    public void alOcurrirError(Session sesion, Throwable error) {
        System.err.println("[Error] Sesion "
                + (sesion != null ? sesion.getId() : "?") + ": " + error.getMessage());
        if (sesion != null) {
            ultimaActividad.remove(sesion);
            if (usuariosActivos.remove(sesion) != null) {
                difundirPresencia();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades de difusion
    // -------------------------------------------------------------------------

    private static void difundir(String texto) {
        for (Session s : usuariosActivos.keySet()) {
            if (s.isOpen()) {
                try {
                    synchronized (s) {
                        if (s.isOpen()) s.getBasicRemote().sendText(texto);
                    }
                } catch (IOException e) {
                    System.err.println("[Difusion] Error al enviar a " + s.getId() + ": " + e.getMessage());
                }
            }
        }
    }

    private static void difundirPresencia() {
        List<String> nombres = new ArrayList<>(usuariosActivos.values());
        Collections.sort(nombres);

        StringBuilder sb = new StringBuilder("{\"tipo\":\"presencia\",\"usuarios\":[");
        for (int i = 0; i < nombres.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escaparJson(nombres.get(i))).append('"');
        }
        sb.append("]}");
        difundir(sb.toString());
    }

    private static String escaparJson(String texto) {
        return texto
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
