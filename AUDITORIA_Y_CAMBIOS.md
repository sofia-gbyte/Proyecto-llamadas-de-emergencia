# Auditoría, cambios y preparación para producción local

Este documento reúne la revisión de seguridad, auditoría y los cambios de preparación para una instalación local de CODES. Está escrito tomando como referencia el código y la configuración que contiene este proyecto, para que lo que aparece acá coincida con lo que realmente está implementado.

## 1. Cambios implementados

### Aislamiento por institución

- Las llamadas quedan asociadas a la institución del usuario que las crea.
- Los usuarios que no son administradores consultan pendientes, llamadas en curso, cerradas y métricas únicamente de su institución.
- Un administrador mantiene supervisión global.
- La asignación y el cierre comprueban que la llamada pertenezca a la misma institución, salvo para administradores.
- Un `operator` puede cerrar una llamada solo si la tiene asignada.
- Un `supervisor` puede cerrar llamadas de su institución.
- Un `administrator` puede operar sobre todas las instituciones.
- Los roles usados por Spring Security son `operator`, `supervisor` y `administrator`.

También existe un backfill para llamadas antiguas: `CallInstitutionBackfillRunner` puede completar la institución cuando la llamada no la tiene y su usuario creador permite determinarla.

### Contraseñas y sesiones

- Las contraseñas se almacenan con BCrypt.
- El endpoint `POST /api/auth/change-password` permite cambiar la contraseña del usuario autenticado.
- Cambiar una contraseña invalida los JWT emitidos antes del cambio mediante `sessionsValidFrom`.
- `POST /api/auth/logout` existe en backend y también invalida los JWT anteriores del usuario.
- Un administrador puede forzar el cierre de sesiones de otro usuario mediante `PATCH /api/users/{id}/close-sessions`.
- Al desactivar un usuario también se invalidan sus sesiones activas.
- `PATCH /api/users/{id}/reset-password` permite a un administrador generar una contraseña temporal aleatoria. La respuesta contiene esa contraseña para comunicarla por un canal seguro.
- El bootstrap del administrador solo crea la cuenta si todavía no existe. No vuelve a sobrescribir la contraseña en cada arranque.
- `tools/iniciar_codes_windows.ps1` genera una contraseña inicial aleatoria y la guarda junto con las demás claves locales; no existe una contraseña fija `Admin123!` en el proyecto.

**Importante sobre el logout:** el endpoint de backend está implementado, pero la función `cerrarSesion()` del `script.js` actualmente elimina la sesión del navegador sin llamar a `/api/auth/logout`. Por lo tanto, el cierre desde el botón de la interfaz todavía no equivale a una invalidación inmediata del JWT en el servidor. Si se necesita ese comportamiento también desde la interfaz, hay que conectar el botón con el endpoint y probar el flujo en navegador.

### Auditoría

`AuditLogService` registra eventos de seguridad y operación en:

```text
logs/auditoria.log
```

Cada evento se guarda como una línea JSON con:

- fecha y hora;
- usuario que realizó la acción;
- acción;
- tipo de recurso;
- identificador del recurso cuando corresponde;
- IP de origen;
- resultado.

Actualmente se registran, entre otras, estas acciones:

- login correcto y fallido;
- logout cuando se invoca el endpoint;
- registro de usuario;
- cambio de contraseña;
- solicitud y ejecución de recuperación de contraseña;
- creación de usuarios;
- activación y desactivación de usuarios;
- reset de contraseña realizado por un administrador;
- cierre forzado de sesiones;
- creación de llamadas;
- asignación de llamadas;
- cierre de llamadas.

Un error al escribir el archivo de auditoría no interrumpe la operación principal: se registra el problema en el log normal de la aplicación y la operación continúa.

El archivo de auditoría sigue siendo un archivo local. No tiene todavía rotación, almacenamiento externo, protección contra borrado por un administrador del sistema ni monitoreo independiente.

### Validaciones de entrada y archivos

- Las coordenadas de la ubicación del operador se validan antes de procesarlas.
- Las llamadas con audio están limitadas a 15 MB por archivo y 16 MB por petición.
- El audio recibido se valida mediante tipo MIME y extensión permitidos.
- Los audios almacenados se cifran con AES-256-GCM mediante `CODES_ENCRYPT_KEY`.
- La clave de cifrado no está definida por defecto en el repositorio y debe proporcionarse mediante variable de entorno.

### Manejo de errores

`GlobalExceptionHandler` tiene un manejador de último recurso para excepciones no controladas.

