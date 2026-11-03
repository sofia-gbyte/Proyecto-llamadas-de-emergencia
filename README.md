# CODES 1.1.0 — Sistema de llamadas de emergencia

CODES es una aplicación web para apoyar la recepción, registro y gestión de llamadas de emergencia. El sistema permite trabajar con llamadas en vivo, transcripción con Groq Whisper, clasificación inicial, geocodificación con Geoapify y seguimiento de los casos desde una interfaz web.

**Versión del proyecto: `1.1.0`.** Consulta el [historial de cambios](CHANGELOG.md) para ver las novedades de esta versión y las notas de compatibilidad.

La aplicación se limita a uso local en el mismo equipo. No admite acceso desde una LAN ni exposición a Internet.

> Leaflet se incluye dentro de la aplicación. Por privacidad, las integraciones externas (mapas, rutas, geocodificación, transcripción y correo) están desactivadas por defecto; no se envían datos a terceros a menos que un administrador habilite explícitamente los proveedores. Ver [operación local](OPERACION_LOCAL.md) y el [inventario de licencias y servicios](THIRD_PARTY_LICENSES.md).

## Contenido

- [Inicio rápido](#inicio-rápido)
- [Arquitectura y decisiones](#arquitectura-y-decisiones)
- [API REST y contratos](#api-rest-y-contratos)
- [Mapa y servicios cartográficos](#mapa-y-servicios-cartográficos)
- [Llamadas en vivo y transcripción](#llamadas-en-vivo-y-transcripción)
- [Direcciones y datos OSM](#direcciones-y-geocodificación)
- [Red y acceso desde otros equipos](#red-y-acceso-desde-otros-equipos)
- [Desarrollo y pruebas](#desarrollo-y-pruebas)
- [Producción](#antes-de-usarlo-en-producción)
- [Licencias y asistencia de IA](THIRD_PARTY_LICENSES.md)
- [Historial de cambios](CHANGELOG.md)

---

> **Alcance de la versión 1.1.0:** versión para pruebas locales de baja capacidad, con datos sintéticos y servicios externos aprobados por el operador. No implica que CODES esté preparado para su exposición a Internet ni para despachos reales sin las revisiones operativas, legales, de privacidad y disponibilidad descritas en [operación local](OPERACION_LOCAL.md).

## Funcionalidades principales

- Gestión de llamadas pendientes, en curso y cerradas.
- Registro trazable de información complementaria en casos abiertos, sin crear llamadas duplicadas.
- CRUD de usuarios para administración y gestión de llamadas con aislamiento institucional.
- Archivo lógico de llamadas pendientes no asignadas para preservar la trazabilidad; los casos en curso o cerrados no se eliminan.
- Registro manual y recepción de llamadas en vivo.
- Transcripción de audio mediante **Groq Whisper**.
- Extracción y corrección de direcciones a partir de la transcripción.
- Geocodificación de direcciones.
- Clasificación inicial de llamadas mediante lógica heurística.
- Mapa basado en **Leaflet** (incluido dentro del JAR), geocodificación con Geoapify y rutas con OSRM.
- Usuarios con roles `operator`, `supervisor` y `administrator`.
- Activación administrativa de cuentas registradas.
- Aislamiento de llamadas y métricas por institución para usuarios no administradores.
- Cambio y recuperación de contraseña.
- Invalidación de sesiones JWT al cerrar sesión, cambiar contraseña, desactivar una cuenta o forzar el cierre de sesiones.
- Limitación de intentos de autenticación y solicitudes de recuperación/registro.
- Registro de eventos de auditoría en `logs/auditoria.log` (app-image: `<directorio de datos>/logs/auditoria.log`).
- Autochequeo interno de base de datos, disco, claves de proveedores externos y caché de geocodificación.
- Cifrado AES-256-GCM para los audios almacenados.
- Base de datos H2 en archivo, sin necesidad de instalar un servidor de base de datos adicional.

---

## Tecnologías

- **Java 21 o superior**
- **Spring Boot 4.0.9**
- Spring Web
- Spring Security
- Spring Data JPA / Hibernate
- H2 Database
- JSON Web Token (JJWT)
- Leaflet
- ALTCHA autoalojado para proteger el inicio de sesión y el registro
- Groq Whisper para transcripción
- Geoapify para geocodificación

## Arquitectura y decisiones

CODES se implementa como un **monolito modular**: una aplicación Spring Boot sirve la interfaz web y la API REST, y organiza el código en controladores, servicios, repositorios y entidades. El controlador recibe y valida la petición; el servicio aplica reglas de institución y estado del caso; el repositorio usa Spring Data JPA e Hibernate; y H2 persiste los datos en `data/llamadas.mv.db`.

Para este proyecto se eligió **Java 21 + Spring Boot** frente a **Python + FastAPI**. Se priorizaron tres criterios propios del caso: (1) seguridad y roles integrados para separar operadores, supervisores y administradores; (2) persistencia relacional y validación de contratos con poco código de infraestructura; y (3) facilidad para probar y ejecutar toda la solución como una unidad. Spring Boot resuelve el enrutamiento HTTP, inyección de dependencias, validación, seguridad y acceso JPA. El costo aceptado es una aplicación más pesada y con más configuración que una API pequeña en FastAPI.

Se mantiene el monolito porque los flujos de registro, clasificación y despacho comparten datos y reglas, y el equipo puede desarrollar y desplegar una sola unidad sin operar varios servicios, redes y contratos distribuidos. Esto simplifica la instalación y las operaciones transaccionales. El costo aceptado es que las partes no escalan ni se despliegan independientemente; si el volumen o la disponibilidad lo exigieran, primero habría que migrar la persistencia a un servidor como PostgreSQL y definir límites de servicio.

## API REST y contratos

La API está orientada a los recursos `users` y `llamadas`. Las operaciones administrativas de usuarios requieren el rol `administrator`; el detalle de una llamada respeta el aislamiento institucional. La corrección del resumen y la dirección se reserva a supervisores y administradores; la información complementaria puede agregarla el operador asignado o un supervisor autorizado, siempre en un caso abierto.

| Recurso | Operación | Ruta | Acceso / resultado |
|---|---|---|---|
| Usuarios | Crear | `POST /api/users` | Administrador; `201 Created` y cabecera `Location` |
| Usuarios | Listar / consultar | `GET /api/users`, `GET /api/users/{id}` | Administrador; `200 OK` |
| Usuarios | Actualizar perfil y rol | `PUT /api/users/{id}` | Administrador; `200 OK` |
| Usuarios | Eliminar | `DELETE /api/users/{id}` | Administrador; `204 No Content`; no permite borrar la propia cuenta ni al último administrador activo |
| Llamadas | Crear en vivo | `POST /api/llamadas/live` | Operador, supervisor o administrador; `201 Created` y `Location` |
| Llamadas | Listar / consultar | `GET /api/llamadas/pending`, `/in-progress`, `/closed`, `GET /api/llamadas/{id}` | Autenticado; `200 OK`, filtrado por institución |
| Llamadas | Corregir resumen y dirección | `PUT /api/llamadas/{id}` | Supervisor o administrador; `200 OK`; solo casos abiertos |
| Llamadas | Agregar información al caso existente | `POST /api/llamadas/{id}/updates` | Operador asignado, supervisor o administrador; caso abierto y autorización institucional |
| Llamadas | Retirar de operación | `DELETE /api/llamadas/{id}` | Administrador; `204 No Content`; archiva lógicamente solo llamadas pendientes sin asignar |

Las peticiones JSON usan `Content-Type: application/json` y las operaciones protegidas requieren `Authorization: Bearer <JWT>`. La creación de llamadas en vivo usa `multipart/form-data`: la parte `datos` contiene la transcripción y coordenadas del operador, y `audio` es opcional.

Ejemplos de contratos:

```json
POST /api/users
{
  "username": "operador01",
  "password": "UnaClaveSegura!2026",
  "rol": "operator",
  "institution": "bomberos"
}
```

```json
PUT /api/users/12
{
  "rol": "supervisor",
  "institution": "bomberos",
  "firstName": "Ana",
  "lastName": "Pérez",
  "email": "ana@example.org"
}
```

```json
PUT /api/llamadas/34
{
  "operationalSummary": "Humo visible en vivienda de un piso.",
  "address": "Av. Central 123"
}
```

```json
POST /api/llamadas/34/updates
{
  "updateId": "ec6de66d-c7d4-4d38-97ad-1afbf15ecf00",
  "information": "Hay personas atrapadas en el segundo piso."
}
```

`updateId` debe ser un UUID generado por el cliente y debe reutilizarse al reintentar la misma petición. El servidor también evita guardar dos veces el mismo texto normalizado. El historial queda asociado al mismo caso, conserva quién y cuándo lo agregó, actualiza la clasificación y las recomendaciones, y no activa despachos ni crea una llamada nueva. Se limita cada aporte a 5.000 caracteres y el historial acumulado a 20.000 caracteres. La creación de llamadas devuelve la representación `CallResponse` después de transcribir, clasificar y geocodificar. Los valores de prioridad y puntuaciones son resultados de clasificación; el `PUT` de llamada solo corrige el resumen operativo y la dirección para evitar alterar manualmente esa clasificación.

Los errores siguen un contrato JSON: `400` para validación o datos inválidos, `401` para falta de autenticación, `403` para rol no autorizado, `404` para recurso inexistente o no visible por institución, `409` para conflictos de estado o unicidad, `413` para cargas demasiado grandes y `500` para errores inesperados (con una referencia de correlación). Las llamadas retiradas se conservan en H2 para no perder la trazabilidad; no aparecen en colas ni métricas operativas.

---

## Requisitos

Para ejecutar CODES desde el código fuente:

- JDK **21 o superior**
- Maven
- Windows si se quieren utilizar los scripts de mantenimiento `.ps1` incluidos.
- Acceso a Internet para Groq Whisper, Geoapify y los servicios cartográficos.

Las llamadas en vivo se envían a Groq al detener y guardar la grabación. El límite de transcripción de Groq es **25 MB** por archivo.

---

# Inicio rápido

## Windows

Para usar el app-image validado de esta entrega, inicia:

```text
dist\CODES\CODES.exe
```

El menú del EXE inicia/detiene CODES, diagnostica la configuración local, crea respaldos y limpia archivos regenerables. Conserva toda la carpeta `CODES` y su subdirectorio `runtime`; el EXE no es autónomo. El app-image incluye Java y es el único punto de entrada para usuarios. Para construir otro paquete desde el código fuente, consulta [App-image para Windows](#app-image-para-windows).

Por defecto, la aplicación queda disponible en:

```text
http://127.0.0.1:8000
```

### Inicio manual

Para ejecutar únicamente Spring Boot:

```powershell
mvn spring-boot:run
```

Para compilar:

```powershell
mvn clean package
```

Para ejecutar las pruebas:

```powershell
mvn test
```

---

# Primer arranque y secretos

CODES necesita secretos para firmar los JWT y cifrar los audios.

Por privacidad, las integraciones con terceros están **desactivadas por defecto**. En este modo no se envían audios, direcciones, coordenadas ni correos a proveedores externos. Para configurarlas en Windows, abre `CODES.exe`, elige **2. Configurar integraciones y API**, agrega las claves/endpoints y habilita los proveedores solo después de aprobar esos flujos. Groq recibe audio, Geoapify direcciones, los proveedores de mapa/rutas coordenadas y SMTP envía correos de recuperación. Esri (mapa satelital) y OSRM (rutas) ya tienen endpoints predeterminados y no requieren una API key propia. Al guardar, cierra y vuelve a abrir el EXE para aplicar los cambios. También se admite la configuración por variables de entorno.

Las variables principales son:

```powershell
$env:CODES_JWT_SECRET="secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="clave-base64-de-32-bytes"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="<tu-clave-segura>"
```

La clave JWT y la clave de cifrado de audios son independientes. **No deben reutilizarse entre sí.**
Para H2 local, el nombre de usuario predeterminado es `sa` y la contraseña queda vacía; se pueden externalizar con `CODES_DB_USERNAME` y `CODES_DB_PASSWORD`.

## Inicio de CODES en Windows

El punto de entrada para usuarios es `dist\CODES\CODES.exe`, incluido en el app-image. Su menú usa el logo de CODES y agrupa las acciones por propósito: iniciar el sistema, configurar servicios, revisar diagnóstico y administrar datos/respaldos. No copies ni distribuyas solo el EXE: conserva la carpeta completa con `runtime`. El menú no incluye una acción que pueda borrar todos los casos y audios. Se retiró el lanzador por lotes; los scripts `.ps1` de compilación y mantenimiento no forman parte del paquete entregable.

El app-image puede crear o reutilizar estos archivos locales:

```text
%LOCALAPPDATA%\CODES\data\.codes-secrets.ps1
%LOCALAPPDATA%\CODES\data\.codes-secrets.properties
```

La base H2, audios, caché, auditoría y secretos se guardan siempre bajo `%LOCALAPPDATA%\CODES\data`, fuera de la instalación y compartidos por todas las versiones del app-image. Si el directorio ya existe, CODES lo reutiliza y conserva sus archivos; solo lo crea en el primer arranque si no está presente. Sin `LOCALAPPDATA`, usa `%USERPROFILE%\.codes\data`. `CODES_DATA_DIR` puede seleccionar explícitamente otra ruta persistente. CODES comprueba que la carpeta pueda escribirse y que esté fuera de la instalación. Hibernate actualiza el esquema al iniciar; respalda los datos antes de actualizar y no abras una base actualizada con una versión anterior sin restaurar el respaldo. El EXE conserva los archivos al actualizar y restringe los permisos al usuario actual cuando el sistema de archivos lo permite. El EXE lee asignaciones simples entre comillas del archivo `.ps1` sin ejecutarlo; la opción **Configurar integraciones y API** guarda ajustes y claves en `.codes-secrets.properties` de forma atómica, con permisos restringidos al usuario actual cuando Windows lo permite. Si un mismo ajuste existe en ambos archivos, `.properties` tiene prioridad. Las claves de API nunca se muestran en la interfaz; deja el campo vacío para conservar la clave guardada.

- No debe subirse al repositorio.
- No debe compartirse.
- Ambos archivos contienen secretos en texto plano: restringe su acceso y protege los respaldos con cifrado.
- La pérdida de `CODES_ENCRYPT_KEY` puede impedir recuperar los audios cifrados.

En una instalación nueva, CODES genera una contraseña inicial aleatoria para el administrador y la persiste en los archivos locales de secretos. Consúltala de forma local y protegida; no compartas esos archivos. Después de entrar al sistema conviene cambiarla.

El bootstrap del administrador se ejecuta únicamente cuando todavía no existe ningún usuario. CODES no reemplaza la contraseña del administrador en cada arranque.

## Configuración de APIs y mapa

En Windows, abre `CODES.exe` y elige **2. Configurar integraciones y API**. La ventana separa mapa/geocodificación, transcripción y correo. Introduce las claves en los campos enmascarados: `GEOAPIFY_API_KEY` para geocodificación, `GROQ_API_KEY` para transcripción y credenciales SMTP para recuperación de contraseña. El mapa satelital Esri y las rutas OSRM tienen endpoints predeterminados; puedes sustituirlos por URLs HTTPS de servicios aprobados. Las URLs de teselas deben incluir `{z}`, `{x}` y `{y}`. No se requiere una API key de Esri para el endpoint predeterminado.

La casilla de activación es global: habilitarla permite que cualquier servicio externo configurado envíe los datos correspondientes. Guarda y cierra CODES; vuelve a abrirlo para cargar los ajustes. El diagnóstico local muestra si las claves existen sin revelar su contenido ni contactar proveedores. Nunca pongas claves en `application.properties`, JavaScript o en el navegador, ni las publiques.

Las claves se guardan en `%LOCALAPPDATA%\CODES\data\.codes-secrets.properties` (o en el directorio indicado por `CODES_DATA_DIR`) con escritura atómica y ACL restringida cuando Windows lo permite. Permanecen en texto plano en disco; protege la cuenta de Windows y los respaldos. Variables de entorno configuradas para el proceso prevalecen sobre los valores guardados desde la interfaz.

## App-image para Windows

Para construir o actualizar el app-image desde el código fuente se necesita Windows, JDK 21 o superior (incluyendo `jpackage`) y Maven; esos son requisitos de compilación, no de ejecución del usuario. Ejecuta desde PowerShell conforme a la política de ejecución aprobada para el equipo:

```powershell
.\tools\package_codes_windows.ps1 -ReplaceExisting
```

Si PowerShell bloquea el script, no uses `ExecutionPolicy Bypass` ni reduzcas la política de seguridad; solicita el procedimiento autorizado al administrador del equipo. El ejecutable de una compilación nueva queda en `dist\CODES\CODES.exe`, y el runtime Java en `dist\CODES\runtime`. Distribuye la carpeta completa, no solo el `.exe`; el usuario final no necesita scripts de compilación o mantenimiento. Al iniciar el sistema desde el menú, el EXE abre `http://127.0.0.1:8000` cuando el servidor está listo. La base H2, los audios, la caché y los secretos se guardan fuera del paquete en `%LOCALAPPDATA%\CODES\data` (o en `CODES_DATA_DIR` si se define). No incluyas archivos de datos o secretos al distribuir el app-image.

El script genera `package-manifest.json` con la lista de archivos, tamaños y SHA-256, además de `SHA256SUMS.txt`. Una segunda compilación puede usar otro destino (`-DestinationDirectory dist\phase2`) o reemplazar un app-image previo creado por el mismo script con `-ReplaceExisting`. El paquete se inspecciona para rechazar scripts y datos locales. Para usar CODES, el usuario final solo abre `CODES.exe`; no necesita ejecutar scripts, instalar Maven/Java ni configurar Java. La app-image es una carpeta autocontenida para distribución, no un EXE independiente: conserva su carpeta completa y el subdirectorio `runtime`.

El app-image no es un instalador firmado ni convierte CODES en un sistema listo para exposición a Internet o despacho real. La operación sigue sujeta a las advertencias de seguridad, privacidad y disponibilidad de este README.

---

# Verificación anti-bots (ALTCHA)

Login y registro usan ALTCHA v2 autoalojado con Argon2id (1 iteración, 8 MiB y paralelismo 1); el widget, worker y traducción española se sirven desde archivos locales de CODES. Cada desafío está firmado, vence en cinco minutos y queda ligado a su propósito (login o registro). No se envían datos a un proveedor de CAPTCHA. El app-image genera y conserva `CODES_ALTCHA_SECRET` en los archivos locales de secretos bajo `%LOCALAPPDATA%\CODES\data`; ALTCHA está activo por defecto y puede desactivarse explícitamente con `CODES_ALTCHA_ENABLED=false`.

El uso de cada desafío se registra en H2 con una restricción única, por lo que el rechazo de repeticiones sobrevive reinicios y es atómico. La verificación Argon2id limita a dos sus cálculos simultáneos para acotar memoria del servidor. Argon2id encarece el cálculo automatizado y dificulta la aceleración con GPU, pero CAPTCHA no demuestra que un cliente sea humano ni elimina bots sofisticados. Mantén controles anti-abuso adicionales. El uso local se realiza en `localhost`.

---

# Red local y acceso

El ejecutable está limitado deliberadamente a conexiones desde el mismo equipo:

```text
127.0.0.1:8000
```

La aplicación rechaza una dirección `server.address` que no sea loopback y fuerza la escucha en `127.0.0.1`. No configures `CODES_SERVER_ADDRESS` para una IP de LAN, no abras puertos del firewall y no publiques el servicio mediante un proxy. La configuración de red multi-equipo no forma parte de este ejecutable.

Para una prueba local sin servicios de terceros, inicia desde el EXE, utiliza la interfaz en `http://127.0.0.1:8000` y crea una llamada con transcripción manual; omite geocodificación, transcripción remota, mapa/rutas y correo. No uses datos reales sensibles. Las fallas de inicio muestran pasos de recuperación sin mostrar secretos; si aparece una referencia local, revisa los registros bajo el directorio de datos y comparte únicamente los tipos de error, nunca su contenido sensible.

---

# Autenticación y usuarios

La API utiliza autenticación mediante JWT.

Las contraseñas se almacenan mediante BCrypt y las cuentas registradas como operadores quedan inicialmente inactivas. Un administrador debe activarlas antes de que puedan iniciar sesión.

Roles disponibles:

| Rol | Alcance general |
|---|---|
| `operator` | Operación de llamadas de su institución y llamadas que tenga asignadas |
| `supervisor` | Supervisión y operación ampliada dentro de su institución |
| `administrator` | Administración de usuarios y supervisión global |

El alcance real de cada operación se valida también en el backend; no depende solamente de ocultar botones en la interfaz.

Las llamadas se asocian a la institución del usuario que las crea. Los usuarios que no son administradores reciben las listas y métricas filtradas por su institución.

---

# Sesiones y contraseñas

Las sesiones utilizan JWT con una duración configurada actualmente en:

```text
480 minutos
```

equivalentes a 8 horas.

El backend permite:

```text
POST /api/auth/logout
POST /api/auth/change-password
PATCH /api/users/{id}/close-sessions
```

Cambiar una contraseña, cerrar sesión desde el endpoint, desactivar una cuenta o forzar el cierre de sesiones invalida los JWT emitidos anteriormente mediante la marca de tiempo de sesiones del usuario.

La recuperación de contraseña utiliza tokens temporales y puede enviar instrucciones por correo cuando SMTP está configurado.

---

# Registro y recuperación de contraseña

Registro:

```text
POST /api/auth/register
```

Una cuenta nueva queda inactiva hasta que un administrador la habilita.

Login:

```text
POST /api/auth/login
```

Recuperación:

```text
POST /api/auth/forgot-password
POST /api/auth/reset-password
```

Cambio de contraseña autenticado:

```text
POST /api/auth/change-password
```

Para habilitar recuperación por correo hay que configurar SMTP:

```powershell
$env:CODES_SMTP_HOST="smtp.example.com"
$env:CODES_SMTP_PORT="587"
$env:CODES_SMTP_USERNAME="usuario"
$env:CODES_SMTP_PASSWORD="password"
$env:CODES_MAIL_FROM="codes@example.com"
$env:CODES_PUBLIC_BASE_URL="https://codes.example.internal"
```

Sin SMTP configurado, la solicitud de recuperación no puede enviar el correo. En ese caso, la administración de cuentas debe realizarse mediante las funciones administrativas disponibles.

---

# Llamadas en vivo y transcripción

El navegador graba el audio localmente; al pulsar **Detener y guardar**, lo envía al backend, que lo transcribe con **Groq Whisper**, clasifica el texto, geocodifica la dirección con Geoapify y almacena el audio cifrado.

Flujo general:

```text
Micrófono del navegador
        ↓
Grabación de audio
        ↓
Backend CODES → Groq Whisper
        ↓
Transcripción
        ↓
Extracción/corrección de dirección
        ↓
Geoapify
        ↓
Registro de la llamada
```

El servicio de transcripción acepta grabaciones de hasta **25 MB** por archivo. El audio se transmite a Groq al guardar y también se conserva cifrado en CODES después de crear correctamente el caso. Si Groq no está configurado o falla, el envío no crea el caso y la interfaz permite reintentar.

La clasificación automática es heurística y debe considerarse una ayuda para el operador, no un mecanismo autónomo de despacho o decisión.

---

# Direcciones y geocodificación

Después de obtener la transcripción, CODES intenta identificar una dirección. No descarga ni utiliza un diccionario local de calles.

La geocodificación se realiza mediante `GeocoderService`. El sistema aplica comprobaciones de similitud antes de aceptar un resultado.

La ubicación GPS del operador no limita la búsqueda: un incidente puede estar lejos del dispositivo. El radio y el centro de búsqueda representan el área operativa configurada en `application.properties`.

Geoapify es el único proveedor de geocodificación. Las búsquedas se limitan al centro y radio operativos configurados y se cachean localmente para evitar repetirlas. La dirección del incidente se envía a Geoapify; configura `GEOAPIFY_API_KEY` como se indica arriba.

## Mapa y servicios cartográficos

El mapa base usa **Esri World Imagery**, un mapa satelital servido desde ArcGIS Online. No requiere cuenta, tarjeta ni clave API. La atribución mostrada se basa en los créditos del servicio: `© Esri — Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community`. Las rutas usan OSRM Demo; la geocodificación usa Geoapify.

Las imágenes requieren conexión a Internet y están sujetas a los [términos de Esri](https://www.esri.com/en-us/legal/terms/full-master-agreement). Al transcribir, el audio se envía a Groq; al geocodificar, la dirección se envía a Geoapify; al pedir una ruta, las coordenadas del operador y del incidente se envían a OSRM. No uses estos servicios con incidentes reales o información sensible sin revisar previamente sus términos, privacidad y tratamiento de datos.

Puedes configurar `CODES_MAP_TILES_URL` y `CODES_ROUTING_URL`. Las rutas deben ser compatibles con OSRM y las teselas con una plantilla URL válida para Leaflet.

---

---

# Datos y almacenamiento

La base de datos por defecto es H2 en archivo:

```text
data/llamadas.mv.db
```

No se necesita instalar un servidor de base de datos adicional para ejecutar el proyecto.

También se utilizan:

```text
data/
├── llamadas.mv.db
├── audios_crudos/
├── audios_encriptados/
├── geocache.json
├── logs/
│   └── auditoria.log
└── .codes-secrets.ps1
```

En el app-image de Windows, estos archivos y los registros de auditoría se guardan bajo `%LOCALAPPDATA%\CODES\data` (o `CODES_DATA_DIR`):

```text
<directorio de datos>/logs/auditoria.log
```

Las rutas y parámetros principales se configuran en:

```text
src/main/resources/application.properties
```

## Cifrado de audios

Los audios almacenados se cifran mediante **AES-256-GCM** utilizando:

```text
CODES_ENCRYPT_KEY
```

La clave no debe incluirse en el repositorio.

El respaldo de esta clave debe gestionarse de forma separada de los datos cifrados. Si se pierde, los audios cifrados pueden quedar irrecuperables.

---

# Auditoría y estado del sistema

Los eventos relevantes de seguridad y operación se registran en:

```text
logs/auditoria.log
```
En el app-image de Windows, están en `<directorio de datos>/logs/auditoria.log`, fuera de la carpeta de instalación.

Entre los eventos registrados se incluyen login, logout mediante el endpoint, registro de usuarios, cambios y recuperación de contraseña, operaciones administrativas y operaciones sobre llamadas.

CODES también ejecuta un autochequeo periódico en segundo plano.

Por defecto:

```text
Primer chequeo: 30 segundos después del arranque
Intervalo:      10 minutos
Espacio mínimo: 1 GB
```

El autochequeo revisa:

- acceso a la base de datos;
- escritura en disco;
- espacio disponible;
- configuración de las claves de Groq Whisper y Geoapify;
- acceso a la caché de geocodificación.

El resultado puede consultarse como administrador:

```text
GET  /api/admin/status
POST /api/admin/selftest
```

La documentación operativa, de seguridad, respaldo y preparación para producción local está en:

```text
OPERACION_LOCAL.md
```

---

### Inventario de rutas

Estas son las rutas principales actualmente implementadas:

```text
GET    /api/health

POST   /api/auth/login
POST   /api/auth/logout
POST   /api/auth/register
POST   /api/auth/forgot-password
POST   /api/auth/reset-password
POST   /api/auth/change-password
GET    /api/auth/captcha-config
GET    /api/auth/captcha-challenge?purpose=login|register

GET    /api/users
POST   /api/users
GET    /api/users/{id}
PUT    /api/users/{id}
DELETE /api/users/{id}
PATCH  /api/users/{id}/disable
PATCH  /api/users/{id}/enable
PATCH  /api/users/{id}/reset-password
PATCH  /api/users/{id}/close-sessions

GET    /api/llamadas/pending
GET    /api/llamadas/in-progress
GET    /api/llamadas/closed
GET    /api/llamadas/{id}
PUT    /api/llamadas/{id}
DELETE /api/llamadas/{id}
POST   /api/llamadas/{id}/assign
POST   /api/llamadas/{id}/close
POST   /api/llamadas/live

GET    /api/metrics

GET    /api/admin/status
POST   /api/admin/selftest
```

Las operaciones protegidas requieren autenticación y, según el caso, un rol autorizado. `DELETE /api/llamadas/{id}` archiva únicamente casos todavía pendientes y sin asignar; los casos cerrados o en gestión se preservan como historial.

---

# Configuración principal

La configuración de la aplicación está en:

```text
src/main/resources/application.properties
```

Variables de entorno principales:

```text
CODES_SERVER_ADDRESS
CODES_SERVER_PORT

CODES_JWT_SECRET
CODES_ENCRYPT_KEY

CODES_ADMIN_USER
CODES_ADMIN_PASSWORD

CODES_ALTCHA_SECRET
CODES_ALTCHA_ENABLED

CODES_MAP_TILES_URL
CODES_ROUTING_URL
GROQ_API_KEY
GROQ_BASE_URL
GROQ_MODEL
GROQ_LANGUAGE
GEOAPIFY_API_KEY
GEOAPIFY_BASE_URL
CODES_GEOCODER_CENTER_LAT
CODES_GEOCODER_CENTER_LNG
CODES_GEOCODER_RADIUS_KM
CODES_GEOCODER_USER_AGENT

CODES_DATA_DIR

CODES_SMTP_HOST
CODES_SMTP_PORT
CODES_SMTP_USERNAME
CODES_SMTP_PASSWORD
CODES_MAIL_FROM
CODES_PUBLIC_BASE_URL
```

`CODES_SERVER_ADDRESS` solo admite direcciones loopback; CODES siempre fuerza `127.0.0.1`. El `CODES_DATA_DIR` del app-image debe apuntar a almacenamiento persistente fuera de la carpeta de instalación. Otros parámetros de operación, como el intervalo del autochequeo y mantenimiento, se encuentran en `application.properties`.

---

# Estructura del proyecto

```text
CODES/
├── .github/
├── data/
│   ├── llamadas.mv.db
│   └── .codes-secrets.ps1
├── src/
│   ├── main/
│   │   ├── java/cl/codes/
│   │   │   ├── classifier/
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── model/
│   │   │   ├── repository/
│   │   │   ├── security/
│   │   │   └── service/
│   │   └── resources/
│   │       ├── static/
│   │       │   ├── index.html
│   │       │   ├── script.js
│   │       │   └── styles.css
│   │       └── application.properties
│   └── test/
├── tools/
├── pom.xml
├── OPERACION_LOCAL.md
├── README.md
└── .gitignore
```

---

# Limpieza del proyecto

El proyecto incluye:

```text
tools/limpiar_proyecto.ps1
```

Sin parámetros, muestra qué elementos podrían limpiarse:

```powershell
.\tools\limpiar_proyecto.ps1
```

Para ejecutar la limpieza:

```powershell
.\tools\limpiar_proyecto.ps1 -Borrar
```

También puede generar un ZIP liviano:

```powershell
.\tools\limpiar_proyecto.ps1 -Borrar -Comprimir
```

Antes de ejecutar una limpieza destructiva conviene revisar qué archivos considera prescindibles el script. Los datos operativos, secretos y audios deben conservarse de acuerdo con la política definida para la instalación.

---

# Desarrollo y pruebas

Para compilar:

```powershell
mvn clean package
```

Para ejecutar:

```powershell
mvn spring-boot:run
```

Para ejecutar las pruebas:

```powershell
mvn test
```

El repositorio contiene pruebas automatizadas, pero una compilación o ejecución exitosa debe comprobarse en el entorno donde se vaya a desplegar. La documentación no considera una prueba como "pasada" únicamente porque el código exista.

---

# Uso local y preparación operativa

El app-image `1.1.0` está preparado para pruebas locales de baja capacidad, no certificado como sistema de producción para datos personales reales ni para despacho de emergencias. El EXE inicia una interfaz local y el servidor se limita a `127.0.0.1`; no habilites acceso LAN/Internet, redirección de puertos ni reglas de firewall para exponerlo. Las integraciones externas están desactivadas por defecto y la interfaz no contacta a proveedores si no se habilitan. Las actualizaciones mantienen una única ubicación de distribución, `dist\CODES`; reemplazar la carpeta del app-image no reemplaza los datos persistidos bajo `%LOCALAPPDATA%\CODES\data`.

Antes de una prueba, configura un usuario Windows dedicado con acceso restringido al equipo, conserva el paquete íntegro y verifica el manifiesto/hash desde un canal confiable. El EXE de esta entrega no está firmado digitalmente; los hashes detectan cambios solo si el manifiesto llega por un canal confiable y no autentican al editor.

**No uses datos personales reales en esta versión sin una aprobación explícita de privacidad y seguridad.** La base H2 y los archivos de secretos se guardan en texto plano; solo los audios se cifran con AES-256-GCM. Las ACL locales no equivalen a cifrado en reposo y no protegen contra administradores del equipo, malware o acceso físico. Si un administrador habilita las integraciones externas, Groq recibe los audios, Geoapify las direcciones, OSRM las coordenadas de ruta y el proveedor de mapas las coordenadas visibles. Usa datos sintéticos hasta completar las revisiones requeridas.

Para un uso operativo con datos sensibles todavía se requiere, como mínimo, definir e implementar cifrado en reposo para H2 y secretos, protección y recuperación de claves, política de retención/eliminación, restauración probada de respaldos, control de acceso del sistema operativo, distribución firmada y revisión de privacidad/legal de cada flujo externo. La app no sustituye un sistema de despacho certificado ni el criterio de un operador.

La entrega se validó con 84 pruebas Maven aprobadas; el app-image incluye su runtime y el manifiesto del paquete fue verificado, sin scripts ni datos locales. La validación no es una certificación de seguridad ni reemplaza pruebas en el equipo de destino.

La configuración actual utiliza H2 con:

```text
spring.jpa.hibernate.ddl-auto=update
```

Esto simplifica una instalación local, pero para una infraestructura de mayor escala conviene definir una estrategia formal de migraciones y evaluar una base de datos como PostgreSQL.

---

# Estado conocido

El proyecto tiene varias medidas de seguridad y operación implementadas, pero todavía existen tareas que dependen de infraestructura o de decisiones de despliegue.

Entre ellas:

- cifrado en reposo de la base H2 y de los secretos, con gestión y recuperación de claves;
- firma del ejecutable y canal autenticado de distribución;
- política formal de retención y eliminación;
- respaldos cifrados y restauración probada;
- acuerdos y configuración segura de los proveedores externos, o modo sin transmisión de datos;
- migraciones formales de base de datos si se amplía el alcance;
- revisión legal, de privacidad y de seguridad independiente.

Además, el backend dispone de `POST /api/auth/logout`, pero el cierre de sesión debe utilizar ese endpoint para invalidar el JWT inmediatamente. El comportamiento visual del frontend no debe confundirse con la invalidación del token en servidor.

Para el detalle operativo y de lo que queda pendiente, consultar:

```text
OPERACION_LOCAL.md
```

---

# Licencias y producción

Este repositorio no declara una licencia open source propia. Los componentes, los datos de OpenStreetMap y los servicios alojados tienen términos distintos. Revisar el [inventario de licencias y servicios](THIRD_PARTY_LICENSES.md) antes de distribuir.

El app-image descrito aquí no admite acceso desde otros equipos. Una futura variante multi-equipo requeriría cambios de arquitectura, HTTPS, protección adecuada de secretos y proveedores, una base de datos de producción, almacenamiento y respaldo protegidos, y pruebas de seguridad independientes. Esta documentación no describe una configuración para exponer el servicio.
