# Hardening para producción local (sin dominio, sin correo)

Este documento complementa `AUDITORIA_Y_CAMBIOS.md`. Toma el checklist de
"CODES Production Hardening v1" y aplica solo lo que es realista con las
limitaciones actuales: **sin dominio propio y sin proveedor de correo
(SMTP)**, corriendo en una red local/LAN. Todo lo demás (HTTPS real,
PostgreSQL, backups automatizados, etc.) queda documentado como pendiente,
pero no se tocó, para no meter cambios grandes que no se puedan probar acá
(este entorno tampoco tiene Maven, igual que les pasó a ustedes: ver
`AUDITORIA_Y_CAMBIOS.md`).

## Implementado en esta ronda

### Sesiones y contraseñas
- Campo `sessions_valid_from` en `User`: cualquier JWT emitido antes de esa
  marca deja de aceptarse, aunque no haya expirado (`JwtAuthFilter`).
- `POST /api/auth/logout`: cierre de sesión real en el servidor (antes solo
  existía en el frontend, como una variable local que se borraba).
- Cambiar la contraseña (propia, o por `reset-password` con token) invalida
  automáticamente cualquier otra sesión abierta con la contraseña anterior.
- `PATCH /api/users/{id}/close-sessions`: un administrador puede forzar el
  cierre de sesión de otra persona sin desactivarle la cuenta (por ejemplo,
  turno terminado y olvidó cerrar sesión en un equipo compartido).
- Al desactivar un usuario (`disable`), además de bloquear el acceso, se le
  cortan las sesiones activas en el acto.
- **`PATCH /api/users/{id}/reset-password`** (nuevo, pensado específicamente
  para la falta de correo): genera una contraseña temporal aleatoria, la
  aplica, invalida las sesiones de ese usuario, y la devuelve en la
  respuesta para que el administrador se la comunique en persona o por el
  canal interno que usen. Mientras `CODES_SMTP_HOST` esté vacío, esta es la
  única forma real de recuperar acceso — por eso conviene que la sala tenga
  siempre más de un administrador activo.

### Auditoría
- `AuditLogService` (nuevo): la propiedad `app.log-auditoria-path` ya
  existía en `application.properties` pero no estaba conectada a nada. Ahora
  cada login, logout, registro, cambio de contraseña, creación/activación/
  desactivación de usuario, reset de contraseña por admin, y asignar/cerrar/
  crear llamada, queda como una línea JSON en ese archivo: quién, qué,
  sobre qué recurso, desde qué IP, con qué resultado.

### Autochequeo invisible ("¿está vivo el servidor?")
- `SelfTestService` (nuevo): corre solo cada 10 minutos (configurable),
  sin crear ninguna llamada ni dato visible para los operadores. Verifica:
  - Base de datos (lectura de prueba).
  - Disco: escribe y borra un archivo temporal invisible en `data/`.
  - Espacio libre en disco (alerta si baja del mínimo configurado).
  - ASR en vivo (`ws://localhost:6006`), marcado como no crítico.
  - Carpeta de caché de geocodificación, también no crítico.
- Expuesto solo para administradores:
  - `GET /api/admin/status` — estado general + resultado del último
    autochequeo + contadores básicos (usuarios, llamadas, uptime).
  - `POST /api/admin/selftest` — lo dispara manualmente, sin esperar el
    próximo ciclo automático.
- Deliberadamente separado de `GET /api/health` (que sigue siendo público y
  mínimo): no queremos que cualquiera sin sesión vea espacio en disco o
  estado del ASR.

### Limpieza programada
- `MaintenanceService` (nuevo): todos los días a las 04:00 borra tokens de
  recuperación de contraseña vencidos. Antes solo se limpiaban "de paso"
  cuando alguien pedía un reset nuevo; si nadie lo pedía, se acumulaban sin
  límite.
- **A propósito NO se borran audios ni llamadas automáticamente.** Eso es
  evidencia operativa y una política de retención real depende de una
  decisión legal/operacional que no corresponde inventar en el código (ver
  el punto 6 del checklist original).

### Errores nunca crudos al frontend
- `GlobalExceptionHandler` ahora tiene un manejador de último recurso para
  cualquier excepción no controlada (`NullPointerException`, fallo de un
  servicio externo, etc.): nunca llega un stacktrace al navegador. Se
  registra completo en el log del servidor con un ID de correlación corto
  de 8 caracteres, y a quien operó se le muestra un mensaje genérico junto
  con ese ID, para poder buscarlo si hace falta investigar.

### Documentación en `application.properties`
- Nota sobre por qué servir HTTP plano dentro de la LAN es razonable
  *mientras* no haya dominio ni certificado, y qué hay que asegurar
  mientras tanto (firewall sin port-forwarding hacia el puerto, CORS
  restringido a las IPs reales de la sala).
- Nota sobre qué pasa con `forgot-password` sin SMTP configurado, y que el
  camino de respaldo es el nuevo `PATCH /api/users/{id}/reset-password`.
- Nuevas variables, todas con default sensato (no rompen una instalación
  existente si no se tocan): `app.data-dir`, `app.selftest.*`,
  `app.maintenance.cron`.

## Pendiente (opcional, de bajo riesgo) — un paso más

El botón "Cerrar sesión" del frontend (`cerrarSesion()` en `script.js`) hoy
solo borra la variable local `sesion`; nunca avisa al backend. Para que el
nuevo `POST /api/auth/logout` tenga efecto real end-to-end, se podría
agregar, al principio de `cerrarSesion()`, antes de `sesion = null;`:

```js
apiFetch('/api/auth/logout', { method: 'POST' }).catch(() => {});
```

No lo apliqué en esta ronda: es un archivo de ~3400 líneas que ya funciona
y prefiero no tocar JavaScript sin poder probarlo en un navegador real. Es
un cambio de una línea, de bajo riesgo, cuando quieran aplicarlo ustedes o
pedírmelo en otra sesión donde puedan probarlo.

## Explícitamente fuera de esta ronda (del checklist original)

Se dejó tal cual porque requiere infraestructura que no existe todavía
(dominio, certificado, otro motor de base de datos) o es una decisión que
no corresponde tomar en el código:

- HTTPS/WSS reales (necesitan dominio o al menos un certificado; con la
  nota agregada en `application.properties` alcanza mientras sea solo LAN).
- Migración de H2 a PostgreSQL + Flyway.
- Backups automatizados y su prueba de restauración.
- Política de retención de audios (cuánto se guardan, quién puede
  escucharlos) — la auditoría ya deja registro de quién crea/asigna/cierra
  llamadas, pero falta decidir la política antes de automatizar nada que
  borre audios.
- Autenticación/origen permitido en el WebSocket del ASR.
- Modo contingencia visible en la interfaz cuando ASR o geocodificación
  fallan (el backend ya expone ese estado en `/api/admin/status`; falta el
  indicador visual en `script.js`).
- Tests de seguridad e integración adicionales, CI/CD, Docker.

## Antes de dar por buena esta versión

Igual que en `AUDITORIA_Y_CAMBIOS.md`: este entorno no tiene Maven, así que
no se pudo ejecutar `mvn clean test`. Antes de confiar en esta versión:

```powershell
mvn clean test
```

Y probar a mano al menos: login, logout (que un segundo login con el mismo
token viejo ya no funcione), cambiar contraseña (que invalide la sesión
anterior), `PATCH /api/users/{id}/reset-password` como admin, y
`GET /api/admin/status` / `POST /api/admin/selftest` como admin.