El backend:

- registra el error completo en el log del servidor;
- genera un identificador corto de correlación;
- evita enviar el stacktrace al navegador;
- devuelve un mensaje genérico junto con ese identificador.

Además, `application.properties` mantiene desactivada la inclusión de mensajes y stacktraces internos en las respuestas de error de Spring.

### Autochequeo interno

`SelfTestService` ejecuta comprobaciones en segundo plano mediante `@Scheduled`.

Por defecto:

- comienza 30 segundos después del arranque;
- vuelve a ejecutarse cada 10 minutos;
- el intervalo y el mínimo de espacio libre son configurables.

Comprueba:

1. acceso a la base de datos;
2. escritura y borrado de un archivo temporal en `data/`;
3. espacio libre en disco;
4. disponibilidad del ASR local en `ws://localhost:6006`;
5. acceso a la carpeta de caché de geocodificación.

La base de datos y la escritura en disco se consideran comprobaciones críticas. ASR y geocache pueden dejar el estado como `DEGRADADO` sin impedir que CODES siga funcionando.

El resultado se puede consultar solo como administrador mediante:

```text
GET /api/admin/status
POST /api/admin/selftest
```

El endpoint público `/api/health` sigue siendo deliberadamente mínimo.

### Limpieza programada

`MaintenanceService` elimina diariamente los tokens de recuperación de contraseña vencidos.

La tarea se ejecuta por defecto a las 04:00 y se puede cambiar con:

```text
app.maintenance.cron
```

No se borran automáticamente llamadas ni audios. La retención de esos datos debe definirse primero según las necesidades operativas y legales del sistema.

## 2. Producción local / LAN

La configuración actual está pensada para una instalación local o en una red interna controlada, no para exponer CODES directamente a Internet.

Por defecto:

```text
http://127.0.0.1:8000
```

El servidor puede configurarse con:

```text
CODES_SERVER_ADDRESS
CODES_SERVER_PORT
```

Si se habilita el acceso desde otros equipos de la LAN, se debe restringir el firewall al tráfico necesario y no hacer port-forwarding del puerto de CODES hacia Internet.

### HTTP y HTTPS

Actualmente CODES no implementa HTTPS directamente.

Para una LAN pequeña y controlada, el proyecto puede ejecutarse por HTTP si la red está realmente aislada y los equipos que acceden son de confianza. Esto no debe interpretarse como que HTTP sea seguro frente a una red comprometida.

Si CODES va a salir de una LAN controlada, o si la red no puede considerarse confiable, debe colocarse un reverse proxy con HTTPS delante de la aplicación. Para una red interna también se puede utilizar una PKI/certificado interno si la infraestructura lo permite.

En el mismo sentido, el ASR utiliza actualmente:

```text
ws://localhost:6006
```

No está preparado para exponerse directamente a otros equipos.

### CORS

La configuración no usa `*`.

Por defecto solo permite:

```text
http://localhost:8000
http://127.0.0.1:8000
```

Para una instalación en LAN hay que cambiar `app.cors-allowed-origins` para incluir únicamente los orígenes reales que usarán los equipos de la sala.

### Secretos

Las claves y credenciales sensibles deben mantenerse fuera del repositorio.

Variables principales:

```text
CODES_JWT_SECRET
CODES_ENCRYPT_KEY
CODES_ADMIN_USER
CODES_ADMIN_PASSWORD
CODES_TURNSTILE_SITE_KEY
CODES_TURNSTILE_SECRET_KEY
```

Si se utiliza el arranque de Windows, las claves locales se guardan en:

```text
data\.codes-secrets.ps1
```

Ese archivo no debe subirse al repositorio ni compartirse.

La clave de JWT y la clave AES de los audios son independientes y no deben reutilizarse.

### Cloudflare Turnstile

El login y el registro utilizan Turnstile.

Si no se configuran claves propias, el script de Windows puede utilizar las claves oficiales de prueba de Cloudflare para desarrollo/demo. No deben utilizarse como configuración de producción.

Para una instalación real se deben definir:

```text
CODES_TURNSTILE_SITE_KEY
CODES_TURNSTILE_SECRET_KEY
```

La clave secreta debe permanecer únicamente en el servidor.

### Recuperación de contraseña

El proyecto soporta recuperación mediante correo cuando SMTP está configurado.

Variables principales:

```text
CODES_SMTP_HOST
CODES_SMTP_PORT
CODES_SMTP_USERNAME
CODES_SMTP_PASSWORD
CODES_MAIL_FROM
CODES_PUBLIC_BASE_URL
```

