# CODES 1.0.0 — Sistema de llamadas de emergencia

CODES es una aplicación web para apoyar la recepción, registro y gestión de llamadas de emergencia. El sistema permite trabajar con llamadas en vivo, transcripción con Groq Whisper, clasificación inicial, geocodificación con Geoapify y seguimiento de los casos desde una interfaz web.

**Versión del proyecto: `1.0.0`.** Consulta el [historial de cambios](CHANGELOG.md) para ver las novedades de esta versión y las notas de compatibilidad.

El proyecto está orientado a uso local o dentro de una red interna controlada. **Aún no está listo para exponerse directamente a Internet.**

> Leaflet se incluye dentro de la aplicación. El mapa base es **Esri World Imagery** (satélite); las rutas usan OSRM y la geocodificación usa Geoapify como proveedor principal. Las direcciones y coordenadas de incidentes se envían a esos servicios cuando se usan las funciones correspondientes. Ver [operación local](OPERACION_LOCAL.md) y el [inventario de licencias y servicios](THIRD_PARTY_LICENSES.md).

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

> **Alcance de la versión 1.0.0:** versión funcional para demostración y uso local controlado. No implica que CODES esté preparado para su exposición directa a Internet ni para despachos de emergencia reales sin las revisiones operativas, legales, de privacidad y disponibilidad descritas en [operación local](OPERACION_LOCAL.md).

## Funcionalidades principales

- Gestión de llamadas pendientes, en curso y cerradas.
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
- Registro de eventos de auditoría en `logs/auditoria.log`.
- Autochequeo interno de base de datos, disco, claves de proveedores externos y caché de geocodificación.
- Cifrado AES-256-GCM para los audios almacenados.
- Base de datos H2 en archivo, sin necesidad de instalar un servidor de base de datos adicional.

---

## Tecnologías

- **Java 21 o superior**
- **Spring Boot 3.3.4**
- Spring Web
- Spring Security
- Spring Data JPA / Hibernate
- H2 Database
- JSON Web Token (JJWT)
- Leaflet
- Cloudflare Turnstile opcional (desactivado por defecto)
- Groq Whisper para transcripción
- Geoapify para geocodificación

## Arquitectura y decisiones

CODES se implementa como un **monolito modular**: una aplicación Spring Boot sirve la interfaz web y la API REST, y organiza el código en controladores, servicios, repositorios y entidades. El controlador recibe y valida la petición; el servicio aplica reglas de institución y estado del caso; el repositorio usa Spring Data JPA e Hibernate; y H2 persiste los datos en `data/llamadas.mv.db`.

Para este proyecto se eligió **Java 21 + Spring Boot** frente a **Python + FastAPI**. Se priorizaron tres criterios propios del caso: (1) seguridad y roles integrados para separar operadores, supervisores y administradores; (2) persistencia relacional y validación de contratos con poco código de infraestructura; y (3) facilidad para probar y ejecutar toda la solución como una unidad. Spring Boot resuelve el enrutamiento HTTP, inyección de dependencias, validación, seguridad y acceso JPA. El costo aceptado es una aplicación más pesada y con más configuración que una API pequeña en FastAPI.

Se mantiene el monolito porque los flujos de registro, clasificación y despacho comparten datos y reglas, y el equipo puede desarrollar y desplegar una sola unidad sin operar varios servicios, redes y contratos distribuidos. Esto simplifica la instalación y las operaciones transaccionales. El costo aceptado es que las partes no escalan ni se despliegan independientemente; si el volumen o la disponibilidad lo exigieran, primero habría que migrar la persistencia a un servidor como PostgreSQL y definir límites de servicio.

## API REST y contratos

La API está orientada a los recursos `users` y `llamadas`. Las operaciones administrativas de usuarios requieren el rol `administrator`; el detalle de una llamada respeta el aislamiento institucional y las modificaciones operativas se reservan a supervisores y administradores.

| Recurso | Operación | Ruta | Acceso / resultado |
|---|---|---|---|
| Usuarios | Crear | `POST /api/users` | Administrador; `201 Created` y cabecera `Location` |
| Usuarios | Listar / consultar | `GET /api/users`, `GET /api/users/{id}` | Administrador; `200 OK` |
| Usuarios | Actualizar perfil y rol | `PUT /api/users/{id}` | Administrador; `200 OK` |
| Usuarios | Eliminar | `DELETE /api/users/{id}` | Administrador; `204 No Content`; no permite borrar la propia cuenta ni al último administrador activo |
| Llamadas | Crear en vivo | `POST /api/llamadas/live` | Operador, supervisor o administrador; `201 Created` y `Location` |
| Llamadas | Listar / consultar | `GET /api/llamadas/pending`, `/in-progress`, `/closed`, `GET /api/llamadas/{id}` | Autenticado; `200 OK`, filtrado por institución |
| Llamadas | Corregir resumen y dirección | `PUT /api/llamadas/{id}` | Supervisor o administrador; `200 OK`; solo casos abiertos |
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

