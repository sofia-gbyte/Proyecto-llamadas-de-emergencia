# CODES — Sistema de llamadas de emergencia

CODES es una aplicación web para apoyar la recepción, registro y gestión de llamadas de emergencia. El sistema permite trabajar con llamadas en vivo, transcripción local mediante ASR, clasificación inicial, geocodificación y seguimiento de los casos desde una interfaz web.

El proyecto está orientado a uso local o dentro de una red interna controlada. **No está pensado para exponerse directamente a Internet.**

> Esta documentación describe el estado actual del código incluido en este repositorio. Las funciones que dependen de infraestructura externa —por ejemplo, correo, claves de Cloudflare, servicios de geocodificación o un reverse proxy HTTPS— deben configurarse y probarse en el entorno donde se vaya a utilizar.

---

## Funcionalidades principales

- Gestión de llamadas pendientes, en curso y cerradas.
- Registro manual y recepción de llamadas en vivo.
- Transcripción local mediante **sherpa-onnx**.
- Extracción y corrección de direcciones a partir de la transcripción.
- Geocodificación de direcciones.
- Clasificación inicial de llamadas mediante lógica heurística.
- Mapa basado en **Leaflet**.
- Usuarios con roles `operator`, `supervisor` y `administrator`.
- Activación administrativa de cuentas registradas.
- Aislamiento de llamadas y métricas por institución para usuarios no administradores.
- Cambio y recuperación de contraseña.
- Invalidación de sesiones JWT al cerrar sesión, cambiar contraseña, desactivar una cuenta o forzar el cierre de sesiones.
- Limitación de intentos de autenticación y solicitudes de recuperación/registro.
- Registro de eventos de auditoría en `logs/auditoria.log`.
- Autochequeo interno de base de datos, disco, ASR y caché de geocodificación.
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
- Cloudflare Turnstile
- sherpa-onnx
- Python, para las herramientas auxiliares de calles

---

## Requisitos

Para ejecutar CODES desde el código fuente:

- JDK **21 o superior**
- Maven
- Windows si se quieren utilizar los scripts `.bat` y `.ps1` incluidos.
- Python, solo para las herramientas relacionadas con el diccionario de calles.
- Acceso a Internet si se necesitan descargar el modelo ASR, utilizar Turnstile o consultar servicios externos de geocodificación/Overpass.

El modelo de ASR no forma parte del ZIP liviano. Los scripts de `tools/asr` pueden instalarlo cuando sea necesario.

Las llamadas en vivo limitan los archivos de audio a **15 MB por archivo** y **16 MB por petición**.

---

# Inicio rápido

## Windows

La forma más sencilla de iniciar CODES es:

```text
CODES.bat
```

El menú permite iniciar CODES con ASR, ejecutar la limpieza, crear un respaldo local o salir.

Durante el inicio, el script comprueba Java y Maven, prepara las claves locales necesarias y ejecuta Spring Boot. Cuando corresponde, también intenta preparar/iniciar el ASR local.

Por defecto, la aplicación queda disponible en:

```text
http://localhost:8000
```

El ASR local utiliza:

```text
ws://localhost:6006
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

Si se necesita el ASR y todavía no está instalado:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

El servicio ASR es local al equipo que ejecuta CODES. No se debe publicar directamente en la red.

---

# Primer arranque y secretos

CODES necesita secretos para firmar los JWT y cifrar los audios.

Las variables principales son:

```powershell
$env:CODES_JWT_SECRET="secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="clave-base64-de-32-bytes"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="una-password-segura"
$env:CODES_TURNSTILE_SITE_KEY="site-key-de-cloudflare"
$env:CODES_TURNSTILE_SECRET_KEY="secret-key-de-cloudflare"
```

La clave JWT y la clave de cifrado de audios son independientes. **No deben reutilizarse entre sí.**

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

---

# Cloudflare Turnstile

El login y el registro utilizan Cloudflare Turnstile.

Para una instalación real se deben configurar:

```powershell
$env:CODES_TURNSTILE_SITE_KEY="..."
$env:CODES_TURNSTILE_SECRET_KEY="..."
```

La `SITE_KEY` puede utilizarse en el cliente. La `SECRET_KEY` debe permanecer únicamente en el servidor.

Los scripts de Windows pueden utilizar claves oficiales de prueba cuando no se proporcionan claves propias. Esas claves son para desarrollo/demo y no deben considerarse una configuración de producción.

Turnstile necesita acceso a Internet desde el navegador y desde el backend según el flujo de validación.

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

El puerto del ASR:

```text
6006
```

debe mantenerse local. Actualmente el flujo utiliza:

```text
ws://localhost:6006
```

y no está preparado para publicar el WebSocket del ASR a otros equipos.

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

# Llamadas en vivo y ASR

El modo de llamada en vivo utiliza un servicio local basado en **sherpa-onnx**.

Flujo general:

```text
Micrófono del navegador
        ↓
