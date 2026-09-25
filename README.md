# CODES

**CODES (Centro de Operaciones y Despacho de Emergencias)** es una aplicación web para gestionar llamadas de emergencia, registrar incidentes, clasificarlos por prioridad y apoyar su ubicación geográfica desde una consola operativa.

El proyecto está construido con **Spring Boot** y dispone de una interfaz web servida por la propia aplicación. Incluye autenticación, gestión de usuarios, clasificación de llamadas, geocodificación, transcripción de llamadas en vivo mediante ASR local y almacenamiento local de la información.

> **Estado:** proyecto en desarrollo. Las configuraciones y medidas de seguridad descritas aquí deben revisarse antes de utilizar CODES en un entorno real.

## Funcionalidades principales

- Panel operativo para visualizar llamadas:
  - **Sin asignar**
  - **En curso**
  - **Cerradas**
- Métricas operativas, incluyendo llamadas totales, urgentes activas, pendientes, en curso y tiempo medio hasta la asignación.
- Asignación y cierre de incidentes por usuarios autorizados.
- Llamadas en vivo mediante captura de micrófono desde el navegador.
- Transcripción mediante **sherpa-onnx** ejecutado localmente.
- Clasificación heurística de la transcripción según el contenido de la llamada.
- Extracción y corrección aproximada de direcciones.
- Geocodificación de ubicaciones para mostrar el incidente en el mapa.
- Corrección de nombres de calles mediante un diccionario basado en datos de OpenStreetMap.
- Almacenamiento de la información en **H2** en modo archivo.
- Cifrado de los audios almacenados mediante **AES-256-GCM**.
- Autenticación mediante **JWT** y contraseñas protegidas con **BCrypt**.
- Protección de inicio de sesión y registro mediante **Cloudflare Turnstile**.
- Control de intentos de autenticación y limitación de solicitudes.
- Gestión de usuarios por rol y activación administrativa de cuentas registradas.
- Interfaz web basada en HTML, CSS y JavaScript, con **Leaflet** para el mapa.

## Tecnologías

- **Java 21 o superior**
- **Spring Boot 3.3.4**
- Spring Web
- Spring Security
- Spring Data JPA
- H2 Database
- JSON Web Token (JJWT)
- Leaflet
- Cloudflare Turnstile
- sherpa-onnx
- Python, para las herramientas auxiliares de calles

## Requisitos

Para ejecutar CODES desde el código fuente necesitas:

- JDK **21 o superior**
- Maven
- Windows si quieres utilizar los scripts `.bat` y `.ps1` incluidos para el arranque y el ASR local.
- Python, solo si vas a generar o ampliar el diccionario de calles mediante las herramientas de `tools/streets`.

El ASR utiliza un modelo que se descarga por separado y **no forma parte del ZIP liviano**.

## Inicio rápido en Windows

La forma más sencilla es utilizar:

```text
CODES.bat
```

El menú permite:

1. Iniciar CODES.
2. Limpiar archivos pesados o regenerables.
3. Salir.

Al iniciar, el script comprueba Java y Maven, prepara las claves locales necesarias, inicia el servidor Spring Boot y, cuando corresponde, inicia también el servicio ASR local.

La aplicación queda disponible normalmente en:

```text
http://localhost:8000
```

El servicio ASR local utiliza:

```text
ws://localhost:6006
```

### Inicio manual

También puedes iniciar Spring Boot directamente:

```powershell
mvn spring-boot:run
```