Si SMTP no está configurado, `forgot-password` no puede enviar el correo. En ese escenario existe el reset administrativo:

```text
PATCH /api/users/{id}/reset-password
```

El administrador recibe una contraseña temporal y debe comunicarla por un canal seguro. El usuario debería cambiarla después de iniciar sesión.

### Base de datos

La instalación actual utiliza H2 en archivo:

```text
data/llamadas.mv.db
```

Esto es adecuado para una instalación local pequeña y evita depender de un servidor de base de datos externo.

No se debe considerar H2 como la configuración definitiva para una infraestructura de producción con alta concurrencia, alta disponibilidad o necesidades de operación más exigentes.

### Datos y audios

El proyecto puede utilizar:

```text
data/
├── llamadas.mv.db
├── audios_crudos/
├── audios_encriptados/
├── geocache.json
├── calles_chile.txt
└── .codes-secrets.ps1
```

Los audios almacenados se cifran con AES-256-GCM.

La pérdida de `CODES_ENCRYPT_KEY` puede impedir recuperar los audios cifrados, por lo que esa clave debe respaldarse mediante un mecanismo seguro y separado de los datos.

## 3. Lo que sigue pendiente

Estas tareas no están implementadas en el proyecto actual y no se presentan como si ya estuvieran resueltas:

- conectar el botón de logout del frontend con `POST /api/auth/logout`;
- HTTPS/WSS para escenarios donde la LAN no sea suficiente;
- una política formal de retención y eliminación de llamadas y audios;
- backups automáticos y, sobre todo, pruebas reales de restauración;
- migración de H2 a PostgreSQL u otro motor apropiado para una instalación de mayor escala;
- migraciones de esquema con una herramienta como Flyway;
- autenticación/origen permitido específico para el WebSocket del ASR;
- protección, rotación y eventual almacenamiento centralizado de los logs de auditoría;
- indicador visible para operadores cuando ASR o geocodificación estén degradados;
- pruebas de integración y seguridad adicionales;
- CI/CD y una estrategia de despliegue reproducible;
- monitoreo externo independiente del servidor.

No se agregaron estas piezas solo para "marcar el checklist": varias requieren infraestructura o decisiones operativas que no están definidas en el proyecto.

## 4. Verificación realizada sobre este proyecto

Durante la revisión se comprobó el contenido del código y la configuración incluidos en este proyecto, especialmente:

- `AuditLogService`;
- `JwtAuthFilter`;
- `AuthController`;
- `UserController`;
- `CallController`;
- `CallService`;
- `SelfTestService`;
- `MaintenanceService`;
- `SecurityService`;
- `AdminSeederRunner`;
- `SecurityConfig`;
- `application.properties`;
- `CodesApplication`;
- `CallRepository`;
- pruebas de `CallService`;
- scripts de arranque y limpieza.

También se verificó que:

- `CallRepository.findByAssignmentDateIsNotNull()` está presente;
- `getInProgress()` trabaja con una lista mutable antes de ordenar;
- los tests de `CallService` incluyen las autenticaciones necesarias;
- el frontend pasa la comprobación sintáctica con `node --check`;
- no queda la contraseña fija `Admin123!` en el proyecto;
- los nombres de rol funcionales del backend/frontend utilizan `operator`, `supervisor` y `administrator`.

### Pruebas que todavía faltan ejecutar

En el entorno de revisión no está disponible Maven, por lo que no se debe afirmar que la suite Java haya pasado.

Antes de considerar esta versión validada para uso operativo, ejecutar desde la raíz del proyecto:

```powershell
mvn clean test
```

Y hacer una prueba manual de:

1. registro y activación de usuario;
2. login;
3. creación de llamada en vivo;
4. aislamiento entre instituciones;
5. asignación y cierre según rol;
6. cambio de contraseña e invalidación de JWT anteriores;
7. reset de contraseña por administrador;
8. `GET /api/admin/status`;
9. `POST /api/admin/selftest`;
10. logout desde el endpoint y, una vez conectado el frontend, desde el botón de la interfaz.

## 5. Criterio para esta documentación

La idea de este documento es separar claramente tres cosas:

- **implementado:** existe en el código y se puede comprobar;
- **limitación actual:** existe parcialmente o depende de una condición que todavía no está cubierta;
- **pendiente:** requiere código, infraestructura o una decisión que todavía no forma parte del proyecto.

Así se evita presentar como "producción lista" algo que todavía depende de pruebas o infraestructura externa.