La creación de llamadas devuelve la representación `CallResponse` después de transcribir, clasificar y geocodificar. Los valores de prioridad y puntuaciones son resultados de clasificación; el `PUT` de llamada solo corrige el resumen operativo y la dirección para evitar alterar manualmente esa clasificación.

Los errores siguen un contrato JSON: `400` para validación o datos inválidos, `401` para falta de autenticación, `403` para rol no autorizado, `404` para recurso inexistente o no visible por institución, `409` para conflictos de estado o unicidad, `413` para cargas demasiado grandes y `500` para errores inesperados (con una referencia de correlación). Las llamadas retiradas se conservan en H2 para no perder la trazabilidad; no aparecen en colas ni métricas operativas.

---

## Requisitos

Para ejecutar CODES desde el código fuente:

- JDK **21 o superior**
- Maven
- Windows si se quieren utilizar los scripts `.bat` y `.ps1` incluidos.
- Acceso a Internet para Groq Whisper, Geoapify y los servicios cartográficos.

Las llamadas en vivo se envían a Groq al detener y guardar la grabación. El límite de transcripción de Groq es **25 MB** por archivo.

---

# Inicio rápido

## Windows

La forma más sencilla de iniciar CODES es:

```text
CODES.bat
```

El menú permite iniciar CODES, diagnosticar Java/Maven, ejecutar la limpieza y crear un respaldo local.

Durante el inicio, el script comprueba Java y Maven, carga las claves locales y ejecuta Spring Boot.

Por defecto, la aplicación queda disponible en:

```text
http://localhost:8000
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

Las variables principales son:

```powershell
$env:CODES_JWT_SECRET="secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="clave-base64-de-32-bytes"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="<tu-clave-segura>"
```

La clave JWT y la clave de cifrado de audios son independientes. **No deben reutilizarse entre sí.**
Para H2 local, el nombre de usuario predeterminado es `sa` y la contraseña queda vacía; se pueden externalizar con `CODES_DB_USERNAME` y `CODES_DB_PASSWORD`.

## Uso de `CODES.bat`

Cuando se inicia mediante los scripts de Windows, CODES puede crear o reutilizar:

```text
data\.codes-secrets.ps1
```

Ese archivo contiene secretos persistentes del entorno local.

- No debe subirse al repositorio.
- No debe compartirse.
- Debe incluirse en los mecanismos de respaldo seguros que correspondan.
- La pérdida de `CODES_ENCRYPT_KEY` puede impedir recuperar los audios cifrados.

En una instalación nueva, el script genera una contraseña inicial aleatoria para el administrador y la muestra durante el primer arranque. Después de entrar al sistema conviene cambiarla.

El bootstrap del administrador se ejecuta únicamente cuando todavía no existe ningún usuario. CODES no reemplaza la contraseña del administrador en cada arranque.

## Claves de Groq y Geoapify

Al iniciar mediante `CODES.bat`, edita el archivo local ignorado por Git:

```text
data\.codes-secrets.ps1
```

Agrega o completa estas líneas con las claves obtenidas en los paneles de cada proveedor:

```powershell
$env:GROQ_API_KEY = 'tu_clave_de_groq'
$env:GEOAPIFY_API_KEY = 'tu_clave_de_geoapify'
```

Guarda el archivo y vuelve a iniciar `CODES.bat`. No pongas las claves en `application.properties`, en JavaScript ni en el navegador; nunca las compartas ni las subas al repositorio. Para iniciar desde una terminal sin el lanzador, exporta esas mismas variables de entorno antes de ejecutar Maven.

---

# Verificación anti-bots (opcional)

Turnstile está desactivado por defecto. Login y registro conservan los límites de intentos del servidor. Para habilitar la verificación externa:

Para una instalación real con Turnstile se deben configurar:

```powershell
$env:CODES_TURNSTILE_SITE_KEY="..."
$env:CODES_TURNSTILE_SECRET_KEY="..."
$env:CODES_TURNSTILE_ENABLED="true"
```

La `SITE_KEY` puede utilizarse en el cliente. La `SECRET_KEY` debe permanecer únicamente en el servidor.

Al habilitarlo, Turnstile requiere acceso a Cloudflare desde el navegador y el backend. Para una instalación pública sin ese proveedor, configura protección equivalente en el proxy/WAF y conserva los límites del servidor; no expongas el login sin controles anti-abuso.

---

# Red y acceso desde otros equipos

Por defecto, Spring Boot escucha solamente en:

```text
127.0.0.1:8000
```

Por lo tanto, CODES no queda disponible para otros equipos de la red automáticamente.

Para utilizarlo desde una LAN se puede definir:

```powershell
$env:CODES_SERVER_ADDRESS="IP_PRIVADA_DEL_SERVIDOR"
$env:CODES_SERVER_PORT="8000"
```

y configurar el firewall para permitir únicamente el tráfico necesario.

También hay que ajustar:

```text
app.cors-allowed-origins
```

para incluir exclusivamente los orígenes reales que utilizarán los equipos de la sala. **No se debe utilizar `*`.**

Ejemplo:

```text
app.cors-allowed-origins=http://192.168.1.10:8000,http://192.168.1.11:8000
```

## HTTP y HTTPS

La configuración actual no implementa HTTPS directamente.

Para una LAN pequeña y realmente controlada puede utilizarse HTTP si el tráfico hacia el servidor está restringido y no existe exposición directa a Internet. Si CODES sale de esa red, o si la red no puede considerarse confiable, debe colocarse un reverse proxy con HTTPS delante de la aplicación.

El puerto web configurado para Spring Boot debe mantenerse dentro de la red autorizada o detrás del proxy HTTPS.

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
$env:CODES_PUBLIC_BASE_URL="http://servidor:8000"
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
└── .codes-secrets.ps1
```