Si vas a utilizar llamadas en vivo y el ASR no está ejecutándose, puedes iniciarlo manualmente:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\asr\install_windows.ps1
.\tools\asr\start_asr_windows.ps1
```

El script de arranque de CODES intenta iniciar el ASR automáticamente cuando los archivos del modelo no están presentes.

## Configuración de secretos

CODES utiliza variables de entorno para las claves criptográficas y las credenciales iniciales.

Variables principales:

```powershell
$env:CODES_JWT_SECRET="secreto-largo-y-aleatorio"
$env:CODES_ENCRYPT_KEY="clave-base64-de-32-bytes"
$env:CODES_ADMIN_USER="admin"
$env:CODES_ADMIN_PASSWORD="una-password-segura"
$env:CODES_TURNSTILE_SITE_KEY="site-key-de-cloudflare"
$env:CODES_TURNSTILE_SECRET_KEY="secret-key-de-cloudflare"
```

### Claves generadas por `CODES.bat`

Cuando se ejecuta mediante el script de Windows, CODES crea o reutiliza:

```text
data\.codes-secrets.ps1
```

Este archivo contiene las claves persistentes utilizadas por el entorno local. **No debe subirse al repositorio ni compartirse.**

El script genera automáticamente claves para JWT y cifrado si todavía no existen.

También establece inicialmente un usuario administrador. Si se utilizan los valores predeterminados del script, la contraseña inicial es:

```text
Admin123!
```

**Cámbiala antes de cualquier uso fuera de un entorno de desarrollo o demostración.**

### Cloudflare Turnstile

El login y el registro requieren una validación Turnstile.

Si no se proporcionan claves reales, `tools/iniciar_codes_windows.ps1` utiliza las claves oficiales de prueba de Cloudflare. Estas claves están destinadas únicamente a desarrollo/demo y no deben utilizarse como configuración de producción.

Para utilizar Turnstile real, configura:

```powershell
$env:CODES_TURNSTILE_SITE_KEY="..."
$env:CODES_TURNSTILE_SECRET_KEY="..."
```

La `SITE_KEY` puede estar presente en el cliente; la `SECRET_KEY` debe permanecer únicamente en el servidor.

El widget necesita acceso a Internet para cargar los recursos de Cloudflare.

## Red y acceso

Por defecto, Spring Boot escucha únicamente en:

```text
127.0.0.1:8000
```

Esto significa que la aplicación no queda expuesta a otros equipos de la red de forma predeterminada.

Si necesitas utilizar CODES desde otros equipos de una red interna, puedes configurar:

```powershell
$env:CODES_SERVER_ADDRESS="IP_PRIVADA_DEL_SERVIDOR"
$env:CODES_SERVER_PORT="8000"
```

y configurar el firewall para permitir únicamente el acceso necesario.

**No se recomienda exponer directamente CODES a Internet.**

El servicio ASR utiliza:

```text
localhost:6006
```

Debe permanecer accesible únicamente desde el equipo que ejecuta CODES. El script de configuración actual no está pensado para publicar ese puerto en una red externa.

## Autenticación y usuarios

CODES utiliza una API sin estado (**stateless**):

- Las peticiones autenticadas utilizan JWT.
- Las contraseñas se almacenan mediante BCrypt.
- Las cuentas registradas como operador se crean inicialmente inactivas.
- Un administrador debe activar una cuenta antes de que pueda iniciar sesión.
- Existen controles de acceso por rol para determinadas operaciones.
- El inicio de sesión incorpora limitación de solicitudes y control de intentos fallidos.

El primer administrador puede crearse mediante:

```text
CODES_ADMIN_USER
CODES_ADMIN_PASSWORD
```

El proceso de bootstrap crea el administrador **solo si todavía no existe**; no sobrescribe su contraseña en cada arranque.

## Llamadas en vivo y ASR

CODES utiliza **sherpa-onnx** para realizar la transcripción local.

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
Clasificación / extracción de dirección
        ↓
Geocodificación
        ↓
Registro del incidente
```

El modelo configurado actualmente es:

```text
sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11
```

El modelo no se incluye en el ZIP liviano porque ocupa un volumen considerable y puede descargarse nuevamente mediante los scripts de instalación.

El servicio ASR local utiliza:

```text
ws://localhost:6006
```

Para el modo de llamada en vivo, el navegador debe acceder a CODES desde `http://localhost:8000`.

> Para un despliegue real, el canal WebSocket y la infraestructura del ASR deberían protegerse y supervisarse de acuerdo con los requisitos de seguridad del entorno.

## Clasificación de llamadas

La clasificación se realiza mediante lógica heurística implementada en el proyecto. No debe interpretarse como un sistema médico, policial o de despacho autónomo.

La clasificación sirve como apoyo al operador y el caso debe ser revisado según los procedimientos del entorno donde se utilice.

## Direcciones y geocodificación

Después de obtener la transcripción, CODES intenta extraer una dirección y corregir posibles errores del ASR.

Para la corrección de calles se utiliza:

```text
data/calles_chile.txt
```

El proyecto incluye un diccionario base y herramientas para ampliarlo utilizando datos de OpenStreetMap.

### Generar/ampliar el diccionario con Overpass

La opción más sencilla es:

```powershell
python -m pip install requests
python tools/streets/fetch_calles_overpass.py data/calles_chile.txt
```

Por defecto, el script trabaja con la Región Metropolitana.

Para otra región:

```powershell
python tools/streets/fetch_calles_overpass.py data/calles_chile.txt --region "Nombre de la región"
```

También existe una opción para trabajar con todo Chile mediante los parámetros disponibles en el propio script.

### Utilizar un archivo PBF de OpenStreetMap

Si necesitas trabajar con un extracto PBF:

```powershell
python -m pip install osmium

Invoke-WebRequest `
  https://download.geofabrik.de/south-america/chile-latest.osm.pbf `
  -OutFile chile-latest.osm.pbf