WebSocket local
        ↓
sherpa-onnx
        ↓
Transcripción
        ↓
Extracción/corrección de dirección
        ↓
Geocodificación
        ↓
Registro de la llamada
```

El modelo configurado actualmente es:

```text
sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11
```

El modelo no se incluye en el ZIP liviano.

El ASR se ejecuta localmente y el endpoint WebSocket actual es:

```text
ws://localhost:6006
```

El backend también dispone de procesamiento para audios completos almacenados, cuyos límites son:

```text
15 MB  máximo por archivo
16 MB  máximo por petición
```

La clasificación automática es heurística y debe considerarse una ayuda para el operador, no un mecanismo autónomo de despacho o decisión.

---

# Direcciones y geocodificación

Después de obtener la transcripción, CODES intenta identificar una dirección y corregir posibles errores producidos por el ASR.

El diccionario local de calles se encuentra en:

```text
data/calles_chile.txt
```

Si el diccionario no está disponible, CODES puede conservar la transcripción sin aplicar esa corrección.

La geocodificación se realiza mediante `GeocoderService`. El sistema aplica comprobaciones de similitud antes de aceptar un resultado.

La ubicación del operador, cuando está disponible, puede utilizarse como señal secundaria para resolver candidatos. No se toma automáticamente como la ubicación del incidente.

Las consultas a servicios externos deben respetar los límites y condiciones de uso del proveedor correspondiente.

---

# Diccionario de calles

## Overpass / OpenStreetMap

Para generar o ampliar el diccionario:

```powershell
python -m pip install requests
python tools/streets/fetch_calles_overpass.py data/calles_chile.txt
```

Por defecto, la herramienta trabaja con la Región Metropolitana.

Para otra región:

```powershell
python tools/streets/fetch_calles_overpass.py data/calles_chile.txt --region "Nombre de la región"
```

## Desde un PBF

Si se dispone de un extracto de OpenStreetMap:

```powershell
python -m pip install osmium
```

Luego se puede descargar, por ejemplo, el PBF de Chile desde Geofabrik y procesarlo con:

```powershell
python tools/streets/extract_calles_chile.py chile-latest.osm.pbf data/calles_chile.txt
```

El archivo PBF es un insumo de trabajo y puede eliminarse después de generar el diccionario si ya no se necesita.

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
├── calles_chile.txt
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
- disponibilidad del ASR local;
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

# API principal

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
PATCH  /api/users/{id}/disable
PATCH  /api/users/{id}/enable
PATCH  /api/users/{id}/reset-password
PATCH  /api/users/{id}/close-sessions

GET    /api/llamadas/pending
GET    /api/llamadas/in-progress
GET    /api/llamadas/closed
POST   /api/llamadas/{id}/assign
POST   /api/llamadas/{id}/close
POST   /api/llamadas/live

GET    /api/metrics

GET    /api/admin/status
POST   /api/admin/selftest
```

Las operaciones protegidas requieren autenticación y, según el caso, un rol autorizado.

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

CODES_DATA_DIR

CODES_SMTP_HOST
CODES_SMTP_PORT
CODES_SMTP_USERNAME
CODES_SMTP_PASSWORD
CODES_MAIL_FROM
CODES_PUBLIC_BASE_URL
```

Otros parámetros de operación, como el intervalo del autochequeo, mantenimiento, CORS, rutas de datos y configuración del ASR, se encuentran en `application.properties`.

---

# Estructura del proyecto

```text
CODES/
├── .github/
├── data/
│   └── calles_chile.txt
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
│   ├── asr/
│   └── streets/
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
- protección del WebSocket del ASR;
- respaldo de `CODES_ENCRYPT_KEY`;
- copias de seguridad de la base de datos;
- pruebas reales de restauración;
- política de retención y eliminación de llamadas y audios;
- protección y rotación de logs;
- configuración de SMTP;
- claves reales de Turnstile;
- límites y condiciones de los servicios externos de geocodificación;
- actualización y procedencia del modelo ASR;
- monitoreo del servidor y del ASR;
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
- endurecimiento del WebSocket del ASR;
- monitoreo externo independiente.

Además, el backend dispone de `POST /api/auth/logout`, pero el cierre de sesión debe utilizar ese endpoint para invalidar el JWT inmediatamente. El comportamiento visual del frontend no debe confundirse con la invalidación del token en servidor.

Para el detalle operativo y de lo que queda pendiente, consultar:

```text
OPERACION_LOCAL.md
```

---

# Licencia y uso

Este repositorio no declara una licencia open source específica.

Salvo que el equipo responsable indique lo contrario, debe considerarse un proyecto en desarrollo para uso interno, académico o controlado. No se debe asumir que el código, los modelos, los datos de OpenStreetMap ni los servicios externos utilizados tienen las mismas condiciones de licencia o redistribución.

Revisar las condiciones de cada dependencia y servicio antes de distribuir una versión del sistema.