Los logs de auditoría se almacenan en:

```text
logs/auditoria.log
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
GET/POST /api/auth/captcha-site-key

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

CODES_TURNSTILE_SITE_KEY
CODES_TURNSTILE_SECRET_KEY
CODES_TURNSTILE_ENABLED

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

Otros parámetros de operación, como el intervalo del autochequeo, mantenimiento, CORS y rutas de datos, se encuentran en `application.properties`.

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
├── CODES.bat
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

# Antes de usarlo en producción

CODES maneja información potencialmente sensible. Antes de utilizarlo operativamente conviene revisar, como mínimo:

- secretos y rotación de claves;
- contraseña inicial del administrador;
- HTTPS si la red no es completamente controlada;
- firewall y segmentación de red;
- CORS;
- protección de las claves y conexiones a proveedores externos;
- respaldo de `CODES_ENCRYPT_KEY`;
- copias de seguridad de la base de datos;
- pruebas reales de restauración;
- política de retención y eliminación de llamadas y audios;
- protección y rotación de logs;
- configuración de SMTP;
- protección anti-abuso para login/registro (Turnstile o controles equivalentes);
- límites y condiciones de los servicios públicos de mapas/geocodificación/rutas, o despliegue de instancias propias;
- revisión de los modelos y condiciones de Groq Whisper;
- monitoreo del servidor y los proveedores externos;
- pruebas de integración y seguridad.

La configuración actual utiliza H2 con:

```text
spring.jpa.hibernate.ddl-auto=update
```

Esto simplifica una instalación local, pero para una infraestructura de mayor escala conviene definir una estrategia formal de migraciones y evaluar una base de datos como PostgreSQL.

---

# Estado conocido

El proyecto tiene varias medidas de seguridad y operación implementadas, pero todavía existen tareas que dependen de infraestructura o de decisiones de despliegue.

Entre ellas:

- HTTPS/reverse proxy;
- política formal de retención;
- backups y restauración probada;
- almacenamiento/monitoreo externo de auditoría;
- migraciones formales de base de datos;
- acuerdos y configuración segura de los proveedores externos;
- monitoreo externo independiente.

Además, el backend dispone de `POST /api/auth/logout`, pero el cierre de sesión debe utilizar ese endpoint para invalidar el JWT inmediatamente. El comportamiento visual del frontend no debe confundirse con la invalidación del token en servidor.

Para el detalle operativo y de lo que queda pendiente, consultar:

```text
OPERACION_LOCAL.md
```

---

# Licencias y producción

Este repositorio no declara una licencia open source propia. Los componentes, los datos de OpenStreetMap y los servicios alojados tienen términos distintos. Revisar el [inventario de licencias y servicios](THIRD_PARTY_LICENSES.md) antes de distribuir.

Para exponer CODES a dispositivos por Internet todavía hacen falta, entre otras cosas, HTTPS, protección adecuada de secretos y proveedores, una base de datos de producción, almacenamiento y respaldo protegidos, y pruebas de seguridad. La guía de siguientes pasos está en [OPERACION_LOCAL.md](OPERACION_LOCAL.md).