python tools/streets/extract_calles_chile.py `
  chile-latest.osm.pbf `
  data/calles_chile.txt
```

El PBF puede eliminarse después de generar el diccionario si ya no se necesita.

### Geocodificación

CODES utiliza servicios externos de geocodificación configurados en `GeocoderService` y aplica comprobaciones de similitud antes de aceptar un resultado.

La ubicación del operador, cuando está disponible, puede utilizarse como una señal secundaria para resolver candidatos, pero no se considera automáticamente como la ubicación del incidente.

Las consultas de geocodificación deben respetar las condiciones y límites de uso del proveedor correspondiente.

## Datos y almacenamiento

La aplicación utiliza una base de datos H2 en archivo:

```text
data/llamadas.mv.db
```

También puede generar o utilizar:

```text
data/
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

La configuración de estas rutas se encuentra en:

```text
src/main/resources/application.properties
```

## Cifrado de audios

Los audios almacenados se cifran mediante **AES-256-GCM** utilizando la clave configurada mediante:

```text
CODES_ENCRYPT_KEY
```

La clave de cifrado debe mantenerse separada de los archivos de datos y no debe incluirse en el repositorio.

Perder la clave puede impedir recuperar los audios cifrados.

## Estructura del proyecto

```text
CODES/
├── .github/
├── data/
│   └── calles_chile.txt
├── src/
│   └── main/
│       ├── java/
│       │   └── cl/codes/
│       │       ├── classifier/
│       │       ├── config/
│       │       ├── controller/
│       │       ├── model/
│       │       ├── repository/
│       │       ├── security/
│       │       └── service/
│       └── resources/
│           ├── static/
│           │   ├── index.html
│           │   ├── script.js
│           │   └── styles.css
│           └── application.properties
├── tools/
│   ├── asr/
│   └── streets/
├── CODES.bat
├── pom.xml
├── README.md
└── .gitignore
```

## Limpieza del proyecto

Para mantener el proyecto liviano existe:

```text
tools/limpiar_proyecto.ps1
```

Sin parámetros, el script solo muestra qué elementos podrían eliminarse.

```powershell
.\tools\limpiar_proyecto.ps1
```

Para ejecutar la limpieza:

```powershell
.\tools\limpiar_proyecto.ps1 -Borrar
```

También permite generar un ZIP liviano:

```powershell
.\tools\limpiar_proyecto.ps1 -Borrar -Comprimir
```

El proceso de limpieza está diseñado para conservar la base de datos, los secretos y los audios de casos.

## API principal

La aplicación expone, entre otras, las siguientes rutas:

```text
GET  /api/health
POST /api/auth/login
POST /api/auth/register

GET  /api/llamadas/pending
GET  /api/llamadas/in-progress
GET  /api/llamadas/closed
POST /api/llamadas/{id}/assign
POST /api/llamadas/{id}/close
POST /api/llamadas/live

GET  /api/metrics
```

Las operaciones protegidas requieren autenticación y, según la operación, un rol autorizado.

## Configuración principal

La configuración de la aplicación está en:

```text
src/main/resources/application.properties
```

Entre los parámetros principales se encuentran:

```text
server.port
server.address
app.encrypt-key
app.jwt-secret
app.jwt-expiracion-minutos
app.admin-bootstrap-user
app.admin-bootstrap-password
app.cors-allowed-origins
app.turnstile-site-key
app.turnstile-secret-key
app.calles-diccionario
app.asr.*
```

## Desarrollo

Para compilar el proyecto:

```powershell
mvn clean package
```

Para ejecutarlo directamente:

```powershell
mvn spring-boot:run
```

Para ejecutar las pruebas:

```powershell
mvn test
```

## Consideraciones antes de producción

Este proyecto contiene información potencialmente sensible relacionada con llamadas e incidentes. Antes de utilizarlo en un entorno real se deberían revisar, como mínimo:

- gestión y rotación de secretos;
- contraseñas iniciales;
- HTTPS;
- protección del WebSocket del ASR;
- autenticación y autorización;
- configuración de CORS;
- firewall y segmentación de red;
- políticas de retención y eliminación de audios;
- copias de seguridad y recuperación de la base de datos;
- protección de logs;
- cumplimiento de las obligaciones legales y de privacidad aplicables;
- límites y condiciones de los servicios externos de geocodificación y mapas.

## Licencia y uso

No se declara una licencia open source específica en este repositorio. Por tanto, salvo que el equipo responsable indique lo contrario, el código debe considerarse **proyecto en desarrollo / uso interno o académico** y no debe asumirse que puede redistribuirse libremente.

